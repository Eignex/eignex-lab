package com.eignex.lab.analysis

import com.eignex.lab.bench.*
import com.eignex.lab.domain.*
import kotlinx.serialization.json.JsonObject

/** Opt-in benchmark transitions, separate from solver feasibility, proof and objective claims. */
internal object BenchTransitions {
    fun enabled(cases: List<CaseResult>) = cases.any { it.problem.benchCase?.attributes?.get("regressionPolicy") == "metric-v1" }

    fun flips(before: List<CaseResult>, after: List<CaseResult>): Flips {
        val failed = mutableListOf<Problem>()
        val recovered = mutableListOf<Problem>()
        val slower = mutableListOf<MetricChange>()
        val faster = mutableListOf<MetricChange>()
        val a = before.groupBy(ComparisonControls::problem)
        val b = after.groupBy(ComparisonControls::problem)
        for (key in a.keys.intersect(b.keys)) {
            val left = a.getValue(key).groupBy(ComparisonControls::block)
            val right = b.getValue(key).groupBy(ComparisonControls::block)
            val blocks = left.keys + right.keys
            var failures = 0; var recoveries = 0
            val losses = mutableMapOf<String, Int>(); val gains = mutableMapOf<String, Int>()
            for (block in blocks) {
                val x = left[block]?.singleOrNull() ?: continue
                val y = right[block]?.singleOrNull() ?: continue
                val cx = x.problem.benchCase ?: continue
                val cy = y.problem.benchCase ?: continue
                if (cx.attributes["regressionPolicy"] != "metric-v1" || cx != cy ||
                    cx.attributes["recordSchema"] == "solver-v1" || x.instrumented || y.instrumented) continue
                if (cx.attributes["labInputDigest"].isNullOrBlank()) continue
                val threshold = cx.attributes["regressionThreshold"]?.toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it > 0 && it < 1 } ?: continue
                val metrics = cx.attributes["regressionMetrics"]?.split(',')?.filter { it.isNotBlank() }
                    ?: listOfNotNull(cx.attributes["regressionMetric"])
                if (metrics.isEmpty()) continue
                val rx = result(x) ?: continue
                val ry = result(y) ?: continue
                if (rx.caseId != cx.id || ry.caseId != cy.id) continue
                val controls = rx.details?.get("benchmarkControls") as? JsonObject ?: continue
                if (controls.isEmpty() || controls != ry.details?.get("benchmarkControls")) continue
                // A confirmation vote belongs to one accepted same-machine worker attempt.
                if (x.origin?.job == y.origin?.job) {
                    if (x.origin == null || x.origin.attempt.isNullOrBlank() || x.origin != y.origin ||
                        x.host == null || x.host != y.host) continue
                }
                fun valid(c: CaseResult, r: BenchResult) = when (r.status) {
                    BenchStatus.SUCCESS -> c.status == Status.DONE
                    BenchStatus.FAILED -> c.status == Status.FAILED
                    BenchStatus.SKIPPED -> false
                }
                if (!valid(x, rx) || !valid(y, ry)) continue
                if (rx.status == BenchStatus.SUCCESS && ry.status == BenchStatus.FAILED) failures++
                if (rx.status == BenchStatus.FAILED && ry.status == BenchStatus.SUCCESS) recoveries++
                if (rx.status != BenchStatus.SUCCESS || ry.status != BenchStatus.SUCCESS) continue
                for (metric in metrics.distinct()) {
                    val mx = rx.metrics.singleOrNull { it.name == metric } ?: continue
                    val my = ry.metrics.singleOrNull { it.name == metric } ?: continue
                    if (mx.unit != my.unit || mx.direction != my.direction || mx.direction == MetricDirection.NONE ||
                        !mx.value.isFinite() || !my.value.isFinite() || mx.value <= 0 || my.value <= 0) continue
                    val ratio = if (mx.direction == MetricDirection.LOWER) my.value / mx.value else mx.value / my.value
                    if (ratio > 1 + threshold) losses[metric] = losses.getOrDefault(metric, 0) + 1
                    if (ratio < 1 / (1 + threshold)) gains[metric] = gains.getOrDefault(metric, 0) + 1
                }
            }
            val problem = b.getValue(key).first().problem
            val paired = (a.getValue(key) + b.getValue(key)).mapNotNull { it.origin?.job }.distinct().size == 1
            val denominator = maxOf(blocks.size, if (paired) Confirm.REPEATS else 1)
            if (failures > denominator / 2) failed += problem
            if (recoveries > denominator / 2) recovered += problem
            slower += losses.filterValues { it > denominator / 2 }.keys.map { MetricChange(problem, it) }
            faster += gains.filterValues { it > denominator / 2 }.keys.map { MetricChange(problem, it) }
        }
        return Flips(emptyList(), emptyList(), benchmarkFailed = failed, benchmarkRecovered = recovered,
            metricLost = slower.map { it.problem }.distinct(), metricGained = faster.map { it.problem }.distinct(),
            metricRegressions = slower, metricImprovements = faster)
    }

    private fun result(case: CaseResult): BenchResult? = case.record?.let {
        runCatching { BenchProtocol.json.decodeFromJsonElement(BenchResult.serializer(), it) }.getOrNull()
    }
}
