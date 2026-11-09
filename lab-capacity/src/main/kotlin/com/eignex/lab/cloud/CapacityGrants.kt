package com.eignex.lab.cloud

import com.eignex.lab.domain.CapacityWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A grant, configuration or provider state the capacity functions refuse to act on. Its message is one of this code's
 * own fixed texts: never a grant, a credential or a provider response, so it is safe to log and to report.
 */
class CapacityRejected(message: String) : RuntimeException(message)

/**
 * Short-lived, pool-scoped coordinator authorization: the coordinator signs a grant ([sign]) from a fresh capacity
 * sample, and the cloud renewal functions [verify] it with their own clock before they renew a pool's lease.
 */
object CapacityGrants {
    /** How long one renewal keeps a pool open. */
    const val LEASE_SECONDS = 1800L
    private const val MAX_PAYLOAD = 8192
    private val HEX = Regex("[0-9a-f]{64}")

    /** The signature of [payload] under [key] (trimmed, as both sides read it from a file or secret): lowercase hex. */
    fun signature(payload: ByteArray, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.trim().toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update(CapacityWire.GRANT_DOMAIN.toByteArray(Charsets.UTF_8))
        return java.util.HexFormat.of().formatHex(mac.doFinal(payload))
    }

    /** The grant JSON (`payload`, `signature`) of [payload] signed with [key]. */
    fun sign(payload: ByteArray, key: String): JsonObject = JsonObject(mapOf(
        "payload" to JsonPrimitive(Base64.getUrlEncoder().withoutPadding().encodeToString(payload)),
        "signature" to JsonPrimitive(signature(payload, key)),
    ))

    /**
     * The payload of [grant] when it is a grant for [pool] at its finite [maxNodes], signed with [key] and fresh at
     * [nowMs] by the verifier's own clock; else [CapacityRejected] saying why. A grant may carry one startup retirement,
     * then under the startup schema, of an instance whose registration grace has expired.
     */
    fun verify(grant: JsonElement?, key: String, pool: String, maxNodes: Int, nowMs: Long): JsonObject {
        val payload = ((grant as? JsonObject)?.get("payload") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val signature = (grant as? JsonObject)?.get("signature")?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
        if (payload == null || signature == null || payload.length > MAX_PAYLOAD) throw CapacityRejected("invalid capacity grant")
        val raw = try {
            Base64.getUrlDecoder().decode(payload.trimEnd('='))
        } catch (_: IllegalArgumentException) {
            throw CapacityRejected("invalid capacity grant")
        }
        val expected = signature(raw, key).toByteArray(Charsets.US_ASCII)
        if (!HEX.matches(signature) || !MessageDigest.isEqual(expected, signature.toByteArray(Charsets.US_ASCII))) {
            throw CapacityRejected("invalid capacity signature")
        }
        val value = try {
            Json.parseToJsonElement(raw.toString(Charsets.UTF_8)) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: throw CapacityRejected("invalid capacity grant")
        val actions = when (val listed = value["startupRetirements"]) {
            null -> emptyList()
            is JsonArray -> listed
            else -> throw CapacityRejected("invalid startup retirement")
        }
        if (actions.size > 1) throw CapacityRejected("invalid startup retirement")
        val launches = actions.map { action ->
            val fields = action as? JsonObject
            val id = (fields?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content
            val launchedAt = integer(fields?.get("launchedAt"))
            if (fields == null || fields.keys != setOf("id", "launchedAt") || id == null ||
                id.codePointCount(0, id.length) !in 1..256 || launchedAt == null || launchedAt <= 0) {
                throw CapacityRejected("invalid startup retirement")
            }
            launchedAt
        }
        val schema = if (actions.isEmpty()) CapacityWire.GRANT_SCHEMA_VERSION else CapacityWire.STARTUP_GRANT_SCHEMA_VERSION
        val named = (value["pool"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (integer(value["schemaVersion"]) != schema.toLong() || named != pool || integer(value["maxNodes"]) != maxNodes.toLong()) {
            throw CapacityRejected("capacity grant target mismatch")
        }
        val issuedAt = integer(value["issuedAt"])
        if (issuedAt == null || nowMs - issuedAt !in -CapacityWire.CLOCK_SKEW_MS..CapacityWire.FRESH_MS) {
            throw CapacityRejected("capacity grant expired")
        }
        val desired = integer(value["desired"])
        if (desired == null || desired !in 0..maxNodes.toLong()) throw CapacityRejected("capacity grant exceeds finite limit")
        if (launches.any { nowMs - it < CapacityWire.STARTUP_GRACE_MS }) {
            throw CapacityRejected("startup registration grace has not expired")
        }
        return value
    }

    /** The startup retirements of a verified grant: each instance id and its provider launch time in milliseconds. */
    fun startupRetirements(value: JsonObject): List<Pair<String, Long>> =
        (value["startupRetirements"] as? JsonArray).orEmpty().map { action ->
            val fields = action as JsonObject
            (fields.getValue("id") as JsonPrimitive).content to integer(fields["launchedAt"])!!
        }

    /** [element] when it is a JSON integer literal (never a string, boolean or fraction), as JSON grants carry them. */
    fun integer(element: JsonElement?): Long? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString || primitive is JsonNull || !INTEGER.matches(primitive.content)) return null
        return primitive.content.toLongOrNull()
    }
    private val INTEGER = Regex("-?(?:0|[1-9][0-9]*)")
}
