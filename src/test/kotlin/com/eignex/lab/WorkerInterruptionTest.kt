package com.eignex.lab

import com.eignex.lab.domain.ResourceBudget
import com.eignex.lab.domain.ResourceCheckpoint
import com.eignex.lab.util.deleteTree
import com.eignex.lab.worker.*
import java.nio.file.Files
import kotlin.test.*

class WorkerInterruptionTest {
    @Test fun `warning stops new claims immediately and bounds active work without resetting its deadline`() {
        val root = Files.createTempDirectory("worker-interruption")
        try {
            val file = root.resolve("draining"); var at = 1_000_000_000L
            val interruption = WorkerInterruption(file) { at }
            assertFalse(interruption.requested()); assertEquals(Long.MAX_VALUE, interruption.remainingMs())
            Files.createFile(file)
            assertTrue(interruption.requested()); assertEquals(45_000L, interruption.remainingMs())
            at += 44_999_000_000; assertEquals(1L, interruption.remainingMs())
            Files.delete(file); assertTrue(interruption.requested())
            at += 1_000_000; assertEquals(0L, interruption.remainingMs())
            at += 5_000_000_000; assertEquals(0L, interruption.remainingMs())
        } finally { deleteTree(root) }
    }

    @Test fun `resource snapshots explicitly distinguish cumulative lower bounds from missing CPU`() {
        val missing = ResourceCheckpoint(1, ResourceBudget(1000, 512), "advisory-native", null, null, emptyMap(), "worker-cumulative-lower-bound")
        assertNull(missing.cpuUsec)
        assertEquals(245620810L, missing.copy(counters = mapOf("cpu.stat" to "usage_usec 245620810\nuser_usec 238508662")).cpuUsec)
        assertFailsWith<IllegalArgumentException> { missing.copy(scope = "final") }
        assertFailsWith<IllegalArgumentException> { missing.copy(retirement = "source-build-failed") }
        assertFailsWith<IllegalArgumentException> { missing.copy(counters = mapOf("cpu.stat" to "usage_usec -1")) }
        assertFailsWith<IllegalArgumentException> { missing.copy(counters = mapOf("command" to "arbitrary")) }
    }
}
