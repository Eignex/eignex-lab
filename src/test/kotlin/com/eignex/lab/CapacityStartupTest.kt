package com.eignex.lab

import com.eignex.lab.capacity.*
import com.eignex.lab.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class CapacityStartupTest {
    private val at = 4_000_000L
    private val old = CapacityInstance("i-old", "general", launchedAt = at - CapacityStartup.GRACE_MS)
    private val key = CapacityStartup.key(old)
    private fun observe(state: CapacityStartupState = CapacityStartupState(), instances: List<CapacityInstance> = listOf(old),
                        registered: Set<String> = emptySet(), ready: Set<String> = emptySet(), fresh: Set<String> = setOf("general")) =
        CapacityStartup.observe(state, instances, fresh, registered, ready, at)

    @Test fun `provider launch age selects an overdue boot immediately without a first seen reset`() {
        val state = observe()
        assertEquals(CapacityStartupRetirement(old.id, old.launchedAt!!), state.retiring.getValue(key))
        assertTrue(state.failed.isEmpty(), "observation is not a destructive authorization")
        for (instance in listOf(old.copy(launchedAt = null), old.copy(launchedAt = at + 1), old.copy(launchedAt = at - CapacityStartup.GRACE_MS + 1))) {
            assertTrue(observe(instances = listOf(instance)).retiring.isEmpty())
        }
    }

    @Test fun `registered previously ready draining and synthetic target slots never become replacements`() {
        assertTrue(observe(registered = setOf(key)).retiring.isEmpty())
        assertTrue(observe(CapacityStartupState(everReady = mapOf(key to old.launchedAt))).retiring.isEmpty())
        for (instance in listOf(old.copy(draining = true), old.copy(countedInTarget = false), old.copy(id = "booting-0"), CapacityInstance("booting-0", "general"))) {
            assertTrue(observe(instances = listOf(instance)).retiring.isEmpty())
        }
    }

    @Test fun `authorization retries and restart charge one exact launch identity and preserve retiring until gone`() {
        var state = CapacityStartup.authorize(observe(), "general", emptySet())
        repeat(5) { state = CapacityStartup.authorize(state, "general", emptySet()) }
        state = Json.decodeFromString<CapacityStartupState>(Json.encodeToString(state))
        assertEquals(setOf(CapacityStartup.identity(key, old.launchedAt!!)), state.failed["general"])
        assertTrue(state.retiring.getValue(key).authorized)
        assertEquals(state, observe(state))
        assertEquals(state.retiring, observe(state, listOf(old.copy(launchedAt = null))).retiring,
            "a missing EC2 record while ASG still lists the member must not forget retirement intent")
        assertEquals(state.retiring, observe(state, instances = emptyList(), fresh = emptySet()).retiring, "stale inventory cannot acknowledge removal")
        assertTrue(observe(state, instances = emptyList()).retiring.isEmpty())
    }

    @Test fun `visible first registration wins before authorization but an issued retirement is not forgotten`() {
        val pending = observe()
        val joined = CapacityStartup.authorize(pending, "general", setOf(old.id))
        assertTrue(joined.retiring.isEmpty()); assertTrue(joined.failed.isEmpty())
        val issued = CapacityStartup.authorize(pending, "general", emptySet())
        assertEquals(issued, CapacityStartup.authorize(issued, "general", setOf(old.id)))
        assertEquals(issued.failed, observe(issued, registered = setOf(key), ready = setOf(key)).failed,
            "late registration after authorization must not refund replacement history")
    }

    @Test fun `third unique failed boot opens a persistent circuit and renewal cannot spend a fourth`() {
        var state = CapacityStartupState()
        repeat(CapacityStartup.MAX_REPLACEMENTS) { index ->
            val instance = old.copy(id = "i-$index")
            state = observe(state, listOf(instance))
            state = CapacityStartup.authorize(state, "general", emptySet())
            state = observe(state, instances = emptyList())
        }
        val restored = Json.decodeFromString<CapacityStartupState>(Json.encodeToString(state))
        assertEquals(CapacityStartup.MAX_REPLACEMENTS, restored.failed.getValue("general").size)
        assertTrue(observe(restored, listOf(old.copy(id = "i-next"))).retiring.isEmpty())
    }

    @Test fun `old healthy neighbors do not reset the circuit but a newly ready boot does`() {
        val neighbor = old.copy(id = "i-neighbor")
        val nkey = CapacityStartup.key(neighbor)
        val failed = mapOf("general" to setOf("one", "two", "three"))
        val previous = CapacityStartupState(everReady = mapOf(nkey to neighbor.launchedAt), failed = failed)
        assertEquals(failed, observe(previous, listOf(old, neighbor), registered = setOf(nkey), ready = setOf(nkey)).failed)
        assertTrue(observe(previous, listOf(old, neighbor), registered = setOf(key, nkey), ready = setOf(key, nkey)).failed.isEmpty())
    }

    @Test fun `reused provider id with a different launch never inherits a destructive old grant`() {
        val previous = CapacityStartup.authorize(observe(), "general", emptySet())
        val changed = old.copy(launchedAt = old.launchedAt!! - 1)
        val next = observe(previous, listOf(changed))
        assertFalse(next.retiring.getValue(key).authorized)
        assertEquals(changed.launchedAt, next.retiring.getValue(key).launchedAt)
        val authorized = CapacityStartup.authorize(next, "general", emptySet())
        assertEquals(2, authorized.failed.getValue("general").size)
    }

    @Test fun `ready identity history is bounded by live inventory and stale pools preserve their history`() {
        val previous = CapacityStartupState(everReady = mapOf(key to old.launchedAt, "other:i-gone" to 1L))
        assertEquals(setOf("other:i-gone"), observe(previous, instances = emptyList()).everReady.keys)
        assertTrue(observe(previous, instances = emptyList(), fresh = setOf("general", "other")).everReady.isEmpty())
    }

    @Test fun `recovery recognizes registered identity outside the expected class or meta`() {
        for (node in listOf(
            buildJsonObject { putJsonObject("Meta") { put(CapacityWire.CAPACITY_ID_META, old.id) }; put("NodeClass", "wrong") },
            buildJsonObject { put("Name", "another-pool-${old.id}") },
            buildJsonObject { putJsonObject("Attributes") { put("unique.platform.aws.instance-id", old.id) } },
        )) assertTrue(CapacityStartup.registered(node, old.id))
        assertFalse(CapacityStartup.registered(buildJsonObject { put("Name", "another-i-host") }, old.id))
    }

    @Test fun `replacement covers pending demand without increasing desired or physical quota`() {
        val pool = CapacityPool("general", "general", 16, 65536, 1, setOf("arch:x86_64"), sustainedMs = 0)
        val node = CapacityNode(key, pool.id, CapacityNodeState.REPLACING)
        val demand = CapacityDemand("solve", 1, ResourceBudget(4000, 4096), 100, pool = pool.id)
        val plan = CapacityPolicy.evaluate(CapacitySettings(listOf(pool), 16), listOf(node), listOf(demand), at = at)
        assertEquals(1, plan.desired[pool.id]); assertFalse(plan.placements.single().ready)
        assertEquals(key, plan.placements.single().node); assertTrue(plan.waiting.isEmpty())
    }

    @Test fun `open circuit prevents new launches and manual growth without removing healthy work`() {
        val pool = CapacityPool("general", "general", 16, 65536, 4, setOf("arch:x86_64"), sustainedMs = 0)
        val settings = CapacitySettings(listOf(pool), 64)
        val ready = CapacityNode("ready", pool.id, CapacityNodeState.READY)
        val demand = CapacityDemand("solve", 1, ResourceBudget(16000, 4096), 100, pool = pool.id)
        val manual = listOf(ManualCapacityLease("manual", pool.id, 4, at + 1000))
        val plan = CapacityPolicy.evaluate(settings, listOf(ready), listOf(demand, demand.copy(id = "wait")), manual, at = at, growthBlocked = setOf(pool.id))
        assertEquals(1, plan.desired[pool.id]); assertEquals("ready", plan.placements.single().node)
        assertEquals("startup replacement circuit open", plan.waiting.single().reason)
        assertEquals(0, CapacityPolicy.evaluate(settings, emptyList(), listOf(demand), manual, at = at, growthBlocked = setOf(pool.id)).desired[pool.id])
    }
}
