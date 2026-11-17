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
import kotlinx.html.*
import kotlinx.serialization.encodeToString

/**
 * How a schedule's runs moved over its commits: solved and proven shares with the solved interval,
 * PAR-2 time, and solved share per suite, against commit or date. The runs ride in the page as
 * JSON; a small script draws the charts.
 */
internal fun trendPage(
    name: String?,
    runs: List<TrendRun>,
    repoUrl: String,
    schedules: List<String> = emptyList(),
    /** Runs left out because they ran an earlier experiment than the schedule's current one. */
    earlier: Int = 0,
    all: Boolean = false,
    /**
     * Finished runs whose points are still being computed in the background, left out until they
     * are.
     */
    computing: Int = 0,
): Html =
    page("${name ?: "regression"} · eignex lab", tab = Tab.REGRESSION, script = PageScripts.trend) {
        // Not live for the page-wide refresh, which refetches the whole page: the charts have no
        // live parts, and a page whose
        // points are computing polls a small progress call instead ([trendPoll]).
        if (name == null) {
            header { h1 { text("Regression") } }
            p(classes = "empty-state") {
                text("No schedule yet: ")
                code { text("lab schedule") }
                text(" sets one up, ")
                text("and its runs chart here, one point per commit.")
            }
            return@page
        }
        header {
            h1 { text("Regression") }
            p(classes = "muted") {
                emit(name)
                text(" · results across commits")
            }
            if (schedules.size > 1) {
                nav(classes = "tools trend-schedules") {
                    attributes["aria-label"] = "Regression schedules"
                    for (other in schedules) {
                        a(
                            href = "/trend${query("name" to other)}",
                            classes = if (other == name) "chip on" else "chip",
                        ) {
                            if (other == name) attributes["aria-current"] = "true"
                            text(other)
                        }
                    }
                }
            }
            p(classes = "muted") {
                emit(runs.size)
                text(" runs · ")
                a(href = "/${query("name" to name)}") {
                    text("its jobs")
                }
                if (computing > 0) {
                    text(" · ")
                    emit(computing)
                    text(" more being computed, shown as they are ready")
                }
                if (earlier > 0) {
                    text(" · ")
                    emit(earlier)
                    text(" earlier runs of a different experiment left out: ")
                    a(href = "/trend${query("name" to name, "all" to "1")}") {
                        text("show them")
                    }
                }
                val specs = runs.map { it.spec }.distinct().size
                if (runs.any { it.sourceChecked } && runs.any { !it.sourceChecked }) {
                    text(
                        " · runs marked unchecked report the solver's own claims; checked ones asked for source-witness validation",
                    )
                }
                if (all && specs > 1) {
                    text(" · ")
                    emit(specs)
                    text(" experiments, each change marked by a dashed line: ")
                    a(href = "/trend${query("name" to name)}") {
                        text("current only")
                    }
                }
            }
        }
        if (runs.isEmpty()) {
            p(classes = "empty-state") {
                emit(
                    when {
                        computing > 0 ->
                            "Computing ${if (all) "its runs" else "this experiment's runs"}; they appear here as they are ready."
                        all -> "No run of this schedule has results yet."
                        else -> "No run of this schedule's current experiment has results yet."
                    },
                )
            }
            emit(trendPoll(runs.size, computing))
            return@page
        }
        if (runs.any { it.benchmark != null }) {
            emit(benchmarkTrendContent(runs, repoUrl))
            script(type = "application/json") {
                id = "trend-data"
                unsafe { +defaultsJson.encodeToString(runs).replace("<", "\\u003c") }
            }
            emit(trendPoll(runs.size, computing))
            return@page
        }
        div(classes = "trend-toolbar") {
            nav(classes = "queue-sections") {
                attributes["aria-label"] = "Regression sections"
                a(href = "#performance") {
                    text("Performance")
                }
                a(href = "#themes") {
                    text("Themes")
                }
                a(href = "#runs") {
                    text("Runs")
                }
            }
            div(classes = "tools") {
                attributes["role"] = "group"
                attributes["aria-label"] = "x-axis"
                span(classes = "muted") {
                    text("X-axis")
                }
                button(classes = "chip on") {
                    attributes["type"] = "button"

                    attributes["aria-pressed"] = "true"
                    attributes["data-x"] = "commit"
                    text("Commit")
                }
                button(classes = "chip") {
                    attributes["type"] = "button"

                    attributes["aria-pressed"] = "false"
                    attributes["data-x"] = "date"
                    text("Date")
                }
            }
        }
        div(classes = "trend-charts") {
            attributes["id"] = "performance"

            section(classes = "chart-panel") {
                attributes["aria-labelledby"] = "solved-heading"
                h2 {
                    attributes["id"] = "solved-heading"
                    text("Solved and fully decided")
                }
                p(classes = "chart-description") {
                    text(
                        "Problems out of those each run ran, with seeds and repeats averaged. SAT and UNSAT are fully decided; optimization requires an optimum or infeasibility. Band: 95% interval of solved.",
                    )
                }
                div(classes = "legend") {
                    span {
                        i {
                            attributes["style"] = "background:var(--series-1)"
                        }
                        text("solved")
                    }
                    span {
                        i {
                            attributes["style"] = "background:var(--series-2)"
                        }
                        text("fully decided")
                    }
                    span {
                        i(classes = "hollow") {}
                        text("run still going")
                    }
                    span {
                        i(classes = "flag") {}
                        text("disagrees with the reference")
                    }
                }
                div(classes = "chart") {
                    attributes["id"] = "chart-solved"
                }
            }
            section(classes = "chart-panel") {
                attributes["aria-labelledby"] = "par2-heading"
                h2 {
                    attributes["id"] = "par2-heading"
                    text("PAR-2 time")
                }
                p(classes = "chart-description") {
                    text(
                        "Mean seconds per problem; an unsolved one is charged twice its budget. Lower is better. Band: 95% interval.",
                    )
                }
                div(classes = "chart") {
                    attributes["id"] = "chart-par2"
                }
            }
        }
        section {
            attributes["id"] = "themes"
            attributes["aria-labelledby"] = "themes-heading"
            h2 {
                attributes["id"] = "themes-heading"
                text("Solved by theme")
            }
            p(classes = "chart-description") {
                text(
                    "Problems solved in each theme the sweep set was drawn by. A problem with several themes counts in each; each axis tops out at the theme's size.",
                )
            }
            div(classes = "multiples") {
                attributes["id"] = "suites"
            }
        }
        section {
            attributes["id"] = "runs"
            attributes["aria-labelledby"] = "runs-heading"
            h2 {
                attributes["id"] = "runs-heading"
                text("Runs ")
                small {
                    emit(runs.size)
                    text(" runs, newest first")
                }
            }
            div(classes = "scroll") {
                attributes["role"] = "region"
                attributes["aria-label"] = "Regression runs"
                attributes["tabindex"] = "0"
                table(classes = "runs-table") {
                    thead {
                        tr {
                            th { text("job") }
                            th { text("commit") }
                            th { text("when") }
                            th(classes = "num") {
                                text("problems")
                            }
                            th(classes = "num") {
                                text("solved")
                            }
                            th(classes = "num") {
                                text("fully decided")
                            }
                            th { text("point / proof evidence") }
                            th(classes = "num") {
                                text("unsupported")
                            }
                            th(classes = "num") {
                                text("errors")
                            }
                            th(classes = "num") {
                                text("disagreements")
                            }
                            th(classes = "num") {
                                attributes["title"] =
                                    "problems solved to a worse objective than the reference"
                                text("worse incumbent")
                            }
                            th(classes = "num") {
                                attributes["title"] =
                                    "against the run before: problems lost and gained, the sign test, and what reruns confirmed"
                                text("vs previous")
                            }
                            th(classes = "num") {
                                text("PAR-2 s")
                            }
                        }
                    }
                    tbody {
                        for (run in runs.asReversed()) {
                            val commit = commitLink(repoUrl, run.sha)
                            tr {
                                td {
                                    a(href = "/jobs/${run.job}") {
                                        emit(run.job)
                                    }
                                    emit(
                                        if (run.finished) ""
                                        else
                                            buildHtml {
                                                text(" ")
                                                small(classes = "muted") {
                                                    text("running")
                                                }
                                            },
                                    )
                                    emit(trendWitness(run))
                                }
                                td { code { emit(commit) } }
                                td { emit(ago(run.at)) }
                                td(classes = "num") {
                                    emit(run.problems)
                                }
                                td(classes = "num") {
                                    emit(percent(run.solved.value))
                                    text(" ")
                                    small(classes = "muted") {
                                        emit(percent(run.solved.low))
                                        text("–")
                                        emit(percent(run.solved.high))
                                    }
                                }
                                td(classes = "num") {
                                    emit(percent(run.proven))
                                }
                                td {
                                    emit(run.checkedPoints)
                                    text(" checked points; ")
                                    emit(run.nativeProofClaims)
                                    text(" native claims; ")
                                    emit(run.contradictedOptima)
                                    text(" contradicted; ")
                                    emit(run.unverifiedOptima)
                                    text(" unverified scope/check")
                                }
                                td(classes = "num") {
                                    emit(run.unsupported)
                                }
                                td(classes = "num") {
                                    emit(run.errors)
                                }
                                td(classes = "num") {
                                    emit(
                                        if (run.disagreements > 0)
                                            buildHtml {
                                                a(classes = "FAILED", href = "/jobs/${run.job}") {
                                                    emit(run.disagreements)
                                                }
                                            }
                                        else buildHtml { text("0") },
                                    )
                                }
                                td(classes = "num") {
                                    a(classes = "plain", href = "/jobs/${run.job}") {
                                        emit(run.worse)
                                    }
                                }
                                td(classes = "num") {
                                    emit(flipCell(run))
                                }
                                td(classes = "num") {
                                    emit("%.2f".format(run.par2.value))
                                }
                            }
                        }
                    }
                }
            }
        }
        div(classes = "tip") {
            attributes["id"] = "tip"

            attributes["hidden"] = ""
        }
        script {
            attributes["type"] = "application/json"
            attributes["id"] = "trend-data"
            // Every field, defaults included: the script reads each run's fields without guarding
            // for absent ones.
            unsafe { +defaultsJson.encodeToString(runs).replace("<", "\\u003c") }
        }
        emit(trendPoll(runs.size, computing))
    }

