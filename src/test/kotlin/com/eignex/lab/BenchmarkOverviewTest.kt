package com.eignex.lab

import com.eignex.lab.analysis.*
import com.eignex.lab.bench.MetricDirection
import com.eignex.lab.domain.*
import com.eignex.lab.execution.BenchRuns
import com.eignex.lab.web.*
import kotlin.test.*

class BenchmarkOverviewTest {
    private val zero = Estimate(0.0, 0.0, 0.0)
    private fun metric(case: String, value: Double, direction: MetricDirection = MetricDirection.LOWER) =
        TrendMetric(case, "time", "ns/op", direction, value)
    private fun run(job: Long, benchmark: BenchmarkTrend, finished: Boolean = true) = TrendRun(job, "a".repeat(40),
        "spec", at = job, finished = finished, cases = benchmark.workloads.size, problems = benchmark.workloads.size,
        solved = zero, proven = 0.0, unsupported = 0, errors = benchmark.failed, par2 = zero, suites = emptyMap(), benchmark = benchmark)

    @Test fun `overview exposes affected workloads with failures and worst regressions first`() {
        val before = BenchmarkTrend(4, 0, 0, 0,
            listOf(metric("smaller", 100.0), metric("larger", 100.0), metric("broken", 300.0), metric("better", 100.0)))
        val after = before.copy(succeeded = 3, failed = 1,
            metrics = listOf(metric("smaller", 120.0), metric("larger", 200.0), metric("better", 80.0)), baselineJob = 1,
            changes = BenchmarkChanges(1, 0, 2, 1), transitions = listOf(
                BenchmarkChange("smaller", "regressed", "time", "pending"),
                BenchmarkChange("larger", "regressed", "time", "confirmed"),
                BenchmarkChange("broken", "failed", "time", "not selected"),
                BenchmarkChange("better", "improved", "time", "not confirmed")))

        val html = benchmarkOverview(listOf(run(1, before), run(2, after))).toString()

        assertTrue(html.indexOf("data-benchmark-case=\"broken\"") < html.indexOf("data-benchmark-case=\"larger\""))
        assertTrue(html.indexOf("data-benchmark-case=\"larger\"") < html.indexOf("data-benchmark-case=\"smaller\""))
        assertTrue("+100.0%" in html && "+20.0%" in html)
        assertTrue("300 ns/op" in html, "a failure must retain its earlier timing")
        assertTrue("Confirmed" in html && "Pending" in html && "Not selected" in html)
        assertTrue("<details><summary>Improvements and recoveries (1)" in html)
        assertFalse("<select" in html)
    }

    @Test fun `overview distinguishes a new baseline from a run without flagged regressions`() {
        val baseline = BenchmarkTrend(1, 0, 0, 0, listOf(metric("dot", 100.0)))

        val first = benchmarkOverview(listOf(run(1, baseline))).toString()
        val next = benchmarkOverview(listOf(run(1, baseline), run(2, baseline.copy(baselineJob = 1,
            changes = BenchmarkChanges(0, 0, 0, 0))))).toString()

        assertTrue("No comparable baseline yet" in first)
        assertFalse("No flagged regressions" in first)
        assertTrue("No flagged regressions or failed workloads" in next)
    }

    @Test fun `old cached counts do not appear as a clean comparison while details refresh`() {
        val benchmark = BenchmarkTrend(1, 0, 0, 0, listOf(metric("dot", 200.0)),
            changes = BenchmarkChanges(0, 0, 1, 0))

        val html = benchmarkOverview(listOf(run(2, benchmark))).toString()

        assertTrue("details are being refreshed" in html)
        assertFalse("No flagged regressions" in html)
    }

    @Test fun `an unfinished run does not replace the completed overview`() {
        val benchmark = BenchmarkTrend(1, 0, 0, 0, listOf(metric("dot", 100.0)))

        val html = benchmarkOverview(listOf(run(1, benchmark), run(2, benchmark, finished = false))).toString()

        assertTrue("#2</a> is still running" in html)
        assertTrue("Run <a href=\"/jobs/1\">#1</a>" in html)
    }

    @Test fun `workload names and units are escaped in the overview`() {
        val case = "<img src=x onerror=alert(1)>"
        val before = BenchmarkTrend(1, 0, 0, 0, listOf(metric(case, 100.0)))
        val after = before.copy(metrics = listOf(metric(case, 200.0)), baselineJob = 1,
            changes = BenchmarkChanges(0, 0, 1, 0), transitions = listOf(BenchmarkChange(case, "regressed", "time", "unconfirmed")))

        val html = benchmarkOverview(listOf(run(1, before), run(2, after))).toString()

        assertFalse("<img" in html)
        assertTrue("&lt;img" in html)
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
}
