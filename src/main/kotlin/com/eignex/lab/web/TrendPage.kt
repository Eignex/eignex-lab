package com.eignex.lab.web

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.nomad.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import com.eignex.lab.worker.*

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val trendJson = Json { encodeDefaults = true }

/**
 * How a schedule's runs moved over its commits: solved and proven shares with the solved interval, PAR-2 time, and
 * solved share per suite, against commit or date. The runs ride in the page as JSON; a small script draws the charts.
 */
internal fun trendPage(
    name: String?,
    runs: List<TrendRun>,
    repoUrl: String,
    schedules: List<String> = emptyList(),
    /** Runs left out because they ran an earlier experiment than the schedule's current one. */
    earlier: Int = 0,
    all: Boolean = false,
    /** Finished runs whose points are still being computed in the background, left out until they are. */
    computing: Int = 0,
): Html = page("${name ?: "regression"} · eignex lab", tab = Tab.REGRESSION, script = PageScripts.trend) {
    // Not live for the page-wide refresh, which refetches the whole page: the charts have no live parts, and a page whose
    // points are computing polls a small progress call instead ([trendPoll]).
    if (name == null) {
        append(Html("<header><h1>Regression</h1></header><p class=\"empty-state\">No schedule yet: <code>lab schedule</code> sets one up, "))
        append(Html("and its runs chart here, one point per commit.</p>"))
        return@page
    }
    append(Html("<header><h1>Regression</h1><p class=\"muted\">") + esc(name) + Html(" · results across commits</p>"))
    if (schedules.size > 1) {
        append(Html("<nav class=\"tools trend-schedules\" aria-label=\"Regression schedules\">"))
        for (other in schedules) {
            append(Html("<a class=\"chip") + (if (other == name) " on" else "") + Html("\"") + (if (other == name) Html(" aria-current=\"true\"") else "") + Html(" href=\"/trend") + query("name" to other) + Html("\">") + esc(other) + Html("</a>"))
        }
        append(Html("</nav>"))
    }
    append(Html("<p class=\"muted\">") + runs.size + Html(" runs · <a href=\"/") + query("name" to name) + Html("\">its jobs</a>"))
    if (computing > 0) append(Html(" · ") + computing + Html(" more being computed, shown as they are ready"))
    if (earlier > 0) {
        append(Html(" · ") + earlier + Html(" earlier runs of a different experiment left out: <a href=\"/trend") + query("name" to name, "all" to "1") + Html("\">show them</a>"))
    }
    val specs = runs.map { it.spec }.distinct().size
    if (runs.any { it.sourceChecked } && runs.any { !it.sourceChecked }) {
        append(Html(" · runs marked unchecked report the solver's own claims; checked ones asked for source-witness validation"))
    }
    if (all && specs > 1) {
        append(Html(" · ") + specs + Html(" experiments, each change marked by a dashed line: <a href=\"/trend") + query("name" to name) + Html("\">current only</a>"))
    }
    append(Html("</p></header>"))
    if (runs.isEmpty()) {
        append(Html("<p class=\"empty-state\">"))
        append(esc(when {
            computing > 0 -> "Computing ${if (all) "its runs" else "this experiment's runs"}; they appear here as they are ready."
            all -> "No run of this schedule has results yet."
            else -> "No run of this schedule's current experiment has results yet."
        }))
        append(Html("</p>") + trendPoll(runs.size, computing))
        return@page
    }
    append(Html("<div class=\"trend-toolbar\"><nav class=\"queue-sections\" aria-label=\"Regression sections\"><a href=\"#performance\">Performance</a><a href=\"#themes\">Themes</a><a href=\"#runs\">Runs</a></nav>"))
    append(Html("<div class=\"tools\" role=\"group\" aria-label=\"x-axis\"><span class=\"muted\">X-axis</span>"))
    append(Html("<button type=\"button\" class=\"chip on\" aria-pressed=\"true\" data-x=\"commit\">Commit</button><button type=\"button\" class=\"chip\" aria-pressed=\"false\" data-x=\"date\">Date</button></div></div>"))
    append(Html("<div id=\"performance\" class=\"trend-charts\"><section class=\"chart-panel\" aria-labelledby=\"solved-heading\"><h2 id=\"solved-heading\">Solved and proven</h2><p class=\"chart-description\">Problems out of those each run ran, with seeds and repeats averaged. Band: 95% interval of solved.</p>"))
    append(Html("<div class=\"legend\"><span><i style=\"background:var(--series-1)\"></i>solved</span>"))
    append(Html("<span><i style=\"background:var(--series-2)\"></i>proven</span>"))
    append(Html("<span><i class=\"hollow\"></i>run still going</span>"))
    append(Html("<span><i class=\"flag\"></i>disagrees with the reference</span></div>"))
    append(Html("<div class=\"chart\" id=\"chart-solved\"></div></section>"))
    append(Html("<section class=\"chart-panel\" aria-labelledby=\"par2-heading\"><h2 id=\"par2-heading\">PAR-2 time</h2><p class=\"chart-description\">Mean seconds per problem; an unsolved one is charged twice its budget. Lower is better. Band: 95% interval.</p>"))
    append(Html("<div class=\"chart\" id=\"chart-par2\"></div></section></div>"))
    append(Html("<section id=\"themes\" aria-labelledby=\"themes-heading\"><h2 id=\"themes-heading\">Solved by theme</h2><p class=\"chart-description\">Problems solved in each theme the sweep set was drawn by. A problem with several themes counts in each; each axis tops out at the theme's size.</p><div class=\"multiples\" id=\"suites\"></div></section>"))
    append(Html("<section id=\"runs\" aria-labelledby=\"runs-heading\"><h2 id=\"runs-heading\">Runs <small>") + runs.size + Html(" runs, newest first</small></h2><div class=\"scroll\" role=\"region\" aria-label=\"Regression runs\" tabindex=\"0\"><table class=\"runs-table\"><thead><tr><th>job</th><th>commit</th><th>when</th>"))
    append(Html("<th class=\"num\">problems</th><th class=\"num\">solved</th><th class=\"num\">eligible native proofs</th><th>point / proof evidence</th>"))
    append(Html("<th class=\"num\">unsupported</th><th class=\"num\">errors</th><th class=\"num\">disagreements</th>"))
    append(Html("<th class=\"num\" title=\"problems solved to a worse objective than the reference\">worse incumbent</th>"))
    append(Html("<th class=\"num\" title=\"against the run before: problems lost and gained, the sign test, and what reruns confirmed\">"))
    append(Html("vs previous</th>"))
    append(Html("<th class=\"num\">PAR-2 s</th></tr></thead><tbody>"))
    for (run in runs.asReversed()) {
        val commit = commitLink(repoUrl, run.sha)
        append(Html("<tr><td><a href=\"/jobs/") + run.job + Html("\">") + run.job + Html("</a>") + (if (run.finished) "" else Html(" <small class=\"muted\">running</small>")))
        append(Html("") + trendWitness(run) + Html("</td>"))
        append(Html("<td><code>") + commit + Html("</code></td><td>") + ago(run.at) + Html("</td><td class=\"num\">") + run.problems + Html("</td>"))
        append(Html("<td class=\"num\">") + percent(run.solved.value) + Html(" <small class=\"muted\">") + percent(run.solved.low) + Html("–") + percent(run.solved.high) + Html("</small></td>"))
        append(Html("<td class=\"num\">") + percent(run.proven) + Html("</td><td>") + run.checkedPoints + Html(" checked points; ") +
            run.nativeProofClaims + Html(" native claims; ") + run.contradictedOptima + Html(" contradicted; ") +
            run.unverifiedOptima + Html(" unverified scope/check</td><td class=\"num\">") + run.unsupported + Html("</td><td class=\"num\">") + run.errors + Html("</td>"))
        append(Html("<td class=\"num\">"))
        append(if (run.disagreements > 0) (Html("<a class=\"FAILED\" href=\"/jobs/") + run.job + Html("\">") + run.disagreements + Html("</a>")) else Html("0"))
        append(Html("</td>"))
        append(Html("<td class=\"num\"><a class=\"plain\" href=\"/jobs/") + run.job + Html("\">") + run.worse + Html("</a></td>"))
        append(Html("<td class=\"num\">") + flipCell(run) + Html("</td>"))
        append(Html("<td class=\"num\">") + ("%.2f".format(run.par2.value)) + Html("</td></tr>"))
    }
    append(Html("</tbody></table></div></section><div id=\"tip\" class=\"tip\" hidden></div>"))
    append(Html("<script type=\"application/json\" id=\"trend-data\">"))
    // Every field, defaults included: the script reads each run's fields without guarding for absent ones.
    append(Html(trendJson.encodeToString(runs).replace("</", "<\\/")))
    append(Html("</script>"))
    append(trendPoll(runs.size, computing))
}

