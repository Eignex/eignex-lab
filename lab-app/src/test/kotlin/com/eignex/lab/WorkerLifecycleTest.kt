package com.eignex.lab

import com.eignex.lab.domain.ResourceBudget
import com.eignex.lab.util.deleteTree
import com.eignex.lab.worker.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

class WorkerLifecycleTest {
    private val observation = ResourceObservation("cgroup-v2-hard", ResourceBudget(1000, 512),
        "100000 100000", "536870912", "25", "Linux", "amd64", emptyMap())

    @Test fun `preclaim retirement retains startup and final lower bounds in the uploaded console`() {
        val root = Files.createTempDirectory("worker-lifecycle")
        try {
            val file = root.resolve("worker-console.log")
            var at = 0L; var cpu = 10L
            WorkerConsole(file, WorkerLogs.MAX_WORKER_BYTES).use {
                val lifecycle = WorkerLifecycle(counters = { mapOf("cpu.stat" to "usage_usec $cpu") }, clock = { at })
                lifecycle.registered(observation)
                at = 6_342_000_000L; cpu = 1200
                lifecycle.reason = WorkerLifecycle.Reason.NO_WORK
                lifecycle.retire()
                lifecycle.retire(WorkerLifecycle.Reason.WORKER_SHUTDOWN)
            }
            val receipts = Files.readAllLines(file).map { Json.decodeFromString<WorkerLifecycle.Receipt>(it.removePrefix("worker lifecycle: ")) }
            assertEquals(listOf("registered", "retired"), receipts.map { it.stage })
            assertTrue(receipts.none { it.claimed })
            assertEquals(listOf(10L, 1200L), receipts.map { it.resources?.cpuUsec })
            assertEquals(6342L, receipts.last().resources?.elapsedMs)
            assertEquals(WorkerLifecycle.Reason.NO_WORK, receipts.last().reason)
            assertTrue(Files.size(file) < 16 * 1024)
        } finally { deleteTree(root) }
    }

    @Test fun `missing counters remain unknown and diagnostic read failures cannot hide retirement`() {
        val lines = mutableListOf<String>()
        var unreadable = false
        val lifecycle = WorkerLifecycle(counters = { if (unreadable) error("credential-bearing error must not be retained") else emptyMap() }, emit = { lines.add(it) })
        lifecycle.registered(observation.copy(enforcement = "advisory-native", cpuMax = null, memoryMax = null))
        lifecycle.claimed = true
        unreadable = true
        lifecycle.retire(WorkerLifecycle.Reason.PROVIDER_INTERRUPTION)
        val receipts = lines.map { Json.decodeFromString<WorkerLifecycle.Receipt>(it.removePrefix("worker lifecycle: ")) }
        assertNull(receipts.first().resources?.cpuUsec)
        assertNull(receipts.last().resources)
        assertTrue(receipts.last().claimed)
        assertEquals(WorkerLifecycle.Reason.PROVIDER_INTERRUPTION, receipts.last().reason)
        assertFalse(lines.joinToString().contains("credential-bearing"))
    }
}
