package com.eignex.lab

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
}
