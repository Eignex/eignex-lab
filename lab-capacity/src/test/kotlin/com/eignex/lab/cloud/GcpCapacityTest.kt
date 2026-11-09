package com.eignex.lab.cloud

import com.eignex.lab.cloud.gcp.GcpCapacity
import com.eignex.lab.cloud.gcp.GcpCloud
import com.eignex.lab.cloud.gcp.GcpRestCloud
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A [ProviderHttp] that answers from [answer] and records each request: method, URI, body and headers. */
class RecordingHttp(var answer: (String, URI, String) -> ProviderResponse) : ProviderHttp() {
    val requests = mutableListOf<Triple<String, URI, String>>()
    val headers = mutableListOf<Map<String, String>>()
    override fun exchange(method: String, uri: URI, body: ByteArray, timeout: Duration, transient: (ProviderResponse) -> Boolean,
                          prepare: () -> Map<String, String>): ProviderResponse {
        headers += prepare()
        requests += Triple(method, uri, body.toString(Charsets.UTF_8))
        return answer(method, uri, body.toString(Charsets.UTF_8))
    }
}

fun ok(text: String, status: Int = 200) = ProviderResponse(status, emptyMap(), text.toByteArray())

/** The GCP renewal and expiry functions (the Python functions' test_gcp_expiry). */
class GcpCapacityTest {
    private val key = "capacity-key-".repeat(4)

    private class FakeCloud(override val config: Map<String, String> = mapOf("POOL" to "general", "MAX_NODES" to "4")) : GcpCloud {
        val calls = mutableListOf<String>()
        var lease = 0L
        val renewed = mutableListOf<Long>()
        override fun owned(): JsonObject { calls += "owned"; return JsonObject(emptyMap()) }
        override fun managedInstances(): List<JsonObject> = emptyList()
        override fun signingKey() = error("not read here")
        override fun writeLease(until: Long) { calls += "writeLease" }
        override fun readLease(): Long { calls += "readLease"; return lease }
        override fun renewIam(until: Long) { calls += "renewIam"; renewed += until }
        override fun close() { calls += "close" }
    }

    @Test fun `renewal preserves unrelated IAM and concurrency and changes only the lease binding`() {
        val owner = Json.parseToJsonElement("""{"role":"roles/owner","members":["user:owner@example.com"]}""")
        val binding = Json.parseToJsonElement("""{"role":"custom/resize","members":["serviceAccount:scaler"],"condition":{"title":"lease","expression":"false","description":"keep"}}""")
        val original = buildJsonObject { put("version", 3); put("etag", "concurrent-etag"); put("bindings", JsonArray(listOf(owner, binding))) }
        val policy = GcpCapacity.renewBinding(original, "custom/resize", "serviceAccount:scaler", "lease", 2800)
        val bindings = policy.getValue("bindings").jsonArray
        assertEquals(owner, bindings[0])
        assertEquals("concurrent-etag", policy.getValue("etag").jsonPrimitive.content)
        val condition = bindings[1].jsonObject.getValue("condition").jsonObject
        assertEquals("request.time < timestamp('1970-01-01T00:46:40Z')", condition.getValue("expression").jsonPrimitive.content)
        assertEquals("keep", condition.getValue("description").jsonPrimitive.content)
        assertEquals("false", original.getValue("bindings").jsonArray[1].jsonObject.getValue("condition").jsonObject.getValue("expression").jsonPrimitive.content)
        val widened = JsonObject(original + ("bindings" to JsonArray(original.getValue("bindings").jsonArray +
            Json.parseToJsonElement("""{"role":"custom/resize","members":["serviceAccount:scaler"]}"""))))
        assertFailsWith<CapacityRejected> { GcpCapacity.renewBinding(widened, "custom/resize", "serviceAccount:scaler", "lease", 2800) }
        val shared = JsonObject(original + ("bindings" to buildJsonArray {
            add(Json.parseToJsonElement("""{"role":"custom/resize","members":["serviceAccount:scaler","user:x"],"condition":{"title":"lease"}}"""))
        }))
        assertFailsWith<CapacityRejected> { GcpCapacity.renewBinding(shared, "custom/resize", "serviceAccount:scaler", "lease", 2800) }
        assertEquals("IAM policy concurrency token required", assertFailsWith<CapacityRejected> {
            GcpCapacity.renewBinding(JsonObject(original - "etag"), "custom/resize", "serviceAccount:scaler", "lease", 2800)
        }.message)
    }

    @Test fun `a fresh signed renewal persists expiry before advancing IAM and rejects replay`() {
        val cloud = FakeCloud()
        assertEquals("""{"leaseUntil":2800}""", GcpCapacity.renew(grant(key, payload()), cloud, key) { 1_000_000 }.toString())
        assertTrue(cloud.calls.indexOf("writeLease") < cloud.calls.indexOf("renewIam"))
        assertEquals(listOf(2800L), cloud.renewed)
        assertFailsWith<CapacityRejected> { GcpCapacity.renew(grant(key, payload(issuedAt = 900_000)), cloud, key) { 1_000_000 } }
        assertEquals(1, cloud.renewed.size)
    }

