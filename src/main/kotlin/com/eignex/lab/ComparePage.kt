package com.eignex.lab

/**
 * Several experiments side by side, each arm named by its job: a scheduled run against the one before it, or any
 * experiments over the same problems. Problems pair up by suite and name, seeds by value.
 */
internal fun comparePage(
    config: Config,
    jobs: List<Job>,
    arms: List<PlannedArm>,
    cases: List<CaseResult>,
    references: Map<ProblemKey, Reference> = emptyMap(),
): String = buildString {
    append(head("compare ${jobs.joinToString(", ") { it.id.toString() }} · eignex lab", live = false))
    append("<header><h1>Compare ")
    append(jobs.joinToString(" · ") { "<a href=\"/jobs/${it.id}\">${it.id} ${esc(it.name)}</a>" })
    append("</h1></header><div>")
    append(experimentSection(config, null, arms, cases, references))
    append("</div>")
    append(SCRIPT)
    append("</body></html>")
}
