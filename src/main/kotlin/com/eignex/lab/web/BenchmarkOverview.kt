package com.eignex.lab.web

import com.eignex.lab.analysis.*
import com.eignex.lab.bench.MetricDirection
import java.util.Locale
import java.text.NumberFormat

/** The latest qualified changes are visible together; individual charts are a drill down. */
internal fun benchmarkOverview(runs: List<TrendRun>): Html = buildHtml {
    val run = runs.lastOrNull { it.finished && it.benchmark != null }
    append(Html("<section class=\"chart-panel benchmark-overview\" aria-labelledby=\"benchmark-overview-title\"><h2 id=\"benchmark-overview-title\">Latest changes</h2>"))
    if (run == null) {
        append(Html("<p class=\"muted\">The overview appears when a benchmark run finishes.</p></section>"))
        return@buildHtml
    }
    val benchmark = run.benchmark!!
    val baseline = runs.find { it.job == benchmark.baselineJob }
    append(Html("<p class=\"chart-description\">Run <a href=\"/jobs/") + run.job + Html("\">#") + run.job + Html("</a>"))
    benchmark.baselineJob?.let { job ->
        append(Html(" compared with <a href=\"/jobs/") + job + Html("\">#") + job + Html("</a>"))
    }
    append(Html(". Changes use the sweep's measurement controls and regression threshold.</p>"))
    runs.lastOrNull()?.takeIf { !it.finished }?.let {
        append(Html("<p class=\"muted small\">Run <a href=\"/jobs/") + it.job + Html("\">#") + it.job + Html("</a> is still running; this overview uses the latest completed run.</p>"))
    }
    val changes = benchmark.changes
    append(Html("<div class=\"benchmark-counts\"><span><b>") + (changes?.regressed ?: "–") + Html("</b> metric regressions</span><span><b>") +
        benchmark.failed + Html("</b> failed workloads</span><span><b>") + (changes?.improved ?: "–") +
        Html("</b> improvements</span><span><b>") + (changes?.recovered ?: "–") + Html("</b> recoveries</span></div>"))
    val transitions = benchmark.transitions + benchmark.workloads.filter { workload ->
        workload.status == "failed" && benchmark.transitions.none { it.caseId == workload.caseId && it.kind == "failed" }
    }.map { BenchmarkChange(it.caseId, "failed", null, "unconfirmed") }
    val losses = transitions.filter { it.kind == "failed" || it.kind == "regressed" }
        .sortedWith(compareBy<BenchmarkChange> { it.kind != "failed" }.thenByDescending { change ->
            metricPair(change, baseline, run)?.let { (before, after) ->
                if (before.value <= 0 || after.value <= 0) 0.0
                else if (before.direction == MetricDirection.LOWER) after.value / before.value else before.value / after.value
            } ?: 0.0
        }.thenBy { it.caseId })
    if (changes == null) {
        append(Html("<p class=\"muted\">No comparable baseline yet. Case-set or measurement-control changes start a new series.</p>"))
    } else if (benchmark.baselineJob == null || changes.regressed > benchmark.transitions.count { it.kind == "regressed" }) {
        append(Html("<p class=\"muted\">Workload comparison details are being refreshed.</p>"))
    } else if (losses.isEmpty()) {
        append(Html("<p>No flagged regressions or failed workloads in this run.</p>"))
    }
    if (losses.isNotEmpty()) append(changeTable(losses, baseline, run))
    val gains = transitions.filter { it.kind == "improved" || it.kind == "recovered" }
    if (gains.isNotEmpty()) {
        append(Html("<details><summary>Improvements and recoveries (") + gains.size + Html(")</summary>") + changeTable(gains, baseline, run) + Html("</details>"))
    }
    run.confirmJob?.let { job ->
        append(Html("<p class=\"muted small\"><a href=\"/jobs/") + job + Html("\">Paired confirmation #") + job +
            Html("</a>. Unconfirmed rows remain candidates.</p>"))
    }
    append(Html("</section>"))
}

private fun changeTable(changes: List<BenchmarkChange>, baseline: TrendRun?, run: TrendRun): Html = buildHtml {
    append(Html("<div class=\"scroll\"><table class=\"benchmark-changes\"><thead><tr><th>Workload</th><th>Change</th><th class=\"num\">Before</th><th class=\"num\">Now</th><th class=\"num\">Difference</th><th>Confirmation</th></tr></thead><tbody>"))
    for (change in changes) {
        val before = baseline?.benchmark?.metrics?.singleOrNull { it.caseId == change.caseId && it.name == change.metric }
        val after = run.benchmark?.metrics?.singleOrNull { it.caseId == change.caseId && it.name == change.metric }
        val difference = if (before != null && before.value > 0 && after != null &&
            before.unit == after.unit && before.direction == after.direction) (after.value / before.value - 1) * 100 else null
        val theme = run.benchmark?.workloads?.find { it.caseId == change.caseId }?.theme
        append(Html("<tr><td><a href=\"#benchmark-detail\" data-benchmark-case=\"") + change.caseId +
            Html("\" data-benchmark-metric=\"") + change.metric.orEmpty() + Html("\"><code>") + change.caseId.substringAfterLast('/') + Html("</code></a>"))
        if (theme != null) append(Html("<small>") + theme + Html("</small>"))
        append(Html("</td><td class=\"") + (if (change.kind in setOf("failed", "regressed")) "FAILED" else "DONE") + Html("\">") +
            when (change.kind) { "failed" -> "Failure"; "regressed" -> "Regression"; "recovered" -> "Recovery"; else -> "Improvement" } + Html("</td>"))
        append(Html("<td class=\"num\">") + formatMetric(before) + Html("</td><td class=\"num\">") + formatMetric(after) +
            Html("</td><td class=\"num\">") + (difference?.let { String.format(Locale.ROOT, "%+.1f%%", it) } ?: "–") + Html("</td><td>"))
        val label = when (change.confirmation) {
            "confirmed" -> "Confirmed"
            "pending" -> "Pending"
            "not selected" -> "Not selected"
            "not confirmed" -> "Not confirmed"
            "inconclusive" -> "Inconclusive"
            else -> "Unconfirmed"
        }
        if (change.confirmation != "unconfirmed" && run.confirmJob != null) {
            append(Html("<a href=\"/jobs/") + run.confirmJob + Html("\">") + label + Html("</a>"))
        } else append(esc(label))
        append(Html("</td></tr>"))
    }
    append(Html("</tbody></table></div>"))
}

private fun formatMetric(metric: TrendMetric?): String = metric?.let {
    val number = if (it.value != 0.0 && kotlin.math.abs(it.value) < 0.001) {
        String.format(Locale.ROOT, "%.4g", it.value)
    } else {
        NumberFormat.getNumberInstance(Locale.ROOT).apply { maximumFractionDigits = 3 }.format(it.value)
    }
    "$number ${it.unit}"
} ?: "–"

private fun metricPair(change: BenchmarkChange, baseline: TrendRun?, run: TrendRun): Pair<TrendMetric, TrendMetric>? {
    val before = baseline?.benchmark?.metrics?.singleOrNull { it.caseId == change.caseId && it.name == change.metric } ?: return null
    val after = run.benchmark?.metrics?.singleOrNull { it.caseId == change.caseId && it.name == change.metric } ?: return null
    return if (before.unit == after.unit && before.direction == after.direction) before to after else null
}
