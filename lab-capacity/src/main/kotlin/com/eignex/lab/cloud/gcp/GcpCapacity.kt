package com.eignex.lab.cloud.gcp

import com.eignex.lab.cloud.CapacityGrants
import com.eignex.lab.cloud.CapacityRejected
import com.eignex.lab.cloud.ProviderError
import com.eignex.lab.cloud.ProviderHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * The GCP calls one pool's capacity control makes. [config] holds the pool's settings: `PROJECT`, `ZONE`, `GROUP`,
 * `POOL`, `OWNERSHIP` (the managed group's description) and `BUCKET`, and for the functions `MAX_NODES`,
 * `SIGNING_SECRET`, `SCALER_ROLE`, `SCALER_MEMBER` and `LEASE_TITLE`.
 */
interface GcpCloud {
    val config: Map<String, String>
    /** The pool's managed instance group; [CapacityRejected] when its description is not the pool's ownership. */
    fun owned(): JsonObject
    /** Every page of the group's managed instances. */
    fun managedInstances(): List<JsonObject>
    fun signingKey(): String
    fun writeLease(until: Long)
    /** The lease's expiry in epoch seconds; 0 when there is none or it is unreadable. */
    fun readLease(): Long
    fun renewIam(until: Long)
    /** Resize the group to zero. */
    fun close()
}

/** [GcpCloud] over the REST APIs, authorized by [token] (an OAuth access token). */
class GcpRestCloud(override val config: Map<String, String>, private val token: () -> String,
                   private val http: ProviderHttp = ProviderHttp.shared) : GcpCloud {
    val group = "https://compute.googleapis.com/compute/v1/projects/${config.getValue("PROJECT")}/zones/${config.getValue("ZONE")}/instanceGroupManagers/${config.getValue("GROUP")}"
    private val iam = "https://cloudresourcemanager.googleapis.com/v1/projects/${config.getValue("PROJECT")}"
    private val leaseName = "leases/${config.getValue("POOL")}.json"
    private val lease = "https://storage.googleapis.com/storage/v1/b/${config.getValue("BUCKET")}/o/${query(leaseName)}"

    /** [method] [url] with [parameters] and a JSON [body]: the response object (empty for an empty answer); [ProviderError] unless 2xx. */
    fun request(method: String, url: String, parameters: Map<String, String> = emptyMap(), body: String? = null,
                contentType: String = "application/json"): JsonObject {
        val response = exchange(method, url, parameters, body, contentType)
        if (response.status !in 200..299) throw ProviderError(error(response.text), response.status)
        return if (response.body.isEmpty()) JsonObject(emptyMap()) else Json.parseToJsonElement(response.text) as JsonObject
    }

    private fun exchange(method: String, url: String, parameters: Map<String, String>, body: String?, contentType: String) =
        http.exchange(method, URI(url + if (parameters.isEmpty()) "" else "?" + parameters.entries.joinToString("&") { (k, v) -> query(k) + "=" + query(v) }),
            body?.toByteArray() ?: ByteArray(0)) {
            mapOf("Authorization" to "Bearer ${token()}") + if (body != null) mapOf("Content-Type" to contentType) else emptyMap()
        }

    override fun owned(): JsonObject {
        val group = request("GET", group)
        if ((group["description"] as? JsonPrimitive)?.content != config["OWNERSHIP"]) throw CapacityRejected("capacity group ownership mismatch")
        return group
    }

    override fun managedInstances(): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        var token: String? = null
        do {
            val page = request("POST", "$group/listManagedInstances", token?.let { mapOf("pageToken" to it) } ?: emptyMap())
            (page["managedInstances"] as? JsonArray)?.forEach { items += it as JsonObject }
            token = (page["nextPageToken"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() }
        } while (token != null)
        return items
    }

    override fun signingKey(): String {
        val result = request("GET", "https://secretmanager.googleapis.com/v1/${config.getValue("SIGNING_SECRET")}/versions/latest:access")
        val data = ((result["payload"] as JsonObject)["data"] as JsonPrimitive).content
        return Base64.getDecoder().decode(data).toString(Charsets.UTF_8)
    }

    override fun writeLease(until: Long) {
        request("POST", "https://storage.googleapis.com/upload/storage/v1/b/${config.getValue("BUCKET")}/o",
            mapOf("uploadType" to "media", "name" to leaseName), """{"leaseUntil": $until}""")
    }

    override fun readLease(): Long {
        val response = exchange("GET", lease, mapOf("alt" to "media"), null, "")
        if (response.status == 404) return 0
        if (response.status !in 200..299) throw ProviderError(error(response.text), response.status)
        return try {
            when (val value = (Json.parseToJsonElement(response.text) as? JsonObject)?.get("leaseUntil")) {
                is JsonPrimitive -> if (value.isString) value.content.trim().toLongOrNull() ?: 0
                                    else value.content.toLongOrNull() ?: value.content.toDoubleOrNull()?.toLong() ?: 0
                else -> 0
            }
        } catch (_: IllegalArgumentException) {
            0
        }
    }

    override fun renewIam(until: Long) {
        val policy = request("POST", "$iam:getIamPolicy", body = """{"options":{"requestedPolicyVersion":3}}""")
        val renewed = GcpCapacity.renewBinding(policy, config.getValue("SCALER_ROLE"), config.getValue("SCALER_MEMBER"), config.getValue("LEASE_TITLE"), until)
        request("POST", "$iam:setIamPolicy", body = buildJsonObject { put("policy", renewed) }.toString())
    }

    override fun close() {
        request("POST", "$group/resize", mapOf("size" to "0"))
    }

    private fun error(text: String): String? = try {
        ((Json.parseToJsonElement(text) as? JsonObject)?.get("error") as? JsonObject)?.get("status")?.let { (it as? JsonPrimitive)?.content }
    } catch (_: Exception) {
        null
    }

    companion object {
        fun query(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
    }
}

/** GCP lease renewal and expiry: the IAM `request.time` condition also fences a stale Mac scaler. */
object GcpCapacity {
    /** [seconds] as the RFC 3339 UTC timestamp an IAM condition compares with. */
    fun timestamp(seconds: Long): String = Instant.ofEpochSecond(seconds).truncatedTo(ChronoUnit.SECONDS).toString()

    /**
     * [policy] with the dedicated scaler binding (role [role], only [member], condition [title]) renewed until
     * [until], and everything else, the etag included, exactly as it was; [CapacityRejected] when that binding is
     * missing or widened or the policy has no concurrency token.
     */
    fun renewBinding(policy: JsonObject, role: String, member: String, title: String, until: Long): JsonObject {
        val bindings = (policy["bindings"] as? JsonArray).orEmpty().map { it as JsonObject }
        fun members(binding: JsonObject) = (binding["members"] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.content }
        val matching = bindings.filter { (it["role"] as? JsonPrimitive)?.content == role && member in members(it) }
        val binding = matching.singleOrNull()
        val condition = binding?.get("condition") as? JsonObject
        if (binding == null || members(binding) != listOf(member) || (condition?.get("title") as? JsonPrimitive)?.content != title) {
            throw CapacityRejected("dedicated scaler lease binding missing or widened")
        }
        if ((policy["etag"] as? JsonPrimitive)?.content.isNullOrEmpty()) throw CapacityRejected("IAM policy concurrency token required")
        val renewed = JsonObject(binding + ("condition" to JsonObject(condition!! + ("expression" to JsonPrimitive("request.time < timestamp('${timestamp(until)}')")))))
        // Keep the etag, all other bindings, and unrelated condition data exactly intact.
        return JsonObject(policy + ("version" to JsonPrimitive(3)) + ("bindings" to JsonArray(bindings.map { if (it === binding) renewed else it })))
    }

    /**
     * Renew [cloud]'s lease for [grant], a fresh grant signed with [key]: persist the lease, then advance the scaler's
     * IAM deadline to it. Startup retirements are not supported on GCP and are refused before any change.
     */
    fun renew(grant: JsonElement?, cloud: GcpCloud, key: String, clock: () -> Long = System::currentTimeMillis): JsonObject {
        val pool = cloud.config.getValue("POOL")
        val maxNodes = cloud.config.getValue("MAX_NODES").toInt()
        fun fresh() {
            val value = CapacityGrants.verify(grant, key, pool, maxNodes, clock())
            if (CapacityGrants.startupRetirements(value).isNotEmpty()) throw CapacityRejected("startup retirement is not supported by this provider")
        }
        fresh(); cloud.owned(); fresh()
        val until = clock() / 1000 + CapacityGrants.LEASE_SECONDS
        cloud.writeLease(until)
        fresh(); cloud.renewIam(until)
        return buildJsonObject { put("leaseUntil", until) }
    }

    /** Resize [cloud]'s group to zero unless its lease is current; a lease too far ahead counts as expired. */
    fun expire(cloud: GcpCloud, clock: () -> Long = System::currentTimeMillis): JsonObject {
        val at = clock() / 1000
        val until = cloud.readLease()
        if (at < until && until <= at + CapacityGrants.LEASE_SECONDS) return buildJsonObject { put("expired", false) }
        cloud.owned()
        // The scaler binding expires without this function. Resizing to zero stops MIG replacement independently.
        cloud.close()
        return buildJsonObject { put("expired", true) }
    }
}
