package com.eignex.lab

/** One problem: every reference solver's verdict on it, and every time the lab ran it. */
internal fun problemPage(report: ProblemReport, repoUrl: String): String = buildString {
    append(head("${report.problem} · eignex lab", live = report.runs.any { it.status.isActive }, tab = Tab.REFERENCE))
    append("<header><h1><code>${esc(report.problem)}</code></h1><p class=\"muted\">")
    append("<a href=\"/references${query("collection" to report.collection)}\">${esc(report.collection)}</a></p></header>")
    append("<h2>Reference <small>${report.references.size} solvers, strongest first</small></h2>")
    if (report.references.isEmpty()) {
        append("<p class=\"muted\">No reference result for this problem.</p>")
    } else {
        append("<div class=\"scroll\"><table><tr><th>solver</th><th>verdict</th><th class=\"num\">objective</th>")
        append("<th class=\"num\">dual bound</th><th class=\"num\">time</th><th class=\"num\">budget</th><th>source</th><th>trust</th></tr>")
        // What the lab's comparisons take from each row: a stale one nothing, a disputed proof only its solution.
        val current = report.references.filter { !it.stale }
        val trusted = current.zip(References.trusted(current)).toMap()
        for (r in report.references) {
            val trust = when {
                r.stale -> "stale: unregistered importer or unchecked legacy policy"
                trusted[r] != r -> "proof set aside: another solver's solution contradicts it"
                else -> r.validation?.let { "solution $it" } ?: ""
            }
            append("<tr><td>${esc(r.solver)}</td><td>${verdictWord(r)}</td><td class=\"num\">${r.objective?.let(::number) ?: "–"}</td>")
            append("<td class=\"num\">${r.dualBound?.let(::number) ?: "–"}</td>")
            append("<td class=\"num\">${"%.2f".format(r.elapsedMs / 1000.0)}s</td><td class=\"num\">${r.budgetMs / 1000}s</td>")
            // An unbound row counts only against runs that recorded no source either ([Reference.sourceIdentity]).
            append("<td>${r.sourceIdentity?.let { "<code title=\"${esc(it)}\">${esc(it.take(12))}</code>" } ?: "<span class=\"muted\">unbound</span>"}</td>")
            append("<td class=\"muted\">${esc(trust)}</td></tr>")
        }
        append("</table></div>")
    }
    append("<h2>Lab runs <small>${report.totalRuns} cases")
    if (report.runs.size < report.totalRuns) {
        append(", the newest ${report.runs.size} shown: <a href=\"/problem${query("collection" to report.collection, "problem" to report.problem, "all" to "1")}\">show all</a>")
    }
    append("</small></h2>")
    if (report.runs.isEmpty()) {
        append("<p class=\"muted\">No experiment has run this problem.</p>")
    } else {
        append("<div class=\"scroll\"><table><tr><th>experiment</th><th>arm</th><th>commit</th><th>seed</th><th>verdict</th>")
        append("<th class=\"num\">time</th><th>output</th><th>when</th></tr>")
        for (run in report.runs) {
            val outcome = Outcome.of(run.record)
            val commit = commitLink(repoUrl, run.sha)
            val time = outcome?.takeIf { it.rank > 0 }?.let { "%.2fs".format(it.timeMs / 1000.0) } ?: "–"
            append("<tr><td><a href=\"/jobs/${run.job}\">${run.job}</a> ${esc(run.jobName)}</td><td>${esc(run.arm)}</td>")
            append("<td><code>$commit</code></td><td>${run.seed ?: "–"}${if (run.repeat > 0) " #${run.repeat}" else ""}</td>")
            append("<td>${verdict(outcome, run.status)}</td><td class=\"num\">$time</td>")
            append("<td><a href=\"/jobs/${run.job}/files/${run.case}.out\">out</a> <a href=\"/jobs/${run.job}/files/${run.case}.err\">err</a></td>")
            append("<td>${ago(run.createdAt)}</td></tr>")
        }
        append("</table></div>")
    }
    append(SCRIPT)
    append("</body></html>")
}

/** A reference verdict in a word, without the solver beside it. */
private fun verdictWord(r: Reference): String = when {
    r.feasible == false -> "infeasible" + if (r.proven) " (proven)" else ""
    r.feasible == null -> "<span class=\"muted\">unknown</span>"
    r.objective != null -> if (r.proven) "optimum" else "solution"
    else -> "sat"
}
