package com.eignex.lab.web

import com.eignex.lab.analysis.*

/** Benchmark history displays workload and theme timings, with failures marked on their charts. */
internal fun benchmarkTrendContent(runs: List<TrendRun>, repoUrl: String): Html = buildHtml {
    append(Html("<div class=\"trend-toolbar\"><h2>Benchmark performance</h2><div class=\"tools\" role=\"group\" aria-label=\"x-axis\"><span class=\"muted\">X-axis</span><button type=\"button\" class=\"chip on\" aria-pressed=\"true\" data-x=\"commit\">Commit</button><button type=\"button\" class=\"chip\" aria-pressed=\"false\" data-x=\"date\">Date</button></div></div>"))
    append(Html("""
<section class="chart-panel" aria-labelledby="benchmark-regressions-heading">
<h2 id="benchmark-regressions-heading">Regressed workloads by engine</h2>
<p class="chart-description">Each workload is compared with its own timing in the first complete run of the same measurement series. Lines count workloads beyond their slowdown threshold; more is worse. Gaps mean measurements are incomplete or controls are unavailable. Changing cases or controls starts a new baseline.</p>
<label>Commit <select id="benchmark-run" aria-label="Selected benchmark commit"></select></label>
<div id="benchmark-engines" class="legend"></div>
<p class="muted small">Rings mark failed captures. A ring at the baseline means timing is unavailable, not zero regressions.</p>
<div class="chart" id="chart-benchmark-regressions"></div>
<p id="benchmark-baseline" class="muted small"></p>
<div id="benchmark-affected" aria-live="polite"></div>
</section>
<section class="chart-panel" id="benchmark-detail" aria-labelledby="benchmark-timing-heading">
<h2 id="benchmark-timing-heading">Absolute workload timing</h2>
<p class="chart-description">Compare engines on the same operation, size and storage. Timings stay in their original unit; no workloads are averaged. Hover for absolute values, relative speed and the measured provider and kernel.</p>
<label>Workload <select id="benchmark-metric" aria-label="Benchmark workload metric"></select></label>
<div id="benchmark-timing-engines" class="legend"></div>
<div class="chart" id="chart-benchmark-metric"></div>
<div id="benchmark-selected" class="benchmark-speeds"></div>
</section>
"""))
    if (runs.any { run -> run.benchmark?.workloads?.any { it.theme != null } == true }) {
        append(Html("<section aria-labelledby=\"benchmark-themes-heading\"><h2 id=\"benchmark-themes-heading\">Regressions by theme</h2><p class=\"chart-description\">Workloads beyond their own baseline threshold, separated by exact engine. Unsupported engines are excluded from coverage; missing or failed measurements leave gaps.</p><div class=\"multiples\" id=\"benchmark-themes\"></div></section>"))
    }
    append(Html("""
<section aria-labelledby="benchmark-cases-heading">
<h2 id="benchmark-cases-heading">Case timings</h2>
<p class="chart-description">Every case and engine at the selected commit, compared with its own earlier measurements in the shown history. Targets require matching cases, units and measurement controls. Click a case to open its timing graph.</p>
<label>Target <select id="benchmark-target" aria-label="Historical timing target"><option value="p05">Fast historical percentile</option><option value="best">Best observed</option></select></label>
<p id="benchmark-target-description" class="muted small"></p>
<p id="benchmark-table-context" class="muted small"></p>
<div class="scroll" role="region" aria-label="Case timing comparisons" tabindex="0">
<table class="benchmark-cases"><thead><tr><th scope="col">Case</th><th scope="col">Engine</th><th scope="col" class="num">Current</th><th scope="col" class="num">Historical target</th><th scope="col" class="num">Slowdown</th><th scope="col" class="num">Prior runs</th><th scope="col">Status</th></tr></thead><tbody id="benchmark-case-rows"></tbody></table>
</div>
</section>
"""))
    append(Html("<section><h2>Runs</h2><div class=\"scroll\"><table class=\"runs-table\"><thead><tr><th>job</th><th>commit</th><th>when</th><th class=\"num\">cases</th><th class=\"num\">successful</th><th class=\"num\">failed</th><th class=\"num\">skipped</th><th class=\"num\">pending</th><th>vs previous</th></tr></thead><tbody>"))
    for (run in runs.asReversed()) {
        val benchmark = run.benchmark ?: continue
        append(Html("<tr><td><a href=\"/jobs/") + run.job + Html("\">") + run.job + Html("</a>") +
            (if (run.finished) "" else " · running") + Html("</td><td><code>") + commitLink(repoUrl, run.sha) + Html("</code></td><td>") + ago(run.at))
        for (n in listOf(run.cases, benchmark.succeeded, benchmark.failed, benchmark.skipped, benchmark.pending)) {
            append(Html("</td><td class=\"num\">") + n)
        }
        append(Html("</td><td>") + benchmarkChanges(benchmark.changes))
        run.confirmJob?.let { job ->
            append(Html(" · <a href=\"/jobs/") + job + Html("\">") +
                (benchmark.confirmed?.let { Html("confirmed ") + benchmarkChanges(it) } ?: Html("confirming")) + Html("</a>"))
        }
        append(Html("</td></tr>"))
    }
    append(Html("</tbody></table></div></section><div id=\"tip\" class=\"tip\" hidden></div>"))
}

private fun benchmarkChanges(changes: BenchmarkChanges?): Html = changes?.let {
    Html("") + it.failed + Html(" failures, ") + it.recovered + Html(" recoveries; ") +
        it.regressed + Html(" metric regressions, ") + it.improved + Html(" improvements")
} ?: Html("–")
