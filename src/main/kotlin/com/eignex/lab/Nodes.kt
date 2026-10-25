package com.eignex.lab

import kotlinx.serialization.Serializable

/**
 * What one job asks of the cloud node pool: its estimated core-hours of work no node or the local machine has started,
 * the cores its largest case holds, and whether it is a profile job, which prefers on-demand unless its selector explicitly asks for spot. A
 * [belowFloor] job, work the local machine could run below [AwsConfig.nodeMinPriority], launches no node: it is not
 * pressure, but a node already up with nothing else to do takes it. [hosts] is how many hosts work on it now: none for
 * a queued job. [measures] is a job that records CLI measurements; it and a profile job are [exclusive]: they measure
 * resources, so nothing runs beside them on a node.
 */
data class Demand(
    val jobId: Long,
    val coreHours: Double,
    val cores: Int,
    val profile: Boolean = false,
    val priority: Int = 0,
    val belowFloor: Boolean = false,
    val hosts: Int = 0,
    val nodeTags: NodeSelector? = null,
    val measures: Boolean = false,
) {
    /** Whether the job keeps a node to itself: a profile, or a job that records measurements. */
    val exclusive: Boolean get() = profile || measures
}

/** A node as the work selection sees it: the cores its cases may hold, whether it was bought on demand, and, for an
 *  on-demand node, whether it takes [ordinary] work, work that does not need an on-demand node ([NodePool.pick]). */
data class NodeShape(val cores: Int, val onDemand: Boolean, val tags: Set<String> = emptySet(), val ordinary: Boolean = true)

data class NodeLaunchPlan(val size: InstanceSize, val onDemand: Boolean, val spotOnly: Boolean)

/** What a node with room does next: help a shared job already running, wherever, with its unclaimed iterations, or claim
 *  a queued one; or [Wait] for its jobs to end, as the job it should take next keeps a node to itself. */
sealed interface NodeWork {
    val jobId: Long

    data class Help(override val jobId: Long) : NodeWork
    data class Claim(override val jobId: Long) : NodeWork
    /** [jobId] is [Demand.exclusive] and comes next, but the node runs other jobs: it takes nothing more, and the jobs
     *  on it claim no more iterations, so it empties for that job. */
    data class Wait(override val jobId: Long) : NodeWork
}

/**
 * The node pool's decisions, kept free of the cloud provider and the store so they can be tested on their own: how much
 * work waits for the cloud (pressure, in core-hours), whether to launch a node, what size, what a free node takes, and
 * when it retires.
 */
object NodePool {
    /** Explicit requirements the local machine cannot satisfy need a matching node even for a short job. */
    fun dedicated(demands: List<Demand>, localTags: Set<String>, provisioned: List<NodeShape>): Demand? =
        demands.firstOrNull { d -> d.nodeTags != null && !d.belowFloor && !NodeTags.matches(d.nodeTags, localTags) &&
            provisioned.none { node -> node.cores >= d.cores && (!NodeTags.onDemand(d.profile, d.nodeTags) || node.onDemand) && NodeTags.matches(d.nodeTags, node.tags) } }

    /** Choose a shape for a compatible group of waiting work instead of combining incompatible architectures.
     *  [coveredCores] are the cores of the nodes up or launching that take this work ([size]). */
    fun launchPlan(sizes: List<InstanceSize>, demands: List<Demand>, coveredCores: Int, freeVcpus: Int, maxVcpus: Int): NodeLaunchPlan? {
        if (demands.isEmpty()) return null
        if (demands.all { it.nodeTags == null }) {
            val chosen = size(sizes, demands.maxOf { it.cores }, pressure(demands), coveredCores, freeVcpus, maxVcpus) ?: return null
            return NodeLaunchPlan(chosen, demands.any { it.profile }, false)
        }
        for (demand in demands.sortedWith(compareByDescending<Demand> { it.priority }.thenByDescending { it.cores })) {
            val onDemand = NodeTags.onDemand(demand.profile, demand.nodeTags)
            val eligible = sizes.filter { NodeTags.matches(demand.nodeTags, it.tags(onDemand)) && it.cores >= demand.cores }
            if (eligible.isEmpty()) continue
            val family = eligible.first().type.substringBefore('.')
            val familySizes = eligible.filter { it.type.substringBefore('.') == family }
            val group = demands.filter { d -> (!NodeTags.onDemand(d.profile, d.nodeTags) || onDemand) && familySizes.any { it.cores >= d.cores && NodeTags.matches(d.nodeTags, it.tags(onDemand)) } }
            val chosen = size(familySizes, group.maxOf { it.cores }, pressure(group), coveredCores, freeVcpus, maxVcpus) ?: continue
            return NodeLaunchPlan(chosen, onDemand, NodeTags.spotOnly(demand.nodeTags))
        }
        return null
    }
    /** Core-hours a case of [arm] may take: its whole budget on every core it holds, the cost of a case that never
     *  solves early. A profile job's one case gets its own allowance. */
    fun caseCoreHours(arm: Arm, profile: Boolean): Double {
        val ms = if (profile) Experiments.profileTimeoutSec(arm) * MS_PER_SEC else arm.timeoutMs
        return ms * arm.cores / MS_PER_HOUR
    }

