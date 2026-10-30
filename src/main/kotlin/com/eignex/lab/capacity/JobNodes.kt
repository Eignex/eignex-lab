package com.eignex.lab.capacity

import com.eignex.lab.analysis.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

/** Public placement facts only; a submission's worker credentials never reach a page. */
internal data class RunningNode(val id: String, val allocation: String?, val nodeClass: String?, val local: Boolean)
internal data class JobNode(val id: String, val name: String, val profile: String?, val local: Boolean, val anchor: String?)

/** Durable RUNNING means the experiment has started; it need not currently have a worker. */
internal fun jobActivity(job: Job, nodes: List<JobNode>): String? = when {
    job.backend != Backend.NOMAD || job.status != Status.RUNNING || job.experiment.isAnalysis == true || job.paused || job.cancelRequested -> null
    job.total > 0 && job.done == job.total -> "CHECKING"
    nodes.isEmpty() -> "WAITING"
    !job.setupDone -> "PREPARING"
    else -> null
}

/** Resolve active bindings from local state without making Nomad requests on the page's refresh path. */
internal fun jobNodes(
    status: StatusSettings,
    store: Store,
    jobs: Collection<Long>,
    /** The capacity settings, from the API's cache ([ApiServices.capacitySettings]); read from their file when not given. */
    settings: CapacitySettings? = runCatching { status.capacitySettings?.let { CapacitySettings.read(it) } }.getOrNull(),
): Map<Long, List<JobNode>> {
    if (jobs.isEmpty()) return emptyMap()
    val snapshot = readCapacityReport(status.paths.capacityReport)
    val allocations = snapshot?.nodes.orEmpty().flatMap { node -> node.reservations.map { it.id to node } }.toMap()
    val pools = (settings?.pools ?: snapshot?.pools).orEmpty()
    return store.execution.runningNodes(jobs).mapValues { (_, nodes) ->
        nodes.map { node ->
            val capacity = node.allocation?.let(allocations::get)
            val pool = pools.singleOrNull { it.id == capacity?.pool || it.nodeClass == node.nodeClass }
            JobNode(node.id, when {
                node.local -> "native · ${node.id.take(8)}"
                capacity != null -> capacity.id
                else -> "${node.nodeClass ?: "node"} · ${node.id.take(8)}"
            }, if (node.local) NodeTags.hardwareProfile(status.local.tags) else pool?.let { NodeTags.hardwareProfile(it.tags) },
                node.local, if (node.local) "local-node" else capacity?.let { "node-${it.id}" })
        }.distinctBy { it.id }.sortedWith(compareBy<JobNode> { !it.local }.thenBy { it.name })
    }
}

/** A running session's node, where its [placement] put it: a node class, or the local machine's native worker. */
internal fun runningNode(id: String, allocation: String?, placement: SessionPlacement): RunningNode =
    RunningNode(id, allocation, placement.nodeClass, placement.native)
