package com.eignex.lab

internal fun executionPage(job: Long, reports: List<ExecutionSessionReport>): String = buildString {
    append(head("Execution · job $job", live = true))
    append("<header><h1>Execution · job $job</h1></header><div id=\"execution\" data-live>")
    append("<p><a href=\"/jobs/$job\">Experiment</a> · <a href=\"/jobs/$job/files/job.log\">Log</a> · <a href=\"/nodes\">Nodes</a></p>")
    append("<table><thead><tr><th>Session / allocation</th><th>Node</th><th>Phase / generation</th><th>Budget</th><th>Accepted iterations</th></tr></thead><tbody>")
    for (report in reports) {
        val session = report.session
        append("<tr><td>${esc(session.id)}<br>${esc(session.allocation ?: "pending")}</td><td>${esc(session.node ?: "pending")}</td>")
        append("<td>${esc(report.phase ?: session.desired.stored)} / ${report.generation ?: 0}</td><td>${session.budget.cpuMillis / 1000.0} vCPU / ${session.budget.memoryMiB} MiB</td><td>${report.committed}</td></tr>")
        report.preparation?.let { append("<tr><td colspan=\"5\">${preparationDetails(report)}</td></tr>") }
        report.observation?.let { append("<tr><td colspan=\"5\">${esc(it)}</td></tr>") }
    }
    append("</tbody></table><p>Live case output is provisional and limited to the first 8 MiB per stream. Full output and captures appear after the paired iteration is accepted.</p></div></body></html>")
}