    /** The mean core-hours of one case of [spec]: the arms alternate within a problem, so each takes an equal share. */
    fun meanCaseCoreHours(spec: ExperimentSpec): Double {
        val arms = Experiments.arms(spec)
        return arms.sumOf { caseCoreHours(it, spec.isProfile) } / arms.size
    }

    /**
     * Cases [spec] is expected to run before it is planned, when the selection is not known yet: per selection its
     * `max`, else its `per-family` over [ASSUMED_FAMILIES] families but at most [ASSUMED_OPEN_SELECTION] (a
     * `per-family` of 1000000 means "all of it", not millions), else [ASSUMED_SELECTION]; a selection naming one
     * problem (`name`) and an exact problem list count as they are. Times the arms, seeds and repeats. A profile job runs one case.
     */
    fun estimatedCases(spec: ExperimentSpec): Int {
        if (spec.isProfile) return 1
        val problems = spec.problemList.size.takeIf { it > 0 } ?: spec.problems.sumOf { selection ->
            if ("name" in selection) return@sumOf 1
            val max = selection["max"]?.toIntOrNull()
            val perFamily = selection["per-family"]?.toIntOrNull()?.times(ASSUMED_FAMILIES)
            max?.let { minOf(it, perFamily ?: it) } ?: perFamily?.let { minOf(it, ASSUMED_OPEN_SELECTION) } ?: ASSUMED_SELECTION
        }
        return problems * Experiments.arms(spec).size * maxOf(1, spec.seeds.size) * spec.repeats
    }

    /** Core-hours left of a queued job by each case's whole timeout: its queued cases when it was planned
     *  ([queuedCases]), its estimate otherwise. The upper bound, which [Estimates] fall back on. */
    fun coreHours(spec: ExperimentSpec, queuedCases: Int?): Double =
        (queuedCases ?: estimatedCases(spec)) * meanCaseCoreHours(spec)

    /** The pressure, in core-hours, that launches the first node: the work that would keep the local machine's
     *  [localCores] busy for [localHours]. A job or two the local machine clears within that never starts a node. */
    fun threshold(localCores: Int, localHours: Double): Double = localCores * localHours

    /** The pressure that launches another node beside the [nodes] up or launching: [threshold] for the first, then
     *  twice it per node already there. */
    fun nextThreshold(threshold: Double, nodes: Int): Double = threshold * if (nodes == 0) 1.0 else 2.0 * nodes

    /** The pressure on the pool: every demand's core-hours but the ones below the priority floor, which launch no node. */
    fun pressure(demands: List<Demand>): Double = demands.filter { !it.belowFloor }.sumOf { it.coreHours }

    /**
     * The size of a new node, or null when none fits the vCPUs left of the quota: the smallest size that has the
     * cores the largest waiting case needs and covers, within [COVER_HOURS], the pressure the nodes already up or
     * launching do not ([coveredCores], their cores, each covering [COVER_HOURS] of it), and no larger than [maxVcpus]
     * unless a case itself needs more. Nodes stay small: more pressure is met by launching another node later, which
     * the damping allows, rather than one large node up front that idles once its burst is gone.
     */
    fun size(sizes: List<InstanceSize>, caseCores: Int, pressure: Double, coveredCores: Int, freeVcpus: Int, maxVcpus: Int): InstanceSize? {
        val fitting = sizes.filter { it.cores >= caseCores && it.vcpus <= freeVcpus }
        if (fitting.isEmpty()) return null
        val share = (pressure - coveredCores * COVER_HOURS).coerceAtLeast(0.0)
        val capped = fitting.filter { it.vcpus <= maxVcpus }.ifEmpty { listOf(fitting.first()) }
        return capped.firstOrNull { it.cores * COVER_HOURS >= share } ?: capped.last()
    }

