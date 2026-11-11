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

    private fun run(store: Store, sha: String, value: Double = 100.0, failed: Boolean = false, finish: Boolean = true, cpu: String = "fixture"): Long {
        val experiment = spec.copy(name = "${spec.name}@$sha", base = mapOf("ref" to sha))
        val id = store.create(experiment.name, sha, emptyList(), experiment = experiment)
        val problems = listOf("dot-64", "dot-4096").map { name -> BenchRuns.problem(BenchCase(name, parameters = mapOf("row" to name.replace('-', '+') + "+uniform"),
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
                    details = buildJsonObject { put("benchmarkControls", buildJsonObject { put("java", "25"); put("cpu", cpu) }) },
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
            assertEquals(first, runs.last().benchmark!!.baselineJob)
            assertEquals(listOf(BenchmarkChange("dot-4096", "failed", null, "unconfirmed"),
                BenchmarkChange("dot-64", "regressed", "median_ns", "unconfirmed")), runs.last().benchmark!!.transitions)
            assertEquals(listOf(Triple("dot-64", "Dense Level 1", "success"),
                Triple("dot-4096", "Dense Level 1", "failed")), runs.last().benchmark!!.workloads.map { Triple(it.caseId, it.theme, it.status) })
        }
        TrendCache(TrendPoints.of(store)).use { cache ->
            val view = trendView(store, cache, "koblas-sweep", false)
            assertEquals(listOf(first, second), view.shown.map { it.job })
            assertEquals(0, view.computing)
            val html = trendPage(view.name, view.shown, "https://github.com/Eignex/koblas").toString()
            assertTrue("Performance vs baseline" in html && "Performance by theme" in html)
            assertTrue("Regressed workloads · JVM SIMD</h2>" in html)
            assertTrue("Performance vs baseline · JVM SIMD</h2>" in html)
            assertTrue("Performance by theme · JVM SIMD</h2>" in html)
            assertTrue("Case timings" in html && "benchmark-case-head" in html && "benchmark-case-rows" in html)
            assertTrue("Fast historical percentile" in html && "Best observed" in html)
            assertTrue("Performance vs baseline" in html && "class=\"trend-charts benchmark-overview\"" in html)
            assertTrue("data-benchmark-target" in html && "data-koblas=\"true\"" in html)
            assertFalse("benchmark-workloads" in html)
            assertFalse("<select" in html || "benchmark-detail" in html)
            assertFalse("Latest changes" in html || "benchmark-changes" in html)
            assertFalse("chart-benchmark-status" in html || "<h2>Correctness</h2>" in html)
            assertTrue("Dense Level 1" in html && "benchmark-themes" in html)
            assertTrue("1 failures, 0 recoveries; 1 metric regressions" in html)
            assertTrue("/jobs/$second" in html && "dot-4096" in html && "ns/op" in html)
            assertFalse("Solved and proven" in html || "PAR-2 time" in html || "No run of this schedule" in html)
        }
        assertTrue(store.schedules.launchNow(schedule))
        assertTrue(store.schedules.all().single().launchRequested)
    }

    @Test fun `changed benchmark controls do not produce an overview comparison`() {
        val store = Store(Files.createTempDirectory("benchmark-overview-controls").resolve("lab.db"))
        run(store, "a".repeat(40))
        run(store, "b".repeat(40), value = 200.0, cpu = "another cpu")

        val last = Trend.runs(TrendData.of(store), spec.name).last().benchmark!!

        assertNull(last.baselineJob)
        assertNull(last.changes)
        assertTrue(last.transitions.isEmpty())
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

    @Test fun `historical Koblas workloads cover six themes including cases without measurements`() {
        val groups = mapOf(
            "Dense Level 1" to "dot axpy scal nrm2 asum iamax rot swap copy sum",
            "Dense Level 2" to "gemv symv ger syr syr2 trsv trmv ger-window-shrinking",
            "Dense Level 3" to "gemm gemmt symm syrk syr2k trsm trmm",
            "Sparse Level 1" to "spdot-raw spdot-sparse spdot-column spaxpy-raw spscatter-raw spgather spgather-zero",
            "Sparse Level 2" to "spgemv spsymv sptrsv sptrmv",
            "Sparse Level 3" to "spsymm spmm spsyrk-dense sptrsm sptrmm")
        val cases = groups.flatMap { (theme, operations) -> operations.split(' ').map { operation ->
            val plan = BenchCase("comparison/owned-mac-arm64/$operation/$operation+64+uniform")
            val case = CaseResult(0, Status.FAILED, BenchRuns.problem(plan), "base", null, null)
            case to theme
        } }

        val summary = BenchmarkTrends.summarize(cases.map { it.first }, "Eignex/koblas")

        assertEquals(41, summary.failed)
        assertTrue(summary.metrics.isEmpty())
        assertEquals(cases.map { it.second }, summary.workloads.map { it.theme })
        assertTrue(summary.workloads.all { it.status == "failed" })
        assertTrue(BenchmarkTrends.summarize(cases.map { it.first }, "Eignex/other").workloads.all { it.theme == null })
    }

    @Test fun `benchmark themes retain pending and skipped cases and honor declared groups`() {
        val plan = BenchCase("custom", attributes = mapOf("theme" to "Custom theme"))
        val pending = CaseResult(0, Status.RUNNING, BenchRuns.problem(plan), "base", null, null)
        val skipped = pending.copy(status = Status.DONE, record = BenchProtocol.json.encodeToJsonElement(
            BenchResult.serializer(), BenchResult(plan.id, BenchStatus.SKIPPED, message = "unsupported fixture")))

        assertEquals(listOf(BenchmarkWorkload("custom", "Custom theme", "pending")),
            BenchmarkTrends.summarize(listOf(pending), "Eignex/other").workloads)
        assertEquals(listOf(BenchmarkWorkload("custom", "Custom theme", "skipped")),
            BenchmarkTrends.summarize(listOf(skipped), "Eignex/other").workloads)
    }

    @Test fun `overview migration refreshes benchmark points without invalidating solver points`() {
        val store = Store(Files.createTempDirectory("benchmark-overview-upgrade").resolve("lab.db"))
        val benchmark = store.job(run(store, "a".repeat(40)))!!
        val solver = store.job(store.create("solver", "main", emptyList(), experiment = ExperimentSpec("solver")))!!
        val oldBenchmark = TrendPoints.Point(TrendCache.version(benchmark).replace("format-6", "format-4"),
            null, System.currentTimeMillis(), null, emptySet())
        val solverPoint = TrendPoints.Point(TrendCache.version(solver), null, System.currentTimeMillis(), null, emptySet())

        TrendCache().use { cache ->
            assertTrue(cache.expired(benchmark, oldBenchmark, null))
            assertFalse(cache.expired(solver, solverPoint, null))
        }
        assertTrue("format-4" in solverPoint.version)
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
        assertTrue("Performance vs baseline" in page && "/jobs/$job" in page)
        assertFalse("No run of this schedule" in page)
        assertEquals(HttpStatusCode.Accepted, client.post("/schedules/$schedule/launch").status)
        assertTrue(store.schedules.all().single().launchRequested)
    }

    @Test fun `confirmation details distinguish agreement from an unselected workload`() {
        fun problem(id: String) = BenchRuns.problem(com.eignex.lab.bench.BenchCase(id,
            attributes = mapOf("regressionMetric" to "time")))
        val selected = problem("selected")
        val omitted = problem("omitted")
        val flips = Flips(emptyList(), emptyList(), metricLost = listOf(selected, omitted))
        val confirmed = Flips(emptyList(), emptyList(), metricLost = listOf(selected))
        val cases = listOf(CaseResult(0, Status.DONE, selected, "after", null, null))

        val changes = BenchmarkTrends.transitions(flips, confirmed, Status.DONE, cases)

        assertEquals(listOf("confirmed", "not selected"), changes.map { it.confirmation })
        assertEquals("not confirmed", BenchmarkTrends.transitions(flips, Flips(emptyList(), emptyList()), Status.DONE, cases).first().confirmation)
        assertEquals("pending", BenchmarkTrends.transitions(flips, null, Status.RUNNING, emptyList()).first().confirmation)
    }

    @Test fun `engine attribution keeps the requested engine separate from its measured provider`() {
        val plan = BenchCase("paired", parameters = mapOf("row" to "dot+64+uniform"),
            attributes = mapOf("regressionThreshold" to "0.15"))
        val record = BenchResult(plan.id, BenchStatus.SUCCESS,
            metrics = listOf(BenchMetric("median_ns/jvm-simd", 12.0, "ns/op", MetricDirection.LOWER)),
            details = buildJsonObject {
                putJsonObject("benchmarkControls") { put("cpu", "fixture") }
                putJsonObject("metricAttribution") {
                    putJsonObject("median_ns/jvm-simd") {
                        put("engine", "jvm-simd"); put("provider", "scalar"); put("kernel", "scalar/dot")
                    }
                }
                putJsonObject("engineStatuses") { put("jvm-simd", "success"); put("native", "unsupported") }
            })
        val case = CaseResult(0, Status.DONE, BenchRuns.problem(plan), "base",
            record = BenchProtocol.json.encodeToJsonElement(record))

        val summary = BenchmarkTrends.summarize(listOf(case), "Eignex/koblas")

        assertEquals("jvm-simd", summary.metrics.single().engine)
        assertEquals("scalar", summary.metrics.single().provider)
        assertEquals("scalar/dot", summary.metrics.single().kernel)
        assertEquals("dot+64+uniform", summary.workloads.single().workload)
        assertEquals(0.15, summary.workloads.single().threshold)
        assertNotNull(summary.workloads.single().controls)
        assertEquals("unsupported", summary.workloads.single().engines["native"])
    }
}
