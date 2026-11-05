package com.eignex.lab

import com.eignex.lab.capacity.CapacitySnapshot
import com.eignex.lab.analysis.TrendRun
import com.eignex.lab.dev.LocalUi
import com.eignex.lab.domain.CapacityNodeState
import com.eignex.lab.domain.ManualCapacityLease
import com.eignex.lab.domain.Status
import com.eignex.lab.store.requestCancel
import com.eignex.lab.web.api
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

class LocalUiTest {
    @Test
    fun `local preview serves populated real pages and sample artifacts without authentication`() = testApplication {
        LocalUi(Files.createTempDirectory("local-ui")).use { lab ->
            application { api(lab.services) }
            val queue = client.queuePage()
            assertContains(queue, "preview-comparison")
            assertContains(queue, "preview-queued")
            assertContains(queue, "preview-failed")
            for (path in listOf("/nodes", "/references", "/trend?name=preview-sweep")) {
                val page = client.get(path) { header(HttpHeaders.Accept, "text/html") }
                assertEquals(HttpStatusCode.OK, page.status, path)
                assertContains(page.bodyAsText(), "<main id=\"content\">", message = path)
            }
            val bench = lab.store.jobs(name = "preview-bench").single()
            val benchPage = client.get("/jobs/${bench.id}") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
            assertContains(benchPage, "mixed provenance")
            assertContains(benchPage, "data-result-limit=\"25\"")
            assertContains(benchPage, "id=\"case-287\"")
            assertContains(benchPage, "How to read these results")
            assertContains(benchPage, "id=\"job-details\"")
            assertContains(client.get("/jobs/${bench.id}/files/cases/0/out/report/solve-record.json").bodyAsText(), "Synthetic local preview artifact")
            val trend = client.get("/trend?name=preview-sweep").bodyAsText()
            assertTrue(Json.parseToJsonElement(trend).toString().contains("preview"))
            assertFalse(trend == "[]", "regression history is precomputed at startup")
            val runs = Json.decodeFromString<List<TrendRun>>(trend)
            assertEquals(10, runs.size)
            for (run in runs) {
                assertEquals(96, run.problems)
                assertEquals(12, run.themes.size)
                assertTrue(run.themes.keys.all { it.isNotBlank() })
                assertTrue(run.themes.values.all { it.problems == 8 })
            }
            val active = lab.store.active().first { it.status == Status.RUNNING }
            val execution = client.get("/jobs/${active.id}/execution") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
            assertContains(execution, "allocation-lab-")
            assertContains(client.get("/jobs/${active.id}/files/job.log").bodyAsText(), "No worker or solver is executed")
            assertEquals(lab.root.resolve("corpus"), lab.config.corpusDir)
            assertEquals(0L, lab.config.updateCheckSec)
            assertFalse(lab.config.requireDocker)
        }
    }

    @Test
    fun `capacity requests boot simulated nodes and releasing them retires idle nodes`() = testApplication {
        var at = System.currentTimeMillis()
        LocalUi(Files.createTempDirectory("local-ui-capacity"), clock = { at }).use { lab ->
            application { api(lab.services) }
            // Keep this scenario about idle capacity rather than queued synthetic workloads.
            lab.store.active().forEach { lab.store.requestCancel(it.id) }
            lab.tick()
            val created = client.post("/capacity/leases") {
                contentType(ContentType.Application.Json)
                setBody("""{"pool":"arm-spot","nodes":2,"ttlSec":900}""")
            }
            assertEquals(HttpStatusCode.Accepted, created.status)
            val lease = Json.decodeFromString<ManualCapacityLease>(created.bodyAsText())
            at += 1000; lab.tick()
            at += 1000; lab.tick()
            var snapshot = Json.decodeFromString<CapacitySnapshot>(client.get("/capacity").bodyAsText())
            assertEquals(2, snapshot.decision.desired["arm-spot"])
            assertEquals(2, snapshot.nodes.count { it.pool == "arm-spot" && it.state == CapacityNodeState.BOOTING })
            at += 4000; lab.tick()
            snapshot = Json.decodeFromString(client.get("/capacity").bodyAsText())
            assertEquals(2, snapshot.nodes.count { it.pool == "arm-spot" && it.state == CapacityNodeState.READY })
            assertEquals(HttpStatusCode.NoContent, client.delete("/capacity/leases/${lease.id}").status)
            repeat(3) { at += 2000; lab.tick() }
            snapshot = Json.decodeFromString(client.get("/capacity").bodyAsText())
            assertEquals(0, snapshot.nodes.count { it.pool == "arm-spot" })
            assertEquals(1, snapshot.nodes.count { it.pool == "general-spot" }, "one warm node remains")
        }
    }

    @Test
    fun `job controls pause paired progress and cancel retry through the real API`() = testApplication {
        var at = System.currentTimeMillis()
        LocalUi(Files.createTempDirectory("local-ui-controls"), clock = { at }).use { lab ->
            application { api(lab.services) }
            val job = lab.store.active().first { it.status == Status.RUNNING }
            lab.store.active().filter { it.id != job.id }.forEach { lab.store.requestCancel(it.id) }
            assertEquals(HttpStatusCode.OK, client.post("/jobs/${job.id}/pause").status)
            at += 4000; lab.tick()
            assertEquals(job.done, lab.store.job(job.id)!!.done)
            assertEquals(HttpStatusCode.OK, client.post("/jobs/${job.id}/resume").status)
            at += 4000; lab.tick()
            assertEquals(job.done + 2, lab.store.job(job.id)!!.done, "both arms of one paired row complete")
            assertEquals(HttpStatusCode.OK, client.post("/jobs/${job.id}/cancel").status)
            lab.tick()
            assertEquals(Status.CANCELLED, lab.store.job(job.id)!!.status)
            assertTrue(lab.store.execution.sessions(job.id).all { session -> lab.nomad.allocations(session.nomadJobId).none { it.active } })
            assertEquals(HttpStatusCode.OK, client.post("/jobs/${job.id}/retry").status)
            at += 4000; lab.tick()
            assertEquals(Status.RUNNING, lab.store.job(job.id)!!.status)
            assertEquals(job.done + 4, lab.store.job(job.id)!!.done)
        }
    }
}
