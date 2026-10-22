package com.eignex.lab

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

/**
 * What the local machine's setup of a job is doing, for the placement report ([Waiting]): the file `setup.step` in the
 * job's directory, holding when the step began and its name as the job log names it ("selecting problems for
 * selection 0"). The runner writes [STARTING] when a setup begins, each retried step of it ([Runner.withRetry])
 * stands in its place while it runs, and the setup's end removes it. The runner and the API are separate processes,
 * so the file is how the API learns it.
 */
object SetupStep {
    const val FILE = "setup.step"
    /** The step named before the setup's first retried step starts, and between its steps. */
    const val STARTING = "starting"

    private fun file(dir: Path): Path = dir.resolve(FILE)

    /** The file's text, or null when no setup runs. */
    fun raw(dir: Path): String? = runCatching { Files.readString(file(dir)) }.getOrNull()

    /** Name [what] as the step the setup runs since [at]. */
    fun write(dir: Path, what: String, at: Long = now()) {
        runCatching { Files.writeString(file(dir), "$at $what") }
    }

    /** Put back the text [raw] read before a step, or remove the file when there was none. */
    fun restore(dir: Path, raw: String?) {
        if (raw == null) clear(dir) else runCatching { Files.writeString(file(dir), raw) }
    }

    fun clear(dir: Path) {
        runCatching { Files.deleteIfExists(file(dir)) }
    }

    /** The step the setup runs and since when, or null when none runs. */
    fun read(dir: Path): Pair<String, Long>? {
        val text = raw(dir) ?: return null
        val at = text.substringBefore(' ').toLongOrNull() ?: return null
        return text.substringAfter(' ').trim().takeIf { it.isNotEmpty() }?.let { it to at }
    }
}

/**
 * The job the local machine runs: its [phase], [SETUP][Waiting.SETUP] while the runner sets it up (building, selecting
 * problems, planning) and [CASES][Waiting.CASES] once it runs its cases; the setup [step] it runs, as the job log
 * names it, and since when ([stepSince]), when known; its progress; and [doing], all of it in a few words.
 */
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

/**
 * The lab's placement diagnostics, `GET /placement`: the cloud worker's last [pool] report as it wrote it, the
 * [cloud] launch gate in a line, whether a node is up or launching ([nodesUp], which orders the local machine's queue),
 * the job the [local] machine runs, and every active job ([jobs]): the running ones, then the queued ones in queue
 * order, then the paused and cancelling ones; and why the local machine holds its queue ([held], [Volumes.hold]), if it does.
 */
@Serializable
data class PlacementReport(
    val at: Long,
    val nodesUp: Boolean,
    val cloud: String? = null,
    val pool: NodePoolReport? = null,
    val local: LocalWork? = null,
    val jobs: List<JobPlacement>,
    val held: String? = null,
)

/**
 * What the placement rules make of the queue as it stands, which [Waiting.waiting] reads: the local machine's queue
 * ([localQueue], [Store.queueOrder]) and the job it takes next ([localNext], [Store.localNext]), told whether a node is
 * up or launching as the runner is; the job it runs ([local]); the cloud worker's [pool] report and the [aws]
 * settings; the local machine's [cores]; the [schedules], which say whether a scheduled run may go to the cloud; why
 * the local machine holds its queue ([diskHeld], a low disk or a missing volume), if it does; and the time [now].
 */
data class QueueView(
    val localQueue: List<Long>,
    val localNext: Long?,
    val local: LocalWork?,
    val pool: NodePoolReport?,
    val aws: AwsConfig?,
    val cores: Int,
    val schedules: List<Schedule> = emptyList(),
    val diskHeld: String? = null,
    val now: Long = now(),
)

/**
 * Why a queued job waits and where it will run, in plain words, by the rules the runner and the cloud worker follow:
 * the local machine takes the first of its queue when it is free, or when the job it runs gives way to a higher
 * priority between its cases ([Store.localNext]); a node launches on pressure, damped by the sustain wait and the
 * cooldown ([LaunchGate]), never for work below the priority floor ([Placement.belowFloor]), and leaves the local
 * machine the job it takes next. The queue page, the job page and `GET /placement` all read it.
 */
