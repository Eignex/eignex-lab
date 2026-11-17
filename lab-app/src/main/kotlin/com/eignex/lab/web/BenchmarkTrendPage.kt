package com.eignex.lab.web

import com.eignex.lab.analysis.*
import kotlinx.html.*

/**
 * Benchmark history shows overall and theme performance, with absolute timings in the case table.
 */
internal fun benchmarkTrendContent(runs: List<TrendRun>, repoUrl: String): Html = buildHtml {
    val koblas = repoUrl.removeSuffix("/").removeSuffix(".git").endsWith("/Eignex/koblas")
    val chartScope = if (koblas) " · JVM SIMD" else ""
    text("\n")
    div(classes = "trend-toolbar") {
        text("\n")
        nav(classes = "queue-sections") {
            attributes["aria-label"] = "Benchmark sections"
            a(href = "#benchmark-performance") {
                text("Performance")
            }
            a(href = "#benchmark-themes-section") {
                text("Themes")
            }
            a(href = "#benchmark-cases-section") {
                text("Case timings")
            }
        }
        text("\n")
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
        text("\n")
    }
    text("\n")
    div(classes = "legend") {
        attributes["id"] = "benchmark-engines"
    }
    text("\n")
    div(classes = "trend-charts benchmark-overview") {
        attributes["id"] = "benchmark-performance"

        text("\n")
        section(classes = "chart-panel") {
            attributes["aria-labelledby"] = "benchmark-regressions-heading"
            text("\n")
            h2 {
                attributes["id"] = "benchmark-regressions-heading"
                text("Regressed workloads$chartScope")
            }
            text("\n")
            p(classes = "chart-description") {
                text(
                    "Workloads beyond their own slowdown threshold since the first complete comparable run. Fewer is better. Counts are candidates; paired confirmations compare neighboring revisions.",
                )
            }
            text("\n")
            div(classes = "chart") {
                attributes["id"] = "chart-benchmark-regressions"
            }
            text("\n")
            div(classes = "benchmark-chart-summary") {
                attributes["id"] = "benchmark-regression-summary"
            }
            text("\n")
        }
        text("\n")
        section(classes = "chart-panel") {
            attributes["aria-labelledby"] = "benchmark-performance-heading"
            text("\n")
            h2 {
                attributes["id"] = "benchmark-performance-heading"
                text("Performance vs baseline$chartScope")
            }
            text("\n")
            p(classes = "chart-description") {
                text(
                    "Geometric mean of each workload’s slowdown against its own baseline, equally weighted. 1× is unchanged; lower is faster. Absolute timings are in the table below.",
                )
            }
            text("\n")
            div(classes = "chart") {
                attributes["id"] = "chart-benchmark-performance"
            }
            text("\n")
            div(classes = "benchmark-chart-summary") {
                attributes["id"] = "benchmark-performance-summary"
            }
            text("\n")
        }
        text("\n")
    }
    text("\n")
    div(classes = "benchmark-context") {
        p(classes = "muted small") {
            attributes["id"] = "benchmark-baseline"

            attributes["aria-live"] = "polite"
        }
        button(classes = "chip") {
            attributes["type"] = "button"

            attributes["id"] = "benchmark-latest"
            text("Latest complete run")
        }
    }
    text("\n")
    p(classes = "muted small") {
        text(
            "Click any chart to inspect a commit. Gaps mean incomplete or incomparable measurements; rings mark failed captures, never zero regressions. Changed cases or controls start a new baseline.",
        )
    }
    text("\n")
    if (runs.any { run -> run.benchmark?.workloads?.any { it.theme != null } == true }) {
        text("\n")
        section {
            attributes["id"] = "benchmark-themes-section"
            attributes["aria-labelledby"] = "benchmark-themes-heading"
            text("\n")
            h2 {
                attributes["id"] = "benchmark-themes-heading"
                text("Performance by theme$chartScope")
            }
            text("\n")
            p(classes = "chart-description") {
                text(
                    "The same baseline-relative performance and scale in every theme, with regression counts at the inspected commit. Each workload is compared with its own baseline.",
                )
            }
            text("\n")
            div(classes = "multiples") {
                attributes["id"] = "benchmark-themes"
            }
            text("\n")
        }
        text("\n")
    }
    text("\n")
    section {
        attributes["id"] = "benchmark-cases-section"
        attributes["aria-labelledby"] = "benchmark-cases-heading"
        text("\n")
        h2 {
            attributes["id"] = "benchmark-cases-heading"
            text("Case timings")
        }
        text("\n")
        p(classes = "chart-description") {
            text(
                "One row per case. Timings appear from the first run. Historical targets and slowdowns appear once earlier comparable measurements exist for that engine. These targets differ from the charts’ fixed baseline.",
            )
        }
        text("\n")
        div(classes = "tools") {
            attributes["role"] = "group"
            attributes["aria-label"] = "Historical timing target"
            span(classes = "muted small") {
                text("Target")
            }
            button(classes = "chip on") {
                attributes["type"] = "button"

                attributes["aria-pressed"] = "true"
                attributes["data-benchmark-target"] = "p05"
                text("Fast historical percentile")
            }
            button(classes = "chip") {
                attributes["type"] = "button"

                attributes["aria-pressed"] = "false"
                attributes["data-benchmark-target"] = "best"
                text("Best observed")
            }
        }
        text("\n")
        p(classes = "muted small") {
            attributes["id"] = "benchmark-target-description"
        }
        text("\n")
        p(classes = "muted small") {
            attributes["id"] = "benchmark-table-context"
        }
        text("\n")
        p(classes = "muted small") {
            attributes["id"] = "benchmark-table-coverage"
        }
        text("\n")
        div(classes = "tools") {
            attributes["role"] = "group"
            attributes["aria-label"] = "Case timing sort"
            button(classes = "chip on") {
                attributes["type"] = "button"
                attributes["id"] = "benchmark-sort-worst"

                attributes["aria-pressed"] = "true"
                text("Worst slowdown")
            }
            span(classes = "muted small") {
                text("Or sort by a column heading.")
            }
        }
        text("\n")
        div(classes = "scroll") {
            attributes["role"] = "region"
            attributes["aria-label"] = "Case timing comparisons"
            attributes["tabindex"] = "0"
            text("\n")
            table(classes = "benchmark-cases") {
                attributes["data-koblas"] = "$koblas"
                thead {
                    tr {
                        attributes["id"] = "benchmark-case-head"
                        th {
                            attributes["scope"] = "col"
                            text("Case")
                        }
                    }
                }
                tbody {
                    attributes["id"] = "benchmark-case-rows"
                }
            }
            text("\n")
        }
        text("\n")
    }
    text("\n")
    section {
        h2 { text("Runs") }
        div(classes = "scroll") {
            table(classes = "runs-table") {
                thead {
                    tr {
                        th { text("job") }
                        th { text("commit") }
                        th { text("when") }
                        th(classes = "num") {
                            text("cases")
                        }
                        th(classes = "num") {
                            text("successful")
                        }
                        th(classes = "num") {
                            text("failed")
                        }
                        th(classes = "num") {
                            text("skipped")
                        }
                        th(classes = "num") {
                            text("pending")
                        }
                        th { text("vs previous") }
                    }
                }
                tbody {
                    for (run in runs.asReversed()) {
                        val benchmark = run.benchmark ?: continue
                        tr {
                            td {
                                a(href = "/jobs/${run.job}") {
                                    emit(run.job)
                                }
                                emit(if (run.finished) "" else " · running")
                            }
                            td { code { emit(commitLink(repoUrl, run.sha)) } }
                            td { emit(ago(run.at)) }
                            for (n in
                                listOf(
                                    run.cases,
                                    benchmark.succeeded,
                                    benchmark.failed,
                                    benchmark.skipped,
                                    benchmark.pending,
                                )) {
                                td(classes = "num") {
                                    emit(n)
                                }
                            }
                            td {
                                emit(benchmarkChanges(benchmark.changes))
                                run.confirmJob?.let { job ->
                                    text(" · ")
                                    a(href = "/jobs/${job}") {
                                        emit(
                                            benchmark.confirmed?.let {
                                                buildHtml {
                                                    text("confirmed ")
                                                    emit(benchmarkChanges(it))
                                                }
                                            } ?: buildHtml { text("confirming") },
                                        )
                                    }
                                }
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
}

private fun benchmarkChanges(changes: BenchmarkChanges?): Html =
    changes?.let {
        buildHtml {
            emit(it.failed)
            text(" failures, ")
            emit(it.recovered)
            text(" recoveries; ")
            emit(it.regressed)
            text(" metric regressions, ")
            emit(it.improved)
            text(" improvements")
        }
    } ?: buildHtml { text("–") }