/**
 * Whether a trend run's solution claims were checked against their sources, as a small word with
 * its meaning on hover.
 */
private fun trendWitness(run: TrendRun): Html = buildHtml {
    text(" ")
    small(classes = if (run.sourceChecked) "DONE" else "muted") {
        title =
            if (run.sourceChecked)
                "asked for source-witness validation: its solutions in checked formats are source-checked"
            else
                "no source-witness validation asked for: its solutions are the solver's own reports${if (run.sourceChecked) "checked" else "unchecked"}"
    }
    text(" ")
    small(classes = "muted") {
        title = run.comparisonSeries.joinToString(", ")
        +(if (
            run.comparisonSeries.isEmpty() ||
                run.comparisonSeries == listOf("historical-unqualified")
        )
            "historical controls unqualified"
        else "controlled series")
    }
}

/**
 * A run against the one before it: lost and gained, the sign test when it is telling, and what
 * reruns confirmed.
 */
private fun flipCell(run: TrendRun): Html = buildHtml {
    val lost = run.lost ?: return buildHtml { text("–") }
    val gained = run.gained ?: 0
    text("feasibility −")
    emit(lost)
    text(" +")
    emit(gained)
    text("; proof −")
    emit(run.proofLost ?: 0)
    text(" +")
    emit(run.proofGained ?: 0)
    text("; objective −")
    emit(run.objectiveLost ?: 0)
    text(" +")
    emit(run.objectiveGained ?: 0)
    run.flipP
        ?.takeIf { it < FLIP_SIGNIFICANCE }
        ?.let {
            text(" ")
            small { +"p=%.3f".format(it) }
        }
    val job = run.confirmJob ?: return@buildHtml
    val confirmedLost = run.confirmedLost
    text(" · ")
    a(href = "/jobs/$job") {
        if (confirmedLost == null) +"confirming"
        else
            span {
                if (
                    confirmedLost > 0 ||
                        (run.confirmedProofLost ?: 0) > 0 ||
                        (run.confirmedObjectiveLost ?: 0) > 0
                )
                    classes = setOf("FAILED")
                +"confirmed feasibility −$confirmedLost +${run.confirmedGained ?: 0}; "
                +"proof −${run.confirmedProofLost ?: 0} +${run.confirmedProofGained ?: 0}; "
                +"objective −${run.confirmedObjectiveLost ?: 0} +${run.confirmedObjectiveGained ?: 0}"
            }
    }
}

/** Below this, a run's flips against the one before lean one way more than chance would. */
private const val FLIP_SIGNIFICANCE = 0.05

private fun percent(share: Double) = "%.1f%%".format(share * 100)

/**
 * While a trend page's points are still being computed ([trendPage]'s `computing`), it polls
 * `/trend/progress` with its own query and reloads once the server would show other than its
 * [runs], so the points appear without the reader asking again; it stops once nothing is computing.
 * A page with nothing computing gets no script.
 */
private fun trendPoll(runs: Int, computing: Int): Html =
    if (computing == 0) buildHtml {}
    else
        buildHtml {
            script {
                unsafe {
                    +"""
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
"""
                }
            }
        }
