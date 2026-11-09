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

import io.ktor.client.statement.bodyAsText

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*

class CapacityCollectorTest {
    private val root = Files.createTempDirectory("capacity-collector")
    private val store = Store(root.resolve("lab.db"))
    private val token = "capacity-secret-".repeat(4)
    private val tokenFile = root.resolve("token").also { Files.writeString(it, token) }
    private val inventory = root.resolve("inventory.json")
    private val state = root.resolve("state.json")
    private val pool = CapacityPool("general", "general", 16, 65536, 4, setOf("arch:x86_64", "simd:avx2"), sustainedMs = 0)
    private val settings = CapacitySettings(listOf(pool), 64)
    private var at = 1000L
    private var nanos = 0L
    private var writable = true
    private val control = object : NomadControl {
        var full: JsonObject? = null
        var allocations = emptyList<JsonObject>()
        var nodeReads = 0
        var nodesFailure = false
        override fun job(id: String): JsonObject? = null
        override fun submit(job: JsonObject) { error("collector cannot submit") }
        override fun stop(id: String) { error("collector cannot stop") }
        override fun allocations(job: String) = emptyList<NomadAllocation>()
        override fun allocation(id: String): NomadAllocation? = null
        override fun jwks() = buildJsonObject { }
        override fun nodes(): List<JsonObject> {
            check(!nodesFailure) { "Nomad inventory unavailable" }
            return full?.let { listOf(JsonObject(it - "Meta")) } ?: emptyList()
        }
        override fun node(id: String): JsonObject? { nodeReads++; return full }
        override fun nodeAllocations(id: String) = allocations
    }
    private fun sample(instances: List<CapacityInstance> = emptyList()) {
        Files.writeString(inventory, Json.encodeToString(CapacityInventory(at, setOf(pool.id), instances)))
    }
    /**
     * What the cloud renewal functions' verifier (lab-capacity's CapacityGrants) makes of [grant] as the coordinator
     * serves it, at [nowMs]: the pool and desired size, or why not.
     */
    private fun providerVerify(grant: CapacityGrant, pool: String, maxNodes: Int, nowMs: Long): String = try {
        val served = Json.parseToJsonElement(Json.encodeToString(grant))
        val value = com.eignex.lab.cloud.CapacityGrants.verify(served, token, pool, maxNodes, nowMs)
        "${value.getValue("pool").jsonPrimitive.content} ${value.getValue("desired").jsonPrimitive.int}"
    } catch (error: com.eignex.lab.cloud.CapacityRejected) {
        error.message!!
    }
    private fun collector() = CapacityCollector(settings, listOf(inventory), tokenFile, state, store, control, { writable }, setOf("local"), { at }, { nanos })

