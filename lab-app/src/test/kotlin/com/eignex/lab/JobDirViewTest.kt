package com.eignex.lab

import com.eignex.lab.domain.*
import com.eignex.lab.store.Store
import com.eignex.lab.store.commandFinished
import com.eignex.lab.util.MacDirectoryEntries
import com.eignex.lab.web.JobDirView
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobDirViewTest {
    @Test fun `large command views use one archive-directory batch and preserve output links`() {
        val config = Config(dataDir = Files.createTempDirectory("batched-job-dir"), auth = Auth.Disabled)
        val store = Store(config.database)
        val id = store.create("files", "main", List(64) { "true" to 1L }, experiment = ExperimentSpec("files"))
        val dir = config.jobDir(id)
        Files.createDirectories(dir.parent)
        val archive = Files.createTempDirectory("archived-job-dir")
        Files.createSymbolicLink(dir, archive)
        Files.writeString(archive.resolve("linked.out"), "linked")
        Files.createSymbolicLink(archive.resolve("1.out"), archive.resolve("linked.out"))
        Termination.record(Termination.file(archive, 0), "reason", at = 1)
        var reads = 0
        val view = JobDirView.read(config.paths, store.job(id)!!, entries = { path, checkActive ->
            reads++
            checkActive()
            assertEquals(archive.toRealPath(), path)
            listOf(
                MacDirectoryEntries.ListingEntry("job.log", false, true, 3),
                MacDirectoryEntries.ListingEntry("0.out", false, true, 17),
                MacDirectoryEntries.ListingEntry("0.err", true, false, 0),
                MacDirectoryEntries.ListingEntry("0.termination", false, true, 9),
                MacDirectoryEntries.ListingEntry("1.out", false, false, 0),
                MacDirectoryEntries.ListingEntry("unrelated", false, true, 99),
            )
        })
        assertEquals(1, reads)
        assertEquals(setOf("job.log"), view.logs)
        assertEquals(mapOf("0.out" to 17L, "1.out" to 6L), view.outputs)
        assertEquals(mapOf(0 to listOf("1 reason")), view.terminations)
        val actual = JobDirView.read(config.paths, store.job(id)!!)
        assertEquals(mapOf("1.out" to 6L), actual.outputs)
        assertEquals(view.terminations, actual.terminations)
    }

    @Test fun `unsupported directory batches retain ordinary files and overviews never enumerate`() {
        val config = Config(dataDir = Files.createTempDirectory("fallback-job-dir"), auth = Auth.Disabled)
        val store = Store(config.database)
        val id = store.create("files", "main", List(64) { "true" to 1L }, experiment = ExperimentSpec("files"))
        val dir = Files.createDirectories(config.jobDir(id))
        Files.writeString(dir.resolve("job.log"), "log")
        Files.writeString(dir.resolve("0.out"), "first")
        val job = store.job(id)!!
        var reads = 0
        val view = JobDirView.read(config.paths, job, entries = { _, _ -> reads++; null })
        assertEquals(1, reads)
        assertEquals(setOf("job.log"), view.logs)
        assertEquals(mapOf("0.out" to 5L), view.outputs)
        val overview = JobDirView.read(config.paths, job, showCommands = false, entries = { _, _ ->
            error("an overview must not enumerate the command directory")
        })
        assertEquals(setOf("job.log"), overview.logs)
        assertTrue(overview.outputs.isEmpty())
    }

    @Test fun `overview skips command files and failed view reads only failed commands`() {
        val config = Config(dataDir = Files.createTempDirectory("job-dir-view"), auth = Auth.Disabled)
        val store = Store(config.database)
        val id = store.create("files", "main", List(3) { "true" to 1L }, experiment = ExperimentSpec("files"))
        store.commandFinished(id, 0, 0)
        store.commandFinished(id, 1, 1)
        val dir = Files.createDirectories(config.jobDir(id))
        Files.writeString(dir.resolve("job.log"), "job")
        Files.writeString(dir.resolve("0.exit"), "0")
        Files.createDirectories(dir.resolve("cases/0/out"))
        Files.writeString(dir.resolve("cases/0/out/artifact"), "artifact")
        for (index in 0..2) {
            Files.writeString(dir.resolve("$index.out"), "out")
            Files.writeString(dir.resolve("$index.err"), "err")
            Termination.record(Termination.file(dir, index), "reason", at = 1)
        }
        val job = store.job(id)!!
        val overview = JobDirView.read(config.paths, job, showCommands = false)
        assertEquals(setOf("job.log"), overview.logs)
        assertTrue(overview.terminations.isEmpty())
        assertTrue(overview.outputs.isEmpty())
        assertTrue(overview.produced.isEmpty())
        assertNull(overview.exit)

        val failed = JobDirView.read(config.paths, job, failedOnly = true)
        assertEquals(mapOf(1 to listOf("1 reason")), failed.terminations)
        assertEquals(mapOf("1.out" to 3L, "1.err" to 3L), failed.outputs)
        val all = JobDirView.read(config.paths, job)
        assertEquals(setOf(0, 1, 2), all.terminations.keys)
        assertEquals(6, all.outputs.size)
    }

    @Test fun `profile overview retains exit termination and produced files without command rows`() {
        val config = Config(dataDir = Files.createTempDirectory("profile-dir-view"), auth = Auth.Disabled)
        val store = Store(config.database)
        val spec = ExperimentSpec("profile", profile = ProfileMain("com.eignex.tools.Main", classpath = listOf("lib/*")))
        val id = store.create("profile", "main", listOf("true" to 1L), experiment = spec)
        val dir = Files.createDirectories(config.jobDir(id))
        Files.writeString(dir.resolve("0.exit"), "timeout\n")
        Files.createDirectories(dir.resolve("cases/0/out"))
        Files.writeString(dir.resolve("cases/0/out/profile.jfr"), "jfr")
        Termination.record(Termination.file(dir, 0), "timeout: stopped", at = 1)
        val job = store.jobsByIds(listOf(id)).single()
        assertTrue(job.commands.isEmpty())

        val view = JobDirView.read(config.paths, job, showCommands = false)
        assertEquals("timeout", view.exit)
        assertEquals(mapOf(0 to listOf("1 timeout: stopped")), view.terminations)
        assertEquals(listOf("cases/0/out/profile.jfr"), view.produced)
    }
}
