package com.eignex.lab

import kotlin.test.*

class CapacityPolicyTest {
    private val pool = CapacityPool("general", "general", 16, 65536, 4, setOf("arch:x86_64", "simd:avx2"), sustainedMs = 0, cooldownMs = 0)
    private val settings = CapacitySettings(listOf(pool), 64, localPressureHours = 0.0)
    private fun demand(id: String, cpu: Int = 4000, memory: Int = 8192, job: Long = id.hashCode().toLong(), work: CapacityWork = CapacityWork.SOLVE) =
        CapacityDemand(id, job, ResourceBudget(cpu, memory, 1), 100.0, 10, 4, work = work)

    @Test fun `solves measurements profiles and a build fill one shared node before growing`() {
        val work = listOf(demand("solve"), demand("measure", work = CapacityWork.MEASUREMENT), demand("profile", work = CapacityWork.PROFILE), demand("build", 2000, work = CapacityWork.BUILD))
        val plan = CapacityPolicy.evaluate(settings, emptyList(), work, at = 1000)
        assertEquals(1, plan.desired["general"]); assertEquals(1, plan.placements.map { it.node }.distinct().size)
        assertTrue(plan.waiting.isEmpty())
    }
    @Test fun `free memory and CPU both govern packing and host reserves are not spent`() {
        val node = CapacityNode("node", "general", CapacityNodeState.READY, listOf(CapacityReservation("used", 1, ResourceBudget(10000, 60000))))
        val plan = CapacityPolicy.evaluate(settings, listOf(node), listOf(demand("fit", 4000, 4096), demand("wait", 1000, 8192)))
        assertEquals("node", plan.placements.single { it.request == "fit" }.node)
        assertNotEquals("node", plan.placements.single { it.request == "wait" }.node)
        assertEquals(2, plan.desired["general"])
    }
    @Test fun `booting workers cover pending allocations and do not launch twice`() {
        val node = CapacityNode("booting", "general", CapacityNodeState.BOOTING)
        val plan = CapacityPolicy.evaluate(settings, listOf(node), listOf(demand("a"), demand("b")))
        assertEquals(1, plan.desired["general"]); assertTrue(plan.placements.all { !it.ready && it.node == "booting" })
    }
    @Test fun `spot requests never silently enable on demand even when it is warm`() {
        val expensive = pool.copy(id = "explicit", nodeClass = "explicit", market = CapacityMarket.ON_DEMAND)
        val config = settings.copy(pools = listOf(expensive))
        val plan = CapacityPolicy.evaluate(config, listOf(CapacityNode("warm", "explicit", CapacityNodeState.READY)), listOf(demand("spot")))
        assertTrue(plan.placements.isEmpty()); assertTrue(plan.waiting.single().reason.contains("spot"))
    }
    @Test fun `global vCPU quota counts booting and draining pools and hardware requirements`() {
        val second = pool.copy(id = "arm", nodeClass = "arm", tags = setOf("arch:arm64", "simd:neon"))
        val config = settings.copy(pools = listOf(pool, second), maxVcpus = 16)
        val nodes = listOf(CapacityNode("departing", "general", CapacityNodeState.DRAINING))
        val asked = demand("arm").copy(selector = NodeSelector(require = setOf("arch:arm64")))
        val plan = CapacityPolicy.evaluate(config, nodes, listOf(asked))
        assertTrue(plan.placements.isEmpty()); assertEquals("capacity or vCPU limit", plan.waiting.single().reason)
    }
    @Test fun `priority floor and sustained pressure apply to growth while warm workers take eligible work`() {
        val p = pool.copy(minPriority = 10, sustainedMs = 30000, cooldownMs = 60000)
        val config = settings.copy(pools = listOf(p))
        val low = demand("low").copy(priority = 1)
        assertTrue(CapacityPolicy.evaluate(config, emptyList(), listOf(low), at = 1000).placements.isEmpty())
        assertEquals("warm", CapacityPolicy.evaluate(config, listOf(CapacityNode("warm", p.id, CapacityNodeState.READY)), listOf(low), at = 1000).placements.single().node)
        val high = demand("high"); val first = CapacityPolicy.evaluate(config, emptyList(), listOf(high), at = 1000)
        assertEquals(0, first.desired[p.id])
        val sustained = CapacityPolicy.evaluate(config, emptyList(), listOf(high), previous = first.history, at = 31000)
        assertEquals(1, sustained.desired[p.id])
        assertEquals(0, CapacityPolicy.evaluate(config, emptyList(), listOf(high), previous = sustained.history, at = 32000).desired[p.id])
    }
    @Test fun `scale in removes only old empty nodes and manual holds expire`() {
        val nodes = listOf(CapacityNode("empty", pool.id, CapacityNodeState.READY, emptySince = 0), CapacityNode("busy", pool.id, CapacityNodeState.READY, listOf(CapacityReservation("active", 1, ResourceBudget(1000, 1024)))))
        val manual = ManualCapacityLease("operator", pool.id, 2, 400000)
        assertEquals(2, CapacityPolicy.evaluate(settings, nodes, emptyList(), listOf(manual), at = 350000).desired[pool.id])
        assertEquals(1, CapacityPolicy.evaluate(settings, nodes, emptyList(), listOf(manual), at = 400000).desired[pool.id])
    }
    @Test fun `a terminating instance still consumes quota but cannot restore an old desired count`() {
        val node = CapacityNode("terminating", pool.id, CapacityNodeState.DRAINING, countedInTarget = false)
        val plan = CapacityPolicy.evaluate(settings.copy(maxVcpus = 16), listOf(node), listOf(demand("new")))
        assertEquals(0, plan.desired[pool.id]); assertTrue(plan.placements.isEmpty())
    }
    @Test fun `pending sessions remain pinned to their selected capability pool`() {
        val alternate = pool.copy(id = "alternate", nodeClass = "alternate")
        val config = settings.copy(pools = listOf(pool, alternate))
        val asked = demand("pending").copy(pool = pool.id)
        val plan = CapacityPolicy.evaluate(config, listOf(CapacityNode("wrong-pool", alternate.id, CapacityNodeState.READY)), listOf(asked))
        assertEquals(pool.id, plan.placements.single().pool)
    }
    @Test fun `per experiment per host cap applies across sessions without build exclusivity`() {
        val work = (1..3).map { demand("a$it", 2000, job = 1).copy(perHostParallel = 2) } + demand("build", 2000, job = 1, work = CapacityWork.BUILD)
        val plan = CapacityPolicy.evaluate(settings, listOf(CapacityNode("warm", pool.id, CapacityNodeState.READY)), work)
        assertEquals(2, plan.placements.filter { it.request.startsWith("a") && it.node == "warm" }.size)
        assertEquals("warm", plan.placements.single { it.request == "build" }.node)
    }
}
