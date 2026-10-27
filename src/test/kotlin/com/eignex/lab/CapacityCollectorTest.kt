package com.eignex.lab

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
        override fun job(id: String): JsonObject? = null
        override fun submit(job: JsonObject) { error("collector cannot submit") }
        override fun stop(id: String) { error("collector cannot stop") }
        override fun allocations(job: String) = emptyList<NomadAllocation>()
        override fun allocation(id: String): NomadAllocation? = null
        override fun jwks() = buildJsonObject { }
        override fun nodes() = full?.let { listOf(buildJsonObject { put("ID", "node") }) } ?: emptyList()
        override fun node(id: String) = full
        override fun nodeAllocations(id: String) = allocations
    }
    private fun sample(instances: List<CapacityInstance> = emptyList()) {
        Files.writeString(inventory, Json.encodeToString(CapacityInventory(at, setOf(pool.id), instances)))
    }
    private fun collector() = CapacityCollector(settings, listOf(inventory), tokenFile, state, store, control, { writable }, setOf("local"), { at }, { nanos })

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
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(token.toByteArray(), "HmacSHA256"))
        mac.update("eignex-capacity-v1\n".toByteArray())
        assertEquals(mac.doFinal(raw).joinToString("") { "%02x".format(it.toInt() and 255) }, grant.signature)
        assertFailsWith<IllegalArgumentException> { c.grant("unknown") }
        nanos += 31_000_000_000
        assertFailsWith<IllegalStateException> { c.grant(pool.id) }
    }
    @Test fun `booting capacity is preserved and unknown allocations consume a conservative node budget`() {
        store.execution.requestCapacity(pool.id, 1, 60000, at)
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
        sample(); val lease = store.execution.requestCapacity(pool.id, 2, 60000, at)
        collector().collect()
        assertEquals(2, collector().also { it.collect() }.snapshot().decision.desired[pool.id])
        assertEquals(lease, Store(root.resolve("lab.db")).execution.capacityLeases(at).single())
        at += 60000; sample(); val c = collector(); c.collect()
        assertEquals(0, c.snapshot().decision.desired[pool.id]); assertTrue(store.execution.capacityLeases(at).isEmpty())
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
        application { workerApi(store, backend, artifacts) { true }; capacityApi(c) }
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
}