    /**
     * What a node of [node]'s shape takes next beside the jobs it runs ([on]), with [free] case slots of its capacity
     * left ([slots]): a shared job running that still has unclaimed iterations ([helpable]), joining it, or one of
     * [queued] (in queue order) it may claim. Priority decides first, running or queued alike; among equal priorities
     * the job with the fewest [Demand.hosts] on it, so nodes spread over the jobs rather than pile onto one (a queued
     * job has none, so it goes before joining a running one); then a running job before a queued one, each in queue
     * order. Work at or above the priority floor goes first; only when there is none does the node take
     * [Demand.belowFloor] work, by the same rules, so a node already up never idles while such work waits behind the
     * local machine's queue. On an on-demand node the work that needs one (a profile job, or a selector asking for
     * on demand) goes before everything else, as that is what it was bought for; an on-demand node that does not take
     * [NodeShape.ordinary] work takes nothing else. A job fits when its largest case's cores fit the node, a profile job
     * prefers an on-demand node unless its selector explicitly requests spot, and beside other jobs its largest case
     * must fit the slots left ([caseSlots]). An [Demand.exclusive] job runs alone: one that comes next while the node
     * runs other jobs makes it [NodeWork.Wait]. [excluded] is the job the local machine takes next when it is free,
     * which a node leaves to it.
     */
    fun pick(
        node: NodeShape, helpable: List<Demand>, queued: List<Demand>, excluded: Long? = null,
        free: Int = slots(node.cores), on: Set<Long> = emptySet(),
    ): NodeWork? {
        fun needsOnDemand(d: Demand) = NodeTags.onDemand(d.profile, d.nodeTags)
        fun fits(d: Demand) = d.cores <= node.cores && (!needsOnDemand(d) || node.onDemand) && NodeTags.matches(d.nodeTags, node.tags) &&
            (!node.onDemand || node.ordinary || needsOnDemand(d)) && d.jobId !in on
        val help = helpable.filter(::fits).map { it to NodeWork.Help(it.jobId) }
        val claim = queued.filter { it.jobId != excluded && fits(it) }.map { it.copy(hosts = 0) to NodeWork.Claim(it.jobId) }
        // sortedWith is stable: equal priority and hosts keep running jobs first, each list in its queue order.
        val order = compareByDescending<Pair<Demand, NodeWork>> { node.onDemand && needsOnDemand(it.first) }
            .thenBy { it.first.belowFloor }.thenByDescending { it.first.priority }.thenBy { it.first.hosts }
        for ((demand, work) in (help + claim).sortedWith(order)) {
            if (on.isEmpty()) return work
            if (demand.exclusive) return NodeWork.Wait(demand.jobId)
            if (caseSlots(demand.cores, slots(node.cores)) <= free) return work
        }
        return null
    }

    /** The cases a node of [cores] physical cores runs at once, from every job on it together: one per two cores, the
     *  density a job that sets no `parallel` runs at, which leaves each case's JVMs a core for their JIT and GC. */
    fun slots(cores: Int): Int = (cores / 2).coerceAtLeast(1)

    /** The slots of a node's [capacity] a case of [caseCores] cores takes: one per two of its cores, rounded up, at
     *  least one and at most the whole node, so a case that needs the whole node runs alone. */
    fun caseSlots(caseCores: Int, capacity: Int): Int = ((caseCores + 1) / 2).coerceIn(1, capacity.coerceAtLeast(1))

    /**
     * Why a node should retire now, or null to keep it: past [maxAgeMs] (its power-off at `maxHours`, less a margin),
     * or idle for [idleMs]. A waiting lab update retires no node: the restarted worker adopts the pool again.
     */
    fun retire(now: Long, launchedAt: Long, idleSince: Long?, idleMs: Long, maxAgeMs: Long): String? = when {
        now - launchedAt >= maxAgeMs -> "is near its maxHours"
        idleSince != null && now - idleSince >= idleMs -> "was idle for ${(now - idleSince) / MS_PER_SEC / SEC_PER_MIN} min"
        else -> null
    }

    /** The age past which a node takes no more work: its `maxHours` power-off, less [RETIRE_MARGIN_MS]. */
    fun maxAgeMs(maxHours: Int): Long = (maxHours * MS_PER_HOUR.toLong() - RETIRE_MARGIN_MS).coerceAtLeast(0)

