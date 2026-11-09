package com.eignex.lab

import com.eignex.lab.capacity.*
import com.eignex.lab.domain.*
import com.eignex.lab.cloud.CapacityRejected
import com.eignex.lab.cloud.gcp.GcpCloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

/** The Mac capacity adapter's shared inventory, contract and publication, and the GCP adapter's inventory. */
class CapacityAdapterTest {
    private val root = Files.createTempDirectory("capacity-adapter")

    private fun ids(value: JsonObject) = value.getValue("instances").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }

    @Test fun `booting nodes fill the target and draining ones do not count`() {
        val value = CapacityAdapter.observation("p", listOf("i-1" to false, "i-2" to true), 3, null, 1000)
        assertEquals(listOf("i-1", "i-2", "booting-0", "booting-1"), ids(value))
        assertEquals(JsonObject(emptyMap()), value["failures"])
        assertEquals("""{"p":"quota"}""", CapacityAdapter.observation("p", emptyList(), 0, "quota", 1000)["failures"].toString())
        assertEquals(500, CapacityAdapter.observation("p", emptyList(), 0, "x".repeat(600), 1000)
            .getValue("failures").jsonObject.getValue("p").jsonPrimitive.content.length)
        // The coordinator reads exactly this shape.
        val inventory = Json.decodeFromString(CapacityInventory.serializer(), value.toString())
        assertEquals(listOf(true, false, true, true), inventory.instances.map { it.countedInTarget })
        assertEquals(listOf(false, true, false, false), inventory.instances.map { it.draining })
        assertEquals(1000, inventory.observedAt)
    }

    @Test fun `merged pools stay distinct and as old as the oldest`() {
        val merged = CapacityAdapter.merge(listOf(CapacityAdapter.observation("a", emptyList(), 1, null, 2000),
            CapacityAdapter.observation("b", emptyList(), 0, "quota", 1000)))
        assertEquals(1000, merged.getValue("observedAt").jsonPrimitive.long)
        assertEquals(listOf("a", "b"), merged.getValue("pools").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("""{"b":"quota"}""", merged["failures"].toString())
        assertEquals("inventory lists a pool twice", assertFailsWith<CapacityRejected> {
            CapacityAdapter.merge(listOf(CapacityAdapter.observation("a", emptyList(), 0, null, 1), CapacityAdapter.observation("a", emptyList(), 0, null, 1)))
        }.message)
        assertEquals("inventory lists an instance twice", assertFailsWith<CapacityRejected> {
            CapacityAdapter.observation("a", listOf("i-1" to false, "i-1" to true), 0, null, 1)
        }.message)
    }

    @Test fun `a pool is checked against the lab contract`() {
        val path = root.resolve("contract.json")
        Files.writeString(path, """{"schemaVersion": 1, "pools": [{"id": "general-spot", "nodeClass": "linux", "maxNodes": 4, "vcpus": 16, "memoryMiB": 65536, "market": "spot"}]}""")
        assertEquals(4, CapacityAdapter.checkContract(path.toString(), "general-spot", mapOf("maxNodes" to 4))!!.getValue("maxNodes").jsonPrimitive.long)
        assertEquals("capacity pool general-spot differs from the contract: maxNodes 8 here, 4 there", assertFailsWith<CapacityRejected> {
            CapacityAdapter.checkContract(path.toString(), "general-spot", mapOf("maxNodes" to 8))
        }.message)
        assertEquals("capacity pool other is not in the contract", assertFailsWith<CapacityRejected> {
            CapacityAdapter.checkContract(path.toString(), "other")
        }.message)
        assertNull(CapacityAdapter.checkContract(null, "anything"))
        Files.writeString(path, """{"schemaVersion": 2, "pools": []}""")
        assertFailsWith<CapacityRejected> { CapacityAdapter.checkContract(path.toString(), "general-spot") }
    }

    @Test fun `the exported contract is what adapters check against`() {
        val settings = CapacitySettings.read(Path.of("deploy/nomad/capacity-architectures.example.json"))
        val path = root.resolve("exported.json")
        Files.writeString(path, Json.encodeToString(CapacityContract.serializer(), CapacityContract.of(settings)))
        val first = settings.pools.first()
        CapacityAdapter.checkContract(path.toString(), first.id, mapOf("maxNodes" to first.maxNodes))
    }

    @Test fun `inventory is published atomically into a private directory`() {
        val file = root.resolve("private/nested/inventory.json")
        CapacityAdapter.writeAtomic(file, CapacityAdapter.observation("p", emptyList(), 1, null, 1000))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.parent)))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals(listOf("inventory.json"), Files.list(file.parent).use { files -> files.map { it.fileName.toString() }.toList() })
        CapacityAdapter.writeAtomic(file, CapacityAdapter.observation("p", emptyList(), 0, null, 2000))
        assertEquals(2000, Json.parseToJsonElement(Files.readString(file)).jsonObject.getValue("observedAt").jsonPrimitive.long)
    }

    private class Cloud(val target: Int, val items: String) : GcpCloud {
        override val config = mapOf("POOL" to "general")
        override fun owned() = Json.parseToJsonElement("""{"targetSize": $target}""").jsonObject
        override fun managedInstances() = Json.parseToJsonElement(items).jsonArray.map { it.jsonObject }
        override fun signingKey() = error("unused")
        override fun writeLease(until: Long) = error("unused")
        override fun readLease() = error("unused")
        override fun renewIam(until: Long) = error("unused")
        override fun close() = error("unused")
    }

    @Test fun `GCP inventory counts boots and terminating workers separately with quota errors`() {
        val value = GcpCapacityAdapter.inventory(Cloud(2, """[{"instance": "https://www.googleapis.com/compute/v1/projects/p/zones/z/instances/node",
            "currentAction": "DELETING", "lastAttempt": {"errors": {"errors": [{"message": "quota exhausted"}]}}}]""")) { 1000 }
        assertEquals(3, value.getValue("instances").jsonArray.size)
        val first = value.getValue("instances").jsonArray[0].jsonObject
        assertEquals("false", first.getValue("countedInTarget").jsonPrimitive.content)
        assertEquals("projects/p/zones/z/instances/node", first.getValue("id").jsonPrimitive.content)
        assertTrue("quota exhausted" in value.getValue("failures").jsonObject.getValue("general").jsonPrimitive.content)
    }

    @Test fun `GCP boots without an instance URL still count towards the desired target`() {
        val value = GcpCapacityAdapter.inventory(Cloud(1, """[{"name": "not-created-yet", "instance": "", "currentAction": "CREATING"}]""")) { 1000 }
        assertEquals(listOf("booting-not-created-yet"), ids(value))
        assertEquals("true", value.getValue("instances").jsonArray[0].jsonObject.getValue("countedInTarget").jsonPrimitive.content)
    }
}
