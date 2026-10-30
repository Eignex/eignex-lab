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

/** One problem: every reference solver's verdict on it, and every time the lab ran it. */
internal fun problemPage(report: ProblemReport, repoUrl: String): Html = buildHtml {
    append(head("${report.problem} · eignex lab", live = report.runs.any { it.status.isActive }, tab = Tab.REFERENCE))
    append(Html("<header><h1><code>") + esc(report.problem) + Html("</code></h1><p class=\"muted\">"))
    append(Html("<a href=\"/references") + query("collection" to report.collection) + Html("\">") + esc(report.collection) + Html("</a></p></header>"))
    append(Html("<h2>Reference <small>") + report.references.size + Html(" solvers, strongest first</small></h2>"))
    if (report.references.isEmpty()) {
        append(Html("<p class=\"muted\">No reference result for this problem.</p>"))
    } else {
        append(Html("<div class=\"scroll\"><table><tr><th>solver</th><th>verdict</th><th class=\"num\">objective</th>"))
        append(Html("<th class=\"num\">dual bound</th><th class=\"num\">time</th><th class=\"num\">budget</th><th>source</th><th>trust</th></tr>"))
        // What the lab's comparisons take from each row: a stale one nothing, a disputed proof only its solution.
        val current = report.references.filter { !it.stale }
        val trusted = current.zip(References.trusted(current)).toMap()
        for (r in report.references) {
            val trust = when {
                r.stale -> "stale: unregistered importer or unchecked legacy policy"
                trusted[r] != r -> "proof set aside: another solver's solution contradicts it"
                else -> r.validation?.let { "solution ${it}" } ?: ""
            }
            append(Html("<tr><td>") + esc(r.solver) + Html("</td><td>") + verdictWord(r) + Html("</td><td class=\"num\">") + (r.objective?.let(::number) ?: "–") + Html("</td>"))
            append(Html("<td class=\"num\">") + (r.dualBound?.let(::number) ?: "–") + Html("</td>"))
            append(Html("<td class=\"num\">") + ("%.2f".format(r.elapsedMs / 1000.0)) + Html("s</td><td class=\"num\">") + (r.budgetMs / 1000) + Html("s</td>"))
            // An unbound row counts only against runs that recorded no source either ([Reference.sourceIdentity]).
            append(Html("<td>") + (r.sourceIdentity?.let { (Html("<code title=\"") + esc(it) + Html("\">") + (esc(it.take(12))) + Html("</code>")) } ?: Html("<span class=\"muted\">unbound</span>")) + Html("</td>"))
            append(Html("<td class=\"muted\">") + esc(trust) + Html("</td></tr>"))
        }
        append(Html("</table></div>"))
    }
    append(Html("<h2>Lab runs <small>") + report.totalRuns + Html(" cases"))
    if (report.runs.size < report.totalRuns) {
        append(Html(", the newest ") + report.runs.size + Html(" shown: <a href=\"/problem") + query("collection" to report.collection, "problem" to report.problem, "all" to "1") + Html("\">show all</a>"))
    }
    append(Html("</small></h2>"))
    if (report.runs.isEmpty()) {
        append(Html("<p class=\"muted\">No experiment has run this problem.</p>"))
    } else {
        append(Html("<div class=\"scroll\"><table><tr><th>experiment</th><th>arm</th><th>commit</th><th>seed</th><th>verdict</th>"))
        append(Html("<th class=\"num\">time</th><th>output</th><th>when</th></tr>"))
        for (run in report.runs) {
            val outcome = Outcome.of(run.record)
            val commit = commitLink(repoUrl, run.sha)
            val time = outcome?.takeIf { it.rank > 0 }?.let { "%.2fs".format(it.timeMs / 1000.0) } ?: "–"
            append(Html("<tr><td><a href=\"/jobs/") + run.job + Html("\">") + run.job + Html("</a> ") + esc(run.jobName) + Html("</td><td>") + esc(run.arm) + Html("</td>"))
            append(Html("<td><code>") + commit + Html("</code></td><td>") + (run.seed ?: "–") + (if (run.repeat > 0) " #${run.repeat}" else "") + Html("</td>"))
            append(Html("<td>") + verdict(outcome, run.status) + Html("</td><td class=\"num\">") + time + Html("</td>"))
            append(Html("<td><a href=\"/jobs/") + run.job + Html("/files/") + run.case + Html(".out\">out</a> <a href=\"/jobs/") + run.job + Html("/files/") + run.case + Html(".err\">err</a></td>"))
            append(Html("<td>") + ago(run.createdAt) + Html("</td></tr>"))
        }
        append(Html("</table></div>"))
    }
    append(Html(SCRIPT))
    append(Html("</body></html>"))
}

/** A reference verdict in a word, without the solver beside it. */
private fun verdictWord(r: Reference): Html = when {
    r.feasible == false -> esc("infeasible" + if (r.proven) " (proven)" else "")
    r.feasible == null -> Html("<span class=\"muted\">unknown</span>")
    r.objective != null -> esc(if (r.proven) "optimum" else "solution")
    else -> Html("sat")
}
