package com.eignex.lab

import kotlinx.serialization.json.*

/** Infrastructure placement may change CPU frequency without changing a session's vCPU budget or frozen inputs. */
internal class NomadCpuPlacement(private val nomad: NomadControl, private val nanoTime: () -> Long = System::nanoTime) {
    /** A Nomad node as calibration sees it: its class, CPU, and the CPU (MHz) and memory (MiB) still free on it. */
    internal data class Node(val id: String, val nodeClass: String, val capacity: NomadCpuCapacity,
                             val freeCpu: Long, val freeMemory: Long)
    private var sampledAt = Long.MIN_VALUE
    private var nodes = emptyList<Node>()

    @Synchronized
    fun hasCapacity(template: NomadBatchJob, budget: ResourceBudget): Boolean {
        refresh()
        return nodes.any { it.nodeClass == template.nodeClass && it.capacity.logicalCpus == template.cpuCapacity.logicalCpus &&
            it.id !in template.excludedNodes && it.freeCpu >= budget.nomadCpuMHz(it.capacity) && it.freeMemory >= budget.memoryMiB }
    }

    private fun refresh() {
        val at = nanoTime()
        if (sampledAt == Long.MIN_VALUE || at - sampledAt >= 10_000_000_000L) {
            nodes = sample(); sampledAt = at
        }
    }

    /** [job] calibrated ([calibrate]) against the nodes as last sampled, at most ten seconds ago. */
    @Synchronized
    fun calibrate(job: JsonObject, budget: ResourceBudget): JsonObject {
        refresh()
        return calibrate(job, budget, nodes)
    }

    internal companion object {
        /**
         * [job], a hard-quota worker of [budget], asking for the CPU of a node among [nodes] it would fit on: the nodes
         * of its class may run at another frequency than its constraints say, and Nomad counts CPU in MHz. The job as it
         * is when it fits as it is, or no node of its class fits better. Pure: [nodes] are what the caller sampled.
         */
        fun calibrate(job: JsonObject, budget: ResourceBudget, nodes: List<Node>): JsonObject {
            val placement = SessionPlacement.of(job)
            if (placement.enforcement != CpuEnforcement.HARD_QUOTA) return job
            val constraints = job.getValue("Constraints").jsonArray.map { it.jsonObject }
            val nodeClass = placement.nodeClass ?: return job
            val capacity = placement.cpuCapacity ?: return job
            val excluded = placement.excludedNodes
            val candidates = nodes.filter { it.nodeClass == nodeClass && it.capacity.logicalCpus == capacity.logicalCpus && it.id !in excluded }
            val fitting = candidates.filter { it.freeCpu >= budget.nomadCpuMHz(it.capacity) && it.freeMemory >= budget.memoryMiB }
            if (fitting.any { it.capacity == capacity }) return job
            val selected = fitting.minByOrNull { it.freeCpu - budget.nomadCpuMHz(it.capacity) }
                ?: if (candidates.any { it.capacity == capacity }) return job else candidates.minByOrNull { it.capacity.usableMHz }
                ?: return job
            if (selected.capacity == capacity) return job
            val groups = job.getValue("TaskGroups").jsonArray.map { group ->
                val tasks = group.jsonObject.getValue("Tasks").jsonArray.map { task ->
                    val resources = task.jsonObject.getValue("Resources").jsonObject
                    require(resources.getValue("CPU").jsonPrimitive.int == budget.nomadCpuMHz(capacity))
                    JsonObject(task.jsonObject + ("Resources" to JsonObject(resources + ("CPU" to JsonPrimitive(budget.nomadCpuMHz(selected.capacity))))))
                }
                JsonObject(group.jsonObject + ("Tasks" to JsonArray(tasks)))
            }
            return JsonObject(job + mapOf("TaskGroups" to JsonArray(groups), "Constraints" to JsonArray(constraints.map {
                if (it["LTarget"]?.jsonPrimitive?.content == "${'$'}{attr.cpu.usablecompute}")
                    JsonObject(it + ("RTarget" to JsonPrimitive(selected.capacity.usableMHz.toString()))) else it
            })))
        }
    }

    private fun sample(): List<Node> = nomad.nodes().filter {
        it["Status"]?.jsonPrimitive?.content == "ready" && it["SchedulingEligibility"]?.jsonPrimitive?.content != "ineligible"
    }.mapNotNull { brief ->
        val id = brief.getValue("ID").jsonPrimitive.content
        val node = nomad.node(id) ?: return@mapNotNull null
        if (node["Status"]?.jsonPrimitive?.content != "ready" || node["SchedulingEligibility"]?.jsonPrimitive?.content == "ineligible" ||
            node["Drain"]?.jsonPrimitive?.booleanOrNull == true || node["DrainStrategy"]?.let { it != JsonNull } == true) return@mapNotNull null
        val attrs = node["Attributes"]?.jsonObject ?: return@mapNotNull null
        if (attrs["kernel.name"]?.jsonPrimitive?.content != "linux") return@mapNotNull null
        val mhz = attrs["cpu.usablecompute"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
        val cores = attrs["cpu.numcores"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
        val allocations = nomad.nodeAllocations(id).filter { it["ClientStatus"]?.jsonPrimitive?.content in setOf("pending", "running", "unknown") }
        val tasks = allocations.flatMap {
            ((it["AllocatedResources"] as? JsonObject)?.get("Tasks") as? JsonObject)?.values
                ?.map { value -> value as? JsonObject ?: return@mapNotNull null } ?: return@mapNotNull null
        }
        fun memory(resources: JsonObject?) = ((resources?.get("Memory") as? JsonObject)?.get("MemoryMB") as? JsonPrimitive)?.longOrNull ?: 0
        val cpu = tasks.sumOf { ((it["Cpu"] as? JsonObject)?.get("CpuShares") as? JsonPrimitive)?.longOrNull ?: mhz.toLong() }
        val freeMemory = memory(node["NodeResources"] as? JsonObject) - memory(node["ReservedResources"] as? JsonObject) - tasks.sumOf(::memory)
        Node(id, node.getValue("NodeClass").jsonPrimitive.content, NomadCpuCapacity(mhz, cores), mhz.toLong() - cpu, freeMemory)
    }
}
