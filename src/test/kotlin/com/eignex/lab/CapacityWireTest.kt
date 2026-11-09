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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapacityWireTest {
    @Test
    fun `the coordinator speaks the wire format deploy capacity-wire json holds`() {
        val wire = Json.parseToJsonElement(Files.readString(Path.of("deploy/capacity-wire.json"))).jsonObject
        assertEquals(wire.getValue("grantDomain").jsonPrimitive.content, CapacityWire.GRANT_DOMAIN)
        assertEquals(wire.getValue("grantSchemaVersion").jsonPrimitive.int, CapacityWire.GRANT_SCHEMA_VERSION)
        assertEquals(wire.getValue("startupGrantSchemaVersion").jsonPrimitive.int, CapacityWire.STARTUP_GRANT_SCHEMA_VERSION)
        assertEquals(wire.getValue("startupGraceMs").jsonPrimitive.long, CapacityWire.STARTUP_GRACE_MS)
        assertEquals(wire.getValue("grantFreshMs").jsonPrimitive.long, CapacityWire.FRESH_MS)
        assertEquals(wire.getValue("grantClockSkewMs").jsonPrimitive.long, CapacityWire.CLOCK_SKEW_MS)
        assertEquals(wire.getValue("desiredMetric").jsonPrimitive.content, CapacityWire.DESIRED_METRIC)
        assertEquals(wire.getValue("capacityIdMeta").jsonPrimitive.content, CapacityWire.CAPACITY_ID_META)
    }

    @Test
    fun `the exported contract is each pool's identity, size and limit`() {
        val settings = CapacitySettings.read(Path.of("deploy/nomad/capacity-architectures.example.json"))
        val contract = CapacityContract.of(settings)
        assertEquals(settings.pools.map { it.id }, contract.pools.map { it.id })
        val first = settings.pools.first()
        assertEquals(CapacityContract.Pool(first.id, first.nodeClass, first.maxNodes, first.vcpus, first.memoryMiB, first.market), contract.pools.first())
        assertEquals(CapacityContract.SCHEMA_VERSION, contract.schemaVersion)
    }

    @Test
    fun `cloud hosts arm their lifetime protection before secret retrieval on both providers`() {
        for (provider in listOf("aws", "gcp")) {
            val text = Files.readString(Path.of("deploy/infra/$provider/user-data.sh.tftpl"))
            val armed = text.indexOf("systemctl enable --now lab-lifetime.timer")
            assertTrue(armed >= 0, provider)
            assertTrue(armed < text.indexOf("mkdir -p"), provider)
            assertTrue(armed < text.indexOf("exec "), provider)
        }
    }
}