    @Test fun `overdue unregistered boot is replaced without increasing target and intent survives restart`() {
        at = 4_000_000
        val instance = CapacityInstance("i-overdue", pool.id, launchedAt = at - CapacityStartup.GRACE_MS)
        store.capacityLeases.request(pool.id, 1, 60_000, at)
        sample(listOf(instance))
        val c = collector(); c.collect()
        assertEquals(1, c.snapshot().decision.desired[pool.id])
        assertEquals(CapacityNodeState.REPLACING, c.snapshot().nodes.single().state)
        fun payload(grant: CapacityGrant) = Json.parseToJsonElement(java.util.Base64.getUrlDecoder().decode(grant.payload).toString(Charsets.UTF_8)).jsonObject
        val first = payload(c.grant(pool.id))
        assertEquals(CapacityWire.STARTUP_GRANT_SCHEMA_VERSION, first.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(instance.id, first.getValue("startupRetirements").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        val restarted = collector(); restarted.collect()
        assertEquals(first.getValue("startupRetirements"), payload(restarted.grant(pool.id)).getValue("startupRetirements"))
        val durable = Json.decodeFromString<CapacityStartupState>(Files.readString(state.resolveSibling("capacity-startup-state.json")))
        assertEquals(1, durable.failed.getValue(pool.id).size, "polling and restart must not spend another replacement")
    }

    @Test fun `fresh registration before signing prevents retirement even outside the expected node class`() {
        at = 4_000_000
        val instance = CapacityInstance("i-overdue", pool.id, launchedAt = at - CapacityStartup.GRACE_MS)
        sample(listOf(instance)); val c = collector(); c.collect()
        control.full = buildJsonObject {
            put("ID", "late-node"); put("NodeClass", "wrong-class"); put("Name", "wrong-pool-${instance.id}"); put("Status", "ready")
        }
        val grant = c.grant(pool.id)
        val payload = Json.parseToJsonElement(java.util.Base64.getUrlDecoder().decode(grant.payload).toString(Charsets.UTF_8)).jsonObject
        assertFalse("startupRetirements" in payload)
        assertEquals(CapacityWire.GRANT_SCHEMA_VERSION, payload.getValue("schemaVersion").jsonPrimitive.int)
        val durable = Json.decodeFromString<CapacityStartupState>(Files.readString(state.resolveSibling("capacity-startup-state.json")))
        assertTrue(durable.retiring.isEmpty()); assertTrue(durable.failed.isEmpty())
    }

    @Test fun `ready first join remains usable after a lost retirement grant without refunding its budget`() {
        at = 4_000_000
        val instance = CapacityInstance("i-overdue", pool.id, launchedAt = at - CapacityStartup.GRACE_MS)
        store.capacityLeases.request(pool.id, 1, 60_000, at)
        sample(listOf(instance))
        val c = collector(); c.collect(); c.grant(pool.id) // Its response never reaches the provider.
        control.full = buildJsonObject {
            put("ID", "late-node"); put("NodeClass", pool.nodeClass); put("Status", "ready"); put("ModifyIndex", 1)
            putJsonObject("Meta") { put(CapacityWire.CAPACITY_ID_META, instance.id) }
        }
        repeat(2) { c.collect() }
        val restarted = collector(); restarted.collect()
        val node = restarted.snapshot().nodes.single()
        assertEquals(CapacityNodeState.READY, node.state)
        val demand = CapacityDemand("late-demand", 1, ResourceBudget(1000, 1024), 100, pool = pool.id)
        val plan = CapacityPolicy.evaluate(settings, listOf(node), listOf(demand), at = at)
        assertTrue(plan.placements.single().ready, "the joined worker must serve pending work")
        val grant = restarted.grant(pool.id)
        val payload = Json.parseToJsonElement(java.util.Base64.getUrlDecoder().decode(grant.payload).toString(Charsets.UTF_8)).jsonObject
        assertFalse("startupRetirements" in payload)
        val durable = Json.decodeFromString<CapacityStartupState>(Files.readString(state.resolveSibling("capacity-startup-state.json")))
        assertTrue(durable.retiring.values.single().authorized, "an already issued grant can still be in flight")
        assertEquals(1, durable.failed.getValue(pool.id).size, "joining after authorization does not refund circuit history")
    }

    @Test fun `failed fresh Nomad revalidation cannot issue a startup retirement`() {
        at = 4_000_000
        sample(listOf(CapacityInstance("i-overdue", pool.id, launchedAt = at - CapacityStartup.GRACE_MS)))
        val c = collector(); c.collect()
        control.nodesFailure = true
        assertEquals("Nomad inventory unavailable", assertFailsWith<IllegalStateException> { c.grant(pool.id) }.message)
        val durable = Json.decodeFromString<CapacityStartupState>(Files.readString(state.resolveSibling("capacity-startup-state.json")))
        assertTrue(durable.failed.isEmpty()); assertFalse(durable.retiring.values.single().authorized)
    }

    @Test fun `lost grant history does not block a distinct failed neighbor or reissue after a ready node disappears`() {
        at = 4_000_000
        val original = CapacityInstance("i-original", pool.id, launchedAt = at - CapacityStartup.GRACE_MS)
        val neighbor = original.copy(id = "i-neighbor")
        store.capacityLeases.request(pool.id, 2, 60_000, at)
        sample(listOf(original)); val c = collector(); c.collect(); c.grant(pool.id)
        control.full = buildJsonObject {
            put("ID", "joined-node"); put("NodeClass", pool.nodeClass); put("Status", "ready"); put("ModifyIndex", 1)
            putJsonObject("Meta") { put(CapacityWire.CAPACITY_ID_META, original.id) }
        }
        sample(listOf(original, neighbor)); c.collect()
        fun retirementIds(grant: CapacityGrant): List<String> {
            val payload = Json.parseToJsonElement(java.util.Base64.getUrlDecoder().decode(grant.payload).toString(Charsets.UTF_8)).jsonObject
            return payload["startupRetirements"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("id").jsonPrimitive.content }
        }
        assertEquals(listOf(neighbor.id), retirementIds(c.grant(pool.id)))
        control.full = null
        c.collect()
        val restarted = collector(); restarted.collect()
        assertEquals(listOf(neighbor.id), retirementIds(restarted.grant(pool.id)), "the original ready identity must never be reissued as a startup retirement")
        val nodes = restarted.snapshot().nodes.associateBy { it.id }
        assertEquals(CapacityNodeState.UNAVAILABLE, nodes.getValue(CapacityStartup.key(original)).state)
        assertEquals(CapacityNodeState.REPLACING, nodes.getValue(CapacityStartup.key(neighbor)).state)
        val durable = Json.decodeFromString<CapacityStartupState>(Files.readString(state.resolveSibling("capacity-startup-state.json")))
        assertEquals(setOf(CapacityStartup.key(original), CapacityStartup.key(neighbor)), durable.retiring.keys)
        assertTrue(durable.retiring.values.all { it.authorized })
        assertEquals(2, durable.failed.getValue(pool.id).size, "old history remains charged without blocking the next exact identity")
    }

    @Test fun `expired capacity sample cannot authorize startup retirement`() {
        at = 4_000_000
        sample(listOf(CapacityInstance("i-overdue", pool.id, launchedAt = at - CapacityStartup.GRACE_MS)))
        val c = collector(); c.collect()
        nanos += 31_000_000_000
        assertFailsWith<IllegalStateException> { c.grant(pool.id) }
    }

    @Test fun `node allocations mark a job active only when pending or running and desired to run`() {
        fun view(job: String, client: String?, desired: String?) = CapacityObservation.AllocationView("a-$job", job, client, desired)
        val active = CapacityObservation.activeJobs(listOf(view("running", "running", "run"), view("pending", "pending", "run"),
            view("stopping", "running", "stop"), view("complete", "complete", "run"), view("unknown", "unknown", "run"), view("legacy", "running", null)))
        assertEquals(setOf("running", "pending"), active)
    }

    @Test fun `unchanged historical nodes do not require full reads on every refresh`() {
        sample(listOf(CapacityInstance("vm", pool.id)))
        control.full = buildJsonObject {
            put("ID", "node"); put("NodeClass", pool.nodeClass); put("ModifyIndex", 1); put("Status", "ready")
            putJsonObject("Meta") { put("lab_capacity_id", "vm") }
        }
        val c = collector()
        repeat(3) { c.collect() }
        assertEquals(1, control.nodeReads)
        control.full = JsonObject(control.full!! + mapOf("ModifyIndex" to JsonPrimitive(2), "Status" to JsonPrimitive("down")))
        c.collect()
        assertEquals(2, control.nodeReads)
        assertEquals(CapacityNodeState.UNAVAILABLE, c.snapshot().nodes.single().state)
        sample(); c.collect()
        assertTrue(c.snapshot().nodes.isEmpty())
        assertEquals(2, control.nodeReads)
        sample(listOf(CapacityInstance("vm", pool.id))); c.collect()
        assertEquals(3, control.nodeReads, "removed nodes must not leave a stale identity cache")
    }

    @Test fun `provider freshness volume health and monotonic sample expiry gate all growth metrics`() {
        sample(); val c = collector(); c.collect()
        assertTrue(c.metrics().contains("lab_capacity_desired_nodes{pool=\"general\"} 0"))
        assertTrue(c.authorized(token)); assertFalse(c.authorized("worker-secret"))
        at += 3_600_000 // Wall-clock changes never rejuvenate a cached sample.
        nanos += 31_000_000_000
        assertFailsWith<IllegalStateException> { c.snapshot() }
        assertFailsWith<IllegalArgumentException> { c.collect() }
        sample(); c.collect(); writable = false
        assertFailsWith<IllegalStateException> { c.snapshot() }
        assertFailsWith<IllegalStateException> { c.collect() }
    }
    @Test fun `cloud grants sign the exact portable payload and cannot outlive a fresh sample`() {
        sample(); val c = collector(); c.collect()
        val grant = c.grant(pool.id)
        val raw = java.util.Base64.getUrlDecoder().decode(grant.payload)
        val payload = Json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
        assertEquals(at, payload.getValue("issuedAt").jsonPrimitive.long)
        assertEquals(pool.maxNodes, payload.getValue("maxNodes").jsonPrimitive.int)
        assertEquals(0, payload.getValue("desired").jsonPrimitive.int)
        // The provider side's own verifier accepts the grant as the coordinator issued it, and only it.
        assertEquals("${pool.id} 0", providerVerify(grant, pool.id, pool.maxNodes, at))
        assertEquals("invalid capacity signature", providerVerify(grant.copy(signature = "0".repeat(64)), pool.id, pool.maxNodes, at))
        assertEquals("capacity grant expired", providerVerify(grant, pool.id, pool.maxNodes, at + CapacityWire.FRESH_MS + 1))
        assertFailsWith<IllegalArgumentException> { c.grant("unknown") }
        nanos += 31_000_000_000
        assertFailsWith<IllegalStateException> { c.grant(pool.id) }
    }
    @Test fun `booting capacity is preserved and unknown allocations consume a conservative node budget`() {
        store.capacityLeases.request(pool.id, 1, 60000, at)
        sample(listOf(CapacityInstance("vm", pool.id)))
        val c = collector(); c.collect()
        assertEquals(CapacityNodeState.BOOTING, c.snapshot().nodes.single().state)
        assertEquals(1, c.snapshot().decision.desired[pool.id])
        control.full = buildJsonObject { put("ID", "node"); put("NodeClass", pool.nodeClass); put("Status", "ready"); put("SchedulingEligibility", "eligible"); putJsonObject("Meta") { put("lab_capacity_id", "vm") } }
        control.allocations = listOf(buildJsonObject { put("ID", "other"); put("JobID", "other-job"); put("ClientStatus", "running") })
        c.collect()
        assertEquals(pool.cpuMillis, c.snapshot().nodes.single().reservations.single().budget.cpuMillis)
        assertEquals(pool.availableMemoryMiB, c.snapshot().nodes.single().reservations.single().budget.memoryMiB)
    }
    @Test fun `manual capacity survives a coordinator restart and expires without changing pool purchasing`() {
        sample(); val lease = store.capacityLeases.request(pool.id, 2, 60000, at)
        collector().collect()
        assertEquals(2, collector().also { it.collect() }.snapshot().decision.desired[pool.id])
        assertEquals(lease, Store(root.resolve("lab.db")).capacityLeases.active(at).single())
        at += 60000; sample(); val c = collector(); c.collect()
        assertEquals(0, c.snapshot().decision.desired[pool.id]); assertTrue(store.capacityLeases.active(at).isEmpty())
    }
    @Test fun `registered down draining and ineligible clients are never reported as booting capacity`() {
        sample(listOf(CapacityInstance("vm", pool.id)))
        fun node(status: String, eligible: Boolean = true, drain: Boolean = false) = buildJsonObject {
            put("ID", "node"); put("NodeClass", pool.nodeClass); put("Status", status)
            put("SchedulingEligibility", if (eligible) "eligible" else "ineligible"); put("Drain", drain)
            putJsonObject("Meta") { put("lab_capacity_id", "vm") }
        }
        val c = collector()
        for ((node, expected) in listOf(
            node("down") to CapacityNodeState.UNAVAILABLE,
            node("ready", eligible = false) to CapacityNodeState.UNAVAILABLE,
            node("ready", drain = true) to CapacityNodeState.DRAINING,
            node("ready") to CapacityNodeState.READY,
        )) {
            control.full = node
            c.collect()
            assertEquals(expected, c.snapshot().nodes.single().state)
            assertEquals(1, c.snapshot().decision.desired[pool.id], "provider-owned instances remain counted against quota")
        }
    }
    @Test fun `worker listener exposes only authenticated read only capacity diagnostics`() = testApplication {
        sample(); val c = collector(); c.collect()
        val artifacts = LocalArtifactStore(root.resolve("artifacts"))
        val backend = NomadBackend(store, control, artifacts, { root.resolve("jobs/$it") })
        application { workerApi(store, backend, artifacts) { true }; capacityApi(c) { ResultJournal.Status(1, 16384, 134217728, null, 5000) } }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/capacity/journal") { bearerAuth("worker-secret") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/capacity/journal") { bearerAuth(token) }.status)
        assertContains(client.get("/capacity/metrics") { bearerAuth(token) }.bodyAsText(), "lab_result_journal_pending_batches 1")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/capacity/snapshot").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/capacity/snapshot") { bearerAuth("worker-secret") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/capacity/snapshot") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/capacity/api/v1/query") { bearerAuth(token); parameter("query", "lab_capacity_desired_nodes{pool=\"general\"}") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/capacity/api/v1/query") { bearerAuth(token); parameter("query", "arbitrary_promql") }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/capacity/leases") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/capacity/grant/general").status)
        assertEquals(HttpStatusCode.OK, client.get("/capacity/grant/general") { bearerAuth(token) }.status)
        nanos += 31_000_000_000
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/capacity/grant/general") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/capacity/metrics") { bearerAuth(token) }.status)
    }

    @Test fun `a stale provider holds its pools while a fresh one is still decided`() {
        val other = CapacityPool("arm", "arm", 16, 65536, 4, setOf("arch:arm64", "simd:neon"), sustainedMs = 0)
        val armInventory = root.resolve("arm-inventory.json")
        fun armSample(instances: List<CapacityInstance>) =
            Files.writeString(armInventory, Json.encodeToString(CapacityInventory(at, setOf(other.id), instances)))
        val c = CapacityCollector(CapacitySettings(listOf(pool, other), 64), listOf(inventory, armInventory), tokenFile, state, store, control,
            { writable }, setOf("local"), { at }, { nanos })
        sample(); armSample(listOf(CapacityInstance("a1", other.id), CapacityInstance("a2", other.id)))
        val lease = store.capacityLeases.request(other.id, 2, 3_600_000, at)
        c.collect()
        assertEquals(2, c.snapshot().decision.desired.getValue(other.id))
        // The arm provider stops reporting and nothing asks for its nodes any more: a fresh decision would drop them,
        // but its pool keeps its two nodes while the general pool is still decided.
        store.capacityLeases.release(lease.id)
        at += 60_000; nanos += 60_000_000_000
        sample()
        c.collect()
        val snapshot = c.snapshot()
        assertEquals(0, snapshot.decision.desired.getValue(pool.id))
        assertEquals(2, snapshot.decision.desired.getValue(other.id))
        assertTrue(snapshot.failures.getValue(other.id).startsWith("inventory stale"), snapshot.failures.toString())
        // Every provider stale: nothing is decided.
        at += 60_000; nanos += 60_000_000_000
        assertFailsWith<IllegalArgumentException> { c.collect() }
    }
}
