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

/**
 * Several experiments side by side, each arm named by its job: a scheduled run against the one
 * before it, or any experiments over the same problems. Problems pair up by suite and name, seeds
 * by value.
 */
internal fun comparePage(
    repoUrl: String,
    jobs: List<Job>,
    arms: List<PlannedArm>,
    cases: List<CaseResult>,
    references: Map<ProblemKey, Reference> = emptyMap(),
): Html =
    page("compare ${jobs.joinToString(", ") { it.id.toString() }} · eignex lab") {
        header {
            h1 {
                text("Compare ")
                emit(
                    jobs.joinHtml(buildHtml { text(" · ") }) {
                        buildHtml {
                            a(href = "/jobs/${it.id}") {
                                emit(it.id)
                                text(" ")
                                emit(it.name)
                            }
                        }
                    },
                )
            }
        }
        div {
            emit(experimentSection(repoUrl, null, arms, cases, references))
        }
    }
