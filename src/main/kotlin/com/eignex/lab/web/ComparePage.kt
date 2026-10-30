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

/**
 * Several experiments side by side, each arm named by its job: a scheduled run against the one before it, or any
 * experiments over the same problems. Problems pair up by suite and name, seeds by value.
 */
internal fun comparePage(
    repoUrl: String,
    jobs: List<Job>,
    arms: List<PlannedArm>,
    cases: List<CaseResult>,
    references: Map<ProblemKey, Reference> = emptyMap(),
): Html = buildHtml {
    append(head("compare ${jobs.joinToString(", ") { it.id.toString() }} · eignex lab", live = false))
    append(Html("<header><h1>Compare "))
    append(jobs.joinHtml(Html(" · ")) { (Html("<a href=\"/jobs/") + it.id + Html("\">") + it.id + Html(" ") + esc(it.name) + Html("</a>")) })
    append(Html("</h1></header><div>"))
    append(experimentSection(repoUrl, null, arms, cases, references))
    append(Html("</div>"))
    append(Html(SCRIPT))
    append(Html("</body></html>"))
}
