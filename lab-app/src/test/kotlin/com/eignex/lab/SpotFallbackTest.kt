package com.eignex.lab

import com.eignex.lab.capacity.*
import com.eignex.lab.domain.*
import com.eignex.lab.nomad.*
import kotlinx.serialization.json.*
import kotlin.test.*

class SpotFallbackTest {
    private val spot = CapacityPool("general-spot", "linux-spot", 4, 8192, 4, setOf("arch:x86_64", "simd:avx2"), onDemandFallback = "general-on-demand")
    private val demand = spot.copy(id = "general-on-demand", nodeClass = "linux-on-demand", market = CapacityMarket.ON_DEMAND, onDemandFallback = null)
    private val capacity = CapacitySettings(listOf(spot, demand), 16)
    private val template = NomadBatchJob("worker", NomadPlatform.LINUX, spot.nodeClass, NomadCpuCapacity(8000, 4),
        ResourceBudget(2000, 2048), listOf("worker"), "registry/worker@sha256:" + "d".repeat(64), statusHostVolume = "lab-status",
        environment = mapOf("LAB_WORKER_EXPERIMENT" to "7"))
    private val target = NomadQueueTarget(template, spot.tags)
    private val fallback = target.copy(template = template.copy(nodeClass = demand.nodeClass), market = CapacityMarket.ON_DEMAND)
    private val queue = NomadQueueSettings("release", "https://lab.example", listOf(target, fallback))
    private val cluster = ClusterSettings(queue, capacity)
    private var spec = ExperimentSpec("test", host = HostPlacement.CLOUD)
    private var sample: CapacitySnapshot? = CapacitySnapshot(1000, 0,
        CapacityDecision(emptyMap(), emptyList(), emptyList(), emptyMap()), emptyList(), emptyMap(), capacity.pools,
        spotFallbacks = mapOf(spot.id to demand.id))
    private val policy = NomadSpotFallback(cluster, { sample }, { spec })

    @Test fun `only recent explicit Spot shortages enable configured fallback`() {
        fun active(code: String = "UnfulfillableCapacity", time: Long = 1000) = SpotFallback.active(capacity,
            listOf(CapacityInventory(2000, capacity.pools.map { it.id }.toSet(), emptyList(), capacityErrors = mapOf(spot.id to CapacityError(code, time)))), 2000)
        assertEquals(mapOf(spot.id to demand.id), active())
        for (code in listOf("VcpuLimitExceeded", "AccessDenied", "InsufficientInstanceCapacity")) assertTrue(active(code).isEmpty())
        assertTrue(active(time = 2001).isEmpty())
        assertTrue(active(time = -SpotFallback.RETRY_SPOT_MS).isEmpty())
        assertTrue(SpotFallback.active(capacity, emptyList(), 2000).isEmpty())
    }

    @Test fun `fallback changes infrastructure constraints without changing resources commands or environment`() {
        val job = template.render()
        val next = policy.apply(job, template.budget)
        assertEquals(demand.nodeClass, SessionPlacement.of(next).nodeClass)
        assertEquals(job["TaskGroups"], next["TaskGroups"])
        assertEquals(SessionPlacement.of(job).cpuCapacity, SessionPlacement.of(next).cpuCapacity)
        assertEquals("UnfulfillableCapacity", next.getValue("Meta").jsonObject.getValue("lab_spot_fallback_reason").jsonPrimitive.content)
        assertEquals(next, policy.apply(next, template.budget), "on-demand sessions never churn back to Spot")
    }

    @Test fun `Spot-only selectors and stale missing or unhealthy samples never trigger fallback`() {
        val job = template.render()
        for (selector in listOf(NodeSelector(require = setOf("spot")), NodeSelector(exclude = setOf("on-demand")))) {
            spec = spec.copy(nodeTags = selector)
            assertEquals(job, policy.apply(job, template.budget))
        }
        spec = spec.copy(nodeTags = null)
        val original = sample!!
        for (unusable in listOf(null, original.copy(ageMs = CapacityCollector.FRESH_MS + 1), original.copy(healthy = false), original.copy(spotFallbacks = emptyMap()))) {
            sample = unusable
            assertEquals(job, policy.apply(job, template.budget))
        }
    }

    @Test fun `ready Spot capacity is reused but a full node permits on-demand growth`() {
        val ready = CapacityNode("spot-ready", spot.id, CapacityNodeState.READY)
        sample = sample!!.copy(nodes = listOf(ready))
        assertEquals(template.render(), policy.apply(template.render(), template.budget))
        sample = sample!!.copy(nodes = listOf(ready.copy(reservations = listOf(CapacityReservation("busy", 1, ResourceBudget(4000, 7000))))))
        assertEquals(demand.nodeClass, SessionPlacement.of(policy.apply(template.render(), template.budget)).nodeClass)
    }

    @Test fun `fallback configuration refuses hardware market and worker-runtime mismatches`() {
        for (wrong in listOf(demand.copy(tags = setOf("arch:arm64")), demand.copy(vcpus = 8), demand.copy(market = CapacityMarket.SPOT), demand.copy(reserveMemoryMiB = 2048))) {
            assertFailsWith<IllegalArgumentException> { CapacitySettings(listOf(spot, wrong), 16) }
        }
        assertFailsWith<IllegalArgumentException> { ClusterSettings(queue.copy(targets = listOf(target,
            fallback.copy(template = fallback.template.copy(environment = mapOf("DIFFERENT" to "runtime"))))), capacity) }
        assertFailsWith<IllegalArgumentException> { CapacitySettings(listOf(spot), 16) }
    }

    @Test fun `fallback demand stays under the shared vCPU budget including real failed Spot instances`() {
        val requested = CapacityDemand("pending", 7, template.budget, 100, pool = demand.id, market = CapacityMarket.ON_DEMAND, localEligible = false)
        val ready = CapacityNode("existing", spot.id, CapacityNodeState.UNAVAILABLE)
        val full = CapacitySettings(capacity.pools, 4)
        val decision = CapacityPolicy.evaluate(full, listOf(ready), listOf(requested), at = 1000000)
        assertEquals(0, decision.desired[demand.id]); assertTrue(decision.placements.isEmpty())
        assertEquals("capacity or vCPU limit", decision.waiting.single().reason)
    }
}
