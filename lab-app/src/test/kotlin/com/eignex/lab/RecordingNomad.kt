package com.eignex.lab

import com.eignex.lab.nomad.NomadAllocation
import com.eignex.lab.nomad.NomadControl
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray

/**
 * An in-memory Nomad for tests: it keeps submitted jobs and serves the allocations and nodes a test places in its
 * maps. Nothing runs and it signs no identities; override a member to fail or count calls.
 */
internal open class RecordingNomad : NomadControl {
    val jobs = mutableMapOf<String, JsonObject>()
    val allocations = mutableMapOf<String, NomadAllocation>()
    val nodeInventory = mutableMapOf<String, JsonObject>()
    /** Allocation rows of each node, by node ID. */
    val nodeTasks = mutableMapOf<String, List<JsonObject>>()

    override fun job(id: String): JsonObject? = jobs[id]
    override fun submit(job: JsonObject) { jobs[job.getValue("ID").jsonPrimitive.content] = job }
    override fun stop(id: String) { jobs.remove(id) }
    override fun allocations(job: String): List<NomadAllocation> = allocations.values.filter { it.job == job }
    override fun allocation(id: String): NomadAllocation? = allocations[id]
    override fun jwks(): JsonObject = buildJsonObject { putJsonArray("keys") {} }
    override fun nodes(): List<JsonObject> = nodeInventory.values.toList()
    override fun node(id: String): JsonObject? = nodeInventory[id]
    override fun nodeAllocations(id: String): List<JsonObject> = nodeTasks[id].orEmpty()
}
