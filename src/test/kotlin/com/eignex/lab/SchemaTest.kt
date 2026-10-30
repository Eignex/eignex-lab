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
