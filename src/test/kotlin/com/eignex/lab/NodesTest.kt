package com.eignex.lab

import kotlin.test.Test
import kotlin.test.assertEquals

class NodesTest {
    private val sizes = listOf(InstanceSize("c7i.2xlarge", 8), InstanceSize("c7i.4xlarge", 16), InstanceSize("c7i.8xlarge", 32))
    private val spec = ExperimentSpec("e", listOf(mapOf("suite" to "s")), description = "d")

    @Test
    fun `a case costs its whole budget on every core it holds, a profile its own allowance`() {
        val arm = Arm("a", mapOf("timeout" to "60000", "processors" to "2"))

        assertEquals(2.0 / 60, NodePool.caseCoreHours(arm, profile = false), 1e-9)
        assertEquals((Experiments.PROFILE_TIMEOUT_MS / 1000.0 + 120) / 3600, NodePool.caseCoreHours(Arm("p", emptyMap()), profile = true), 1e-9)
    }

    @Test
    fun `an unplanned job is estimated from its selection caps, arms, seeds and repeats`() {
        fun cases(vararg selection: Map<String, String>, seeds: List<Long> = emptyList(), repeats: Int = 1, arms: Int = 1) =
            NodePool.estimatedCases(spec.copy(problems = selection.toList(), seeds = seeds, repeats = repeats,
                configs = (1..arms).map { mapOf("label" to "a$it") }))

        assertEquals(listOf(30, 20, 30, NodePool.ASSUMED_SELECTION),
            listOf(cases(mapOf("suite" to "s", "max" to "30")), cases(mapOf("suite" to "s", "per-family" to "2")),
                cases(mapOf("suite" to "s", "per-family" to "5", "max" to "30")), cases(mapOf("suite" to "s"))))
        assertEquals(10 * 2 * 2 * 3, cases(mapOf("suite" to "s", "max" to "10"), seeds = listOf(1, 2), repeats = 3, arms = 2))
        assertEquals(NodePool.ASSUMED_OPEN_SELECTION, cases(mapOf("suite" to "s", "per-family" to "1000000")))
        assertEquals(15, cases(mapOf("suite" to "s", "max" to "5"), mapOf("suite" to "t", "max" to "10")))
        assertEquals(2 * 3, cases(mapOf("suite" to "s", "name" to "a"), mapOf("suite" to "s", "name" to "b"), repeats = 3))
        assertEquals(3, NodePool.estimatedCases(spec.copy(problems = emptyList(), problemList = List(3) { Problem("s", "p$it") })))
        assertEquals(1, NodePool.estimatedCases(ExperimentSpec("p", profile = ProfileMain("com.eignex.klause.bench.profile.T"))))
    }

    @Test
    fun `a planned job counts its queued cases, an unplanned one its estimate`() {
        val timed = spec.copy(problems = listOf(mapOf("suite" to "s", "max" to "10")), base = mapOf("timeout" to "360000"))

        assertEquals(0.1 * 4, NodePool.coreHours(timed, queuedCases = 4), 1e-9)
        assertEquals(0.1 * 10, NodePool.coreHours(timed, queuedCases = null), 1e-9)
    }

    @Test
    fun `a short profile alone, even capped to all of the Mac's cores, is far below the launch threshold`() {
        val profile = Experiments.capToMac(ExperimentSpec("p", base = mapOf("processors" to "12", "timeout" to "180000"),
            profile = ProfileMain("com.eignex.klause.bench.profile.T")), cores = 10, maxParallel = 6)
        val threshold = NodePool.threshold(macCores = 10, macHours = 1.0)

        assertEquals(10.0, threshold, 1e-9)
        assertEquals(10 * 300 / 3600.0, NodePool.coreHours(profile, queuedCases = null), 1e-9)
        assertEquals(false, LaunchGate().let { gate ->
            listOf(0L, 10L, 20L).map { gate.shouldLaunch(it * 60_000, NodePool.coreHours(profile, null), 0, threshold, true, 10 * 60_000, 5 * 60_000) }
        }.any { it })
    }

    @Test
    fun `pressure leaves out work below the minimum priority`() {
        val demands = listOf(Demand(1, 2.0, 1, priority = 0), Demand(2, 3.0, 1, priority = -1), Demand(3, 0.5, 1, priority = 4))

        assertEquals(listOf(5.5, 2.5, 0.5), listOf(-1, 0, 1).map { NodePool.pressure(demands, it) })
    }