    /** How many hours of its share of the pressure a node is sized to clear. */
    const val COVER_HOURS = 2
    /** Families a `per-family` selection is assumed to span before it is planned. */
    const val ASSUMED_FAMILIES = 10
    /** Problems a selection with neither `max` nor `per-family` is assumed to give before it is planned. */
    const val ASSUMED_SELECTION = 20
    /** Problems a `per-family` selection without `max` is assumed to give at most before it is planned. */
    const val ASSUMED_OPEN_SELECTION = 200
    /** A node stops taking work an hour before it powers itself off. */
    const val RETIRE_MARGIN_MS = 60 * 60_000L
    private const val MS_PER_HOUR = 3_600_000.0
    private const val MS_PER_SEC = 1000L
    private const val SEC_PER_MIN = 60L
}

/**
 * A node's [capacity] in case slots ([NodePool.slots]), shared by the jobs it runs at once: each case holds
 * [NodePool.caseSlots] of them while it runs. A job just taken holds a reservation until its cases start ([reserve]), so
 * the node does not take more jobs than it has room for while they set up. Slots go out first come, first served: a job
 * that found too few free waits at the head of the line, and no other job takes a slot before it, so a job of large
 * cases is not starved by small ones, nor a job of low `parallel` by one of high. A job that stops asking leaves the
 * line after [STALE_MS].
 */
class NodeSlots(val capacity: Int) {
    private var used = 0
    private val reserved = HashMap<Long, Int>()
    /** The jobs waiting for slots, in the order they first asked: the slots each needs, and when it last asked. */
    private val waiting = LinkedHashMap<Long, Pair<Int, Long>>()

    /** Take [slots] for a case of [job], or false when there is no room for it now or another job waits first. */
    @Synchronized
    fun acquire(job: Long, slots: Int, now: Long = System.currentTimeMillis()): Boolean {
        waiting.entries.removeIf { now - it.value.second > STALE_MS }
        val first = waiting.keys.firstOrNull()
        if (used + slots <= capacity && (first == null || first == job)) {
            waiting.remove(job)
            reserved.remove(job)
            used += slots
            return true
        }
        waiting[job] = slots to now
        return false
    }

    /** Give back [slots] a case held. */
    @Synchronized
    fun release(slots: Int) {
        used = (used - slots).coerceAtLeast(0)
    }

    /** Hold [slots] for [job], just taken, until its first case starts or it leaves. */
    @Synchronized
    fun reserve(job: Long, slots: Int) {
        reserved[job] = slots
    }

    /** [job] left the node: it waits for nothing and holds no reservation. */
    @Synchronized
    fun leave(job: Long) {
        reserved.remove(job)
        waiting.remove(job)
    }

    /** The slots the running cases hold. */
    @get:Synchronized
    val busy: Int get() = used

    /** The slots no case, reservation or waiting job claims, for another job to take. */
    @Synchronized
    fun free(now: Long = System.currentTimeMillis()): Int {
        waiting.entries.removeIf { now - it.value.second > STALE_MS }
        return (capacity - used - reserved.values.sum() - waiting.values.sumOf { it.first }).coerceAtLeast(0)
    }

    companion object {
        /** How long a job that stopped asking for a slot keeps its place in the line: its dispatch asks every
         *  [Runner.DISPATCH_POLL_MS] while it waits. */
        const val STALE_MS = 5_000L
    }
}

/**
 * When to launch a node: once the pressure has stayed at or over the threshold for [sustainMs], and for each further
 * node only while the pressure is at least twice the threshold per node already up or launching, and [cooldownMs]
 * after the last launch. A burst that passes before then launches nothing; the damping keeps the pool from chasing
 * every queued job.
 */
class LaunchGate {
    /** When the pressure came to stand at or over the threshold for the next node, with no launch since; null while
     *  it is under. */
    @Volatile
    var since: Long? = null
        private set
    /** When the gate last said to launch. */
    @Volatile
    var lastLaunch: Long? = null
        private set

    /** Whether [pressure] calls for another node beside [nodes], at [now], with room for one ([room]). Records the
     *  launch when it says yes. The settings come with each call, since the worker rereads them on every poll. */
    @Synchronized
    fun shouldLaunch(
        now: Long, pressure: Double, nodes: Int, threshold: Double, room: Boolean, sustainMs: Long, cooldownMs: Long,
    ): Boolean {
        val wanted = pressure >= NodePool.nextThreshold(threshold, nodes)
        if (!wanted) {
            since = null
            return false
        }
        // The wait counts while there is no room, so a node launches as soon as there is.
        val start = since ?: now.also { since = it }
        if (!room || now - start < sustainMs) return false
        if (nodes > 0 && lastLaunch?.let { now - it < cooldownMs } == true) return false
        lastLaunch = now
        since = null
        return true
    }

    /** Record a node started by hand at [now] ([ManualLaunch]) as the last launch, so the cooldown holds the next
     *  automatic one, and start the sustain wait afresh. */
    @Synchronized
    fun manualLaunch(now: Long) {
        lastLaunch = now
        since = null
    }

