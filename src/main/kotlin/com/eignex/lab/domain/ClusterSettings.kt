package com.eignex.lab.domain

import com.eignex.lab.util.*

import java.nio.file.Path
import com.eignex.lab.nomad.NomadBatchJob
import com.eignex.lab.nomad.NomadPlatform

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
    }

    /** The capacity pool a cloud worker of [template] runs in: the one of its node class. */
    fun poolOf(template: NomadBatchJob): CapacityPool? = capacity?.pools?.singleOrNull { it.nodeClass == template.nodeClass }

    companion object {
        /** The settings of [queue] and, when given, [capacity], checked together. */
        fun load(queue: Path, capacity: Path?): ClusterSettings =
            ClusterSettings(kotlinx.serialization.json.Json.decodeFromString(java.nio.file.Files.readString(queue)), capacity?.let(CapacitySettings::read))
    }
}