object Waiting {
    const val SETUP = "setup"
    const val CASES = "cases"

    /** What job [job], RUNNING on the local machine, is doing, its setup step read from its directory [dir]. */
    fun localWork(job: Job, dir: Path): LocalWork {
        val step = SetupStep.read(dir)
        val setup = step != null || !job.setupDone && job.experiment != null && job.experiment.isAnalysis.not()
        val named = step?.first?.takeIf { it != SetupStep.STARTING }
        val doing = if (setup) "setting up" + (named?.let { ": $it" } ?: "")
            else "running cases: ${job.done} of ${job.total} done, ${job.running} running"
        return LocalWork(job.id, job.name, job.priority, if (setup) SETUP else CASES, named, step?.second, job.done, job.total,
            job.running, doing)
    }

    /** Why queued [job] waits, in plain words: each host it may run on, and what holds it there. */
    fun waiting(job: Job, view: QueueView): String {
        if (job.cancelRequested) return "cancel requested: it ends without running"
        if (job.paused) return "paused: no host takes it until it is resumed (lab resume ${job.id})"
        val host = job.experiment?.host
        val parts = mutableListOf<String>()
        when (Placement.where(host)) {
            Placement.LOCAL_ONLY -> {
                parts += local(job, view)
                localOnly(job, view)?.let { parts += it }
            }
            Placement.LOCAL_OR_CLOUD -> {
                parts += local(job, view)
                val aws = view.aws
                when {
                    aws == null -> parts += "cloud: the lab has no cloud settings"
                    Placement.belowFloor(host, job.priority, aws.nodeMinPriority) ->
                        parts += "below the cloud priority floor (priority ${job.priority} < ${aws.nodeMinPriority}): " +
                            "no node launches for it, but an idle node takes it"
                    // The local machine takes it next; the cloud worker leaves it to it.
                    job.id == view.localNext -> Unit
                    else -> parts += cloud(job, view, aws)
                }
            }
            else -> parts += view.aws?.let { cloud(job, view, it) } ?: "placed on the cloud, but the lab has no cloud settings"
        }
        return parts.joinToString("; ")
    }

    /** What holds [job] on the local machine's side: a hold ([Volumes.hold]), the job it runs, or the jobs ahead in its queue. */
    private fun local(job: Job, view: QueueView): String {
        val position = view.localQueue.indexOf(job.id) + 1
        val place = if (position > 0) "#$position in the local queue" else "not in the local queue"
        view.diskHeld?.let { return "the local machine holds its queue: $it; $place" }
        val running = view.local
        if (job.id == view.localNext) {
            return when {
                running != null -> "next for the local machine: job ${running.id} (priority ${running.priority}, ${running.doing}) " +
                    "gives way to it before its next case"
                view.pool?.updateWaiting == true -> "next for the local machine, after the lab update that waits for it"
                else -> "next for the local machine, which is free: it takes it within seconds"
            }
        }
        if (running == null) {
            val first = view.localQueue.firstOrNull()
            return if (first == null || first == job.id) place else "$place; the free local machine takes job $first first"
        }
        val busy = "local machine busy with job ${running.id} (${running.doing}); $place"
        // A running job gives way only to a higher priority: the first of the queue waits for it to end.
        return if (position == 1) "$busy, taken when it ends (it gives way only to a priority above its ${running.priority})" else busy
    }

    /** Why local-only [job] does not go to the cloud, when the lab has cloud settings; null when no rule says (an
     *  operator placed it so). */
    private fun localOnly(job: Job, view: QueueView): String? {
        val aws = view.aws ?: return null
        val spec = job.experiment ?: return null
        val reason = Placement.localOnlyReason(spec, job.name, Placement.cloudSchedule(job.name, view.schedules))
            ?: when {
                spec.auto -> "taken by the local machine, which resumes it"
                !Placement.awsFits(spec, aws) -> "no cloud size within the quota holds its cases"
                else -> return null
            }
        return "local only: $reason"
    }

