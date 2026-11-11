package com.eignex.lab

import com.eignex.lab.capacity.*
import com.eignex.lab.cloud.CapacityRejected
import com.eignex.lab.cloud.ProviderError
import com.eignex.lab.cloud.aws.AsgActivity
import com.eignex.lab.cloud.aws.AsgGroup
import com.eignex.lab.cloud.aws.AsgMember
import com.eignex.lab.cloud.aws.AutoScaling
import com.eignex.lab.cloud.aws.Ec2
import com.eignex.lab.cloud.aws.Ec2Instance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** The AWS inventory and renewal adapter (the Python adapter's test_aws_adapter and its inventory cases). */
class AwsCapacityAdapterTest {
    private val root = Files.createTempDirectory("aws-adapter").also { Files.writeString(it.resolve("key"), "fixture-key-".repeat(4)) }
    private val inventoryFile = root.resolve("inventory.json")
    private val config = Json.parseToJsonElement("""{"deployment": "lab", "inventoryFile": "$inventoryFile",
        "capacityTokenFile": "${root.resolve("key")}", "coordinator": "http://coordinator",
        "pools": [{"pool": "avx2", "group": "group-avx2", "renewFunction": "renew-avx2"},
                  {"pool": "neon", "group": "group-neon", "renewFunction": "renew-neon"}]}""").jsonObject
    private val invoked = ConcurrentLinkedQueue<String>()
    private val emptyGrant: (String, String) -> JsonElement = { _, _ -> JsonObject(emptyMap()) }
    private val succeed = AwsCapacityAdapter.Functions { function, _ -> invoked += function; null to "{}".toByteArray() }

    private fun observation(pool: JsonObject, at: Long = 123) = buildJsonObject {
        put("observedAt", at); put("pools", Json.parseToJsonElement("[\"${pool.getValue("pool").jsonPrimitive.content}\"]"))
        put("instances", Json.parseToJsonElement("""[{"id": "${pool.getValue("group").jsonPrimitive.content}", "pool": "${pool.getValue("pool").jsonPrimitive.content}"}]"""))
        put("failures", JsonObject(emptyMap()))
    }
    private fun published() = Json.parseToJsonElement(Files.readString(inventoryFile)).jsonObject
    private fun once(observe: (JsonObject) -> JsonObject = { observation(it) }, functions: AwsCapacityAdapter.Functions = succeed,
                     grant: (String, String) -> JsonElement = emptyGrant) {
        AwsCapacityAdapter.publish(config, observe)
        AwsCapacityAdapter.renew(config, functions, grant)
    }

    private class Asg(var group: AsgGroup?, var activity: AsgActivity? = null) : AutoScaling {
        override fun group(name: String) = group
        override fun latestActivity(name: String) = activity
        override fun update(name: String, minSize: Int?, desiredCapacity: Int?, maxSize: Int?) = error("the adapter never changes a group")
        override fun unprotect(name: String, ids: List<String>) = error("the adapter never changes a group")
        override fun replace(id: String) = error("the adapter never replaces an instance")
    }
    private class Instances(var instances: List<Ec2Instance>) : Ec2 {
        val asked = mutableListOf<List<String>>()
        override fun describe(ids: List<String>) = error("the adapter filters by id")
        override fun describeAmong(ids: List<String>): List<Ec2Instance> { asked += ids; return instances.filter { it.id in ids } }
        override fun tagged(tags: Map<String, String>, states: List<String>) = error("the adapter never lists by tag")
        override fun terminate(ids: List<String>) = error("the adapter never terminates")
    }
    private val tags = mapOf("lab:deployment" to "lab", "lab:pool" to "general")

    @Test fun `Spot shortages carry the real activity timestamp and exact code through fleet publication`() {
        val asg = Asg(AsgGroup(0, 4, 1, emptyList(), tags), AsgActivity("Failed", "Could not launch Spot Instances. UnfulfillableCapacity - unavailable", 1000))
        val value = AwsCapacityAdapter.inventory(asg, "group", "lab", "general", null) { 2000 }
        val merged = CapacityAdapter.merge(listOf(value))
        assertEquals(CapacityError("UnfulfillableCapacity", 1000), Json.decodeFromString<CapacityInventory>(merged.toString()).capacityErrors["general"])
        for (activity in listOf(AsgActivity("Failed", "VcpuLimitExceeded", 1000), AsgActivity("Failed", "UnfulfillableCapacity", null),
                AsgActivity("Successful", "UnfulfillableCapacity", 1000))) {
            asg.activity = activity
            assertTrue(Json.decodeFromString<CapacityInventory>(AwsCapacityAdapter.inventory(asg, "group", "lab", "general", null).toString()).capacityErrors.isEmpty())
        }
    }

    @Test fun `error reports keep provider codes and never a message`() {
        assertEquals("HTTP_400", CapacityAdapter.code(ProviderError("secret response body", 400)))
        assertEquals("RuntimeException", AwsCapacityAdapter.describe(RuntimeException("secret message")))
        assertEquals("TooManyRequestsException", AwsCapacityAdapter.describe(ProviderError("TooManyRequestsException", 429)))
        assertEquals("CapacityRejected: pool group missing", AwsCapacityAdapter.describe(CapacityRejected("pool group missing")))
    }

    @Test fun `a failed scaling activity without a message is still published`() {
        val asg = Asg(AsgGroup(0, 4, 0, emptyList(), tags), AsgActivity("Failed", null))
        assertEquals("""{"general":"capacity unavailable"}""", AwsCapacityAdapter.inventory(asg, "group", "lab", "general", null)["failures"].toString())
        asg.activity = AsgActivity("Failed", "Could not launch Spot Instances")
        assertEquals("""{"general":"Could not launch Spot Instances"}""", AwsCapacityAdapter.inventory(asg, "group", "lab", "general", null)["failures"].toString())
        asg.activity = AsgActivity("Successful", "fine")
        assertEquals("{}", AwsCapacityAdapter.inventory(asg, "group", "lab", "general", null)["failures"].toString())
    }

    @Test fun `all pools are published before their independent renewals`() {
        once(grant = { _, _ -> assertEquals(listOf("avx2", "neon"), published().getValue("pools").jsonArray.map { it.jsonPrimitive.content }); JsonObject(emptyMap()) })
        assertEquals(listOf("avx2", "neon"), published().getValue("instances").jsonArray.map { it.jsonObject.getValue("pool").jsonPrimitive.content })
        assertEquals(setOf("renew-avx2", "renew-neon"), invoked.toSet())
        assertEquals(2, invoked.size)
    }

    @Test fun `grants are fetched with the trimmed capacity key and handed to the function as JSON`() {
        val payloads = ConcurrentLinkedQueue<String>()
        once(functions = { _, payload -> payloads += payload.toString(Charsets.UTF_8); null to ByteArray(0) },
            grant = { pool, key -> assertEquals("fixture-key-".repeat(4), key); Json.parseToJsonElement("""{"payload": "$pool", "signature": "s"}""") })
        assertEquals(setOf("""{"payload":"avx2","signature":"s"}""", """{"payload":"neon","signature":"s"}"""), payloads.toSet())
    }

    @Test fun `slow pool requests overlap instead of aging the entire fleet`() {
        val barrier = CyclicBarrier(2)
        once(observe = { barrier.await(2, TimeUnit.SECONDS); observation(it) })
        assertEquals(2, invoked.size)
    }

    @Test fun `a missing pool does not publish a partial fresh inventory`() {
        assertFailsWith<CapacityRejected> {
            once(observe = { if (it.getValue("pool").jsonPrimitive.content == "neon") throw CapacityRejected("pool group missing") else observation(it) })
        }
        assertFalse(Files.exists(inventoryFile))
        assertTrue(invoked.isEmpty())
    }

    @Test fun `one failed renewal does not starve other pools`() {
        val error = assertFailsWith<AwsCapacityAdapter.RenewalError> {
            once(functions = { function, _ -> invoked += function; (if (function == "renew-avx2") "Unhandled" else null) to "{}".toByteArray() })
        }
        assertEquals("capacity renewal held for avx2=provider_error", error.message)
        assertEquals(2, invoked.size)
    }

    @Test fun `renewal reports actionable codes without exposing response secrets`() {
        val error = assertFailsWith<AwsCapacityAdapter.RenewalError> {
            once(grant = { pool, _ -> if (pool == "avx2") throw CapacityAdapter.CoordinatorStatus(503) else JsonObject(emptyMap()) },
                functions = { _, _ -> "Unhandled" to """{"errorType":"com.eignex.lab.cloud.CapacityRejected","errorMessage":"secret-signature"}""".toByteArray() })
        }
        assertEquals("capacity renewal held for avx2=HTTP_503, neon=com.eignex.lab.cloud.CapacityRejected", error.message)
        assertTrue("secret" !in error.message!!)
        val odd = assertFailsWith<AwsCapacityAdapter.RenewalError> {
            once(functions = { _, _ -> "Unhandled" to """{"errorType":"secret type with spaces"}""".toByteArray() })
        }
        assertEquals("capacity renewal held for avx2=provider_error, neon=provider_error", odd.message)
    }

    @Test fun `a provider error code survives without logging its message`() {
        val error = assertFailsWith<AwsCapacityAdapter.RenewalError> {
            once(functions = { _, _ -> throw ProviderError("TooManyRequestsException", 429) })
        }
        assertTrue("avx2=TooManyRequestsException" in error.message!!)
        assertTrue("secret" !in error.message!!)
    }

    @Test fun `inventory can refresh while a pool renewal is blocked`() {
        AwsCapacityAdapter.publish(config) { observation(it) }
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val renewal = worker.submit {
                AwsCapacityAdapter.renew(config, { _, _ ->
                    started.countDown()
                    check(release.await(2, TimeUnit.SECONDS)) { "inventory waited for renewal" }
                    null to ByteArray(0)
                }, emptyGrant)
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            AwsCapacityAdapter.publish(config) { observation(it, at = 456) }
            assertEquals(456, published().getValue("observedAt").jsonPrimitive.long)
            release.countDown()
            renewal.get(2, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test fun `inventory accounts for missing boots and does not reopen terminating targets`() {
        val asg = Asg(AsgGroup(0, 4, 2, listOf(AsgMember("i-1", "InService", true)), tags))
        assertEquals(2, AwsCapacityAdapter.inventory(asg, "group", "lab", "general", null) { 1000 }.getValue("instances").jsonArray.size)
        asg.group = AsgGroup(0, 4, 0, listOf(AsgMember("i-1", "Terminating", true)), tags)
        val value = AwsCapacityAdapter.inventory(asg, "group", "lab", "general", null) { 1000 }
        assertEquals("false", value.getValue("instances").jsonArray[0].jsonObject.getValue("countedInTarget").jsonPrimitive.content)
        asg.group = AsgGroup(0, 4, 0, emptyList(), emptyMap())
        assertEquals("pool ownership mismatch", assertFailsWith<CapacityRejected> { AwsCapacityAdapter.inventory(asg, "group", "lab", "general", null) }.message)
        asg.group = null
        assertEquals("pool group missing", assertFailsWith<CapacityRejected> { AwsCapacityAdapter.inventory(asg, "group", "lab", "general", null) }.message)
    }

    private val ident = "i-0123456789abcdef0"
    private val member = AsgMember(ident, "InService", false)
    private val instance = Ec2Instance(ident, 1000, "running", tags)

    @Test fun `inventory uses the actual provider launch time without aging synthetic slots`() {
        val asg = Asg(AsgGroup(0, 4, 2, listOf(member), tags))
        val value = AwsCapacityAdapter.inventory(asg, "group", "lab", "general", Instances(listOf(instance))) { 1_000_000 }
        val instances = value.getValue("instances").jsonArray.map { it.jsonObject }
        assertEquals(1000, instances[0].getValue("launchedAt").jsonPrimitive.long)
        assertFalse("launchedAt" in instances[1])
        // The coordinator reads it as the launch time.
        assertEquals(1000L, Json.decodeFromString(CapacityInventory.serializer(), value.toString()).instances[0].launchedAt)
    }

    @Test fun `a vanished terminating instance does not hold the inventory of its healthy neighbor`() {
        val healthy = "i-0123456789abcdef1"
        val asg = Asg(AsgGroup(0, 4, 1, listOf(member.copy(lifecycleState = "Terminating"), member.copy(id = healthy)), tags))
        val ec2 = Instances(listOf(instance.copy(id = healthy)))
        val value = AwsCapacityAdapter.inventory(asg, "group", "lab", "general", ec2) { 1_000_000 }
        assertEquals(listOf(listOf(ident, healthy)), ec2.asked)
        assertEquals(1_000_000, value.getValue("observedAt").jsonPrimitive.long)
        val instances = value.getValue("instances").jsonArray.map { it.jsonObject }
        assertEquals(2, instances.size)
        assertEquals("false", instances[0].getValue("countedInTarget").jsonPrimitive.content)
        assertFalse("launchedAt" in instances[0], "an absent provider record must not acquire a fabricated age")
        assertEquals(1000, instances[1].getValue("launchedAt").jsonPrimitive.long)
    }

    @Test fun `launch times are asked in batches of a hundred`() {
        val members = (1..250).map { AsgMember("i-$it", "InService", false) }
        val ec2 = Instances(emptyList())
        AwsCapacityAdapter.inventory(Asg(AsgGroup(0, 300, 250, members, tags)), "group", "lab", "general", ec2)
        assertEquals(listOf(100, 100, 50), ec2.asked.map { it.size })
    }

    @Test fun `pools are distinct and each is the contract's`() {
        assertEquals(listOf("avx2", "neon"), AwsCapacityAdapter.definitions(config).map { it.getValue("pool").jsonPrimitive.content })
        assertEquals("lab", AwsCapacityAdapter.definitions(config)[1].getValue("deployment").jsonPrimitive.content)
        val twice = Json.parseToJsonElement("""{"pools": [{"pool": "a", "group": "g"}, {"pool": "b", "group": "g"}]}""").jsonObject
        assertEquals("configure distinct pools and groups", assertFailsWith<CapacityRejected> { AwsCapacityAdapter.definitions(twice) }.message)
        val contract = root.resolve("contract.json")
        Files.writeString(contract, """{"schemaVersion": 1, "pools": [{"id": "avx2", "maxNodes": 8}]}""")
        val checked = JsonObject(config + ("contract" to kotlinx.serialization.json.JsonPrimitive(contract.toString())))
        assertEquals("capacity pool neon is not in the contract", assertFailsWith<CapacityRejected> { AwsCapacityAdapter.definitions(checked) }.message)
        val single = Json.parseToJsonElement("""{"pool": "general", "group": "g", "renewFunction": "f"}""").jsonObject
        assertEquals(listOf(single), AwsCapacityAdapter.definitions(single))
    }

    @Test fun `credentials come from the profile, AWS_PROFILE or the environment`() {
        val region = buildJsonObject { put("region", "eu-north-1") }
        val keys = mapOf("AWS_ACCESS_KEY_ID" to "AKIDENV", "AWS_SECRET_ACCESS_KEY" to "s")
        assertEquals("AKIDENV", AwsCapacityAdapter.credentials(region, keys)().accessKeyId)
        val home = root.resolve("home").also { Files.createDirectories(it.resolve(".aws")) }
        Files.writeString(home.resolve(".aws/credentials"), "[lab]\naws_access_key_id = AKIDLAB\naws_secret_access_key = l\n")
        val profile = JsonObject(region + ("profile" to kotlinx.serialization.json.JsonPrimitive("lab")))
        assertEquals("AKIDLAB", AwsCapacityAdapter.credentials(profile, keys + ("HOME" to home.toString()))().accessKeyId)
        assertEquals("AKIDLAB", AwsCapacityAdapter.credentials(region, keys + mapOf("HOME" to home.toString(), "AWS_PROFILE" to "lab"))().accessKeyId)
    }

    @Test fun `the examples one pool's setup is copied from name that pool alike`() {
        fun load(path: String) = Json.parseToJsonElement(Files.readString(Path.of(path))).jsonObject
        fun ids(path: String, list: String, key: String) = load(path).getValue(list).jsonArray.map { it.jsonObject.getValue(key).jsonPrimitive.content }.toSet()
        val single = ids("deploy/nomad/capacity.example.json", "pools", "id")
        assertEquals(single, ids("deploy/nomad/autoscaler.example.json", "targets", "pool"))
        assertEquals(single, ids("deploy/nomad/autoscaler-gcp.example.json", "targets", "pool"))
        assertEquals(single, setOf(load("deploy/infra/aws/adapter.example.json").getValue("pool").jsonPrimitive.content))
        assertEquals(single, setOf(load("deploy/infra/gcp/adapter.example.json").getValue("POOL").jsonPrimitive.content))
        val architectures = ids("deploy/nomad/capacity-architectures.example.json", "pools", "id")
        val fleet = ids("deploy/infra/aws/adapter-fleet.example.json", "pools", "pool")
        assertTrue(architectures.containsAll(fleet), (fleet - architectures).toString())
        // The examples are configurations the adapter accepts.
        assertEquals(fleet.size, AwsCapacityAdapter.definitions(load("deploy/infra/aws/adapter-fleet.example.json")).size)
        assertEquals(1, AwsCapacityAdapter.definitions(load("deploy/infra/aws/adapter.example.json")).size)
    }
}
