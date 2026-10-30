package com.eignex.lab

import com.eignex.lab.util.*
import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.execution.*
import com.eignex.lab.worker.*
import com.eignex.lab.nomad.*
import com.eignex.lab.web.*
import com.eignex.lab.cli.*

import kotlin.test.*

class CapacityPolicyTest {
    @Test fun `compiler slots place waiting preparations on an idle node while solves still share CPU`() {
        val busy = CapacityNode("busy", pool.id, CapacityNodeState.READY,
            List(2) { CapacityReservation("build-$it", it.toLong(), ResourceBudget(2000, 4096), CapacityWork.BUILD) })
        val idle = CapacityNode("idle", pool.id, CapacityNodeState.READY)
        val build = demand("next-build", 2000, 4096, work = CapacityWork.BUILD).copy(maxConcurrentBuildsPerNode = 2)
        val solve = demand("solve", 1000, 4096)
        val plan = CapacityPolicy.evaluate(settings, listOf(busy, idle), listOf(build, solve))
        assertEquals("idle", plan.placements.single { it.request == build.id }.node)
        assertEquals("busy", plan.placements.single { it.request == solve.id }.node)
        assertEquals(2, plan.desired[pool.id])
    }

    @Test fun `compiler admission never raises the pool or physical CPU quota`() {
        val busy = CapacityNode("busy", pool.id, CapacityNodeState.READY,
            List(2) { CapacityReservation("build-$it", it.toLong(), ResourceBudget(2000, 4096), CapacityWork.BUILD) })
        val build = demand("next-build", 2000, 4096, work = CapacityWork.BUILD).copy(maxConcurrentBuildsPerNode = 2)
        val plan = CapacityPolicy.evaluate(settings.copy(maxVcpus = pool.vcpus), listOf(busy), listOf(build))
        assertTrue(plan.placements.isEmpty())
        assertEquals("capacity or vCPU limit", plan.waiting.single().reason)
        assertEquals(1, plan.desired[pool.id])
    }

    @Test fun `largest fitting cold pool wins but warm smaller capacity is reused`() {
        val large = pool.copy(id = "z-large")
        val small = pool.copy(id = "a-small", nodeClass = "small", vcpus = 2, memoryMiB = 8192)
        val config = settings.copy(pools = listOf(small, large))
        val ask = demand("small", 1000, 4608)
        assertEquals(large.id, CapacityPolicy.evaluate(config, emptyList(), listOf(ask)).placements.single().pool)
        val warm = CapacityNode("warm-small", small.id, CapacityNodeState.READY)
        assertEquals(warm.id, CapacityPolicy.evaluate(config, listOf(warm), listOf(ask)).placements.single().node)
        assertEquals(large.id, CapacityPolicy.evaluate(config, emptyList(), listOf(demand("cpu", 4000, 4608))).placements.single().pool)
        assertEquals(large.id, CapacityPolicy.evaluate(config, emptyList(), listOf(demand("memory", 1000, 8192))).placements.single().pool)
    }

    @Test fun `six memory heavy workers share one large node instead of six small nodes`() {
        val small = pool.copy(id = "small", nodeClass = "small", vcpus = 2, memoryMiB = 8192, reserveMemoryMiB = 3072)
        val config = settings.copy(pools = listOf(small, pool))
        val asks = (1..6).map { demand("solve-$it", 1000, 4608, job = 1).copy(perHostParallel = null) }
        val plan = CapacityPolicy.evaluate(config, emptyList(), asks)
        assertEquals(mapOf(small.id to 0, pool.id to 1), plan.desired)
        assertEquals(6, plan.placements.size)
        assertEquals(1, plan.placements.map { it.node }.distinct().size)
        assertTrue(plan.waiting.isEmpty())
    }

    @Test fun `smaller cold pools remain usable when large nodes exceed quota or pool limits`() {
        val small = pool.copy(id = "small", nodeClass = "small", vcpus = 2, memoryMiB = 8192)
        val config = settings.copy(pools = listOf(small, pool))
        val ask = demand("small", 1000, 4608)
        val limited = CapacityPolicy.evaluate(config.copy(maxVcpus = 2), emptyList(), listOf(ask))
        assertEquals(small.id, limited.placements.single().pool)
        val full = CapacityNode("full", pool.id, CapacityNodeState.READY,
            listOf(CapacityReservation("busy", 2, ResourceBudget(pool.cpuMillis, pool.availableMemoryMiB))))
        val capped = CapacityPolicy.evaluate(config.copy(pools = listOf(small, pool.copy(maxNodes = 1))), listOf(full), listOf(ask))
        assertEquals(small.id, capped.placements.single().pool)
    }

    @Test fun `small sustained backlog grows beyond two nodes without an estimated core hour threshold`() {
        val p = pool.copy(sustainedMs = 30000)
        val configuration = settings.copy(pools = listOf(p))
        val nodes = (1..2).map { CapacityNode("full-$it", p.id, CapacityNodeState.READY, listOf(CapacityReservation("used-$it", it.toLong(), ResourceBudget(16000, 16384)))) }
        val asked = demand("small", 1000)
        val first = CapacityPolicy.evaluate(configuration, nodes, listOf(asked), at = 1000)
        assertEquals(2, first.desired[p.id])
        val next = CapacityPolicy.evaluate(configuration, nodes, listOf(asked), previous = first.history, at = 31000)
        assertEquals(3, next.desired[p.id]); assertTrue(next.waiting.isEmpty())
        val booting = nodes + CapacityNode("booting", p.id, CapacityNodeState.BOOTING)
        val covered = CapacityPolicy.evaluate(configuration, booting, listOf(asked), previous = next.history, at = 32000)
        assertEquals(3, covered.desired[p.id]); assertNull(covered.history[p.id]?.pressureSince)
    }

