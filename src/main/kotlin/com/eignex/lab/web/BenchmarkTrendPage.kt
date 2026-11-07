package com.eignex.lab.web

import com.eignex.lab.analysis.*

/** Benchmark history displays workload and theme timings, with failures marked on their charts. */
internal fun benchmarkTrendContent(runs: List<TrendRun>, repoUrl: String): Html = buildHtml {
    append(Html("<div class=\"trend-toolbar\"><h2>Benchmark performance</h2><div class=\"tools\" role=\"group\" aria-label=\"x-axis\"><span class=\"muted\">X-axis</span><button type=\"button\" class=\"chip on\" aria-pressed=\"true\" data-x=\"commit\">Commit</button><button type=\"button\" class=\"chip\" aria-pressed=\"false\" data-x=\"date\">Date</button></div></div>"))
    append(Html("<section class=\"chart-panel\"><h2>Workload timing</h2><p class=\"chart-description\">Each workload keeps its own metric and unit. Lines break when benchmark controls or the case set change.</p><div class=\"legend\"><span><i class=\"flag\"></i>failed benchmark case; a circle on the baseline means timing is unavailable</span></div><label>Workload <select id=\"benchmark-metric\" aria-label=\"Benchmark workload metric\"></select></label><div class=\"chart\" id=\"chart-benchmark-metric\"></div></section>"))
    if (runs.any { run -> run.benchmark?.workloads?.any { it.theme != null } == true }) {
        append(Html("<section aria-labelledby=\"benchmark-themes-heading\"><h2 id=\"benchmark-themes-heading\">Timing by theme</h2><p class=\"chart-description\">Mean time per workload within each theme. Every workload has equal weight; lower is better. Incomplete or failed themes have no timing point. Circles mark failures only in the affected theme.</p><div class=\"multiples\" id=\"benchmark-themes\"></div></section>"))
    }
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
