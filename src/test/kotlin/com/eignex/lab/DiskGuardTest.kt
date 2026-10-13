package com.eignex.lab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiskGuardTest {
    @Test
    fun `a disk below the floor holds the queue until space returns`() {
        val readings = ArrayDeque(listOf(5L, 9L, 10L, 20L))
        val guard = DiskGuard(minFreeBytes = 10L) { readings.removeFirst() }

        val allowed = List(4) { guard.allowsWork() }

        assertEquals(listOf(false, false, true, true), allowed)
    }

    @Test
    fun `disk usage measures the lab's data part by part, largest first`() {
        val data = java.nio.file.Files.createTempDirectory("lab")
        data.resolve("jobs/1").toFile().mkdirs()
        data.resolve("jobs/1/big").toFile().writeBytes(ByteArray(200_000))
        data.resolve("work").toFile().mkdirs()
        data.resolve("work/small").toFile().writeBytes(ByteArray(10_000))
        data.resolve("stray").toFile().writeBytes(ByteArray(50_000))
        val report = DiskUsage(Config(dataDir = data, corpusDir = data.resolve("corpus"), passwordHash = null)).measure()
        val ours = report.places.filter { it.path.startsWith(data.toString()) }

        assertEquals(listOf("job files", "data: stray", "job worktrees"), ours.map { it.label })
        assertTrue(ours.first().bytes!! >= 200_000)
        assertEquals(listOf(1L), report.largestJobs.map { it.first })
    }
}
