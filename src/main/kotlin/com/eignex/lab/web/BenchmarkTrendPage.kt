package com.eignex.lab.web

import com.eignex.lab.analysis.*

/** Benchmark history shows overall and theme performance, with absolute timings in the case table. */
internal fun benchmarkTrendContent(runs: List<TrendRun>, repoUrl: String): Html = buildHtml {
    append(Html("""
<div class="trend-toolbar">
<nav class="queue-sections" aria-label="Benchmark sections"><a href="#benchmark-performance">Performance</a><a href="#benchmark-themes-section">Themes</a><a href="#benchmark-cases-section">Case timings</a></nav>
<div class="tools" role="group" aria-label="x-axis"><span class="muted">X-axis</span><button type="button" class="chip on" aria-pressed="true" data-x="commit">Commit</button><button type="button" class="chip" aria-pressed="false" data-x="date">Date</button></div>
</div>
<div id="benchmark-engines" class="legend"></div>
<div id="benchmark-performance" class="trend-charts benchmark-overview">
<section class="chart-panel" aria-labelledby="benchmark-regressions-heading">
<h2 id="benchmark-regressions-heading">Regressed workloads by engine</h2>
<p class="chart-description">Workloads beyond their own slowdown threshold since the first complete comparable run. Fewer is better. Counts are candidates; paired confirmations compare neighboring revisions.</p>
<div class="chart" id="chart-benchmark-regressions"></div>
<div id="benchmark-regression-summary" class="benchmark-chart-summary"></div>
</section>
<section class="chart-panel" aria-labelledby="benchmark-performance-heading">
<h2 id="benchmark-performance-heading">Performance vs baseline</h2>
<p class="chart-description">Geometric mean of each workload’s slowdown against its own baseline, equally weighted. 1× is unchanged; lower is faster. Absolute timings are in the table below.</p>
<div class="chart" id="chart-benchmark-performance"></div>
<div id="benchmark-performance-summary" class="benchmark-chart-summary"></div>
</section>
</div>
<div class="benchmark-context"><p id="benchmark-baseline" class="muted small" aria-live="polite"></p><button type="button" class="chip" id="benchmark-latest">Latest complete run</button></div>
<p class="muted small">Click any chart to inspect a commit. Gaps mean incomplete or incomparable measurements; rings mark failed captures, never zero regressions. Changed cases or controls start a new baseline.</p>
"""))
    if (runs.any { run -> run.benchmark?.workloads?.any { it.theme != null } == true }) {
        append(Html("""
<section id="benchmark-themes-section" aria-labelledby="benchmark-themes-heading">
<h2 id="benchmark-themes-heading">Performance by theme</h2>
<p class="chart-description">The same baseline-relative performance and scale in every theme, with regression counts at the inspected commit. Each engine is compared with its own baseline.</p>
<div class="multiples" id="benchmark-themes"></div>
</section>
"""))
    }
    val koblas = repoUrl.removeSuffix("/").removeSuffix(".git").endsWith("/Eignex/koblas")
    append(Html("""
<section id="benchmark-cases-section" aria-labelledby="benchmark-cases-heading">
<h2 id="benchmark-cases-heading">Case timings</h2>
<p class="chart-description">One row per case. Each engine is compared independently with its own earlier measurements: current timing, historical target, slowdown and prior-run count. These targets differ from the charts’ fixed baseline.</p>
<div class="tools" role="group" aria-label="Historical timing target"><span class="muted small">Target</span><button type="button" class="chip on" aria-pressed="true" data-benchmark-target="p05">Fast historical percentile</button><button type="button" class="chip" aria-pressed="false" data-benchmark-target="best">Best observed</button></div>
<p id="benchmark-target-description" class="muted small"></p>
<p id="benchmark-table-context" class="muted small"></p>
<p id="benchmark-table-coverage" class="muted small"></p>
<div class="scroll" role="region" aria-label="Case timing comparisons" tabindex="0">
<table class="benchmark-cases" data-koblas="$koblas"><thead><tr id="benchmark-case-head"><th scope="col">Case</th></tr></thead><tbody id="benchmark-case-rows"></tbody></table>
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