    @Test fun `retired settings an older file still has are ignored on read`() {
        val text = """{"maxVcpus": 64, "localCores": 8, "localPressureHours": 0.25, "pools": [""" +
            """{"id": "general", "nodeClass": "general", "vcpus": 16, "memoryMiB": 65536, "maxNodes": 4,""" +
            """ "tags": ["arch:x86_64", "simd:avx2"], "reserveCpuMillis": 2000}]}"""
        assertEquals(CapacitySettings(listOf(pool.copy(sustainedMs = 30_000, cooldownMs = 60_000)), 64), CapacitySettings.parse(text))
    }

    @Test fun `logical CPU packing matches Nomad hard quotas without subtracting a second host reserve`() {
        val p = pool
        val asks = (1..8).map { demand("build-$it", 2000, 4096, work = CapacityWork.BUILD) }
        val plan = CapacityPolicy.evaluate(settings.copy(pools = listOf(p)), emptyList(), asks)
        assertEquals(16000, p.cpuMillis)
        assertEquals(36400, asks.sumOf { it.budget.nomadCpuMHz(NomadCpuCapacity(36400, 16)) })
        assertEquals(1, plan.desired[p.id]); assertTrue(plan.waiting.isEmpty())
    }

    private val pool = CapacityPool("general", "general", 16, 65536, 4, setOf("arch:x86_64", "simd:avx2"), sustainedMs = 0, cooldownMs = 0)
    private val settings = CapacitySettings(listOf(pool), 64)
    private fun demand(id: String, cpu: Int = 4000, memory: Int = 8192, job: Long = id.hashCode().toLong(), work: CapacityWork = CapacityWork.SOLVE) =
        CapacityDemand(id, job, ResourceBudget(cpu, memory, 1), 10, 4, work = work)

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
    @Test fun `unavailable nodes cannot satisfy demand and replacements still respect physical vCPU quota`() {
        val node = CapacityNode("unavailable", pool.id, CapacityNodeState.UNAVAILABLE)
        val plan = CapacityPolicy.evaluate(settings, listOf(node), listOf(demand("replacement")))
        assertEquals(2, plan.desired[pool.id])
        assertTrue(plan.placements.none { it.node == node.id })
        val limited = CapacityPolicy.evaluate(settings.copy(maxVcpus = pool.vcpus), listOf(node), listOf(demand("replacement")))
        assertEquals(1, limited.desired[pool.id])
        assertTrue(limited.placements.isEmpty())
        assertEquals("capacity or vCPU limit", limited.waiting.single().reason)
    }
    @Test fun `cancelled demand withdraws unused launches while pending work and manual requests keep them`() {
        val nodes = listOf(CapacityNode("booting", pool.id, CapacityNodeState.BOOTING))
        assertEquals(0, CapacityPolicy.evaluate(settings, nodes, emptyList()).desired[pool.id])
        assertEquals(1, CapacityPolicy.evaluate(settings, nodes, listOf(demand("pending"))).desired[pool.id])
        val lease = ManualCapacityLease("manual", pool.id, 1, 2000)
        assertEquals(1, CapacityPolicy.evaluate(settings, nodes, emptyList(), listOf(lease), at = 1000).desired[pool.id])
        assertEquals(0, CapacityPolicy.evaluate(settings, nodes, emptyList(), listOf(lease), at = 2000).desired[pool.id])
        val allocated = nodes.single().copy(reservations = listOf(CapacityReservation("allocation", 1, ResourceBudget(1000, 1024))))
        assertEquals(1, CapacityPolicy.evaluate(settings, listOf(allocated), emptyList()).desired[pool.id])
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
    @Test fun `same experiment fills CPU and memory on warm nodes without a default per host cap`() {
        val asks = (1..16).map { demand("work-$it", 1000, 2048, job = 1).copy(perHostParallel = null) }
        val plan = CapacityPolicy.evaluate(settings, listOf(CapacityNode("warm", pool.id, CapacityNodeState.READY)), asks)
        assertEquals(1, plan.desired[pool.id])
        assertEquals(16, plan.placements.count { it.node == "warm" })
        assertTrue(plan.waiting.isEmpty())
    }

    @Test fun `per experiment per host cap applies across sessions without build exclusivity`() {
        val work = (1..3).map { demand("a$it", 2000, job = 1).copy(perHostParallel = 2) } + demand("build", 2000, job = 1, work = CapacityWork.BUILD)
        val plan = CapacityPolicy.evaluate(settings, listOf(CapacityNode("warm", pool.id, CapacityNodeState.READY)), work)
        assertEquals(2, plan.placements.filter { it.request.startsWith("a") && it.node == "warm" }.size)
        assertEquals("warm", plan.placements.single { it.request == "build" }.node)
    }
}
