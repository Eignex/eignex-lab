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

internal fun executionPage(job: Long, reports: List<ExecutionSessionReport>): Html = buildHtml {
    append(head("Execution · job ${job}", live = true))
    append(Html("<header><h1>Execution · job ") + job + Html("</h1></header><div id=\"execution\" data-live>"))
    append(Html("<p><a href=\"/jobs/") + job + Html("\">Experiment</a> · <a href=\"/jobs/") + job + Html("/files/job.log\">Log</a> · <a href=\"/nodes\">Nodes</a></p>"))
    append(Html("<table><thead><tr><th>Session / allocation</th><th>Node</th><th>Phase / generation</th><th>Budget</th><th>Accepted iterations</th></tr></thead><tbody>"))
    for (report in reports) {
        val session = report.session
        append(Html("<tr><td>") + esc(session.id) + Html("<br>") + esc(session.allocation ?: "pending") + Html("</td><td>") + esc(session.node ?: "pending") + Html("</td>"))
        append(Html("<td>") + esc(report.phase ?: session.desired.stored) + Html(" / ") + (report.generation ?: 0) + Html("</td><td>") + (session.budget.cpuMillis / 1000.0) + Html(" vCPU / ") + session.budget.memoryMiB + Html(" MiB</td><td>") + report.committed + Html("</td></tr>"))
        report.preparation?.let { append(Html("<tr><td colspan=\"5\">") + preparationDetails(report) + Html("</td></tr>")) }
        report.observation?.let { append(Html("<tr><td colspan=\"5\">") + esc(it) + Html("</td></tr>")) }
    }
    append(Html("</tbody></table><p>Live case output is provisional and limited to the first 8 MiB per stream. Full output and captures appear after the paired iteration is accepted.</p></div></body></html>"))
}
