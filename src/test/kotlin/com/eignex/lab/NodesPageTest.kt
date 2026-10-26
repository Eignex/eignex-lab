package com.eignex.lab

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

class NodesPageTest {
    private val host = HostReport("Mac/Arm64", 10, "java", "engine", true, emptyList())
    private val pool = CapacityPool("avx512", "linux-avx512-spot", 16, 65536, 8, setOf("arch:x86_64", "simd:avx512"))
    private val arm = CapacityPool("arm", "linux-arm-spot", 8, 32768, 8, setOf("arch:arm64", "simd:neon"))
    private val settings = CapacitySettings(listOf(pool, arm), 64)
    private fun config(): Config {
        val root = Files.createTempDirectory("nodes-page")
        val path = root.resolve("capacity.json")
        Files.writeString(path, Json.encodeToString(settings))
        return Config(dataDir = root, passwordHash = null, executionBackend = ExecutionBackendKind.NOMAD, nomadCapacitySettings = path)
    }

    @Test fun `nodes tab shows hardware allocations and expiring requests with a fresh report`() = testApplication {
        val config = config()
        val store = Store(config.database)
        val lease = store.execution.requestCapacity(pool.id, 1, 60000)
        val reservation = CapacityReservation("allocation<script>", 42, ResourceBudget(2000, 4096))
        val report = CapacitySnapshot(now(), 0, CapacityDecision(mapOf(pool.id to 1), emptyList(), emptyList(), emptyMap()),
            listOf(CapacityNode("avx512:instance", pool.id, CapacityNodeState.READY, listOf(reservation))), emptyMap(), settings.pools)
        Files.createDirectories(config.nomadCapacityReport.parent)
        Files.writeString(config.nomadCapacityReport, Json.encodeToString(report))
        application { api(config, store, host) }

        val page = client.get("/nodes").bodyAsText()
        assertContains(page, "href=\"/nodes\" class=\"on\" aria-current=\"page\">Nodes")
        assertContains(page, "Mac/Arm64")
        assertContains(page, "16 vCPU / 65536 MiB")
        assertContains(page, "simd:avx512")
        assertContains(page, "data-limit=\"4\"")
        assertContains(page, "avx512:instance")
        assertContains(page, "href=\"/jobs/42\">job 42")
        assertContains(page, "12.0 vCPU / 60416 MiB")
        assertContains(page, "allocation&lt;script&gt;")
        assertContains(page, "data-release-capacity=\"${lease.id}\"")
        assertContains(page, "setInterval(refresh, 10000)")
        assertContains(client.get("/").bodyAsText(), "href=\"/nodes\">Nodes")
        val alias = client.config { followRedirects = false }.get("/capacity/view")
        assertEquals(HttpStatusCode.Found, alias.status)
        assertEquals("/nodes", alias.headers[HttpHeaders.Location])
    }

    @Test fun `manual starts select the exact pool respect limits and can be released`() = testApplication {
        val config = config()
        val store = Store(config.database)
        application { api(config, store, host) }

        val page = client.get("/nodes").bodyAsText()
        assertContains(page, "Capacity has not reported")
        assertContains(page, "id=\"node-request\"")
        assertContains(page, "value=\"arm\"")
        val response = client.post("/capacity/leases") {
            contentType(ContentType.Application.Json)
            setBody("""{"pool":"arm","nodes":2,"ttlSec":900}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        val lease = store.execution.capacityLeases().single()
        assertEquals(arm.id, lease.pool)
        assertEquals(2, lease.nodes)
        assertTrue(lease.expiresAt in now() + 890000..now() + 900000)
        for (body in listOf(
            """{"pool":"avx512","nodes":5}""",
            """{"pool":"unknown","nodes":1}""",
            """{"pool":"arm","nodes":0}""",
            """{"pool":"arm","nodes":1,"ttlSec":3601}""",
        )) assertEquals(HttpStatusCode.BadRequest, client.post("/capacity/leases") {
            contentType(ContentType.Application.Json); setBody(body)
        }.status)
        assertEquals(listOf(lease), store.execution.capacityLeases())
        assertEquals(HttpStatusCode.Forbidden, client.delete("/capacity/leases/${lease.id}") { header("Sec-Fetch-Site", "cross-site") }.status)
        assertEquals(listOf(lease), store.execution.capacityLeases())
        assertEquals(HttpStatusCode.NoContent, client.delete("/capacity/leases/${lease.id}").status)
        assertTrue(store.execution.capacityLeases().isEmpty())
        assertContains(client.get("/nodes").bodyAsText(), "No active requests.")
    }

    @Test fun `nodes and manual capacity are protected by the operator password`() = testApplication {
        val config = config().copy(passwordHash = Password.hash("fixture-password"))
        val store = Store(config.database)
        application { api(config, store, host) }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/nodes").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/capacity/leases") {
            contentType(ContentType.Application.Json); setBody("""{"pool":"arm","nodes":1}""")
        }.status)
        assertTrue(store.execution.capacityLeases().isEmpty())
        val response = client.get("/nodes") { basicAuth("lab", "fixture-password") }
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
