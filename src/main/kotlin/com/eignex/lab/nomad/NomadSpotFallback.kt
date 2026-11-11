package com.eignex.lab.nomad

import com.eignex.lab.capacity.*
import com.eignex.lab.domain.*
import kotlinx.serialization.json.*

/** Changes only pending infrastructure placement. Bound workers and their paired rows keep their original node. */
internal class NomadSpotFallback(
    private val cluster: ClusterSettings,
    private val snapshot: () -> CapacitySnapshot?,
    private val experiment: (Long) -> ExperimentSpec?,
) {
    fun apply(job: JsonObject, budget: ResourceBudget): JsonObject {
        val placement = SessionPlacement.of(job)
        if (placement.native) return job
        val source = cluster.capacity?.pools?.singleOrNull { it.nodeClass == placement.nodeClass } ?: return job
        val fallback = source.onDemandFallback ?: return job
        val sample = snapshot()?.takeIf { it.healthy && it.ageMs in 0..CapacityCollector.FRESH_MS } ?: return job
        if (sample.spotFallbacks[source.id] != fallback) return job
        val tasks = job.getValue("TaskGroups").jsonArray.flatMap { it.jsonObject.getValue("Tasks").jsonArray }
        val id = tasks.firstNotNullOfOrNull { it.jsonObject["Env"]?.jsonObject?.get("LAB_WORKER_EXPERIMENT")?.jsonPrimitive?.content?.toLongOrNull() }
            ?: return job
        val spec = experiment(id) ?: return job
        if (NodeTags.spotOnly(spec.nodeTags)) return job
        // Continue using ready Spot resources instead of buying another node for work they can already hold.
        if (sample.nodes.any { node -> node.pool == source.id && node.state == CapacityNodeState.READY &&
                source.cpuMillis - node.reservations.sumOf { it.budget.cpuMillis.toLong() } >= budget.cpuMillis &&
                source.availableMemoryMiB - node.reservations.sumOf { it.budget.memoryMiB.toLong() } >= budget.memoryMiB }) return job
        val target = cluster.capacity.pools.single { it.id == fallback }
        if (placement.cpuCapacity?.logicalCpus != target.vcpus || budget.cpuMillis > target.cpuMillis || budget.memoryMiB > target.availableMemoryMiB) return job
        if (!NodeTags.matches(spec.nodeTags, target.capabilities)) return job
        val constraints = job.getValue("Constraints").jsonArray.map { c ->
            if (c.jsonObject["LTarget"]?.jsonPrimitive?.content == "${'$'}{node.class}")
                JsonObject(c.jsonObject + ("RTarget" to JsonPrimitive(target.nodeClass))) else c
        }
        return JsonObject(job + mapOf("Constraints" to JsonArray(constraints),
            "Meta" to JsonObject(job["Meta"]?.jsonObject.orEmpty() + mapOf(
                "lab_spot_fallback_from" to JsonPrimitive(source.id), "lab_spot_fallback_reason" to JsonPrimitive("UnfulfillableCapacity")))))
    }
}
