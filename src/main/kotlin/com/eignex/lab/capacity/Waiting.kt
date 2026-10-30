package com.eignex.lab.capacity

import com.eignex.lab.analysis.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
data class LocalWork(
    val id: Long,
    val name: String,
    val priority: Int,
    val phase: String,
    val step: String? = null,
    val stepSince: Long? = null,
    val done: Int = 0,
    val total: Int = 0,
    val running: Int = 0,
    val doing: String,
)

/** A job's remaining work as the queue page estimates it ([WorkEstimate]): its cases left, their core-hours, the
 *  local machine's wall time for them, and where the time a case came from. */
@Serializable
data class PlacementEstimate(val cases: Int, val coreHours: Double, val localHours: Double, val note: String)

/**
 * Where an active job may run and why it waits. [placement] is `local only`, `local or cloud` or `cloud`
 * ([Placement.where]), [host] the same as `local`, `auto` or `cloud`; [queuePosition] its place in the whole queue as
 * the queue page shows it and [localPosition] in the local machine's ([Store.queueOrder]). A queued job's [waiting]
 * says in plain words what it waits for; a running one's [doing] says where it runs and what it is doing.
 */
@Serializable
data class JobPlacement(
    val id: Long,
    val name: String,
    val status: Status,
    val placement: String,
    val host: String,
    val priority: Int,
    val paused: Boolean = false,
    val cancelRequested: Boolean = false,
    val queuePosition: Int? = null,
    val localPosition: Int? = null,
    val estimate: PlacementEstimate? = null,
    val waiting: String? = null,
    val doing: String? = null,
    /** Each host's part of a running job, wherever its cases ran ([HostProgress]): a shared job may run on several. */
    val hosts: List<HostProgress> = emptyList(),
)

/** Nomad placement diagnostics plus the maintenance runner's local analysis. */
@Serializable
data class PlacementReport(val at: Long, val nodesUp: Boolean, val cloud: String? = null,
    val local: LocalWork? = null, val jobs: List<JobPlacement>, val held: String? = null)

data class QueueView(val localQueue: List<Long>, val localNext: Long?, val local: LocalWork?, val cores: Int,
    val schedules: List<Schedule> = emptyList(), val diskHeld: String? = null, val now: Long = now())

object Waiting {
    const val SETUP = "setup"
    const val CASES = "cases"
    fun localWork(job: Job, dir: Path?): LocalWork = LocalWork(job.id, job.name, job.priority, "analysis",
        done = job.done, total = job.total, running = job.running, doing = "analysing retained results")

    fun waiting(job: Job, view: QueueView): String = when {
        job.cancelRequested -> "cancel requested: it ends without running"
        job.paused -> "paused: no worker takes it until resumed (lab resume ${job.id})"
        view.diskHeld != null -> "queue held: ${view.diskHeld}"
        job.experiment.isAnalysis == true -> "waiting for native execution to finish before local analysis"
        else -> "Nomad: waiting for session admission or compatible capacity; lab execution ${job.id} shows allocations"
    }

    fun view(config: Config, store: Store, at: Long = now()): QueueView {
        val active = store.active(withCommands = false)
        val analysis = active.filter { it.experiment.isAnalysis == true && !it.paused && !it.cancelRequested }
            .sortedWith(compareByDescending<Job> { it.priority }.thenBy { it.id })
        val running = analysis.firstOrNull { it.status == Status.RUNNING }
        return QueueView(analysis.filter { it.status == Status.QUEUED }.map { it.id }, analysis.firstOrNull()?.id,
            running?.let { localWork(it, null) }, config.cores, store.schedules.all(),
            runCatching { Volumes.hold(config) }.getOrNull(), at)
    }

    fun report(config: Config, store: Store, history: (Job) -> CaseHistory?, at: Long = now()): PlacementReport {
        val view = view(config, store, at)
        val positions = store.queue().withIndex().associate { (index, id) -> id to index + 1 }
        val active = store.active(withCommands = false)
        val progress = store.hostProgress(active.map { it.id })
        val assignments = jobNodes(config, store, active.filter { it.backend == Backend.NOMAD }.map { it.id })
        val jobs = active.sortedWith(compareBy<Job> { it.status != Status.RUNNING }.thenBy { positions[it.id] ?: Int.MAX_VALUE }.thenBy { it.id })
            .map { placement(config, it, view, positions[it.id], history, progress[it.id].orEmpty(), assignments[it.id].orEmpty()) }
        val snapshot = readCapacityReport(config)
        val ready = snapshot?.takeIf { it.healthy && it.ageMs <= CapacityCollector.FRESH_MS }?.nodes.orEmpty().count { it.state == CapacityNodeState.READY }
        return PlacementReport(at, ready > 0, "Nomad: $ready ready cloud nodes; see /nodes", view.local, jobs, view.diskHeld)
    }

    internal fun placement(config: Config, job: Job, view: QueueView, position: Int?, history: (Job) -> CaseHistory?,
                  hosts: List<HostProgress> = emptyList(), nodes: List<JobNode>? = null): JobPlacement {
        val host = job.experiment.host
        val estimate = if (job.status.isActive) Estimates.estimate(job, history, config.cores, config.maxParallel) else null
        val activity = nodes?.let { jobActivity(job, it) }
        return JobPlacement(job.id, job.name, job.status, Placement.where(host), host.word, job.priority,
            job.paused, job.cancelRequested, position.takeIf { job.status == Status.QUEUED },
            view.localQueue.indexOf(job.id).takeIf { it >= 0 }?.plus(1),
            estimate?.let { PlacementEstimate(it.cases, it.coreHours, it.localHours, it.note) },
            if (job.status == Status.QUEUED || activity == "WAITING") waiting(job, view) else null,
            if (job.status == Status.RUNNING) when {
                activity == "WAITING" -> null
                activity == "CHECKING" -> "ingesting results and checking outputs"
                activity == "PREPARING" -> "preparing inputs"
                job.experiment.isAnalysis == true -> "analysing retained results"
                !nodes.isNullOrEmpty() -> "running on ${nodes.joinToString { it.name }}"
                else -> "Nomad sessions; accepted results and source checks determine completion"
            } else null,
            hosts)
    }

    fun hostsText(local: Boolean, nodes: Int): String =
        listOfNotNull("local".takeIf { local }, nodes.takeIf { it > 0 }?.let { "$it ${if (it == 1) "node" else "nodes"}" })
            .joinToString(" + ").ifEmpty { "no host" }
}
