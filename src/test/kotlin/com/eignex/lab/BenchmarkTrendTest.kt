package com.eignex.lab

import com.eignex.lab.analysis.*
import com.eignex.lab.bench.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.store.*
import com.eignex.lab.web.*
import java.nio.file.Files
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlin.test.*

class BenchmarkTrendTest {
    private val spec = ExperimentSpec("koblas-status-sweep", priority = 98, repository = "Eignex/koblas",
        bench = BenchSpec("bench.json", runTimeoutSec = 600))

    private fun run(store: Store, sha: String, value: Double = 100.0, failed: Boolean = false, finish: Boolean = true): Long {
        val experiment = spec.copy(name = "${spec.name}@$sha", base = mapOf("ref" to sha))
        val id = store.create(experiment.name, sha, emptyList(), experiment = experiment)
        val problems = listOf("dot-64", "dot-4096").map { name -> BenchRuns.problem(BenchCase(name,
            attributes = mapOf("regressionPolicy" to "metric-v1", "regressionMetric" to "median_ns",
                "regressionThreshold" to "0.15", "labInputDigest" to "a".repeat(64)))) }
        store.plan(id, Experiments.arms(experiment).map { PlannedArm(it, sha) }, problems,
            Experiments.cases(2, 1, emptyList()), problems.map { "true" to 60L })
        store.setup(id, sha)
        problems.forEachIndexed { index, problem ->
            if (finish || index == 0) {
                val status = if (failed && index == 1) BenchStatus.FAILED else BenchStatus.SUCCESS
                val result = BenchResult(problem.problem, status, metrics = if (status == BenchStatus.SUCCESS)
                    listOf(BenchMetric("median_ns", value * (index + 1), "ns/op", MetricDirection.LOWER)) else emptyList(),
                    details = buildJsonObject { put("benchmarkControls", buildJsonObject { put("java", "25"); put("cpu", "fixture") }) },
                    message = if (status == BenchStatus.FAILED) "wrong numerical result" else null)
                store.caseRecord(id, index, BenchProtocol.json.encodeToString(BenchResult.serializer(), result))
                store.commandFinished(id, index, if (status == BenchStatus.FAILED) 1 else 0)
            }
        }
        if (finish) store.finish(id, if (failed) Status.FAILED else Status.DONE)
        return id
    }

    @Test fun `stored benchmark history remains visible after rename with per workload timings and transitions`() {
        val store = Store(Files.createTempDirectory("benchmark-trend").resolve("lab.db"))
        val schedule = store.schedules.create(spec.name, "main", spec, 120)
        val first = run(store, "a".repeat(40))
        val second = run(store, "b".repeat(40), value = 120.0, failed = true)
        store.schedules.ran(schedule, "b".repeat(40), second)
        assertTrue(store.schedules.rename(schedule, "koblas-sweep"))
        // Production reads the compact scoring records, then persists points for the page and later restarts.
        TrendCache(TrendPoints.of(store)).use { cache ->
            val runs = Trend.runs(TrendData.of(store), "koblas-sweep", cache)
            assertEquals(listOf(first, second), runs.map { it.job })
            assertEquals(listOf(100.0, 200.0), runs.first().benchmark!!.metrics.map { it.value })
            assertEquals(BenchmarkChanges(1, 0, 1, 0), runs.last().benchmark!!.changes)
        }
        TrendCache(TrendPoints.of(store)).use { cache ->
            val view = trendView(store, cache, "koblas-sweep", false)
            assertEquals(listOf(first, second), view.shown.map { it.job })
            assertEquals(0, view.computing)
            val html = trendPage(view.name, view.shown, "https://github.com/Eignex/koblas").toString()
            assertTrue("Workload timing" in html && "Correctness" in html)
            assertTrue("1 failures, 0 recoveries; 1 metric regressions" in html)
            assertTrue("/jobs/$second" in html && "dot-4096" in html && "ns/op" in html)
            assertFalse("Solved and proven" in html || "PAR-2 time" in html || "No run of this schedule" in html)
        }
        assertTrue(store.schedules.launchNow(schedule))
        assertTrue(store.schedules.all().single().launchRequested)
    }

