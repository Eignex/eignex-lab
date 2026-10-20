package com.eignex.lab

import kotlinx.serialization.Serializable

/**
 * What one job asks of the AWS node pool: its estimated core-hours of work no node or the Mac has started, the cores
 * its largest case holds, and whether it is a profile job, which only an on-demand node may run.
 */
data class Demand(
    val jobId: Long,
    val coreHours: Double,
    val cores: Int,
    val profile: Boolean = false,
    val priority: Int = 0,
)

/** A node as the work selection sees it: the cores its cases may hold, and whether it was bought on demand. */
data class NodeShape(val cores: Int, val onDemand: Boolean)

/** What a free node does next: help a job already running on AWS with its unclaimed problems, or claim a queued one. */
sealed interface NodeWork {
    val jobId: Long

    data class Help(override val jobId: Long) : NodeWork
    data class Claim(override val jobId: Long) : NodeWork
}

/**
 * The node pool's decisions, kept free of AWS and the store so they can be tested on their own: how much work waits
 * for AWS (pressure, in core-hours), whether to launch a node, what size, what a free node takes, and when it retires.
 */
object NodePool {
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
     * `max`, else its `per-family` over [ASSUMED_FAMILIES] families, else [ASSUMED_SELECTION]; a selection naming one
     * problem (`name`) and an exact problem list count as they are. Times the arms, seeds and repeats. A profile job runs one case.
     */
    fun estimatedCases(spec: ExperimentSpec): Int {
        if (spec.isProfile) return 1
        val problems = spec.problemList.size.takeIf { it > 0 } ?: spec.problems.sumOf { selection ->
            if ("name" in selection) return@sumOf 1
            val max = selection["max"]?.toIntOrNull()
            val perFamily = selection["per-family"]?.toIntOrNull()?.times(ASSUMED_FAMILIES)
            listOfNotNull(max, perFamily).minOrNull() ?: ASSUMED_SELECTION
        }
        return problems * Experiments.arms(spec).size * maxOf(1, spec.seeds.size) * spec.repeats
    }

    /** Core-hours left of a queued job: its queued cases when it was planned ([queuedCases]), its estimate otherwise. */
    fun coreHours(spec: ExperimentSpec, queuedCases: Int?): Double =
        (queuedCases ?: estimatedCases(spec)) * meanCaseCoreHours(spec)

    /** The pressure, in core-hours, that launches the first node: the work that would keep the Mac's [macCores] busy
     *  for [macHours]. A job or two the Mac clears within that never starts a node. */
    fun threshold(macCores: Int, macHours: Double): Double = macCores * macHours

    /** The pressure on the pool: every demand's core-hours, the ones at [minPriority] or above. */
    fun pressure(demands: List<Demand>, minPriority: Int): Double =
        demands.filter { it.priority >= minPriority }.sumOf { it.coreHours }

    /**
     * The size of a new node, or null when none fits the vCPUs left of the quota: the smallest size that has the
     * cores the largest waiting case needs and covers this node's share of the pressure (split over the [nodes]
     * running and this one) within [COVER_HOURS], and no larger than [maxVcpus] unless a case itself needs more.
     * Nodes stay small: more pressure is met by launching another node later, which the damping allows, rather than
     * one large node up front that idles once its burst is gone.
     */
    fun size(sizes: List<InstanceSize>, caseCores: Int, pressure: Double, nodes: Int, freeVcpus: Int, maxVcpus: Int): InstanceSize? {
        val fitting = sizes.filter { it.cores >= caseCores && it.vcpus <= freeVcpus }
        if (fitting.isEmpty()) return null
        val share = pressure / (nodes + 1)
        val capped = fitting.filter { it.vcpus <= maxVcpus }.ifEmpty { listOf(fitting.first()) }
        return capped.firstOrNull { it.cores * COVER_HOURS >= share } ?: capped.last()
    }

    /**
     * What a free node of [node]'s shape takes next. First a job running on AWS that still has unclaimed problems
     * ([helpable], highest priority first), joining it; else the first of [queued] (in queue order) it may claim. A
     * job fits when its largest case's cores fit the node, and a profile job only an on-demand node runs. [excluded]
     * is the job the Mac takes next when it is free, which a node leaves to it.
     */
    fun pick(node: NodeShape, helpable: List<Demand>, queued: List<Demand>, excluded: Long? = null): NodeWork? {
        fun fits(d: Demand) = d.cores <= node.cores && (!d.profile || node.onDemand)
        helpable.filter(::fits).maxByOrNull { it.priority }?.let { return NodeWork.Help(it.jobId) }
        return queued.firstOrNull { it.jobId != excluded && fits(it) }?.let { NodeWork.Claim(it.jobId) }
    }

    /**
     * Why a node should retire now, or null to keep it: past [maxAgeMs] (its power-off at `maxHours`, less a margin),
     * or idle for [idleMs], at once when an update waits for the pool to drain.
     */
    fun retire(now: Long, launchedAt: Long, idleSince: Long?, idleMs: Long, maxAgeMs: Long, updateWaiting: Boolean): String? = when {
        now - launchedAt >= maxAgeMs -> "is near its maxHours"
        idleSince != null && updateWaiting -> "is idle and a lab update waits"
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
    /** A node stops taking work an hour before it powers itself off. */
    const val RETIRE_MARGIN_MS = 60 * 60_000L
    private const val MS_PER_HOUR = 3_600_000.0
    private const val MS_PER_SEC = 1000L
    private const val SEC_PER_MIN = 60L
}

/**
 * When to launch a node: once the pressure has stayed at or over the threshold for [sustainMs], and for each further
 * node only while the pressure is at least twice the threshold per node already up or launching, and [cooldownMs]
 * after the last launch. A burst that passes before then launches nothing; the damping keeps the pool from chasing
 * every queued job.
 */
class LaunchGate {
    private var since: Long? = null
    private var lastLaunch: Long? = null

    /** Whether [pressure] calls for another node beside [nodes], at [now], with room for one ([room]). Records the
     *  launch when it says yes. The settings come with each call, since the worker rereads them on every poll. */
    @Synchronized
    fun shouldLaunch(
        now: Long, pressure: Double, nodes: Int, threshold: Double, room: Boolean, sustainMs: Long, cooldownMs: Long,
    ): Boolean {
        val wanted = pressure >= threshold * if (nodes == 0) 1.0 else 2.0 * nodes
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
}

/** The pool as the pages show it, written by the AWS worker to `aws/nodes.json` on each change. */
@Serializable
data class NodePoolReport(
    val updatedAt: Long,
    val pressure: Double,
    val launching: Int,
    val nodes: List<NodeReport>,
)

@Serializable
data class NodeReport(val instance: String, val type: String, val market: String, val doing: String, val job: Long? = null)
