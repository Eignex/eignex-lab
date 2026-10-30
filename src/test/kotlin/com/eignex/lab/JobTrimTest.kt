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
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JobTrimTest {
    private val day = 24 * 3_600_000L

    private fun job(jobs: Path, id: Long, bulk: Int): Path = jobs.resolve(id.toString()).also { dir ->
        Files.createDirectories(dir.resolve("cases/0/out/sub"))
        Files.writeString(dir.resolve("job.log"), "log\n")
        Files.writeString(dir.resolve("setup.log"), "setup\n")
        Files.writeString(dir.resolve("0.exit"), "0")
        Files.writeString(dir.resolve("selection-0.jsonl"), "{}\n")
        Files.write(dir.resolve("0.out"), ByteArray(bulk))
        Files.writeString(dir.resolve("cases/0/record.json"), "{}")
        Files.write(dir.resolve("cases/0/p.sol"), ByteArray(bulk))
        Files.write(dir.resolve("cases/0/out/sub/profile.jfr"), ByteArray(bulk))
    }

    @Test
    fun `a trim keeps logs, exits, termination reasons, selections and records, and nothing else`() {
        for (kept in listOf("job.log", "setup.log", "sha", "3.exit", "3.termination", "selection-1.jsonl", "cases/4/record.json", "trimmed")) {
            assertTrue(JobTrim.keeps(Path(kept)), kept)
        }
        for (gone in listOf("0.out", "0.err", "0.pid", "cases/0/p.sol", "cases/0/out/timings.json", "cases/0/out/a/b.json", "aws-instances")) {
            assertFalse(JobTrim.keeps(Path(gone)), gone)
        }
    }

    @Test
    fun `a job a week ended is trimmed once, a recent one is not, and the log says so`() {
        val jobs = Files.createTempDirectory("jobs")
        val old = job(jobs, 1, 1000)
        val recent = job(jobs, 2, 1000)
        val now = 30 * day
        val logged = mutableListOf<Long>()
        val trim = JobTrim(jobs, { listOf(1L to now - 8 * day, 2L to now - day) }, { Long.MAX_VALUE }, { id, _ -> logged += id }, { now })

        assertEquals(3000L, trim.run())
        assertEquals(0L, trim.run())
        assertEquals(listOf(1L), logged)
        assertFalse(Files.exists(old.resolve("0.out")))
        assertFalse(Files.exists(old.resolve("cases/0/out")))
        assertTrue(Files.exists(old.resolve("cases/0/record.json")) && Files.exists(old.resolve("job.log")))
        assertTrue(Files.exists(old.resolve(JobTrim.MARKER)))
        assertTrue(Files.exists(recent.resolve("0.out")))
    }

    @Test
    fun `on a short disk the largest ended jobs go first, until enough is free`() {
        val jobs = Files.createTempDirectory("jobs")
        job(jobs, 1, 100)
        val big = job(jobs, 2, 5000)
        val small = job(jobs, 3, 10)
        val now = 30 * day
        var free = JobTrim.TARGET_FREE_BYTES - 1
        val trim = JobTrim(jobs, { listOf(1L to now - day, 2L to now - day, 3L to now - day) }, { free }, { _, _ -> free += 1 }, { now })

        trim.run()

        assertTrue(Files.exists(big.resolve(JobTrim.MARKER)))
        assertFalse(Files.exists(small.resolve(JobTrim.MARKER)))
        assertTrue(Files.exists(jobs.resolve("1/0.out")))
    }

    @Test
    fun `a held job is not trimmed by age, and is once its hold ends, the trim's log naming it while held`() {
        val jobs = Files.createTempDirectory("jobs")
        val old = job(jobs, 1, 1000)
        val now = 30 * day
        var held = setOf(1L)
        val reports = mutableListOf<String>()
        val trim = JobTrim(jobs, { listOf(1L to now - 8 * day) }, { Long.MAX_VALUE }, { _, _ -> }, { now }, held = { held }, report = { reports += it })

        assertEquals(0L, trim.run())
        assertTrue(Files.exists(old.resolve("0.out")))
        assertEquals(listOf("job trim: skipped held job 1: held for download"), reports)

        held = emptySet()
        assertEquals(3000L, trim.run())
        assertTrue(Files.exists(old.resolve(JobTrim.MARKER)))
    }

    @Test
    fun `a low disk leaves a held job and one within its grace alone, and trims them once the hold ends or the grace passes`() {
        val jobs = Files.createTempDirectory("jobs")
        val heldJob = job(jobs, 1, 5000)
        val recent = job(jobs, 2, 4000)
        val past = job(jobs, 3, 10)
        var now = 30 * day
        var held = setOf(1L)
        val reports = mutableListOf<String>()
        val free = JobTrim.TARGET_FREE_BYTES - 1
        val trim = JobTrim(
            jobs, { listOf(1L to 30 * day - 8 * 3_600_000L, 2L to 30 * day - 3_600_000L, 3L to 30 * day - 7 * 3_600_000L) },
            { free }, { _, _ -> }, { now }, held = { held }, graceMs = 6 * 3_600_000L, criticalFreeBytes = 5 * BYTES_PER_GB,
            report = { reports += it },
        )

        trim.run()
        assertFalse(Files.exists(heldJob.resolve(JobTrim.MARKER)))
        assertFalse(Files.exists(recent.resolve(JobTrim.MARKER)))
        assertTrue(Files.exists(past.resolve(JobTrim.MARKER)))
        assertEquals(listOf("job trim: skipped held job 1: held for download"), reports)

        held = emptySet()
        trim.run()
        assertTrue(Files.exists(heldJob.resolve(JobTrim.MARKER)))
        assertFalse(Files.exists(recent.resolve(JobTrim.MARKER)))

        now += 6 * 3_600_000L
        trim.run()
        assertTrue(Files.exists(recent.resolve(JobTrim.MARKER)))
    }

    @Test
    fun `a critically low disk trims a job within its grace, but never a held one`() {
        val jobs = Files.createTempDirectory("jobs")
        val heldJob = job(jobs, 1, 5000)
        val recent = job(jobs, 2, 4000)
        val now = 30 * day
        val reports = mutableListOf<String>()
        val trim = JobTrim(
            jobs, { listOf(1L to now - 60_000L, 2L to now - 60_000L) }, { BYTES_PER_GB }, { _, _ -> }, { now },
            held = { setOf(1L) }, graceMs = 6 * 3_600_000L, criticalFreeBytes = 5 * BYTES_PER_GB, report = { reports += it },
        )

        trim.run()

        assertFalse(Files.exists(heldJob.resolve(JobTrim.MARKER)))
        assertTrue(Files.exists(heldJob.resolve("0.out")))
        assertTrue(Files.exists(recent.resolve(JobTrim.MARKER)))
        assertEquals(listOf("job trim: skipped held job 1: held for download"), reports)
    }

    @Test
    fun `LAB_TRIM_AGE_DAYS and LAB_JOBS_TARGET_FREE_GB set when a job is old and when the job files' volume is short`() {
        val jobs = Files.createTempDirectory("jobs")
        val month = job(jobs, 1, 1000)
        val now = 100 * day
        val ended = { listOf(1L to now - 30 * day) }
        val reasons = mutableListOf<String>()

        // A 90-day age keeps a month-old job on a volume with room; the short one below the target trims it.
        JobTrim(jobs, ended, { 200 * BYTES_PER_GB }, { _, why -> reasons += why }, { now },
            targetFreeBytes = 100 * BYTES_PER_GB, maxAgeMs = 90 * day).run()
        assertFalse(Files.exists(month.resolve(JobTrim.MARKER)))
        JobTrim(jobs, ended, { 99 * BYTES_PER_GB }, { _, why -> reasons += why }, { now },
            targetFreeBytes = 100 * BYTES_PER_GB, maxAgeMs = 90 * day).run()
        assertTrue(Files.exists(month.resolve(JobTrim.MARKER)))
        assertTrue("under 100 GB free" in reasons.single(), reasons.single())

        val old = job(jobs, 2, 1000)
        JobTrim(jobs, { listOf(2L to now - 91 * day) }, { Long.MAX_VALUE }, { _, why -> reasons += why }, { now }, maxAgeMs = 90 * day).run()
        assertTrue(Files.exists(old.resolve(JobTrim.MARKER)))
        assertTrue("over 90 days ago" in reasons.last(), reasons.last())
    }

    @Test
    fun `the trim's settings come from the environment's defaults, the job files from LAB_JOBS`() {
        val data = Files.createTempDirectory("lab")
        val config = Config(dataDir = data, auth = Auth.Disabled)

        assertEquals(JobTrim.MAX_AGE_MS, config.trimAgeMs)
        assertEquals(25 * BYTES_PER_GB, config.jobsTargetFreeBytes)
        assertEquals(data.resolve("jobs"), config.jobsDir)
    }

    @Test
    fun `a jobs directory that is not there is left alone, not made`() {
        val jobs = Files.createTempDirectory("lab").resolve("gone/jobs")

        assertEquals(0L, JobTrim(jobs, { listOf(1L to 0L) }, { 0L }, { _, _ -> }, { 100 * day }).run())
        assertFalse(Files.exists(jobs.parent))
    }

    @Test
    fun `the critical floor defaults to the queue's, so a held queue never waits on a grace`() {
        val config = Config(dataDir = Files.createTempDirectory("lab"), auth = Auth.Disabled, minFreeBytes = 12 * BYTES_PER_GB)
        assertEquals(config.minFreeBytes, config.trimCriticalFreeBytes)
        assertEquals(JobTrim.DEFAULT_CRITICAL_FREE_BYTES, Config(dataDir = Files.createTempDirectory("lab"), auth = Auth.Disabled).trimCriticalFreeBytes)
        assertEquals(JobTrim.DEFAULT_GRACE_MS, Config(dataDir = Files.createTempDirectory("lab"), auth = Auth.Disabled).trimGraceMs)
    }
}