    /** What holds [job] back from a cloud node: a node that is free takes it, else the launch gate holds the next. */
    private fun cloud(job: Job, view: QueueView, aws: AwsConfig): String {
        val spec = job.experiment
        val cores = spec?.let { runCatching { Experiments.arms(it).maxOf { arm -> arm.cores } }.getOrNull() }
        if (cores != null && aws.sizeFor(cores) == null) return "cloud: no size within the ${aws.vcpuQuota}-vCPU quota holds its $cores-core cases"
        val pool = view.pool ?: return "cloud: no pool report yet"
        val stale = (view.now - pool.updatedAt).takeIf { it > STALE_REPORT_MS }
            ?.let { " (pool report ${LaunchGate.minutes(it)} min old)" }.orEmpty()
        val profile = spec?.isProfile == true
        val free = pool.nodes.filter { it.doing == IDLE && (!profile || it.market == AwsWorker.ON_DEMAND) }
        if (free.isNotEmpty()) return "cloud: a free node takes it at its next poll$stale"
        val nodes = when {
            pool.nodes.isEmpty() -> ""
            profile -> "no on-demand node free, as a profile needs; "
            else -> "${pool.nodes.size} ${if (pool.nodes.size == 1) "node" else "nodes"} up, none free; "
        }
        return "cloud: $nodes${launch(pool, aws, view.cores)}$stale"
    }

    /** What the cloud worker's launch gate says of the next node, from its [pool] report, in plain words. */
    fun launch(pool: NodePoolReport, aws: AwsConfig, cores: Int): String {
        val count = pool.nodes.size + pool.launching
        val threshold = pool.threshold ?: NodePool.threshold(cores, aws.nodeLaunchLocalHours)
        val next = pool.nextThreshold ?: NodePool.nextThreshold(threshold, count)
        val which = if (count == 0) "the first node" else "node ${count + 1}"
        val pressure = "pressure ${coreHoursText(pool.pressure)} of ${coreHoursText(next)} core-h for $which"
        val launching = if (pool.launching > 0) "${pool.launching} launching; " else ""
        val blocker = pool.blocker
        return launching + when {
            blocker == null -> "$pressure: a node launches at the next poll"
            blocker == LaunchGate.BELOW -> "$pressure, below the launch threshold"
            blocker == LaunchGate.AT_MAX -> "at maxInstances (${aws.maxInstances}): no more nodes launch"
            blocker == LaunchGate.QUOTA_FULL -> "the ${aws.vcpuQuota}-vCPU quota is full: no node launches"
            blocker == LaunchGate.UPDATE_WAITING -> "a lab update waits: no node launches until it is done"
            blocker.startsWith("waiting ") -> "$pressure, over the threshold: a node launches once it holds ${blocker.removePrefix("waiting ")}"
            blocker.startsWith("cooldown ") -> "$pressure: the cooldown after the last launch holds the next for ${blocker.removePrefix("cooldown ")}"
            else -> "$pressure: $blocker"
        }
    }

    /** The cloud pool in a line: the launch gate ([launch]) and the nodes up, each with what it is doing. */
    fun cloudSummary(pool: NodePoolReport?, aws: AwsConfig, cores: Int, now: Long): String {
        if (pool == null) return "no pool report yet"
        val nodes = if (pool.nodes.isEmpty()) "no nodes up" else "nodes: " + pool.nodes.joinToString(", ") { node ->
            "${node.type} ${node.market}${if (node.manual) " manual" else ""} ${node.doing}"
        }
        val stale = (now - pool.updatedAt).takeIf { it > STALE_REPORT_MS }?.let { " · report ${LaunchGate.minutes(it)} min old" }.orEmpty()
        return "${launch(pool, aws, cores)} · $nodes$stale"
    }

