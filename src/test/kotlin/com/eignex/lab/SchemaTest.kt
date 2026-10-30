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

    @Test
    fun `a database from before numbered steps is brought up to date and keeps its rows`() {
        val file = file()
        val store = Store(file)
        val id = store.create("old", "main", emptyList(), experiment = ExperimentSpec("old"))
        // What an older release left: no version, and a paused job that ended.
        DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
            c.createStatement().use {
                it.execute("DROP TRIGGER jobs_valid_update")
                it.execute("UPDATE jobs SET status = 'DONE', finished_at = 1, paused = 1 WHERE id = $id")
                it.execute("PRAGMA user_version = 0")
            }
        }

        val reopened = Store(file)
        assertEquals(Schema.version, version(file))
        assertEquals(false, reopened.job(id)!!.paused)
        assertEquals(Status.DONE, reopened.job(id)!!.status)
    }

    @Test
    fun `version eight databases gain compiler admission storage and retain existing jobs`() {
        val file = file()
        val id = Store(file).create("existing", "main", emptyList(), experiment = ExperimentSpec("existing"))
        DriverManager.getConnection("jdbc:sqlite:$file").use { db -> db.createStatement().use {
            it.execute("ALTER TABLE execution_sessions DROP COLUMN preparation_slots")
            it.execute("PRAGMA user_version = 8")
        } }
        val upgraded = Store(file)
        assertEquals("existing", upgraded.job(id)!!.name)
        assertEquals(Schema.version, version(file))
        upgraded.execution.createSession("prep", id, ExecutionClass.PREPARE, "release", ResourceBudget(1000, 512), ExecutionStore.token(),
            """{"Meta":{"lab_preparation_slots":"3"}}""", 1000)
        assertEquals(3, Store(file).execution.placement("prep").preparationSlots)
    }

    @Test
    fun `version nine databases gain failure diagnostics without changing existing progress`() {
        val file = file()
        val spec = ExperimentSpec("existing-preparation")
        val id = Store(file).create(spec.name, "main", emptyList(), experiment = spec)
        val token = ExecutionStore.token()
        val budget = ResourceBudget(1000, 512)
        val store = Store(file)
        store.execution.createSession("prep", id, ExecutionClass.PREPARE, "release", budget, token, "{}", 1000)
        store.execution.bind("prep", token, "allocation", "node", "release", 1000)
        val claim = store.execution.claim("prep", token, "allocation", 1100)!!
        store.execution.phase(claim.owner, "preparing", 1101)
        val progress = PreparationProgress("compiler-wait", 500, 500, emptyMap())
        store.execution.preparationProgress(claim.owner, progress, 1600)
        DriverManager.getConnection("jdbc:sqlite:$file").use { db -> db.createStatement().use {
            it.execute("ALTER TABLE iteration_attempts DROP COLUMN preparation_diagnostics")
            it.execute("PRAGMA user_version = 9")
        } }
        val upgraded = Store(file)
        assertEquals(progress, upgraded.execution.reports(id).single().preparation!!.progress)
        val diagnostic = PreparationDiagnostics(500, "compiler-wait", budget, "advisory-native", null, null, emptyMap(), 1000, 1000, 2)
        upgraded.execution.preparationDiagnostics(claim.owner, diagnostic, 1601)
        assertEquals(diagnostic, Store(file).execution.reports(id).single().preparation!!.diagnostics)
        assertEquals(Schema.version, version(file))
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
        for (from in Status.entries) for (to in Status.entries) {
            assertEquals(from == to || to in JobLifecycle.moves.getValue(from), JobLifecycle.allows(from, to), "$from to $to")
        }
        assertTrue(JobLifecycle.allows(Status.FAILED, Status.QUEUED))
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
        for (from in Status.entries) for (to in Status.entries) {
            assertEquals(from == to || to in CommandLifecycle.moves.getValue(from), CommandLifecycle.allows(from, to), "$from to $to")
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
        assertEquals(0 to 1, job.priority to job.parallel)
        assertEquals(1, store.reading { store.job(id)!!.commands.size })
    }
}
