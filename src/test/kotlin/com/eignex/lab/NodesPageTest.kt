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
    private val host = HostReport("Mac/Arm64", 10, "java")
    private val pool = CapacityPool("avx512", "linux-avx512-spot", 16, 65536, 8, setOf("arch:x86_64", "simd:avx512"))
    private val arm = CapacityPool("arm", "linux-arm-spot", 8, 32768, 8, setOf("arch:arm64", "simd:neon"))
    private val settings = CapacitySettings(listOf(pool, arm), 64)
    private fun config(): Config {
        val root = Files.createTempDirectory("nodes-page")
        val path = root.resolve("capacity.json")
        Files.writeString(path, Json.encodeToString(settings))
        return Config(dataDir = root, auth = Auth.Disabled, nomadCapacitySettings = path)
    }

    @Test fun `journal status exposes bounded usage retained errors and stale reports without leaking markup`() = testApplication {
        val config = config(); val store = Store(config.database)
        val path = config.dataDir.resolve("nomad/result-journal-status.json"); Files.createDirectories(path.parent)
        val report = ResultJournalReport(now() - 30_000,
            ResultJournal.Status(2, 125L * 1024 * 1024, 128L * 1024 * 1024, "failure<script>", 90_000, 3))
        Files.writeString(path, Json.encodeToString(report))
        application { api(config, store, host) }
        val page = client.get("/nodes").bodyAsText()
        assertContains(page, "2 pending batches"); assertContains(page, "oldest 90s")
        assertContains(page, "3 drain errors"); assertContains(page, "failure&lt;script&gt;")
        assertContains(page, "report is stale"); assertContains(page, "near capacity")
        val health = Json.parseToJsonElement(client.get("/health").bodyAsText()).toString()
        assertContains(health, "resultJournal"); assertContains(health, "oldestAgeMs")
        val status = Json.decodeFromString<ResultJournalReport>(client.get("/journal").bodyAsText())
        assertTrue(status.stale); assertEquals(2, status.status.batches)
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
        assertContains(page, "x86_64 / AVX-512")
        assertContains(page, "data-limit=\"4\"")
        assertContains(page, "avx512:instance")
        assertContains(page, "href=\"/jobs/42\">job 42")
        assertContains(page, "14.0 vCPU / 60416 MiB")
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
        val config = config().copy(auth = Auth.Required(Password.hash("fixture-password")))
        val store = Store(config.database)
        application { api(config, store, host) }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/nodes").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/journal").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/capacity/leases") {
            contentType(ContentType.Application.Json); setBody("""{"pool":"arm","nodes":1}""")
        }.status)
        assertTrue(store.execution.capacityLeases().isEmpty())
        val response = client.get("/nodes") { basicAuth("lab", "fixture-password") }
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
