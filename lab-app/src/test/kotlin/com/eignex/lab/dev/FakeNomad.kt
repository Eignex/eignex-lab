package com.eignex.lab.dev

import com.eignex.lab.capacity.CapacityInstance
import com.eignex.lab.capacity.CapacityInventory
import com.eignex.lab.domain.CapacityPool
import com.eignex.lab.domain.CapacityWire
import com.eignex.lab.nomad.NomadAllocation
import com.eignex.lab.nomad.NomadControl
import kotlinx.serialization.json.*

/** In-memory Nomad for UI fixtures. It records submissions and allocations; no command or container is executed. */
internal class FakeNomad(private val pools: List<CapacityPool>, private val clock: () -> Long) : NomadControl {
    private data class Node(val id: String, val pool: CapacityPool, val readyAt: Long)
    private val machines = linkedMapOf<String, Node>()
    private val jobs = linkedMapOf<String, JsonObject>()
    private val allocations = linkedMapOf<String, NomadAllocation>()
    private var sequence = 0

    init {
        for (pool in pools) repeat(pool.warmNodes) { add(pool, ready = true) }
    }

    @Synchronized fun scale(desired: Map<String, Int>) {
        for (pool in pools) {
            val count = desired.getOrDefault(pool.id, 0).coerceIn(0, pool.maxNodes)
            val own = machines.values.filter { it.pool.id == pool.id }
            repeat((count - own.size).coerceAtLeast(0)) { add(pool, ready = false) }
            own.asReversed().filter { node -> allocations.values.none { it.node == node.id && it.active } }
                .take((own.size - count).coerceAtLeast(0)).forEach { machines.remove(it.id) }
        }
    }

    private fun add(pool: CapacityPool, ready: Boolean) {
        val id = "preview-${pool.id}-${++sequence}"
        machines[id] = Node(id, pool, clock() + if (ready) 0 else 3000)
    }

    @Synchronized fun inventory(): CapacityInventory = CapacityInventory(clock(), pools.map { it.id }.toSet(),
        machines.values.map { CapacityInstance(it.id, it.pool.id, launchedAt = it.readyAt - 3000) })

    @Synchronized fun readyNode(nodeClass: String): String? = machines.values.firstOrNull {
        it.pool.nodeClass == nodeClass && it.readyAt <= clock() && allocations.values.none { a -> a.node == it.id && a.active }
    }?.id

    @Synchronized override fun submit(job: JsonObject) {
        val id = job.getValue("ID").jsonPrimitive.content
        if (id in jobs) return
        val nodeClass = job.getValue("Constraints").jsonArray.map { it.jsonObject }.single {
            it["LTarget"]?.jsonPrimitive?.content == "\${node.class}"
        }.getValue("RTarget").jsonPrimitive.content
        val node = checkNotNull(readyNode(nodeClass)) { "fake Nomad has no idle ready node in $nodeClass" }
        jobs[id] = job
        allocations["allocation-$id"] = NomadAllocation("allocation-$id", id, node, "running", "run", clock())
    }

    @Synchronized override fun stop(id: String) {
        jobs.remove(id)
        allocations.replaceAll { _, allocation ->
            if (allocation.job == id) allocation.copy(client = "complete", desired = "stop") else allocation
        }
    }

    @Synchronized override fun job(id: String): JsonObject? = jobs[id]
    @Synchronized override fun allocations(job: String): List<NomadAllocation> = allocations.values.filter { it.job == job }
    @Synchronized override fun allocation(id: String): NomadAllocation? = allocations[id]
    @Synchronized override fun nodes(): List<JsonObject> = machines.values.map(::nodeJson)
    @Synchronized override fun node(id: String): JsonObject? = machines[id]?.let(::nodeJson)
    @Synchronized override fun nodeAllocations(id: String): List<JsonObject> = allocations.values.filter { it.node == id }.map {
        buildJsonObject {
            put("ID", it.id); put("JobID", it.job); put("NodeID", it.node)
            put("ClientStatus", it.client); put("DesiredStatus", it.desired)
        }
    }
    override fun jwks(): JsonObject = error("fake Nomad does not authenticate or run workers")

    private fun nodeJson(node: Node): JsonObject = buildJsonObject {
        put("ID", node.id); put("Name", node.id); put("NodeClass", node.pool.nodeClass)
        put("Status", if (node.readyAt <= clock()) "ready" else "initializing")
        put("SchedulingEligibility", "eligible"); put("Drain", false)
        // Change this index when the simulated node boots so the real collector invalidates its node cache.
        put("ModifyIndex", if (node.readyAt <= clock()) 2 else 1)
        putJsonObject("Meta") { put(CapacityWire.CAPACITY_ID_META, node.id) }
    }
}
