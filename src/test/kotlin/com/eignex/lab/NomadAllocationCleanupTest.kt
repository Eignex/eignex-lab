package com.eignex.lab

import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class NomadAllocationCleanupTest {
    private val root = Files.createTempDirectory("allocation-cleanup")
    private val store = Store(root.resolve("lab.db"))
    private val at = 10_000_000L
    private val target = NomadQueueTarget(NomadBatchJob("template", NomadPlatform.MAC, "native",
        NomadCpuCapacity(8000, 8), ResourceBudget(1000, 1024), listOf("/releases/fixture/worker")), setOf("arch:arm64"))
    private val settings = NomadQueueSettings("release", "http://127.0.0.1:8091", listOf(target))
    private val job = store.create("fixture", "main", emptyList(), experiment = ExperimentSpec("fixture"))
    private val session = store.execution.createSession("session", job, ExecutionClass.SOLVE, "release", ResourceBudget(1000, 1024), "secret-".repeat(8), "{}")
    private val control = object : NomadControl {
        var rows = emptyList<JsonObject>()
        var fail: String? = null
        var eligible = true
        val attempts = mutableListOf<String>()
        override fun job(id: String): JsonObject? = null
        override fun submit(job: JsonObject) { error("cleanup cannot submit") }
        override fun stop(id: String) { error("cleanup cannot stop") }
        override fun allocations(job: String) = emptyList<NomadAllocation>()
        override fun allocation(id: String): NomadAllocation? = null
        override fun jwks() = buildJsonObject {}
        override fun nodes() = listOf(
            buildJsonObject { put("ID", "native"); put("NodeClass", "native"); put("Status", "ready") },
            buildJsonObject { put("ID", "cloud"); put("NodeClass", "cloud"); put("Status", "ready") },
            buildJsonObject { put("ID", "down"); put("NodeClass", "native"); put("Status", "down") },
        )
        override fun nodeAllocations(id: String): List<JsonObject> { assertEquals("native", id); return rows }
        override fun collectAllocation(id: String): Boolean { attempts += id; if (id == fail) error("client unavailable"); return eligible }
    }
    private fun row(id: String, status: String, modified: Long = at - 3_600_000, known: Boolean = true) = buildJsonObject {
        put("ID", id); put("JobID", if (known) session.nomadJobId else "unrelated"); put("ClientStatus", status)
        put("Namespace", "default")
        put("ModifyTime", modified * 1_000_000)
    }

    @Test fun `only old terminal allocations belonging to this lab are collected`() {
        control.rows = listOf(row("done", "complete"), row("failed", "failed"), row("live", "running"),
            row("pending", "pending"), row("unknown", "unknown"), row("lost", "lost"),
            row("recent", "complete", at - 1000), row("future", "complete", at + 1000),
            row("missing-time", "complete", 0), row("other", "complete", known = false),
            JsonObject(row("foreign", "complete") + ("Namespace" to JsonPrimitive("other"))))
        val cleanup = NomadAllocationCleanup(control, store, settings) { at }
        repeat(2) { cleanup.reconcile() }
        assertEquals(listOf("done", "failed"), control.attempts)
    }

    @Test fun `one unavailable allocation does not prevent cleanup and is retried`() {
        control.rows = listOf(row("unavailable", "complete"), row("done", "complete"))
        control.fail = "unavailable"
        val cleanup = NomadAllocationCleanup(control, store, settings) { at }
        cleanup.reconcile()
        control.fail = null
        cleanup.reconcile()
        assertEquals(listOf("unavailable", "done", "unavailable"), control.attempts)
    }

    @Test fun `a client eligibility race is retried without treating it as collected`() {
        control.rows = listOf(row("done", "complete"))
        control.eligible = false
        val cleanup = NomadAllocationCleanup(control, store, settings) { at }
        cleanup.reconcile()
        control.eligible = true
        cleanup.reconcile(); cleanup.reconcile()
        assertEquals(listOf("done", "done"), control.attempts)
    }
}