    @Test
    fun `a node is the smallest size that covers its share of the pressure, capped well below the largest`() {
        fun size(cores: Int = 1, pressure: Double, nodes: Int = 0, free: Int = 128, max: Int = 16) =
            NodePool.size(sizes, cores, pressure, nodes, free, max)?.type

        assertEquals("c7i.2xlarge", size(pressure = 2.0))
        assertEquals("c7i.2xlarge", size(pressure = 8.0))
        assertEquals("c7i.4xlarge", size(pressure = 12.0))
        // More pressure than one capped node covers: still capped, the next launch adds another node instead.
        assertEquals("c7i.4xlarge", size(pressure = 200.0))
        assertEquals("c7i.8xlarge", size(pressure = 200.0, max = 32))
        // Shared over the nodes up: a third node for 24 core-hours needs only a small one.
        assertEquals("c7i.2xlarge", size(pressure = 24.0, nodes = 2))
        // A case's own cores win over the cap, and the quota over everything.
        assertEquals("c7i.8xlarge", size(cores = 16, pressure = 1.0))
        assertEquals(null, size(cores = 16, pressure = 1.0, free = 16))
        assertEquals("c7i.2xlarge", size(pressure = 200.0, free = 8))
        assertEquals(null, size(pressure = 1.0, free = 4))
    }

    @Test
    fun `a free node helps a running job before it claims a queued one`() {
        val node = NodeShape(cores = 4, onDemand = false)
        val running = listOf(Demand(1, 1.0, 1, priority = 0), Demand(2, 1.0, 1, priority = 3))
        val queued = listOf(Demand(3, 9.0, 1, priority = 9))

        assertEquals(NodeWork.Help(2), NodePool.pick(node, running, queued))
        assertEquals(NodeWork.Claim(3), NodePool.pick(node, emptyList(), queued))
        assertEquals(null, NodePool.pick(node, emptyList(), emptyList()))
    }

    @Test
    fun `a node takes only work whose cases fit it, profiles only on demand, and leaves the free Mac its next job`() {
        val spot = NodeShape(cores = 4, onDemand = false)
        val onDemand = NodeShape(cores = 4, onDemand = true)
        val queued = listOf(Demand(1, 1.0, 8), Demand(2, 1.0, 1, profile = true), Demand(3, 1.0, 2), Demand(4, 1.0, 1))

        assertEquals(NodeWork.Claim(3), NodePool.pick(spot, listOf(Demand(9, 1.0, 8)), queued))
        assertEquals(NodeWork.Claim(2), NodePool.pick(onDemand, emptyList(), queued))
        assertEquals(NodeWork.Claim(4), NodePool.pick(spot, emptyList(), queued, excluded = 3))
        assertEquals(NodeWork.Claim(1), NodePool.pick(NodeShape(8, onDemand = false), emptyList(), queued))
    }

    @Test
    fun `a node retires when idle long enough, at once when an update waits, and near its maxHours`() {
        val idle = 5 * 60_000L
        val maxAge = NodePool.maxAgeMs(24)

        assertEquals(23 * 3_600_000L, maxAge)
        assertEquals(null, NodePool.retire(1_000_000, 0, idleSince = null, idle, maxAge, updateWaiting = true))
        assertEquals(null, NodePool.retire(1_000_000, 0, idleSince = 1_000_000 - idle + 1, idle, maxAge, updateWaiting = false))
        assertEquals("was idle for 5 min", NodePool.retire(1_000_000, 0, idleSince = 1_000_000 - idle, idle, maxAge, updateWaiting = false))
        assertEquals("is idle and a lab update waits", NodePool.retire(1_000_000, 0, idleSince = 999_000, idle, maxAge, updateWaiting = true))
        assertEquals("is near its maxHours", NodePool.retire(maxAge, 0, idleSince = null, idle, maxAge, updateWaiting = false))
    }

    @Test
    fun `the first node launches once the pressure has held for the sustain time`() {
        val gate = LaunchGate()
        fun at(min: Long, pressure: Double, nodes: Int = 0, room: Boolean = true) =
            gate.shouldLaunch(min * 60_000, pressure, nodes, 1.0, room, 3 * 60_000, 5 * 60_000)

        assertEquals(listOf(false, false, true), listOf(at(0, 2.0), at(2, 2.0), at(3, 2.0)))
        // A burst that passes before the sustain time launches nothing.
        assertEquals(listOf(false, false, false, false), listOf(at(10, 2.0), at(12, 0.5), at(13, 2.0), at(15, 2.0)))
        assertEquals(true, at(16, 2.0))
    }