    /**
     * Why no node launches at [now], in plain words for the pages, given what [shouldLaunch] was last given: the
     * pressure is under the threshold, there is no room ([noRoom], the reason), the pressure has not held for
     * [sustainMs] yet, or the cooldown after the last launch runs. Null when nothing holds a launch back.
     */
    @Synchronized
    fun blocker(
        now: Long, pressure: Double, nodes: Int, threshold: Double, noRoom: String?, sustainMs: Long, cooldownMs: Long,
    ): String? {
        if (pressure < NodePool.nextThreshold(threshold, nodes)) return BELOW
        if (noRoom != null) return noRoom
        since?.let { start -> (sustainMs - (now - start)).takeIf { it > 0 }?.let { return "waiting ${minutes(it)} min more" } }
        if (nodes > 0) lastLaunch?.let { at -> (cooldownMs - (now - at)).takeIf { it > 0 }?.let { return "cooldown ${minutes(it)} min" } }
        return null
    }

    companion object {
        const val BELOW = "below threshold"
        const val AT_MAX = "at maxInstances"
        const val QUOTA_FULL = "vCPU quota full"
        /** The job files cannot be used ([Volumes.jobFiles]), or the runner's startup recovery has not run: a node
         *  could take no work. */
        const val NO_JOB_FILES = "job files unavailable"
        private const val MS_PER_MIN = 60_000L

        /** [ms] in whole minutes, rounded up, so a wait under a minute reads as one. */
        fun minutes(ms: Long): Long = (ms + MS_PER_MIN - 1) / MS_PER_MIN
    }
}

/**
 * The pool as the pages show it, written by the AWS worker to `aws/nodes.json` on each change. The launch gate's
 * fields are null in a file an older lab wrote: the [threshold] for the first node and the [nextThreshold] for the
 * next one, in core-hours; since when the pressure has stood over it ([overSince], the gate's wait); the [lastLaunch];
 * the [sustainMs] and [cooldownMs] settings; and the [blocker] that keeps the next node from launching, in plain words.
 * For a manual start ([ManualLaunch]): whether a lab update waits ([updateWaiting]), the vCPUs the nodes hold of the
 * quota ([busyVcpus]), and the size the queue calls for ([manualSize], null when none fits the quota left).
 */
@Serializable
data class NodePoolReport(
    val updatedAt: Long,
    val pressure: Double,
    val launching: Int,
    val nodes: List<NodeReport>,
    val threshold: Double? = null,
    val nextThreshold: Double? = null,
    val overSince: Long? = null,
    val lastLaunch: Long? = null,
    val sustainMs: Long? = null,
    val cooldownMs: Long? = null,
    val blocker: String? = null,
    val updateWaiting: Boolean? = null,
    val busyVcpus: Int? = null,
    val manualSize: String? = null,
)

/** A node as the pages show it; [manual] when it was started by hand ([ManualLaunch]), [adopted] when a restarted
 *  worker took it back ([AwsWorker.recover]); [launchedAt], its first launch, gives its age (null from an older lab).
 *  [jobs] are the jobs it works on now ([job], the first of them, for an older reader), [slots] its case slots and
 *  [busySlots] those its cases hold ([NodeSlots]); [free] when it has room to take another job (null from an older lab:
 *  only an idle node has); [ordinary] when it takes work that does not need an on-demand node. */
@Serializable
data class NodeReport(
    val instance: String, val type: String, val market: String, val doing: String, val job: Long? = null, val manual: Boolean = false,
    val launchedAt: Long? = null, val adopted: Boolean = false,
    val tags: Set<String> = emptySet(),
    val cpu: CpuCapabilities? = null,
    val jobs: List<Long> = listOfNotNull(job),
    val slots: Int? = null,
    val busySlots: Int? = null,
    val free: Boolean? = null,
    val ordinary: Boolean = true,
)

/** The pool as a restarted worker adopts it, in `aws/pool.json`, written on each change: how many nodes were
 *  launched, for their names, and each node. */
@Serializable
data class PoolState(val launched: Int = 0, val nodes: List<SavedNode> = emptyList())

/** A node of [PoolState]: what adopting it needs, its first launch included, so its age and `maxHours` stand, and the
 *  commits built on it. */
@Serializable
data class SavedNode(
    val instance: String, val type: String, val vcpus: Int, val market: String, val ip: String = "", val manual: Boolean = false,
    val launchedAt: Long, val built: List<String> = emptyList(),
)
