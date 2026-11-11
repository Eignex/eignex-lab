package com.eignex.lab.cloud

import com.eignex.lab.cloud.aws.AwsPool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The AWS renewal and expiry functions' lease handling (the Python functions' test_aws_expiry). */
class AwsCapacityTest {
    @Test fun `fresh zero demand cancels an unfilled launch without waiting for a Nomad node`() {
        asg.group = asg.group!!.copy(instances = emptyList())
        pool().renew(grant(key, payload(desired = 0)), key)
        assertEquals(listOf(Triple<Int?, Int?, Int?>(null, 0, null)), asg.updates)
        assertTrue(calls.index("leases.write") < calls.index("asg.update"))
        assertTrue(ec2.terminated.isEmpty())
    }

    @Test fun `zero demand never directly shrinks a group containing a real member`() {
        pool().renew(grant(key, payload(desired = 0)), key)
        assertTrue(asg.updates.isEmpty())
        asg.group = asg.group!!.copy(instances = emptyList())
        var reads = 0
        asg.onGroup = { if (++reads == 2) asg.group = asg.group!!.copy(instances = listOf(com.eignex.lab.cloud.aws.AsgMember("i-new", "Pending", false))) }
        pool().renew(grant(key, payload(desired = 0)), key)
        assertTrue(asg.updates.isEmpty(), "a launch racing zero-demand cleanup stays with the ordinary scaler")
    }

    @Test fun `positive stale or wrong-pool grants never cancel unfilled launches`() {
        asg.group = asg.group!!.copy(instances = emptyList())
        pool().renew(grant(key, payload(desired = 1)), key)
        for (invalid in listOf(payload(desired = 0, issuedAt = 900_000), payload(desired = 0, pool = "other"))) {
            assertFailsWith<CapacityRejected> { pool().renew(grant(key, invalid), key) }
        }
        assertTrue(asg.updates.isEmpty())
    }
    private val key = "capacity-key-".repeat(4)
    private val calls = Calls()
    private val asg = FakeAsg(calls)
    private val ec2 = FakeEc2(calls)
    private val leases = FakeLeases(calls)
    private fun pool(now: Long = 1_000_000) = AwsPool("group", "lab", "general", 4, asg, ec2, leases) { now }

    @Test fun `expiry clamps min, desired and max before removing remaining workers`() {
        val result = pool().expire()
        assertTrue(result.getValue("expired").jsonPrimitive.boolean)
        assertEquals(1, result.long("terminated"))
        assertEquals(listOf(Triple<Int?, Int?, Int?>(0, 0, 0)), asg.updates)
        assertEquals(listOf("i-1"), asg.unprotected.single())
        assertTrue(calls.index("asg.unprotect") < calls.index("asg.update"))
        assertTrue(calls.index("asg.update") < calls.index("ec2.terminate"))
        assertEquals(mapOf("lab:deployment" to "lab", "lab:pool" to "general") to listOf("pending", "running", "stopping", "stopped"),
            ec2.taggedQueries.single())
        assertEquals(listOf(listOf("i-1")), ec2.terminated)
    }

    @Test fun `expiry unprotects in batches of fifty and terminates in batches of a hundred`() {
        asg.group = asg.group!!.copy(instances = (1..120).map { com.eignex.lab.cloud.aws.AsgMember("i-$it", "InService", true) })
        ec2.instances = (1..250).map { ec2.instances.single().copy(id = "i-$it") }
        pool().expire()
        assertEquals(listOf(50, 50, 20), asg.unprotected.map { it.size })
        assertEquals(listOf(100, 100, 50), ec2.terminated.map { it.size })
    }

    @Test fun `fresh lease does not terminate and an invalid far-future lease fails closed`() {
        leases.row = "2000"
        assertFalse(pool().expire().getValue("expired").jsonPrimitive.boolean)
        assertTrue(asg.updates.isEmpty())
        assertTrue(ec2.terminated.isEmpty())
        leases.row = "99999999999"
        assertTrue(pool().expire().getValue("expired").jsonPrimitive.boolean)
        for (unreadable in listOf("not-a-number", "2000.5", "")) {
            leases.row = unreadable
            assertTrue(pool().expire().getValue("expired").jsonPrimitive.boolean, unreadable)
        }
        leases.row = "1000"
        assertTrue(pool().expire().getValue("expired").jsonPrimitive.boolean, "a lease ending now has expired")
    }

    @Test fun `expiry without a group still removes tagged workers`() {
        asg.group = null
        assertTrue(pool().expire().getValue("expired").jsonPrimitive.boolean)
        assertTrue(asg.updates.isEmpty())
        assertEquals(listOf(listOf("i-1")), ec2.terminated)
    }

    @Test fun `expiry refuses a group another deployment owns`() {
        asg.group = asg.group!!.copy(tags = emptyMap())
        assertFailsWith<CapacityRejected> { pool().expire() }
        assertTrue(asg.updates.isEmpty())
        assertTrue(ec2.terminated.isEmpty())
    }

    @Test fun `a signed fresh grant persists the lease before reopening a closed group`() {
        asg.group = asg.group!!.copy(maxSize = 0)
        val result = pool().renew(grant(key, payload()), key)
        assertTrue(calls.index("leases.write") < calls.index("asg.update"))
        assertEquals(listOf(2800L), leases.written)
        assertEquals(2800, result.long("leaseUntil"))
        assertFalse("startupRetired" in result)
        assertEquals(listOf(Triple<Int?, Int?, Int?>(0, null, 4)), asg.updates)
        for (stale in listOf(payload(issuedAt = 900_000), payload(pool = "other"), payload(desired = 5))) {
            assertFailsWith<CapacityRejected> { pool().renew(grant(key, stale), key) }
        }
        assertEquals(1, leases.written.size)
    }

    @Test fun `an open group is renewed without reopening and a different limit is refused`() {
        pool().renew(grant(key, payload()), key)
        assertTrue(asg.updates.isEmpty())
        assertEquals(1, leases.written.size)
        for (changed in listOf(asg.group!!.copy(maxSize = 8), asg.group!!.copy(minSize = 1))) {
            asg.group = changed
            assertEquals("capacity group finite limit differs from configuration",
                assertFailsWith<CapacityRejected> { pool().renew(grant(key, payload()), key) }.message)
        }
        asg.group = null
        assertFailsWith<CapacityRejected> { pool().renew(grant(key, payload()), key) }
        assertEquals(1, leases.written.size)
    }

    @Test fun `a grant that ages out between provider calls opens nothing`() {
        asg.group = asg.group!!.copy(maxSize = 0)
        var now = 1_000_000L
        asg.onGroup = { now += 31_000 }
        assertEquals("capacity grant expired", assertFailsWith<CapacityRejected> {
            AwsPool("group", "lab", "general", 4, asg, ec2, leases) { now }.renew(grant(key, payload()), key)
        }.message)
        assertTrue(leases.written.isEmpty())
        assertTrue(asg.updates.isEmpty())
    }

    @Test fun `results are the JSON the Python functions returned`() {
        assertEquals("""{"leaseUntil":2800}""", pool().renew(grant(key, payload()), key).toString())
        leases.row = "2000"
        assertEquals("""{"expired":false}""", pool().expire().toString())
        leases.row = null
        assertEquals("""{"expired":true,"terminated":1}""", pool().expire().toString())
        assertEquals(JsonPrimitive(true), pool().expire()["expired"])
    }
}
