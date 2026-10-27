package com.eignex.lab

import kotlinx.serialization.json.*

/** Infrastructure placement may change CPU frequency without changing a session's vCPU budget or frozen inputs. */
internal class NomadCpuPlacement(private val nomad: NomadControl) {
    private data class Node(val id: String, val nodeClass: String, val capacity: NomadCpuCapacity,
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
        val at = System.nanoTime()
        if (sampledAt == Long.MIN_VALUE || at - sampledAt >= 10_000_000_000L) {
            nodes = sample(); sampledAt = at
        }
    }

    @Synchronized
    fun calibrate(job: JsonObject, budget: ResourceBudget): JsonObject {
        if (job["Meta"]?.jsonObject?.get("lab_cpu_enforcement")?.jsonPrimitive?.content != "hard-quota") return job
        val constraints = job.getValue("Constraints").jsonArray.map { it.jsonObject }
        fun value(target: String) = constraints.singleOrNull {
            it["LTarget"]?.jsonPrimitive?.content == target && it["Operand"]?.jsonPrimitive?.content == "="
        }?.get("RTarget")?.jsonPrimitive?.content
        val nodeClass = value("${'$'}{node.class}") ?: return job
        val capacity = NomadCpuCapacity(value("${'$'}{attr.cpu.usablecompute}")?.toIntOrNull() ?: return job,
            value("${'$'}{attr.cpu.numcores}")?.toIntOrNull() ?: return job)
        val excluded = constraints.filter { it["LTarget"]?.jsonPrimitive?.content == "${'$'}{node.unique.id}" &&
            it["Operand"]?.jsonPrimitive?.content == "!=" }.map { it.getValue("RTarget").jsonPrimitive.content }.toSet()
        refresh()
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
