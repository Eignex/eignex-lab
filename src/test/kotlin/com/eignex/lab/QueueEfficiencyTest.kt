package com.eignex.lab

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.*

class QueueEfficiencyTest {
    @Test fun `racing native admissions cannot exceed the shared CPU budget`() {
        val store = Store(Files.createTempDirectory("native-budgets").resolve("lab.db"))
        val spec = ExperimentSpec("native", profile = ProfileMain(main = "com.eignex.Main", classpath = listOf("tools/lib/*")))
        val jobs = (1..3).map { store.create("native-$it", "main", emptyList(), experiment = spec) }
        val limit = ResourceBudget(4000, 4096)
        val budget = ResourceBudget(2000, 1024)
        val payload = NomadBatchJob("fixture", NomadPlatform.MAC, "native", NomadCpuCapacity(28000, 10), budget,
            listOf("/release/bin/eignex-lab", "worker"), nativeCapacity = limit).render().toString()
        val threads = Executors.newFixedThreadPool(3)
        try {
            val results = threads.invokeAll(jobs.mapIndexed { i, job -> Callable {
                runCatching { store.execution.createSession("native-$i", job, "prepare", "release", budget, ExecutionStore.token(), payload) }.isSuccess
            } }).map { it.get() }
            assertEquals(2, results.count { it })
            assertEquals(4000L to 2048L, store.execution.nativeReservation())
            val admitted = store.execution.sessions().first()
            store.execution.loseSession(admitted.id, "completed")
            val waiting = jobs[results.indexOf(false)]
            store.execution.createSession("replacement", waiting, "prepare", "release", budget, ExecutionStore.token(), payload)
            assertEquals(4000L to 2048L, store.execution.nativeReservation())
        } finally { threads.shutdownNow() }
    }

    @Test fun `native memory budget is enforced even when CPU is available`() {
        val store = Store(Files.createTempDirectory("native-memory").resolve("lab.db"))
        val spec = ExperimentSpec("native", profile = ProfileMain(main = "com.eignex.Main", classpath = listOf("tools/lib/*")))
        val jobs = (1..2).map { store.create("native-$it", "main", emptyList(), experiment = spec) }
        val budget = ResourceBudget(1000, 3072)
        val payload = NomadBatchJob("fixture", NomadPlatform.MAC, "native", NomadCpuCapacity(28000, 10), budget,
            listOf("/release/bin/eignex-lab", "worker"), nativeCapacity = ResourceBudget(8000, 4096)).render().toString()
        store.execution.createSession("first", jobs[0], "prepare", "release", budget, ExecutionStore.token(), payload)
        assertFailsWith<IllegalStateException> { store.execution.createSession("second", jobs[1], "prepare", "release", budget, ExecutionStore.token(), payload) }
        assertEquals(1000L to 3072L, store.execution.nativeReservation())
    }

    @Test fun `cache volumes are created only once on eligible worker nodes`() {
        val created = mutableListOf<JsonObject>()
        val control = object : NomadControl {
            override fun job(id: String): JsonObject? = null
            override fun submit(job: JsonObject) = Unit
            override fun stop(id: String) = Unit
            override fun allocations(job: String) = emptyList<NomadAllocation>()
            override fun allocation(id: String): NomadAllocation? = null
            override fun jwks() = buildJsonObject {}
            override fun nodes() = listOf(
                buildJsonObject { put("ID", "ready"); put("NodeClass", "cloud"); put("Status", "ready") },
                buildJsonObject { put("ID", "down"); put("NodeClass", "cloud"); put("Status", "down") },
                buildJsonObject { put("ID", "other"); put("NodeClass", "unmanaged"); put("Status", "ready") },
            )
            override fun hostVolumes() = created
            override fun createHostVolume(volume: JsonObject) { created += volume }
        }
        val template = NomadBatchJob("fixture", NomadPlatform.LINUX, "cloud", NomadCpuCapacity(36400, 16), ResourceBudget(16000, 64000),
            listOf("worker"), image = "registry/worker@sha256:" + "a".repeat(64), cacheHostVolume = "lab-cache")
        val controller = NomadBuildVolumes(control, NomadQueueSettings("release", "https://lab.example", listOf(NomadQueueTarget(template, setOf("arch:x86_64")))))
        controller.reconcile(); controller.reconcile()
        assertEquals("ready", created.single().getValue("NodeID").jsonPrimitive.content)
        assertEquals("single-node-multi-writer", created.single().getValue("RequestedCapabilities").jsonArray.single().jsonObject.getValue("AccessMode").jsonPrimitive.content)
        val group = template.render().getValue("TaskGroups").jsonArray.single().jsonObject
        assertEquals("single-node-multi-writer", group.getValue("Volumes").jsonObject.getValue("cache").jsonObject.getValue("AccessMode").jsonPrimitive.content)
    }
}
