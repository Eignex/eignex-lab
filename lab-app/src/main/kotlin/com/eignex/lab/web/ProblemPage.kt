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

/** One problem: every reference solver's verdict on it, and every time the lab ran it. */
internal fun problemPage(report: ProblemReport, repoUrl: String): Html =
    page(
        "${report.problem} · eignex lab",
        live = report.runs.any { it.status.isActive },
        tab = Tab.REFERENCE,
    ) {
        header {
            h1 { code { emit(report.problem) } }
            p(classes = "muted") {
                a(href = "/references${query("collection" to report.collection)}") {
                    emit(report.collection)
                }
            }
        }
        h2 {
            text("Reference ")
            small {
                emit(report.references.size)
                text(" solvers, strongest first")
            }
        }
        if (report.references.isEmpty()) {
            p(classes = "muted") {
                text("No reference result for this problem.")
            }
        } else {
            div(classes = "scroll") {
                table {
                    tr {
                        th { text("solver") }
                        th { text("verdict") }
                        th(classes = "num") {
                            text("objective")
                        }
                        th(classes = "num") {
                            text("dual bound")
                        }
                        th(classes = "num") {
                            text("time")
                        }
                        th(classes = "num") {
                            text("budget")
                        }
                        th { text("source") }
                        th { text("trust") }
                    }
                    // What the lab's comparisons take from each row: a stale one nothing, a
                    // disputed proof only its solution.
                    val current = report.references.filter { !it.stale }
                    val trusted = current.zip(References.trusted(current)).toMap()
                    for (r in report.references) {
                        val trust =
                            when {
                                r.stale -> "stale: unregistered importer or unchecked legacy policy"
                                trusted[r] != r ->
                                    "proof set aside: another solver's solution contradicts it"
                                else -> r.validation?.let { "solution ${it}" } ?: ""
                            }
                        tr {
                            td { emit(r.solver) }
                            td { emit(verdictWord(r)) }
                            td(classes = "num") {
                                emit(r.objective?.let(::number) ?: "–")
                            }
                            td(classes = "num") {
                                emit(r.dualBound?.let(::number) ?: "–")
                            }
                            td(classes = "num") {
                                emit("%.2f".format(r.elapsedMs / 1000.0))
                                text("s")
                            }
                            td(classes = "num") {
                                emit(r.budgetMs / 1000)
                                text("s")
                            }
                            // An unbound row counts only against runs that recorded no source
                            // either ([Reference.sourceIdentity]).
                            td {
                                emit(
                                    r.sourceIdentity?.let {
                                        buildHtml {
                                            code {
                                                attributes["title"] = (it)
                                                emit(it.take(12))
                                            }
                                        }
                                    }
                                        ?: buildHtml {
                                            span(classes = "muted") {
                                                text("unbound")
                                            }
                                        },
                                )
                            }
                            td(classes = "muted") {
                                emit(trust)
                            }
                        }
                    }
                }
            }
        }
        h2 {
            text("Lab runs ")
            small {
                emit(report.totalRuns)
                text(" cases")
                if (report.runs.size < report.totalRuns) {
                    text(", the newest ")
                    emit(report.runs.size)
                    text(" shown: ")
                    a(
                        href =
                            "/problem${(query(
                                            "collection" to report.collection,
                                            "problem" to report.problem,
                                            "all" to "1",
                                        ))
                                        }",
                    ) {
                        text("show all")
                    }
                }
            }
        }
        if (report.runs.isEmpty()) {
            p(classes = "muted") {
                text("No experiment has run this problem.")
            }
        } else {
            div(classes = "scroll") {
                table {
                    tr {
                        th { text("experiment") }
                        th { text("arm") }
                        th { text("commit") }
                        th { text("seed") }
                        th { text("verdict") }
                        th(classes = "num") {
                            text("time")
                        }
                        th { text("output") }
                        th { text("when") }
                    }
                    for (run in report.runs) {
                        val outcome = Outcome.of(run.record)
                        val commit = commitLink(repoUrl, run.sha)
                        val time =
                            outcome
                                ?.takeIf { it.rank > 0 }
                                ?.let { "%.2fs".format(it.timeMs / 1000.0) } ?: "–"
                        tr {
                            td {
                                a(href = "/jobs/${run.job}") {
                                    emit(run.job)
                                }
                                text(" ")
                                emit(run.jobName)
                            }
                            td { emit(run.arm) }
                            td { code { emit(commit) } }
                            td {
                                emit(run.seed ?: "–")
                                emit(if (run.repeat > 0) " #${run.repeat}" else "")
                            }
                            td { emit(verdict(outcome, run.status)) }
                            td(classes = "num") {
                                emit(time)
                            }
                            td {
                                a(href = "/jobs/${run.job}/files/${run.case}.out") {
                                    text("out")
                                }
                                text(" ")
                                a(href = "/jobs/${run.job}/files/${run.case}.err") {
                                    text("err")
                                }
                            }
                            td { emit(ago(run.createdAt)) }
                        }
                    }
                }
            }
        }
    }

/** A reference verdict in a word, without the solver beside it. */
private fun verdictWord(r: Reference): Html =
    when {
        r.feasible == false -> htmlText("infeasible${if (r.proven) " (proven)" else ""}")
        r.feasible == null ->
            buildHtml {
                span(classes = "muted") {
                    text("unknown")
                }
            }
        r.objective != null -> htmlText(if (r.proven) "optimum" else "solution")
        else -> buildHtml { text("sat") }
    }
