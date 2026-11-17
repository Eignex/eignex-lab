package com.eignex.lab

import com.eignex.lab.domain.*
import com.eignex.lab.store.Store
import com.eignex.lab.store.commandFinished
import com.eignex.lab.web.JobDirView
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobDirViewTest {
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
