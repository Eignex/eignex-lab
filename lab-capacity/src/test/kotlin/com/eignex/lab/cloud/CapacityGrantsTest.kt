package com.eignex.lab.cloud

import com.eignex.lab.domain.CapacityWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CapacityGrantsTest {
    private val key = "capacity-key-".repeat(4)

    @Test fun `grants speak the shared wire format`() {
        val wire = Json.parseToJsonElement(Files.readString(Path.of("../deploy/capacity-wire.json"))).jsonObject
        assertEquals(wire.getValue("grantDomain").jsonPrimitive.content, CapacityWire.GRANT_DOMAIN)
        assertEquals(wire.getValue("grantSchemaVersion").jsonPrimitive.int, CapacityWire.GRANT_SCHEMA_VERSION)
        assertEquals(wire.getValue("startupGrantSchemaVersion").jsonPrimitive.int, CapacityWire.STARTUP_GRANT_SCHEMA_VERSION)
        assertEquals(wire.getValue("startupGraceMs").jsonPrimitive.long, CapacityWire.STARTUP_GRACE_MS)
        assertEquals(wire.getValue("grantFreshMs").jsonPrimitive.long, CapacityWire.FRESH_MS)
        assertEquals(wire.getValue("grantClockSkewMs").jsonPrimitive.long, CapacityWire.CLOCK_SKEW_MS)
    }

    @Test fun `a grant signed as the Python verifier and the coordinator sign it verifies`() {
        // HMAC-SHA256 of the domain and payload, computed independently with openssl.
        val grant = Json.parseToJsonElement("""{"payload": "eyJzY2hlbWFWZXJzaW9uIjoxLCJwb29sIjoiZ2VuZXJhbCIsIm1heE5vZGVzIjo0LCJkZXNpcmVkIjoyLCJpc3N1ZWRBdCI6MTAwMDAwMH0",
            "signature": "05e48037ff2e1f19801fbbfe4be3c288294e91ff221f232ad7212ef0fac72b3e"}""")
        val value = CapacityGrants.verify(grant, key, "general", 4, 1_000_000)
        assertEquals(2, value.getValue("desired").jsonPrimitive.int)
        assertEquals(grant, CapacityGrants.sign(
            """{"schemaVersion":1,"pool":"general","maxNodes":4,"desired":2,"issuedAt":1000000}""".toByteArray(), "  $key\n"))
        // Trailing padding is ignored.
        val padded = JsonObject(grant.jsonObject + ("payload" to JsonPrimitive(grant.jsonObject.getValue("payload").jsonPrimitive.content + "=")))
        CapacityGrants.verify(padded, key, "general", 4, 1_000_000)
    }

    @Test fun `freshness is the verifier's own clock with bounded skew`() {
        val signed = grant(key, payload())
        CapacityGrants.verify(signed, key, "general", 4, 1_000_000 + CapacityWire.FRESH_MS)
        CapacityGrants.verify(signed, key, "general", 4, 1_000_000 - CapacityWire.CLOCK_SKEW_MS)
        for (now in listOf(1_000_001 + CapacityWire.FRESH_MS, 999_999 - CapacityWire.CLOCK_SKEW_MS)) {
            assertEquals("capacity grant expired", assertFailsWith<CapacityRejected> { CapacityGrants.verify(signed, key, "general", 4, now) }.message)
        }
    }

    @Test fun `target, limit and malformed grants are refused with fixed reasons`() {
        fun reason(grant: kotlinx.serialization.json.JsonElement?, maxNodes: Int = 4) =
            assertFailsWith<CapacityRejected> { CapacityGrants.verify(grant, key, "general", maxNodes, 1_000_000) }.message
        assertEquals("capacity grant target mismatch", reason(grant(key, payload(pool = "other"))))
        assertEquals("capacity grant target mismatch", reason(grant(key, payload()), maxNodes = 8))
        assertEquals("capacity grant target mismatch", reason(grant(key, payload(schemaVersion = 2))))
        assertEquals("capacity grant exceeds finite limit", reason(grant(key, payload(desired = 5))))
        assertEquals("capacity grant exceeds finite limit", reason(grant(key, payload(desired = -1))))
        assertEquals("capacity grant exceeds finite limit", reason(grant(key, JsonObject(payload() + ("desired" to JsonPrimitive("2"))))))
        assertEquals("capacity grant expired", reason(grant(key, JsonObject(payload() + ("issuedAt" to JsonPrimitive(1_000_000.0))))))
        assertEquals("invalid capacity signature", reason(grant("another-key-".repeat(4), payload())))
        assertEquals("invalid capacity grant", reason(null))
        assertEquals("invalid capacity grant", reason(JsonNull))
        assertEquals("invalid capacity grant", reason(Json.parseToJsonElement("""{"payload": 1, "signature": "x"}""")))
        assertEquals("invalid capacity grant", reason(Json.parseToJsonElement("""{"payload": "${"a".repeat(8193)}", "signature": "x"}""")))
        assertEquals("invalid capacity grant", reason(CapacityGrants.sign("[1]".toByteArray(), key)))
        assertEquals("invalid capacity grant", reason(CapacityGrants.sign("not json".toByteArray(), key)))
        assertEquals("invalid capacity signature", reason(Json.parseToJsonElement("""{"payload": "e30", "signature": "${"A".repeat(64)}"}""")))
    }
}
