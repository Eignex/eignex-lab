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

internal fun executionPage(job: Long, reports: List<ExecutionSessionReport>): Html =
    page("Execution · job ${job}", live = true) {
        header {
            h1 {
                text("Execution · job ")
                emit(job)
            }
        }
        div {
            attributes["id"] = "execution"
            attributes["data-live"] = ""
            p {
                a(href = "/jobs/${job}") {
                    text("Experiment")
                }
                text(" · ")
                a(href = "/jobs/${job}/files/job.log") {
                    text("Log")
                }
                text(" · ")
                a(href = "/nodes") {
                    text("Nodes")
                }
            }
            div(classes = "scroll") {
                attributes["role"] = "region"
                attributes["aria-label"] = "Execution sessions"
                attributes["tabindex"] = "0"
                table {
                    thead {
                        tr {
                            th { text("Session / allocation") }
                            th { text("Node") }
                            th { text("Phase / generation") }
                            th { text("Budget") }
                            th { text("Accepted iterations") }
                        }
                    }
                    tbody {
                        for (report in reports) {
                            val session = report.session
                            tr {
                                td {
                                    emit(session.id)
                                    br {}
                                    emit(session.allocation ?: "pending")
                                }
                                td { emit(session.node ?: "pending") }
                                td {
                                    emit(report.phase ?: session.desired.stored)
                                    text(" / ")
                                    emit(report.generation ?: 0)
                                }
                                td {
                                    emit(session.budget.cpuMillis / 1000.0)
                                    text(" vCPU / ")
                                    emit(session.budget.memoryMiB)
                                    text(" MiB")
                                }
                                td { emit(report.committed) }
                            }
                            report.preparation?.let {
                                tr {
                                    td {
                                        attributes["colspan"] = "5"
                                        emit(preparationDetails(report))
                                    }
                                }
                            }
                            report.observation?.let {
                                tr {
                                    td {
                                        attributes["colspan"] = "5"
                                        emit(it)
                                    }
                                }
                            }
                            report.resources?.let { resource ->
                                tr {
                                    td {
                                        attributes["colspan"] = "5"
                                        text("Worker cumulative CPU: ")
                                        emit(
                                            resource.cpuUsec?.let {
                                                htmlText("at least ${it / 1_000_000.0} s")
                                            } ?: buildHtml { text("unavailable") },
                                        )
                                        resource.retirement?.let {
                                            text(" · ")
                                            emit(it)
                                        }
                                        text(
                                            " · snapshot excludes later cleanup and delivery; additional CPU unknown",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            p {
                text(
                    "Live case output is provisional and limited to the first 8 MiB per stream. Full output and captures appear after the paired iteration is accepted.",
                )
            }
        }
    }
