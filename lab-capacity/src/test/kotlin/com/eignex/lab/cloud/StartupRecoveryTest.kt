package com.eignex.lab.cloud

import com.eignex.lab.cloud.aws.AsgMember
import com.eignex.lab.cloud.aws.AwsPool
import com.eignex.lab.cloud.aws.Ec2Instance
import com.eignex.lab.domain.CapacityWire
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Startup recovery through the AWS renewal function (the Python functions' test_startup_recovery). */
class StartupRecoveryTest {
    private val key = "fixture-key-".repeat(4)
    private var now = 1_000_000L
    private val launched = 1000L
    private val ident = "i-0123456789abcdef0"
    private val action = buildJsonObject { put("id", ident); put("launchedAt", launched) }
    private val member = AsgMember(ident, "InService", false)
    private val tags = mapOf("lab:deployment" to "lab", "lab:pool" to "general")
    private val calls = Calls()
    private val asg = FakeAsg(calls).apply { group = com.eignex.lab.cloud.aws.AsgGroup(0, 4, 1, listOf(member), tags) }
    private val instance = Ec2Instance(ident, launched, "running", tags)
    private val ec2 = FakeEc2(calls).apply { instances = listOf(instance) }
    private val leases = FakeLeases(calls)

    private fun startupGrant(vararg changes: Pair<String, JsonElement>): JsonObject {
        val fields = payload(desired = 1, issuedAt = now, schemaVersion = 2, extra = mapOf("startupRetirements" to buildJsonArray { add(action) }))
        return grant(key, JsonObject(fields - "nonce" + changes))
    }

    private fun members(vararg values: AsgMember) { asg.group = asg.group!!.copy(instances = values.toList()) }
    private fun renew(grant: JsonElement = startupGrant()) = AwsPool("group", "lab", "general", 4, asg, ec2, leases) { now }.renew(grant, key)
    private fun notFound() = ProviderError("InvalidInstanceID.NotFound", 400)

    @Test fun `recovery uses normal exact-member replacement and preserves target and limits`() {
        val result = renew()
        assertEquals(listOf(ident), result.getValue("startupRetired").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(ident), asg.replaced)
        assertTrue(asg.updates.isEmpty())
        assertEquals(listOf(listOf(ident)), ec2.described)
        assertEquals(1, asg.group!!.desiredCapacity)
        assertEquals(4, asg.group!!.maxSize)
        assertTrue(calls.index("leases.write") < calls.index("asg.replace"))
    }

    @Test fun `already removed or terminating members are idempotent without EC2 or termination`() {
        for (current in listOf(emptyList(), listOf(member.copy(lifecycleState = "Terminating:Wait")))) {
            asg.group = asg.group!!.copy(instances = current)
            assertTrue("startupRetired" !in renew())
        }
        assertTrue(ec2.described.isEmpty())
        assertTrue(asg.replaced.isEmpty())
    }

    @Test fun `a forged signature or an unowned group cannot reach a destructive action`() {
        val forged = JsonObject(startupGrant() + ("signature" to JsonPrimitive("0".repeat(64))))
        assertFailsWith<CapacityRejected> { renew(forged) }
        asg.group = asg.group!!.copy(tags = mapOf("lab:deployment" to "other"))
        assertFailsWith<CapacityRejected> { renew() }
        assertTrue(ec2.described.isEmpty())
        assertTrue(asg.replaced.isEmpty())
    }

    @Test fun `protected or standby members are not replaced`() {
        for (changed in listOf(member.copy(protectedFromScaleIn = true), member.copy(lifecycleState = "Standby"), member.copy(protectedFromScaleIn = null))) {
            members(changed)
            assertFailsWith<CapacityRejected> { renew() }
        }
        assertTrue(asg.replaced.isEmpty())
    }

    @Test fun `provider ownership, state and exact launch identity are required`() {
        for (changed in listOf(instance.copy(tags = emptyMap()), instance.copy(id = "i-another"), instance.copy(state = "stopped"),
                               instance.copy(launchedAt = 2000))) {
            ec2.instances = listOf(changed)
            assertFailsWith<CapacityRejected> { renew() }
        }
        ec2.instances = emptyList()
        assertEquals("startup retirement instance missing", assertFailsWith<CapacityRejected> { renew() }.message)
        assertTrue(asg.replaced.isEmpty())
    }

    @Test fun `final membership and protection are rechecked after the provider read`() {
        ec2.onDescribe = { members(member.copy(protectedFromScaleIn = true)); listOf(instance) }
        assertFailsWith<CapacityRejected> { renew() }
        assertTrue(asg.replaced.isEmpty())
    }

    @Test fun `a grant expiring during provider reads cannot replace an instance`() {
        ec2.onDescribe = { now += 31_000; listOf(instance) }
        assertEquals("capacity grant expired", assertFailsWith<CapacityRejected> { renew() }.message)
        assertTrue(asg.replaced.isEmpty())
    }

    @Test fun `a concurrent replacement's validation error succeeds only if the member is now gone`() {
        asg.onReplace = { members(member.copy(lifecycleState = "Terminating")); throw ProviderError("ValidationError", 400) }
        renew()
        members(member)
        asg.onReplace = { throw ProviderError("ValidationError", 400) }
        assertEquals("ValidationError", assertFailsWith<ProviderError> { renew() }.code)
    }

    @Test fun `action schema, malformed launch times and unexpired grace fail closed`() {
        val malformed = listOf(
            arrayOf("schemaVersion" to JsonPrimitive(1)),
            arrayOf("startupRetirements" to buildJsonArray { add(action); add(action) }),
            arrayOf("startupRetirements" to buildJsonArray { add(JsonObject(action + ("launchedAt" to JsonPrimitive(true)))) }),
            arrayOf("startupRetirements" to buildJsonArray { add(JsonObject(action + ("launchedAt" to JsonPrimitive(now - CapacityWire.STARTUP_GRACE_MS + 1)))) }),
            arrayOf("startupRetirements" to buildJsonArray { add(JsonObject(action + ("extra" to JsonPrimitive("ignored")))) }),
            arrayOf("startupRetirements" to buildJsonArray { add(JsonObject(action + ("launchedAt" to JsonPrimitive(1000.0)))) }),
            arrayOf("startupRetirements" to buildJsonArray { add(JsonObject(action + ("launchedAt" to JsonPrimitive("1000")))) }),
            arrayOf("startupRetirements" to buildJsonArray { add(JsonObject(action + ("launchedAt" to JsonPrimitive(0)))) }),
            arrayOf("startupRetirements" to buildJsonArray { add(JsonObject(action + ("id" to JsonPrimitive("")))) }),
            arrayOf("startupRetirements" to buildJsonArray { add(JsonObject(action + ("id" to JsonPrimitive("i".repeat(257))))) }),
            arrayOf("startupRetirements" to JsonObject(action)),
        )
        for (changes in malformed) {
            assertFailsWith<CapacityRejected>(changes.toList().toString()) { CapacityGrants.verify(startupGrant(*changes), key, "general", 4, now) }
        }
        assertTrue(asg.replaced.isEmpty())
    }

    @Test fun `ordinary schema one renewals remain compatible`() {
        renew(startupGrant("schemaVersion" to JsonPrimitive(1), "startupRetirements" to JsonArray(emptyList())))
        assertTrue(asg.replaced.isEmpty())
        assertTrue(ec2.described.isEmpty())
    }

    @Test fun `a missing retirement record is idempotent only after fresh ASG absence or termination`() {
        for (next in listOf(emptyList(), listOf(member.copy(lifecycleState = "Terminating")))) {
            members(member)
            ec2.onDescribe = { asg.group = asg.group!!.copy(instances = next); throw notFound() }
            renew()
        }
        members(member)
        ec2.onDescribe = { throw notFound() }
        assertEquals("InvalidInstanceID.NotFound", assertFailsWith<ProviderError> { renew() }.code)
        assertTrue(asg.replaced.isEmpty())
    }

    @Test fun `a missing record does not hide changed ownership or unrelated provider errors`() {
        ec2.onDescribe = { asg.group = asg.group!!.copy(tags = emptyMap()); throw notFound() }
        assertEquals("capacity group ownership mismatch", assertFailsWith<CapacityRejected> { renew() }.message)
        asg.group = asg.group!!.copy(tags = tags)
        ec2.onDescribe = { throw ProviderError("UnauthorizedOperation", 403) }
        assertEquals("UnauthorizedOperation", assertFailsWith<ProviderError> { renew() }.code)
        assertTrue(asg.replaced.isEmpty())
    }

    @Test fun `startup recovery without provider launch identity refuses`() {
        val pool = AwsPool("group", "lab", "general", 4, asg, null, leases) { now }
        assertEquals("startup recovery requires provider launch identity", assertFailsWith<CapacityRejected> { pool.renew(startupGrant(), key) }.message)
    }
}