    @Test
    fun `no launch without room, and the wait counts on until there is`() {
        val gate = LaunchGate()
        fun at(min: Long, room: Boolean) = gate.shouldLaunch(min * 60_000, 5.0, 0, 1.0, room, 3 * 60_000, 5 * 60_000)

        assertEquals(listOf(false, false, true), listOf(at(0, room = false), at(4, room = false), at(5, room = true)))
    }

    @Test
    fun `another node needs twice the threshold per node, the sustain time and the cooldown`() {
        val gate = LaunchGate()
        fun at(min: Long, pressure: Double, nodes: Int) = gate.shouldLaunch(min * 60_000, pressure, nodes, 1.0, true, 3 * 60_000, 5 * 60_000)

        assertEquals(true, listOf(at(0, 3.0, 0), at(3, 3.0, 0)).last())
        // One node up: 1.5 core-hours is not twice the threshold.
        assertEquals(false, at(4, 1.5, 1))
        assertEquals(listOf(false, false, true), listOf(at(4, 2.5, 1), at(7, 2.5, 1), at(8, 2.5, 1)))
        assertEquals(listOf(false, false), listOf(at(9, 3.9, 2), at(20, 3.9, 2)))
    }

    @Test
    fun `the next node's threshold is the threshold, then twice it per node`() {
        assertEquals(listOf(10.0, 20.0, 40.0, 60.0), (0..3).map { NodePool.nextThreshold(10.0, it) })
    }

    @Test
    fun `the gate says what holds a launch back, the threshold, the room, the sustain time or the cooldown`() {
        val gate = LaunchGate()
        fun at(min: Long, pressure: Double, nodes: Int = 0, noRoom: String? = null): Pair<Boolean, String?> {
            val launched = gate.shouldLaunch(min * 60_000, pressure, nodes, 1.0, noRoom == null, 3 * 60_000, 5 * 60_000)
            return launched to gate.blocker(min * 60_000, pressure, nodes, 1.0, noRoom, 3 * 60_000, 5 * 60_000)
        }

        assertEquals(false to LaunchGate.BELOW, at(0, 0.5))
        assertEquals(null, gate.since)
        assertEquals(false to "waiting 3 min more", at(0, 2.0))
        assertEquals(0L, gate.since)
        assertEquals(false to LaunchGate.QUOTA_FULL, at(1, 2.0, noRoom = LaunchGate.QUOTA_FULL))
        assertEquals(false to "waiting 1 min more", at(2, 2.0))
        assertEquals(true, at(3, 2.0).first)
        assertEquals(listOf(null, 3 * 60_000L), listOf(gate.since, gate.lastLaunch))
        // One node up: 2.5 core-hours is past twice the threshold, held 4 minutes, but only 5 after the launch.
        at(4, 2.5, nodes = 1)
        assertEquals(false to "cooldown 1 min", at(7, 2.5, nodes = 1))
        assertEquals(false to LaunchGate.AT_MAX, at(7, 2.5, nodes = 1, noRoom = LaunchGate.AT_MAX))
        assertEquals(true, at(8, 2.5, nodes = 1).first)
    }

    @Test
    fun `a job's remaining estimate counts its queued cases, a running one's unfinished ones, and an unplanned one's guess`() {
        val timed = spec.copy(problems = listOf(mapOf("suite" to "s", "max" to "10")), base = mapOf("timeout" to "360000"))
        fun job(status: Status, counts: CommandCounts?, experiment: ExperimentSpec? = timed) =
            Job(1, "e", "main", status = status, cancelRequested = false, setupDone = true, createdAt = 0, experiment = experiment, counts = counts)

        assertEquals(0.1 * 4, NodePool.remainingCoreHours(job(Status.QUEUED, CommandCounts(10, 4, 0, 2)))!!, 1e-9)
        assertEquals(0.1 * 6, NodePool.remainingCoreHours(job(Status.RUNNING, CommandCounts(10, 4, 0, 2)))!!, 1e-9)
        assertEquals(0.1 * 10, NodePool.remainingCoreHours(job(Status.RUNNING, CommandCounts(0, 0, 0, 0)))!!, 1e-9)
        assertEquals(null, NodePool.remainingCoreHours(job(Status.QUEUED, null, experiment = null)))
    }
}