    /**
     * The placement report of the lab as it stands ([PlacementReport]), read from [store], the cloud worker's pool
     * report and the job directories. [history] is the earlier runs the estimates learn from ([EstimateHistory]).
     */
    fun report(config: Config, store: Store, history: (Job) -> CaseHistory?, at: Long = now()): PlacementReport {
        val view = view(config, store, at)
        val active = store.active(withCommands = false)
        val queue = store.queue(nodesUp = config.aws != null && nodesUp(view.pool, at))
        val positions = queue.withIndex().associate { (index, id) -> id to index + 1 }
        val ordered = active.sortedWith(compareBy<Job> { it.status != Status.RUNNING }.thenBy { positions[it.id] ?: Int.MAX_VALUE }.thenBy { it.id })
        val progress = store.hostProgress(active.filter { it.status == Status.RUNNING }.map { it.id })
        val jobs = ordered.map { placement(config, it, view, positions[it.id], history, progress[it.id].orEmpty()) }
        val cloud = config.aws?.let { cloudSummary(view.pool, it, config.cores, at) }
        return PlacementReport(at, config.aws != null && nodesUp(view.pool, at), cloud, view.pool, view.local, jobs, view.diskHeld)
    }

    /** What the placement rules read ([QueueView]), from [store] and the lab's files at [at]. */
    fun view(config: Config, store: Store, at: Long = now()): QueueView {
        val pool = readPoolReport(config)
        val up = config.aws != null && nodesUp(pool, at)
        val running = store.active(withCommands = false).firstOrNull { it.status == Status.RUNNING && it.local }
        val diskHeld = runCatching { Volumes.hold(config) }.getOrNull()
        return QueueView(store.queueOrder(up), store.localNext(up), running?.let { localWork(it, config.jobDir(it.id)) }, pool, config.aws,
            config.cores, store.schedules(), diskHeld, at)
    }

    /** Where [job] may run and why it waits or what it does, by [view]; [position] is its place in the whole queue. */
    fun placement(config: Config, job: Job, view: QueueView, position: Int?, history: (Job) -> CaseHistory?,
                  hosts: List<HostProgress> = emptyList()): JobPlacement {
        val host = job.experiment?.host ?: Experiments.LAB_HOST
        val estimate = if (job.status in ACTIVE) Estimates.estimate(job, history, config.cores, config.maxParallel) else null
        val local = view.localQueue.indexOf(job.id).takeIf { it >= 0 }?.plus(1)
        val doing = when (job.status) {
            Status.QUEUED -> null
            Status.RUNNING -> runningOn(job, view) + (if (hosts.size > 1) "; by host: ${hosts.joinToString(", ") { it.text() }}" else "")
            else -> "ended ${job.status}"
        }
        return JobPlacement(
            job.id, job.name, job.status, Placement.where(host), Experiments.hostWord(host), job.priority, job.paused, job.cancelRequested,
            position.takeIf { job.status == Status.QUEUED }, local,
            estimate?.let { PlacementEstimate(it.cases, it.coreHours, it.localHours, it.note) },
            if (job.status == Status.QUEUED) waiting(job, view) else null, doing, hosts,
        )
    }

    /**
     * Where running [job] runs and what each host does: the local machine when it works on it ([Job.local]), and the
     * cloud nodes the pool report says work on it. A shared job can run on both at once, each host on whole iterations.
     */
    private fun runningOn(job: Job, view: QueueView): String {
        val nodes = view.pool?.nodes.orEmpty().filter { it.job == job.id }
        val local = "the local machine: " + (view.local?.takeIf { it.id == job.id }?.doing ?: "running")
        val cloud = "${nodes.size} cloud ${if (nodes.size == 1) "node" else "nodes"}: " + nodes.joinToString(", ") { "${it.type} ${it.market} ${it.doing}" }
        return when {
            job.local && nodes.isNotEmpty() -> "on ${hostsText(true, nodes.size)}: $local; $cloud"
            job.local -> "on $local"
            nodes.isNotEmpty() -> "on $cloud"
            job.experiment?.host == Experiments.AWS_HOST -> "on the cloud"
            else -> "running, no host on it now"
        }
    }

    /** The hosts on a running job in a few words: `local`, `2 nodes`, `local + 2 nodes`. */
    fun hostsText(local: Boolean, nodes: Int): String =
        listOfNotNull("local".takeIf { local }, nodes.takeIf { it > 0 }?.let { "$it ${if (it == 1) "node" else "nodes"}" })
            .joinToString(" + ").ifEmpty { "no host" }

    /** What the cloud worker reports an idle node doing ([NodeReport.doing]). */
    private const val IDLE = "idle"
}
