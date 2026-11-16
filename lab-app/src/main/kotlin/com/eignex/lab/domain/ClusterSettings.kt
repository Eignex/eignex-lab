package com.eignex.lab.domain

import com.eignex.lab.util.*

import java.nio.file.Path
import com.eignex.lab.domain.NomadBatchJob
import com.eignex.lab.domain.NomadPlatform

/**
 * The Nomad queue's worker targets and the capacity pools the cloud ones run on, loaded and checked together once.
 * Each cloud target has the one pool admission will hold its sessions to ([Placement.admit]), and fits it, so a job the
 * API places on a target is one the coordinator admits: the queue and the pools cannot disagree after startup. Both
 * processes load it from the same files ([Config.cluster], [NomadCoordinatorCli]).
 */
class ClusterSettings(val queue: NomadQueueSettings, val capacity: CapacitySettings?) {
    init {
        if (capacity != null) for (target in queue.targets.filter { it.template.platform == NomadPlatform.LINUX }) {
            val template = target.template
            val pool = requireNotNull(poolOf(template)) { "queue target ${template.nodeClass} has no capacity pool of its node class" }
            require(template.budget.cpuMillis <= pool.cpuMillis && template.budget.memoryMiB <= pool.availableMemoryMiB) {
                "queue target ${template.nodeClass} asks for more than pool ${pool.id} has: ${template.budget} against " +
                    "${pool.cpuMillis} mCPU and ${pool.availableMemoryMiB} MiB"
            }
            require(template.cpuCapacity.logicalCpus == pool.vcpus) {
                "queue target ${template.nodeClass} counts ${template.cpuCapacity.logicalCpus} CPUs, pool ${pool.id} has ${pool.vcpus}"
            }
            require(target.market == pool.market) { "queue target ${template.nodeClass} buys ${target.market}, pool ${pool.id} ${pool.market}" }
            require(NodeTags.matches(NodeSelector(require = target.tags), pool.capabilities)) {
                "queue target ${template.nodeClass} offers tags pool ${pool.id} does not have"
            }
        }
        for (source in capacity?.pools.orEmpty().filter { it.onDemandFallback != null }) {
            val target = capacity!!.pools.single { it.id == source.onDemandFallback }
            val original = requireNotNull(queue.targets.singleOrNull { it.template.nodeClass == source.nodeClass })
            val fallback = requireNotNull(queue.targets.singleOrNull { it.template.nodeClass == target.nodeClass })
            require(original.copy(template = original.template.copy(nodeClass = target.nodeClass), market = CapacityMarket.ON_DEMAND) == fallback) {
                "on-demand fallback worker must preserve the Spot worker runtime and resource contract"
            }
        }
    }

    /**
     * Cloud targets whose memory holds fewer one-CPU cases at [NomadQueueSettings.caseMemoryMiB] than a session as wide
     * as the target runs ([Experiments.caseConcurrency]), each saying how many fit and what its pool could hold. Such a
     * session reserves only the CPUs of the cases that fit ([NomadQueueSettings.solveBudget]), so the rest of the node
     * idles: raise the target's memory to the pool's, or choose a pool with more memory per vCPU.
     */
    val memoryLimited: List<String> = queue.targets.filter { it.template.platform == NomadPlatform.LINUX }.mapNotNull { target ->
        val budget = target.template.budget
        val byCpu = Experiments.caseConcurrency(budget.cpuMillis / 1000, 1)
        fun fit(memory: Int) = ((memory - queue.sessionMemoryMiB) / queue.caseMemoryMiB).coerceAtLeast(0)
        val byMemory = fit(budget.memoryMiB)
        if (byMemory >= byCpu) return@mapNotNull null
        val pool = poolOf(target.template)
        val needed = queue.sessionMemoryMiB + byCpu * queue.caseMemoryMiB
        "queue target ${target.template.nodeClass} holds $byMemory of the $byCpu cases a full-width session runs at " +
            "${queue.caseMemoryMiB} MiB each: its ${budget.memoryMiB} MiB is short of $needed MiB" +
            (pool?.let { ", and pool ${it.id} has ${it.availableMemoryMiB} MiB, room for ${fit(it.availableMemoryMiB)}" } ?: "")
    }

    /** The capacity pool a cloud worker of [template] runs in: the one of its node class. */
    fun poolOf(template: NomadBatchJob): CapacityPool? = capacity?.pools?.singleOrNull { it.nodeClass == template.nodeClass }

    companion object {
        /** The settings of [queue] and, when given, [capacity], checked together. */
        fun load(queue: Path, capacity: Path?): ClusterSettings =
            ClusterSettings(kotlinx.serialization.json.Json.decodeFromString(java.nio.file.Files.readString(queue)), capacity?.let(CapacitySettings::read))
    }
}