/** Whether a trend run's solution claims were checked against their sources, as a small word with its meaning on hover. */
private fun trendWitness(run: TrendRun): Html =
    (if (run.sourceChecked) {
        Html(" <small class=\"DONE\" title=\"asked for source-witness validation: its solutions in checked formats are source-checked\">checked</small>")
    } else {
        Html(" <small class=\"muted\" title=\"no source-witness validation asked for: its solutions are the solver's own reports\">unchecked</small>")
    }) + (Html(" <small class=\"muted\" title=\"") + (esc(run.comparisonSeries.joinToString(", "))) + Html("\">")) +
        (if (run.comparisonSeries.isEmpty() || run.comparisonSeries == listOf("historical-unqualified"))
            "historical controls unqualified" else "controlled series") + Html("</small>")

/** A run against the one before it: lost and gained, the sign test when it is telling, and what reruns confirmed. */
private fun flipCell(run: TrendRun): Html = buildHtml {
    val lost = run.lost ?: return Html("–")
    val gained = run.gained ?: 0
    append(Html("feasibility −") + lost + Html(" +") + gained)
    append(Html("; proof −") + (run.proofLost ?: 0) + Html(" +") + (run.proofGained ?: 0))
    append(Html("; objective −") + (run.objectiveLost ?: 0) + Html(" +") + (run.objectiveGained ?: 0))
    run.flipP?.takeIf { it < FLIP_SIGNIFICANCE }?.let { append(Html(" <small>p=%.3f</small>".format(it))) }
    val job = run.confirmJob ?: return@buildHtml
    val confirmedLost = run.confirmedLost
    append(Html(" · <a href=\"/jobs/") + job + Html("\">"))
    append(
        if (confirmedLost == null) {
            Html("confirming")
        } else {
            val cls = if (confirmedLost > 0 || (run.confirmedProofLost ?: 0) > 0 || (run.confirmedObjectiveLost ?: 0) > 0) Html(" class=\"FAILED\"") else ""
            (Html("<span") + cls + Html(">confirmed feasibility −") + confirmedLost + Html(" +") + (run.confirmedGained ?: 0) + Html("; ")) +
                "proof −${run.confirmedProofLost ?: 0} +${run.confirmedProofGained ?: 0}; " +
                (Html("objective −") + (run.confirmedObjectiveLost ?: 0) + Html(" +") + (run.confirmedObjectiveGained ?: 0) + Html("</span>"))
        },
    )
    append(Html("</a>"))
}

/** Below this, a run's flips against the one before lean one way more than chance would. */
private const val FLIP_SIGNIFICANCE = 0.05

private fun percent(share: Double) = "%.1f%%".format(share * 100)

/**
 * While a trend page's points are still being computed ([trendPage]'s `computing`), it polls `/trend/progress` with its
 * own query and reloads once the server would show other than its [runs], so the points appear without the reader asking
 * again; it stops once nothing is computing. A page with nothing computing gets no script.
 */
private fun trendPoll(runs: Int, computing: Int): Html = if (computing == 0) Html("") else Html("""<script>
(function () {
  const poll = setInterval(async function () {
    if (document.hidden) return;
    try {
      const response = await fetch('/trend/progress' + location.search, {headers: {Accept: 'application/json'}});
      if (!response.ok) return;
      const progress = await response.json();
      if (progress.runs !== $runs) location.reload();
      else if (progress.computing === 0) clearInterval(poll);
    } catch (e) {}
  }, 5000);
})();
</script>""")
