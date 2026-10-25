package com.eignex.lab

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CancelTest {
    /**
     * A lab over a local repository whose `main` holds a fake build ([gradlew]) and a fake bench ([bench]), and a
     * reference job of it taken from the queue. Retries wait only milliseconds, so a retried step would show at once.
     * `PIDFILE` in either script names [pidFile], where a step can say which process it is.
     */
    private class Lab(gradlew: String, bench: String) {
        val root: Path = Files.createTempDirectory("cancel")
        val repo: Path = root.resolve("repo").createDirectories()
        val pidFile: Path = root.resolve("step.pid")
        val config = Config(
            dataDir = root.resolve("data"),
            repoUrl = repo.toString(),
            retry = Backoff(3, 10, 10),
            buildRetry = Backoff(3, 10, 10),
            updateCheckSec = 0,
        )
        val store = Store(config.dataDir.createDirectories().resolve("lab.db"))
        val runner = Runner(config, store, sourceUrl = { repo.toString() })
        val id: Long

        init {
            script(repo.resolve("gradlew"), gradlew)
            script(repo.resolve("klause-bench/build/install/klause-bench/bin/klause-bench"), bench)
            git("init", "-q", "-b", "main")
            git("add", "-A", "-f")
            git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "fake klause")
            val spec = ExperimentSpec("ref", listOf(mapOf("suite" to "s")), base = mapOf("backend" to "reference", "solver" to "cvc5"))
            id = store.create("ref", "main", emptyList(), experiment = spec)
            checkNotNull(store.next())
        }

        val setupLog: File get() = config.jobDir(id).resolve("setup.log").toFile()
        val jobLog: String get() = config.jobDir(id).resolve("job.log").toFile().takeIf { it.isFile }?.readText().orEmpty()

        /** Run the job, cancelling it once [marker] shows in its setup log; how long the run took after the cancel. */
        fun cancelDuring(marker: String): Long {
            var cancelledAt = 0L
            val canceller = thread {
                assertTrue(eventually { setupLog.isFile && marker in setupLog.readText() }, "never reached $marker")
                Thread.sleep(300)
                cancelledAt = System.nanoTime()
                assertEquals(CancelOutcome.REQUESTED, store.requestCancel(id))
            }
            runner.runJob(checkNotNull(store.job(id)))
            val ended = System.nanoTime()
            canceller.join()
            return (ended - cancelledAt) / 1_000_000
        }

        private fun script(path: Path, text: String) {
            path.parent.createDirectories()
            path.writeText("#!/bin/sh\n${text.replace("PIDFILE", quote(pidFile.toString()))}\n")
            path.toFile().setExecutable(true)
        }

        private fun git(vararg args: String) {
            val (exit, out) = gitIn(repo.toFile(), args.toList())
            check(exit == 0) { out }
        }
    }

    @Test
    fun `a cancel stops a long problem selection within seconds, ends the job cancelled and retries nothing`() {
        // The bench's pid shows whether the step's process went with the cancel.
        val lab = Lab(gradlew = "exit 0", bench = "echo \$\$ > PIDFILE; sleep 600")

        val afterCancel = lab.cancelDuring("klause-bench select")

        assertTrue(afterCancel < 3_500, "the job ran on $afterCancel ms after its cancel")
        assertEquals(Status.CANCELLED, lab.store.job(lab.id)!!.status)
        assertEquals(1, lab.setupLog.readText().lines().count { "klause-bench select" in it }, lab.setupLog.readText())
        val pid = lab.pidFile.toFile().readText().trim().toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "the selection is still running")
        assertTrue("cancel requested — stopping selecting problems for selection 0" in lab.jobLog, lab.jobLog)
        assertTrue(lab.jobLog.trimEnd().lines().any { it.endsWith("job cancelled") }, lab.jobLog)
        assertFalse("retrying" in lab.jobLog, lab.jobLog)
        assertFalse(lab.config.worktree(lab.id).exists())
    }

    @Test
    fun `a cancel stops a long build within seconds and retries nothing`() {
        val lab = Lab(gradlew = "sleep 600", bench = "exit 0")

        val afterCancel = lab.cancelDuring("./gradlew")

        assertTrue(afterCancel < 3_500, "the job ran on $afterCancel ms after its cancel")
        assertEquals(Status.CANCELLED, lab.store.job(lab.id)!!.status)
        assertEquals(1, lab.setupLog.readText().lines().count { it.startsWith("$ ./gradlew") }, lab.setupLog.readText())
        assertTrue("cancel requested — stopping building " in lab.jobLog, lab.jobLog)
        assertFalse("retrying" in lab.jobLog, lab.jobLog)
    }

    @Test
    fun `a cancel kills a running local case at once`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"))
        val store = Store(config.dataDir.resolve("lab.db"))
        val id = store.create("kills", "main", listOf("sleep 600" to 600L, "sleep 600" to 600L), parallel = 2)
        val dir = config.jobDir(id).createDirectories()
        config.worktree(id).createDirectories()
        store.next()
        store.setup(id, "head")
        var cancelledAt = 0L
        val canceller = thread {
            eventually { dir.resolve("0.pid").exists() && dir.resolve("1.pid").exists() }
            cancelledAt = System.nanoTime()
            store.requestCancel(id)
        }

        assertEquals(Dispatched.CANCELLED, Runner(config, store).dispatch(store.job(id)!!, dir))
        val afterCancel = (System.nanoTime() - cancelledAt) / 1_000_000
        canceller.join()

        assertTrue(afterCancel < 2_500, "the cases ran on $afterCancel ms after the cancel")
        for (index in 0..1) {
            assertEquals(listOf(Termination.CANCEL), Termination.read(dir, index).map { it.substringAfter(' ') })
            val leader = dir.resolve("$index.pid").toFile().readText().trim().split(" ").first().toLong()
            assertFalse(signalGroups("0", listOf(leader)))
        }
        val log = config.jobDir(id).resolve("job.log").toFile().readText()
        assertTrue("cancel requested — stopping 2 running cases" in log, log)
    }

    private companion object {
        fun eventually(check: () -> Boolean): Boolean {
            val until = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < until) {
                if (check()) return true
                Thread.sleep(50)
            }
            return check()
        }
    }
}
