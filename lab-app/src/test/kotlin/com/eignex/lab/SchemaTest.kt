package com.eignex.lab

import com.eignex.lab.util.*
import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.execution.*
import com.eignex.lab.worker.*
import com.eignex.lab.nomad.*
import com.eignex.lab.web.*
import com.eignex.lab.cli.*

import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SchemaTest {
    private fun file() = Files.createTempDirectory("lab").resolve("lab.db")
    private fun version(file: java.nio.file.Path) = DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
        c.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { r -> r.next(); r.getInt(1) } }
    }

    @Test
    fun `processes opening one database together apply each step once`() {
        val file = file()
        val start = CountDownLatch(1)
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val openers = List(4) { thread { start.await(); runCatching { Store(file) }.onFailure(failures::add) } }
        start.countDown()
        openers.forEach { it.join() }

        assertEquals(emptyList(), failures)
        assertEquals(Schema.version, version(file))
    }

    /** A database as a release at the squashed baseline left it: `schema-21.sql` and version 21. */
    private fun baseline21(file: java.nio.file.Path) {
        val text = requireNotNull(Schema::class.java.getResourceAsStream("schema-21.sql")).bufferedReader().use { it.readText() }
        val statements = text.lines().filterNot { it.startsWith("--") }.joinToString("\n")
            .split(Regex("\n(?=CREATE |INSERT )")).map { it.trim() }.filter { it.isNotEmpty() }
        DriverManager.getConnection("jdbc:sqlite:$file").use { c -> c.createStatement().use { s ->
            statements.forEach(s::execute); s.execute("PRAGMA user_version = 21")
        } }
    }

    private fun activeAttempt(file: java.nio.file.Path, iteration: String, claim: String?) =
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.prepareStatement("INSERT INTO iteration_attempts (job_id, iteration_id, generation, session_id, allocation_id, token_hash, lease_until, state, indices, started_at" +
                (if (claim == null) "" else ", claim_id") + ") VALUES (1, ?, 1, 's', 'a', 'h', 9, 'executing', '[]', 0" + (if (claim == null) "" else ", ?") + ")").use { p ->
                p.setString(1, iteration); if (claim != null) p.setString(2, claim); p.executeUpdate()
            }
        }

    @Test
    fun `step 22 keeps a session's running attempt and lets named claims run side by side`() {
        val file = file()
        baseline21(file)
        activeAttempt(file, "0:none:0", null)
        assertFailsWith<SQLException> { activeAttempt(file, "1:none:0", null) }
        Store(file)
        assertEquals(Schema.version, version(file))
        DriverManager.getConnection("jdbc:sqlite:$file").use { c -> c.createStatement().use { s ->
            s.executeQuery("SELECT iteration_id, state, claim_id FROM iteration_attempts").use { r ->
                assertTrue(r.next()); assertEquals("0:none:0", r.getString(1)); assertEquals("executing", r.getString(2)); assertEquals(null, r.getString(3))
            }
        } }
        // An unnamed attempt is still its session's only one; named ones are one per name.
        assertFailsWith<SQLException> { activeAttempt(file, "1:none:0", null) }
        activeAttempt(file, "1:none:0", "a".repeat(32))
        activeAttempt(file, "2:none:0", "b".repeat(32))
        assertFailsWith<SQLException> { activeAttempt(file, "3:none:0", "b".repeat(32)) }
    }

    @Test
    fun `problem history migration indexes both collection and declared bench identity`() {
        val file = file()
        baseline21(file)
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use { s ->
                s.executeUpdate("""INSERT INTO problems VALUES
                    (1, 0, '{"collection":"c","problem":"legacy"}'),
                    (2, 0, '{"collection":"c","problem":"adapter-id","benchCase":{"attributes":{"problem":"declared"}}}'),
                    (3, 0, '{"collection":"other","problem":"declared"}')""")
            }
        }
        Store(file)
        DriverManager.getConnection("jdbc:sqlite:$file").use { c -> c.createStatement().use { s ->
            for ((problem, job) in listOf("legacy" to 1, "declared" to 2)) {
                val query = """SELECT job_id FROM problems p WHERE json_extract(p.problem, '$.collection') = 'c'
                    AND COALESCE(json_extract(p.problem, '$.benchCase.attributes.problem'), json_extract(p.problem, '$.problem')) = '$problem'"""
                val plan = s.executeQuery("EXPLAIN QUERY PLAN $query").use { r ->
                    generateSequence { if (r.next()) r.getString("detail") else null }.joinToString(" ")
                }
                assertTrue("INDEX problems_identity (<expr>=? AND <expr>=?)" in plan, plan)
                s.executeQuery(query).use { r ->
                    assertTrue(r.next()); assertEquals(job, r.getInt(1)); assertTrue(!r.next())
                }
            }
        } }
    }

    @Test
    fun `a database older than the squashed baseline is refused, not half upgraded`() {
        val file = file()
        Store(file)
        DriverManager.getConnection("jdbc:sqlite:$file").use { it.createStatement().use { s -> s.execute("PRAGMA user_version = 20") } }
        assertFailsWith<IllegalStateException> { Store(file) }
        assertEquals(20, version(file))
    }

    @Test
    fun `the database refuses a job state the lab cannot be in`() {
        val file = file()
        val store = Store(file)
        val id = store.create("e", "main", emptyList(), experiment = ExperimentSpec("e"))
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            assertFailsWith<SQLException> { c.createStatement().execute("UPDATE jobs SET status = 'DONE' WHERE id = $id") }
            assertFailsWith<SQLException> { c.createStatement().execute("UPDATE jobs SET finished_at = 5 WHERE id = $id") }
            assertFailsWith<SQLException> { c.createStatement().execute("UPDATE jobs SET status = 'LOST' WHERE id = $id") }
        }
        assertEquals(1, store.finish(id, Status.DONE))
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            assertFailsWith<SQLException> { c.createStatement().execute("UPDATE jobs SET paused = 1 WHERE id = $id") }
        }
    }

    @Test
    fun `a job's status moves only as its lifecycle allows`() {
        val file = file()
        val store = Store(file)
        val done = store.create("done", "main", emptyList(), experiment = ExperimentSpec("done"))
        store.finish(done, Status.DONE)
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            assertFailsWith<SQLException> { c.createStatement().execute("UPDATE jobs SET status = 'CANCELLED' WHERE id = $done") }
            assertFailsWith<SQLException> {
                c.createStatement().execute("UPDATE jobs SET status = 'QUEUED', finished_at = NULL WHERE id = $done")
            }
        }
    }

    @Test
    fun `a command's status moves only as its lifecycle allows`() {
        val file = file()
        val store = Store(file)
        val id = store.create("e", "main", listOf("true" to 1L, "true" to 1L), experiment = ExperimentSpec("e"))
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().execute("UPDATE commands SET status = 'CANCELLED' WHERE job_id = $id AND idx = 0")
            c.createStatement().execute("UPDATE commands SET status = 'DONE' WHERE job_id = $id AND idx = 1")
            assertFailsWith<SQLException> { c.createStatement().execute("UPDATE commands SET status = 'DONE' WHERE job_id = $id AND idx = 0") }
            assertFailsWith<SQLException> { c.createStatement().execute("UPDATE commands SET status = 'RUNNING' WHERE job_id = $id AND idx = 1") }
            c.createStatement().execute("UPDATE commands SET status = 'QUEUED' WHERE job_id = $id")
        }
    }

    @Test
    fun `deleting a job deletes everything recorded for it`() {
        val store = Store(file())
        val id = store.create("e", "main", listOf("true" to 1L), experiment = ExperimentSpec("e"))
        store.holds.take(id, 60_000)
        store.finish(id, Status.DONE)

        assertTrue(store.deleteJob(id))
        store.executionTransaction { db ->
            for (table in Schema.JOB_TABLES) {
                assertEquals(0, db.sqlRows("SELECT COUNT(*) FROM $table WHERE job_id = ?", id) { it.getInt(1) }.single(), table)
            }
        }
    }

    @Test
    fun `a write inside a write joins it, and a read sees one committed snapshot`() {
        val store = Store(file())
        val id = store.create("e", "main", listOf("true" to 1L), experiment = ExperimentSpec("e"))
        assertFailsWith<IllegalStateException> {
            store.executionTransaction { db ->
                db.sqlUpdate("UPDATE jobs SET priority = 9 WHERE id = ?", id)
                store.executionTransaction { inner -> inner.sqlUpdate("UPDATE jobs SET parallel = 7 WHERE id = ?", id) }
                error("abort both")
            }
        }
        val job = store.job(id)!!
        assertEquals(0 to 1, job.priority to job.vcpus)
        assertEquals(1, store.reading { store.job(id)!!.commands.size })
    }
}
