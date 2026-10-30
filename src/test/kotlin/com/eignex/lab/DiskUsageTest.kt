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
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiskUsageTest {
    private fun alive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    private fun started(handle: ProcessHandle) = handle.info().startInstant().get().toEpochMilli()

    private fun awaitGone(pid: Long) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (alive(pid) && System.nanoTime() < until) Thread.sleep(50)
    }

    @Test
    fun `a du that hangs is killed at its timeout with everything it started`() {
        val dir = Files.createTempDirectory("du")
        val child = dir.resolve("child")
        val pids = dir.resolve("pids")
        val runner = DuRunner(pids, timeoutMs = 1500, command = listOf("bash", "-c", "sleep 600 & echo \$! > $child; wait", "du"))

        val begun = System.nanoTime()
        assertNull(runner.run(listOf("/nowhere")))

        assertTrue(System.nanoTime() - begun < TimeUnit.SECONDS.toNanos(15))
        val pid = child.readText().trim().toLong()
        awaitGone(pid)
        assertFalse(alive(pid), "the hanging du's child $pid is still running")
        assertTrue(pids.toFile().listFiles().orEmpty().isEmpty(), "its record is left in $pids")
    }

    @Test
    fun `a measurement asked for while one runs does not start a second`() {
        val dir = Files.createTempDirectory("lab")
        val gate = dir.resolve("gate")
        val calls = dir.resolve("calls")
        val script = "echo x >> $calls; while [ ! -e $gate ]; do sleep 0.05; done; for p in \"\$@\"; do printf '4\\t%s\\n' \"\$p\"; done"
        val data = dir.resolve("data").createDirectories()
        data.resolve("work").createDirectories()
        val config = Config(dataDir = data, corpusDir = dir.resolve("corpus"), auth = Auth.Disabled)
        val usage = DiskUsage(config, { emptySet() }, DuRunner(data.resolve(DuRunner.PID_DIR), command = listOf("bash", "-c", script, "du")),
            0, System::currentTimeMillis)

        var first: DiskReport? = null
        val running = thread { first = usage.measure() }
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!calls.exists() && System.nanoTime() < until) Thread.sleep(20)

        assertNull(usage.measure(), "a second measurement started while the first ran")
        assertEquals(1, calls.readLines().size)
        gate.writeText("")
        running.join(30_000)
        assertNotNull(first)
    }

    @Test
    fun `only the recorded walks of a lab process that is gone are killed at startup`() {
        val pids = Files.createTempDirectory("du").resolve(DuRunner.PID_DIR).createDirectories()
        fun sleeper() = ProcessBuilder(inSession("sleep", "600")).start().toHandle()
        val gone = ProcessBuilder("true").start().also { it.waitFor() }.toHandle()
        val self = ProcessHandle.current()
        val leftover = sleeper()
        val unrecorded = sleeper()
        val ownersAlive = sleeper()
        val reusedPid = sleeper()
        fun record(walk: ProcessHandle, start: Long, owner: Long, ownerStart: Long) =
            pids.resolve(walk.pid().toString()).writeText("${walk.pid()} $start $owner $ownerStart\n")
        record(leftover, started(leftover), gone.pid(), 1)
        record(ownersAlive, started(ownersAlive), self.pid(), started(self))
        record(reusedPid, 1, gone.pid(), 1)
        try {
            val killed = DuRunner.reapLeftovers(pids)

            assertEquals(listOf(leftover.pid()), killed)
            awaitGone(leftover.pid())
            assertFalse(alive(leftover.pid()))
            assertTrue(alive(unrecorded.pid()) && alive(ownersAlive.pid()) && alive(reusedPid.pid()))
            assertTrue(pids.resolve(ownersAlive.pid().toString()).exists(), "a live lab process's record was dropped")
        } finally {
            listOf(leftover, unrecorded, ownersAlive, reusedPid).forEach { it.destroyForcibly() }
        }
    }

    @Test
    fun `job directories are measured again only when new, active or changed, across a restart too`() {
        val dir = Files.createTempDirectory("lab")
        val log = dir.resolve("log")
        val script = "for p in \"\$@\"; do echo \"\$p\" >> $log; printf '4\\t%s\\n' \"\$p\"; done"
        val data = dir.resolve("data").createDirectories()
        val jobs = dir.resolve("jobs").createDirectories()
        val corpus = dir.resolve("corpus").createDirectories()
        val config = Config(dataDir = data, jobsDir = jobs, corpusDir = corpus, auth = Auth.Disabled)
        val now = System.currentTimeMillis()
        fun job(id: Long, ageMs: Long): Path =
            jobs.resolve("$id").createDirectories().also { Files.setLastModifiedTime(it, FileTime.fromMillis(now - ageMs)) }
        var active = setOf<Long>()
        fun usage() = DiskUsage(config, { active }, DuRunner(data.resolve(DuRunner.PID_DIR), command = listOf("bash", "-c", script, "du")),
            0, System::currentTimeMillis)
        fun measured(): Set<String> = (if (log.exists()) log.readLines() else emptyList()).toSet().also { Files.deleteIfExists(log) }
        fun jobsMeasured(lines: Set<String>) = lines.filter { it.startsWith("$jobs/") }.map { it.substringAfterLast('/').toLong() }.toSet()
        listOf(1L, 2L, 3L).forEach { job(it, 2 * 3_600_000L) }

        val usage = usage()
        usage.measure()
        val first = measured()
        assertEquals(setOf(1L, 2L, 3L), jobsMeasured(first))
        assertTrue(corpus.toString() in first)

        job(4, 2 * 3_600_000L)
        job(2, 3_600_000L)
        active = setOf(3L)
        val report = usage.measure()!!
        val second = measured()
        assertEquals(setOf(2L, 3L, 4L), jobsMeasured(second))
        assertFalse(corpus.toString() in second, "the corpus was walked again before its interval")
        assertEquals(4 * 4096L, report.places.single { it.label == "job files" }.bytes)
        assertEquals(setOf(1L, 2L, 3L, 4L), report.largestJobs.map { it.first }.toSet())

        active = emptySet()
        val restarted = usage()
        restarted.measure()
        // Job 3 was active when last measured, so once more; the rest come from the saved sizes.
        assertEquals(setOf(3L), jobsMeasured(measured()))
    }
}