    @Test fun `unsupported startup actions fail closed before provider mutations`() {
        val cloud = FakeCloud()
        val startup = payload(desired = 1, schemaVersion = 2, extra = mapOf("startupRetirements" to buildJsonArray {
            add(buildJsonObject { put("id", "node"); put("launchedAt", 1000) })
        }))
        assertEquals("startup retirement is not supported by this provider",
            assertFailsWith<CapacityRejected> { GcpCapacity.renew(grant(key, startup), cloud, key) { 1_000_000 } }.message)
        assertFalse("writeLease" in cloud.calls)
        assertFalse("renewIam" in cloud.calls)
    }

    @Test fun `expiry stops replacement while a fresh lease keeps the group running`() {
        val cloud = FakeCloud()
        assertTrue(GcpCapacity.expire(cloud) { 1_000_000 }.getValue("expired").jsonPrimitive.boolean)
        assertEquals(listOf("readLease", "owned", "close"), cloud.calls)
        cloud.calls.clear(); cloud.lease = 2000
        assertEquals("""{"expired":false}""", GcpCapacity.expire(cloud) { 1_000_000 }.toString())
        assertFalse("close" in cloud.calls)
        cloud.lease = 99_999_999
        assertTrue(GcpCapacity.expire(cloud) { 1_000_000 }.getValue("expired").jsonPrimitive.boolean)
    }

    private val config = mapOf("PROJECT" to "p", "ZONE" to "z", "GROUP" to "group", "BUCKET" to "b", "POOL" to "general", "OWNERSHIP" to "lab:pool")

    @Test fun `cloud expiry uses resize zero and refuses wrong group ownership`() {
        val http = RecordingHttp { _, _, _ -> ok("""{"description":"wrong"}""") }
        val cloud = GcpRestCloud(config, { "token" }, http)
        assertFailsWith<CapacityRejected> { cloud.owned() }
        cloud.close()
        val (method, uri, _) = http.requests.last()
        assertEquals("POST", method)
        assertEquals("https://compute.googleapis.com/compute/v1/projects/p/zones/z/instanceGroupManagers/group/resize?size=0", uri.toString())
        assertEquals("Bearer token", http.headers.last()["Authorization"])
    }

    @Test fun `the lease object is written and read as the Python functions kept it`() {
        val http = RecordingHttp { _, _, _ -> ok("") }
        val cloud = GcpRestCloud(config, { "token" }, http)
        cloud.writeLease(2800)
        val (_, upload, body) = http.requests.single()
        assertEquals("https://storage.googleapis.com/upload/storage/v1/b/b/o?uploadType=media&name=leases%2Fgeneral.json", upload.toString())
        assertEquals("""{"leaseUntil": 2800}""", body)
        http.answer = { _, _, _ -> ok("""{"leaseUntil": 2800}""") }
        assertEquals(2800, cloud.readLease())
        assertEquals("https://storage.googleapis.com/storage/v1/b/b/o/leases%2Fgeneral.json?alt=media", http.requests.last().second.toString())
        http.answer = { _, _, _ -> ok("""{"leaseUntil": "2900"}""") }
        assertEquals(2900, cloud.readLease())
        for (unreadable in listOf("""{"other": 1}""", "not json", """{"leaseUntil": "soon"}""")) {
            http.answer = { _, _, _ -> ok(unreadable) }
            assertEquals(0, cloud.readLease(), unreadable)
        }
        http.answer = { _, _, _ -> ok("", 404) }
        assertEquals(0, cloud.readLease())
        http.answer = { _, _, _ -> ok("""{"error":{"status":"PERMISSION_DENIED"}}""", 403) }
        assertEquals("PERMISSION_DENIED", assertFailsWith<ProviderError> { cloud.readLease() }.code)
    }

    @Test fun `IAM renewal reads policy version three and writes back the renewed policy`() {
        val policy = """{"version":1,"etag":"e","bindings":[{"role":"custom/resize","members":["serviceAccount:scaler"],"condition":{"title":"lease","expression":"x"}}]}"""
        val http = RecordingHttp { _, uri, _ -> ok(if (uri.path.endsWith(":getIamPolicy")) policy else "{}") }
        GcpRestCloud(config + mapOf("SCALER_ROLE" to "custom/resize", "SCALER_MEMBER" to "serviceAccount:scaler", "LEASE_TITLE" to "lease"),
            { "token" }, http).renewIam(2800)
        assertEquals("""{"options":{"requestedPolicyVersion":3}}""", http.requests[0].third)
        val written = Json.parseToJsonElement(http.requests[1].third).jsonObject.getValue("policy").jsonObject
        assertEquals(JsonPrimitive(3), written["version"])
        assertEquals("e", written.getValue("etag").jsonPrimitive.content)
        assertEquals("https://cloudresourcemanager.googleapis.com/v1/projects/p:setIamPolicy", http.requests[1].second.toString())
    }

    @Test fun `the signing key is the secret's latest version`() {
        val http = RecordingHttp { _, _, _ -> ok("""{"payload":{"data":"${java.util.Base64.getEncoder().encodeToString(key.toByteArray())}"}}""") }
        assertEquals(key, GcpRestCloud(config + ("SIGNING_SECRET" to "projects/p/secrets/s"), { "token" }, http).signingKey())
        assertEquals("https://secretmanager.googleapis.com/v1/projects/p/secrets/s/versions/latest:access", http.requests.single().second.toString())
    }
}