    @Test fun `unfinished cases remain pending and failed cases do not fabricate timings`() {
        val store = Store(Files.createTempDirectory("benchmark-live").resolve("lab.db"))
        val pending = run(store, "a".repeat(40), finish = false)
        val first = Trend.runs(TrendData.of(store), spec.name).single()
        assertEquals(pending, first.job)
        assertEquals(1, first.benchmark!!.succeeded)
        assertEquals(1, first.benchmark.pending)
        assertEquals(1, first.benchmark.metrics.size)
        val failed = run(store, "b".repeat(40), failed = true)
        val last = Trend.runs(TrendData.of(store), spec.name).last()
        assertEquals(failed, last.job)
        assertEquals(1, last.benchmark!!.failed)
        assertEquals(listOf("dot-64"), last.benchmark.metrics.map { it.caseId })
    }

    @Test fun `old empty benchmark points are invalidated after deploying benchmark trends`() {
        val store = Store(Files.createTempDirectory("benchmark-upgrade").resolve("lab.db"))
        val job = run(store, "a".repeat(40))
        val points = TrendPoints.memory()
        val old = TrendPoints.Point("DONE/null/format-2", null, System.currentTimeMillis(), null, emptySet())
        points.save(job, old)
        TrendCache(points).use { cache ->
            assertTrue(cache.expired(store.job(job)!!, old, null))
            val missing = mutableListOf<Long>()
            val pointless = mutableListOf<Long>()
            Trend.stored(TrendData.of(store), spec.name, cache, missing = missing, pointless = pointless)
            assertEquals(listOf(job), missing)
            assertTrue(pointless.isEmpty(), "the page must keep polling while an empty old point is recomputed")
        }
    }

    @Test fun `launching a sweep keeps polling while its plan is still empty`() {
        val store = Store(Files.createTempDirectory("benchmark-launch").resolve("lab.db"))
        val experiment = spec.copy(name = "${spec.name}@aaa")
        val job = store.create(experiment.name, "aaa", emptyList(), experiment = experiment)
        TrendCache().use { cache ->
            cache.put(job, TrendCache.version(store.job(job)!!), null, null, emptySet())
            val missing = mutableListOf<Long>()
            Trend.stored(TrendData.of(store), spec.name, cache, missing = missing)
            assertEquals(listOf(job), missing)
            val page = trendPage(spec.name, emptyList(), "https://example.com", computing = missing.size).toString()
            assertTrue("/trend/progress" in page)
        }
    }

    @Test fun `renamed sweep HTTP page lists actual benchmark runs and launch action is accepted`() = io.ktor.server.testing.testApplication {
        val config = Config(dataDir = Files.createTempDirectory("benchmark-page"), auth = Auth.Disabled)
        val store = Store(config.database)
        val schedule = store.schedules.create(spec.name, "main", spec, 120)
        val job = run(store, "a".repeat(40))
        store.schedules.rename(schedule, "koblas-sweep")
        TrendCache(TrendPoints.of(store)).use { Trend.runs(TrendData.of(store), "koblas-sweep", it) }
        application { api(config, store, HostReport("fixture", 4, "java")) }
        val points = client.get("/trend?name=koblas-sweep").bodyAsText()
        assertTrue("\"job\":$job" in points && "median_ns" in points && "ns/op" in points)
        val page = client.get("/trend?name=koblas-sweep") { accept(ContentType.Text.Html) }.bodyAsText()
        assertTrue("Workload timing" in page && "/jobs/$job" in page)
        assertFalse("No run of this schedule" in page)
        assertEquals(HttpStatusCode.Accepted, client.post("/schedules/$schedule/launch").status)
        assertTrue(store.schedules.all().single().launchRequested)
    }
}
