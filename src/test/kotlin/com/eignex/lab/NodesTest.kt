package com.eignex.lab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun `a short profile alone, even capped to all of the local machine's cores, is far below the launch threshold`() {
        val profile = Experiments.capToLocal(ExperimentSpec("p", base = mapOf("processors" to "12", "timeout" to "180000"),
            profile = ProfileMain("com.eignex.klause.bench.profile.T")), cores = 10, maxParallel = 6)
        val threshold = NodePool.threshold(localCores = 10, localHours = 1.0)

        assertEquals(10.0, threshold, 1e-9)
        assertEquals(10 * 300 / 3600.0, NodePool.coreHours(profile, queuedCases = null), 1e-9)
        assertEquals(false, LaunchGate().let { gate ->
            listOf(0L, 10L, 20L).map { gate.shouldLaunch(it * 60_000, NodePool.coreHours(profile, null), 0, threshold, true, 10 * 60_000, 5 * 60_000) }
        }.any { it })
    }

    @Test
    fun `pressure leaves out work below the priority floor`() {
        val demands = listOf(Demand(1, 2.0, 1, priority = 0), Demand(2, 3.0, 1, priority = -1, belowFloor = true), Demand(3, 0.5, 1, priority = 4))

        assertEquals(2.5, NodePool.pressure(demands))
    }

    @Test
    fun `a node already up takes work below the priority floor only when nothing at or above it waits`() {
        val node = NodeShape(cores = 4, onDemand = false)
        val below = listOf(Demand(7245, 1.0, 1, priority = -4, belowFloor = true), Demand(7242, 1.0, 1, priority = -4, belowFloor = true))
        val running = Demand(9, 1.0, 1, priority = -4, belowFloor = true)

        assertEquals(NodeWork.Claim(7245), NodePool.pick(node, emptyList(), below))
        // The job the local machine takes next is left to it.
        assertEquals(NodeWork.Claim(7242), NodePool.pick(node, emptyList(), below, excluded = 7245))
        assertEquals(NodeWork.Help(9), NodePool.pick(node, listOf(running), below))
        assertEquals(NodeWork.Claim(1), NodePool.pick(node, listOf(running), below + Demand(1, 1.0, 1, priority = 0)))
        assertEquals(null, NodePool.pick(node, emptyList(), below.map { it.copy(cores = 8) }))
    }

    @Test
    fun `a node is the smallest size that covers the pressure the nodes up leave, capped well below the largest`() {
        fun size(cores: Int = 1, pressure: Double, covered: Int = 0, free: Int = 128, max: Int = 16) =
            NodePool.size(sizes, cores, pressure, covered, free, max)?.type

        assertEquals("c7i.2xlarge", size(pressure = 2.0))
        assertEquals("c7i.2xlarge", size(pressure = 8.0))
        assertEquals("c7i.4xlarge", size(pressure = 12.0))
        // More pressure than one capped node covers: still capped, the next launch adds another node instead.
        assertEquals("c7i.4xlarge", size(pressure = 200.0))
        assertEquals("c7i.8xlarge", size(pressure = 200.0, max = 32))
        // The nodes up cover their cores for two hours each: beside two 2xlarge nodes, 24 core-hours need only a small one.
        assertEquals("c7i.2xlarge", size(pressure = 24.0, covered = 8))
        // The second node's threshold beside a 4xlarge (8 cores): 20 core-hours, of which it covers 16, so a 2xlarge;
        // splitting the pressure evenly would have bought another 4xlarge.
        assertEquals("c7i.2xlarge", size(pressure = 20.0, covered = 8))
        assertEquals("c7i.4xlarge", size(pressure = 30.0, covered = 8))
        assertEquals("c7i.2xlarge", size(pressure = 5.0, covered = 64))
        // A case's own cores win over the cap, and the quota over everything.
        assertEquals("c7i.8xlarge", size(cores = 16, pressure = 1.0))
        assertEquals(null, size(cores = 16, pressure = 1.0, free = 16))
        assertEquals("c7i.2xlarge", size(pressure = 200.0, free = 8))
        assertEquals(null, size(pressure = 1.0, free = 4))
    }

    @Test
    fun `priority decides first, running or queued alike`() {
        val node = NodeShape(cores = 4, onDemand = false)
        val running = listOf(Demand(1, 1.0, 1, priority = 0, hosts = 1), Demand(2, 1.0, 1, priority = 3, hosts = 2))

        assertEquals(NodeWork.Help(2), NodePool.pick(node, running, listOf(Demand(3, 9.0, 1, priority = 2), Demand(4, 9.0, 1, priority = 1))))
        assertEquals(NodeWork.Claim(3), NodePool.pick(node, running, listOf(Demand(3, 9.0, 1, priority = 9))))
        assertEquals(NodeWork.Claim(3), NodePool.pick(node, emptyList(), listOf(Demand(3, 9.0, 1, priority = 9))))
        assertEquals(null, NodePool.pick(node, emptyList(), emptyList()))
    }

    @Test
    fun `among equal priorities a node spreads to the job with the fewest hosts`() {
        val node = NodeShape(cores = 4, onDemand = false)
        val crowded = Demand(7590, 1.0, 1, priority = 0, hosts = 2)
        val joined = Demand(7591, 1.0, 1, priority = 0, hosts = 1)

        // A queued job has no hosts: it goes before becoming the third host on a running one.
        assertEquals(NodeWork.Claim(7600), NodePool.pick(node, listOf(crowded), listOf(Demand(7600, 1.0, 1, priority = 0))))
        assertEquals(NodeWork.Help(7591), NodePool.pick(node, listOf(crowded, joined), emptyList()))
        // Ties keep the queue order, a running job before a queued one.
        assertEquals(NodeWork.Help(7590), NodePool.pick(node, listOf(crowded, crowded.copy(jobId = 7592)), emptyList()))
        assertEquals(NodeWork.Help(7593), NodePool.pick(node, listOf(Demand(7593, 1.0, 1)), listOf(Demand(7600, 1.0, 1))))
        // The local machine's next job is left to it, and work below the floor still waits behind the rest.
        assertEquals(NodeWork.Help(7590), NodePool.pick(node, listOf(crowded), listOf(Demand(7600, 1.0, 1)), excluded = 7600))
        assertEquals(NodeWork.Help(7590), NodePool.pick(node, listOf(crowded), listOf(Demand(7600, 1.0, 1, priority = -4, belowFloor = true))))
        assertEquals(NodeWork.Claim(7600), NodePool.pick(node, listOf(crowded.copy(cores = 8)), listOf(Demand(7600, 1.0, 1, priority = -4, belowFloor = true))))
    }

    @Test
    fun `a node takes only work whose cases fit it, profiles only on demand, and leaves the free local machine its next job`() {
        val spot = NodeShape(cores = 4, onDemand = false)
        val onDemand = NodeShape(cores = 4, onDemand = true)
        val queued = listOf(Demand(1, 1.0, 8), Demand(2, 1.0, 1, profile = true), Demand(3, 1.0, 2), Demand(4, 1.0, 1))

        assertEquals(NodeWork.Claim(3), NodePool.pick(spot, listOf(Demand(9, 1.0, 8)), queued))
        assertEquals(NodeWork.Claim(2), NodePool.pick(onDemand, emptyList(), queued))
        assertEquals(NodeWork.Claim(4), NodePool.pick(spot, emptyList(), queued, excluded = 3))
        assertEquals(NodeWork.Claim(1), NodePool.pick(NodeShape(8, onDemand = false), emptyList(), queued))
    }

    @Test
    fun `an on-demand node runs the profile jobs it was bought for before anything else, and only them unless it takes ordinary work`() {
        val onDemand = NodeShape(cores = 4, onDemand = true)
        val profileOnly = onDemand.copy(ordinary = false)
        val urgent = Demand(1, 9.0, 1, priority = 9)
        val profile = Demand(8280, 0.1, 1, profile = true)
        val running = Demand(8270, 1.0, 1, priority = 5, hosts = 1)

        assertEquals(NodeWork.Claim(8280), NodePool.pick(onDemand, listOf(running), listOf(urgent, profile)))
        assertEquals(NodeWork.Claim(8280), NodePool.pick(profileOnly, listOf(running), listOf(urgent, profile)))
        // No profile left: ordinary work, unless the node keeps to profiles, which then retires once idle long enough.
        assertEquals(NodeWork.Claim(1), NodePool.pick(onDemand, listOf(running), listOf(urgent)))
        assertEquals(null, NodePool.pick(profileOnly, listOf(running), listOf(urgent)))
        // A selector asking for on demand is work for it too.
        val asksOnDemand = Demand(3, 1.0, 1, nodeTags = NodeSelector(require = setOf("on-demand")))
        assertEquals(NodeWork.Claim(3), NodePool.pick(profileOnly.copy(tags = setOf("on-demand")), emptyList(), listOf(urgent, asksOnDemand)))
        // A spot node keeps priority order and never takes the profile.
        assertEquals(NodeWork.Claim(1), NodePool.pick(NodeShape(4, onDemand = false), listOf(running), listOf(urgent, profile)))
    }

    @Test
    fun `a busy node takes another job that fits its free slots, and empties for an exclusive one`() {
        val node = NodeShape(cores = 8, onDemand = true)
        val small = Demand(2, 1.0, 1)
        val wide = Demand(3, 1.0, 4)

        // Beside job 1, with one slot left: the job already on it is not taken twice, and a 4-core case needs two slots.
        assertEquals(NodeWork.Claim(2), NodePool.pick(node, listOf(Demand(1, 1.0, 1, hosts = 1)), listOf(wide, small), free = 1, on = setOf(1)))
        assertEquals(null, NodePool.pick(node, emptyList(), listOf(wide), free = 1, on = setOf(1)))
        assertEquals(NodeWork.Claim(3), NodePool.pick(node, emptyList(), listOf(wide), free = 2, on = setOf(1)))
        assertEquals(null, NodePool.pick(node, emptyList(), listOf(small), free = 0, on = setOf(1)))
        // A profile or measuring job keeps the node to itself: next in line on a busy node, the node waits for it.
        val profile = Demand(8280, 0.1, 1, profile = true)
        assertEquals(NodeWork.Wait(8280), NodePool.pick(node, emptyList(), listOf(profile, small), free = 3, on = setOf(8270)))
        assertEquals(NodeWork.Claim(8280), NodePool.pick(node, emptyList(), listOf(profile, small)))
        val measuring = Demand(4, 1.0, 1, priority = 3, measures = true)
        assertEquals(NodeWork.Wait(4), NodePool.pick(node, emptyList(), listOf(small, measuring), free = 3, on = setOf(1)))
        // An exclusive job below the next one in line does not hold the node.
        assertEquals(NodeWork.Claim(2), NodePool.pick(node, emptyList(), listOf(small.copy(priority = 5), measuring), free = 3, on = setOf(1)))
    }

    @Test
    fun `a node runs one case per two physical cores, a case of more cores taking more slots`() {
        assertEquals(listOf(1, 1, 2, 4, 8), listOf(1, 2, 4, 8, 16).map { NodePool.slots(it) })
        assertEquals(listOf(1, 1, 2, 2, 4, 4), listOf(1, 2, 3, 4, 8, 16).map { NodePool.caseSlots(it, capacity = 4) })
    }

    @Test
    fun `a node's slots go first come first served across its jobs, and a job just taken holds its reservation`() {
        val slots = NodeSlots(4)

        slots.reserve(1, 4)
        assertEquals(0, slots.free(now = 0))
        assertTrue(slots.acquire(1, 1, now = 0))
        // Its first case ends the reservation.
        assertEquals(3, slots.free(now = 0))
        assertTrue(slots.acquire(1, 1, now = 0))
        assertTrue(slots.acquire(2, 1, now = 0))
        assertEquals(listOf(3, 1), listOf(slots.busy, slots.free(now = 0)))
        // Job 3 needs two: it waits, and no other job takes the slot left before it.
        assertFalse(slots.acquire(3, 2, now = 0))
        assertEquals(0, slots.free(now = 0))
        assertFalse(slots.acquire(1, 1, now = 10))
        slots.release(1)
        assertFalse(slots.acquire(2, 1, now = 20))
        assertTrue(slots.acquire(3, 2, now = 20))
        assertEquals(4, slots.busy)
        // A job that stopped asking leaves the line.
        slots.release(2)
        assertFalse(slots.acquire(4, 3, now = 30))
        assertTrue(slots.acquire(1, 1, now = 30 + NodeSlots.STALE_MS + 1))
        // A job that leaves the node gives up its place and its reservation.
        slots.reserve(5, 2)
        slots.leave(5)
        assertEquals(1, slots.free(now = 30 + NodeSlots.STALE_MS + 1))
    }

    @Test
    fun `a node retires when idle long enough and near its maxHours, never for a waiting update`() {
        val idle = 5 * 60_000L
        val maxAge = NodePool.maxAgeMs(24)

        assertEquals(23 * 3_600_000L, maxAge)
        assertEquals(null, NodePool.retire(1_000_000, 0, idleSince = null, idle, maxAge))
        assertEquals(null, NodePool.retire(1_000_000, 0, idleSince = 1_000_000 - idle + 1, idle, maxAge))
        assertEquals(null, NodePool.retire(1_000_000, 0, idleSince = 999_000, idle, maxAge))
        assertEquals("was idle for 5 min", NodePool.retire(1_000_000, 0, idleSince = 1_000_000 - idle, idle, maxAge))
        assertEquals("is near its maxHours", NodePool.retire(maxAge, 0, idleSince = null, idle, maxAge))
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
}
