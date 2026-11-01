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

import java.net.URLEncoder
import java.time.Instant

/** One job: its settings, controls while it is unfinished, and every command with its output. */
internal fun jobPage(
    status: StatusSettings,
    job: Job,
    failedOnly: Boolean,
    arms: List<PlannedArm> = emptyList(),
    cases: List<CaseResult> = emptyList(),
    showCommands: Boolean = true,
    previous: Long? = null,
    references: Map<ProblemKey, Reference> = emptyMap(),
    /** The downloads' holds on this job that have not lapsed ([Hold]). */
    holds: List<Hold> = emptyList(),
    /** Why the job waits, while it is queued ([Waiting.waiting]). */
    waiting: String? = null,
    /** Each host's part of the job ([Store.hostProgress]). */
    hosts: List<HostProgress> = emptyList(),
    nodes: List<JobNode> = emptyList(),
    preparation: List<ExecutionSessionReport> = emptyList(),
    /** What the job's directory holds ([JobDirView.read]); the route reads it before rendering, behind the volume probe. */
    files: JobDirView = JobDirView.read(status.paths, job),
    parallelism: Pair<Int, Int>? = null,
): Html = buildHtml {
    val active = job.status.isActive
    append(head("${job.id} ${job.name} · eignex lab", live = active))
    append(Html("<header><h1>") + job.id + Html(" ") + esc(job.name) + Html("</h1>"))
    job.experiment.description.takeIf { it.isNotBlank() }?.let { append(Html("<p class=\"description\">") + esc(it) + Html("</p>")) }
    append(Html("</header>"))
    append(Html("<div id=\"job\" data-live>"))
    append(Html("<dl class=\"meta\">"))
    append(Html("<dt>status</dt><dd>") + statusCell(job, null, nodes) + Html("</dd>"))
    if (job.backend == Backend.NOMAD) append(Html("<dt>execution</dt><dd>Nomad · <a href=\"/jobs/") + job.id + Html("/execution\">sessions and generations</a> · <a href=\"/nodes\">nodes</a></dd>"))
    for (report in preparation.filter { it.preparation != null }) {
        append(Html("<dt>preparation</dt><dd>") + preparationDetails(report) + Html("</dd>"))
    }
    append(Html("<dt>repository</dt><dd>") + esc(job.experiment.repository) + Html("</dd>"))
    append(Html("<dt>ref</dt><dd>") + refText(status.repoUrl, job) + Html("</dd>"))
    append(Html("<dt>progress</dt><dd>") + progress(job) + Html("</dd>"))
    append(Html("<dt>created</dt><dd>") + ago(job.createdAt) + Html("</dd>"))
    if (job.startedAt != null) append(Html("<dt>started</dt><dd>") + ago(job.startedAt) + Html("</dd>"))
    if (job.finishedAt != null) append(Html("<dt>finished</dt><dd>") + ago(job.finishedAt) + Html("</dd>"))
    append(Html("<dt>elapsed</dt><dd>") + elapsed(job) + Html("</dd>"))
    append(Html("<dt>where</dt><dd>") + hostCell(job, hosts, nodes, files.instances) + Html("</dd>"))
    job.experiment.nodeTags?.let { selector ->
        append(Html("<dt>node tags</dt><dd>"))
        if (selector.require.isNotEmpty()) append(Html("require ") + (esc(selector.require.sorted().joinToString())))
        if (selector.exclude.isNotEmpty()) append(Html("; exclude ") + (esc(selector.exclude.sorted().joinToString())))
        append(Html("</dd>"))
    }
    if (hosts.size > 1 || hosts.any { it.host != RecordedHost.Local }) {
        // One job's iterations may run on different machines, every arm of one iteration on the same one.
        append(Html("<dt>hosts</dt><dd>") + (hosts.joinHtml(Html("<br>")) { esc(it.text()) }))
        append(Html("<br><small class=\"muted\">each iteration's arms ran on one machine; timing across iterations mixes machines</small></dd>"))
    }
    job.experiment.capped.takeIf { it.isNotEmpty() }?.let { caps ->
        append(Html("<dt>capped</dt><dd>") + (caps.joinHtml(Html("<br>")) { esc(it) }) + Html("</dd>"))
    }
    job.experiment.compilationRecording?.let { mode ->
        val recording = if (mode == Experiments.COMPILATION_JFR_LOG) "compilation and HotSpot log" else "compilation recording"
        append(Html("<dt>instrumented</dt><dd><span class=\"PARTIAL\">") + recording + Html("</span>: every compilation of each "))
        append(Html("case's CLI is recorded, so its times are no runtime measurement</dd>"))
    }
    append(Html("<dt>vcpus</dt><dd>") + job.vcpus + Html("</dd><dt>priority</dt><dd>") + job.priority + Html("</dd>"))
    append(Html("<dt>files</dt><dd><a href=\"/jobs/") + job.id + Html("/files\">all files</a>"))
    for (log in listOf("setup.log", "job.log")) {
        if (log in files.logs) append(Html(" · <a href=\"/jobs/") + job.id + Html("/files/") + log + Html("\">") + log + Html("</a>"))
    }
    append(Html("</dd>"))
    holds.maxOfOrNull { it.expiresAt }?.let { end ->
        append(Html("<dt>held</dt><dd>held for download until ") + clockTime(end) + Html(": the disk's trim leaves its files alone</dd>"))
    }
    append(Html("</dl>"))
    parallelism?.takeIf { active }?.let { (executing, pending) ->
        append(Html("<p>") + executing + Html(" executing / ") + Experiments.caseConcurrency(job.vcpus, Experiments.caseCpus(job.experiment)) + Html(" requested slots"))
        if (pending > 0) append(Html(" · ") + pending + Html(" awaiting allocation"))
        append(Html(" · <a href=\"/jobs/") + job.id + Html("/execution\">worker details</a></p>"))
    }
    job.error?.let { append(Html("<pre class=\"error\">") + esc(it) + Html("</pre>")) }
    if (job.backend == Backend.NOMAD && (job.status == Status.FAILED || job.status == Status.CANCELLED)) {
        append(Html("<div class=\"actions\"><button onclick=\"act('/jobs/") + job.id + Html("/retry', null, 'Queue job ") + job.id + Html(" again?')\">retry</button>"))
        append(Html("<span class=\"muted\">queues its unfinished cases again; finished ones are kept</span></div>"))
    }
    if (active) {
        val id = job.id
        append(Html("<div class=\"actions\">"))
        append(
            if (job.paused) {
                (Html("<button onclick=\"act('/jobs/") + id + Html("/resume')\">resume</button>"))
            } else {
                (Html("<button onclick=\"act('/jobs/") + id + Html("/pause')\">pause</button>"))
            },
        )
        append(Html("<label>priority <input id=\"priority\" type=\"number\" value=\"") + job.priority + Html("\"></label>"))
        append(Html("<button onclick=\"act('/jobs/") + id + Html("/priority', {priority: +document.getElementById('priority').value})\">set</button>"))
        append(Html("<label>vcpus <input id=\"vcpus\" type=\"number\" min=\"1\" value=\"") + job.vcpus + Html("\"></label>"))
        append(Html("<button onclick=\"act('/jobs/") + id + Html("/vcpus', {vcpus: +document.getElementById('vcpus').value})\">set</button>"))
        if (!job.cancelRequested) {
            append(Html("<button class=\"danger\" onclick=\"act('/jobs/") + id + Html("/cancel', null, 'Cancel job ") + id + Html("?')\">cancel</button>"))
        }
        append(Html("</div>"))
    }
    if (previous != null) {
        append(Html("<p>Previous run of this schedule: <a href=\"/jobs/") + previous + Html("\">") + previous + Html("</a> · "))
        append(Html("<a href=\"/compare?jobs=") + previous + Html(",") + job.id + Html("\">compare with it</a> · "))
        append(Html("<a href=\"/trend") + query("name" to job.origin.series) + Html("\">trend</a></p>"))
    }
    val spec = job.experiment
    when (spec.work) {
        is Work.Analysis -> append(analysisSection(job, spec, files))
        is Work.Bench -> {
            if (cases.any { it.problem.benchCase?.attributes?.get("recordSchema") == "solver-v1" })
                append(experimentSection(status.repoUrl, job.id, arms, cases, references))
            append(benchSection(job, cases, arms))
        }
        is Work.Profile, is Work.Script -> append(singleCaseSection(job, spec, files))
        Work.Recipe -> append(experimentSection(status.repoUrl, job.id, arms, cases, references))
    }
    if (!showCommands) {
        append(Html("<p><a href=\"/jobs/") + job.id + Html("?commands\">every case's command</a></p></div>"))
        append(Html(SCRIPT))
        append(Html("</body></html>"))
        return@buildHtml
    }
    val commands = if (failedOnly) job.commands.filter { it.status == Status.FAILED } else job.commands
    append(Html("<h2>Commands"))
    if (failedOnly) {
        append(Html(" <small>") + commands.size + Html(" failed of ") + job.commands.size + Html(" · <a href=\"/jobs/") + job.id + Html("\">show all</a></small>"))
    } else if (job.failed > 0) {
        append(Html(" <small><a href=\"/jobs/") + job.id + Html("?failed\">show the ") + job.failed + Html(" failed</a></small>"))
    }
    append(Html("</h2><div class=\"scroll\"><table><tr><th>#</th><th>command</th><th>status</th><th>host</th><th>started</th>"))
    append(Html("<th>elapsed</th><th>output</th></tr>"))
    val legacyHost = if (job.experiment.host == Experiments.AWS_HOST) RecordedHost.UnknownCloud else RecordedHost.Local
    for (command in commands) {
        val exit = command.exitCode?.takeIf { it != 0 }?.let { (Html("<br><small>exit ") + it + Html("</small>")) } ?: ""
        append(Html("<tr><td>") + command.index + Html("</td><td><code>") + esc(command.cmd) + Html("</code></td>"))
        append(Html("<td class=\"") + command.status + Html("\">") + command.status + exit + terminations(files, command.index) + Html("</td>"))
        // Where it ran; a case started before the lab recorded that ran on its job's host.
        val host = command.host ?: legacyHost.takeIf { command.status != Status.QUEUED && command.status != Status.CANCELLED }
        append(Html("<td>") + (host?.let { (Html("<code>") + esc(it.text) + Html("</code>")) }.orEmpty()) + Html("</td>"))
        append(Html("<td>") + ago(command.startedAt) + Html("</td><td>") + duration(command.startedAt, command.finishedAt) + Html("</td><td>"))
        if (command.status != Status.QUEUED) {
            append(output(job.id, files, "${command.index}.out", command.status == Status.RUNNING))
            append(Html("<br>"))
            append(output(job.id, files, "${command.index}.err", command.status == Status.RUNNING))
        }
        append(Html("</td></tr>"))
    }
    append(Html("</table></div></div>"))
    append(Html(SCRIPT))
    append(Html("</body></html>"))
}

/** A profile job, or a retired script job: what it ran, its exit, and what it left in `$OUT`, each readable with the job's files. */
private fun singleCaseSection(job: Job, spec: ExperimentSpec, files: JobDirView): Html = buildHtml {
    val profile = spec.work.profile
    if (profile != null) {
        append(Html("<h2>Profile</h2><p>Runs <code>") + esc(profile.script ?: profile.main) + Html("</code>"))
        if (profile.args.isNotEmpty()) append(Html(" with <code>") + (esc(profile.args.joinToString(" "))) + Html("</code>"))
        append(Html(" from <code>") + esc(spec.repository) + Html("</code> at the job's commit"))
        if (profile.script == null) append(Html(", recording JFR to <code>profile.jfr</code> in <code>") + ("$") + Html("OUT</code>"))
        else append(Html("; its output files are kept in <code>") + ("$") + Html("OUT</code>"))
        append(Html(".</p>"))
        append(Html("<p>Time limit: ") + (esc(Experiments.profileLimitText(spec))) + Html(" (") + Experiments.duration(Experiments.PROFILE_TIMEOUT_MS) + Html(" unless the spec's "))
        append(Html("<code>timeout</code> says, at most ") + Experiments.duration(Experiments.PROFILE_MAX_TIMEOUT_MS) + Html("); at it the main gets SIGTERM, and SIGKILL ") + Experiments.STOP_GRACE_SEC + Html(" s later if still running.</p>"))
        Experiments.profileRunTimeoutSec(profile)?.let { append(Html("<p>Whole-job active-time limit: ") + it + Html(" seconds, including setup and cleanup.</p>")) }
        Experiments.profileCaps(spec).forEach { append(Html("<p class=\"muted\">Capped: ") + esc(it) + Html(".</p>")) }
    } else {
        append(Html("<h2>Script</h2><p class=\"muted\">A retired script job: the lab no longer runs these.</p>"))
    }
    (spec.work as? Work.Script)?.let { script ->
        script.file?.let { append(Html("<p>Runs <code>") + esc(it) + Html("</code> from the repository at the job's commit.</p>")) }
        script.script?.let { append(Html("<pre class=\"script\">") + esc(it) + Html("</pre>")) }
    }
    val exit = files.exit
    append(Html("<p>"))
    append(if (exit == null) Html("<span class=\"muted\">not finished</span>") else (Html("exit <b>") + esc(exit) + Html("</b>")))
    val limited = exit == "timeout" || files.terminations[0].orEmpty().any { line ->
        line.substringAfter(' ').let { it.startsWith("timeout:") || it.startsWith("deadline:") }
    }
    if (limited) append(Html(" · <b>stopped at its time limit</b>; what it wrote before then is kept"))
    append(terminations(files, 0))
    append(Html(" · <a href=\"/jobs/") + job.id + Html("/files/0.out\">stdout</a> · <a href=\"/jobs/") + job.id + Html("/files/0.err\">stderr</a>"))
    val out = files.produced
    if (out.isNotEmpty()) {
        append(Html(" · <b>") + ("$") + Html("OUT</b>: "))
        append(out.joinHtml(Html(", ")) { (Html("<a href=\"/jobs/") + job.id + Html("/files/") + (esc(pathUrl(it))) + Html("\"><code>") + (esc(it.removePrefix("cases/0/out/"))) + Html("</code></a>")) })
    }
    append(Html("</p>"))
}

/** An analysis job: the job whose captures it analysed, and the reports it wrote, once it has. */
private fun analysisSection(job: Job, spec: ExperimentSpec, files: JobDirView): Html = buildHtml {
    val analyze = spec.work.analysis ?: return@buildHtml
    if (analyze.kind == AnalyzeSpec.CHECK) {
        append(Html("<h2>Recheck</h2><p>The lab's source checkers, run again over the stored records of job "))
        append(Html("<a href=\"/jobs/") + analyze.job + Html("\">") + analyze.job + Html("</a>: each Solver solution claim in a format the lab checks gets a "))
        append(Html("fresh verdict, which its pages, trend and comparisons then read. Nothing was built and no solver ran.</p>"))
        val result = files.recheck
        if (result == null) {
            append(Html("<p class=\"muted\">") + (if (job.status.isActive) "Not finished." else "No result written: see job.log.") + Html("</p>"))
        } else {
            val states = result.states.entries.joinToString(", ") { "${it.value} ${it.key}" }.ifEmpty { "none" }
            append(Html("<p>") + result.checked + Html(" of ") + result.cases + Html(" cases checked: ") + states + Html("; policies ") + (esc(result.policies.joinToString())) + Html(".</p>"))
        }
        return@buildHtml
    }
    if (analyze.archive != null) {
        val link = if ("://" in analyze.archive) (Html("<a href=\"") + esc(analyze.archive) + Html("\">") + (esc(analyze.archive.substringAfterLast('/'))) + Html("</a>"))
            else (Html("<code>") + esc(analyze.archive) + Html("</code>"))
        append(Html("<h2>Analysis</h2><p>The lab's ") + esc(analyze.kind) + Html(" analyser, run over the captures in the pinned archive ") + link + Html(" "))
        append(Html("(sha256 <code>") + (esc(analyze.sha256.orEmpty())) + Html("</code>), checked against its pin and verified against its manifest, "))
        append(Html("writing each case's ") + CompilationReports.REPORT + Html(" into this job's files. Nothing was built and no solver ran.</p>"))
    } else {
        append(Html("<h2>Analysis</h2><p>The lab's ") + esc(analyze.kind) + Html(" analyser, run again over the retained captures of job "))
        append(Html("<a href=\"/jobs/") + analyze.job + Html("\">") + analyze.job + Html("</a>, writing each case's ") + CompilationReports.REPORT + Html(" afresh. Nothing "))
        append(Html("was built and no solver ran.</p>"))
    }
    // A schema 1 analysis.json, from before archives, reads too: it always names its job.
    val result = files.analysis
    if (result == null) {
        append(Html("<p class=\"muted\">") + (if (job.status.isActive) "Not finished." else "No reports written: see job.log.") + Html("</p>"))
        return@buildHtml
    }
    result.archive?.let { archive ->
        append(Html("<p>Archive <code>") + esc(archive.name) + Html("</code>: ") + archive.bytes + Html(" bytes, ") + archive.verifiedFiles + Html(" files verified against manifest "))
        append(Html("<code>") + (esc(archive.manifest.substringAfterLast('/'))) + Html("</code> (sha256 <code>") + esc(archive.manifestSha256) + Html("</code>)"))
        archive.campaign?.let { append(Html(", campaign <code>") + esc(it) + Html("</code>")) }
        append(Html(".</p>"))
    }
    val files = result.job ?: job.id
    append(Html("<div class=\"scroll\"><table><tr><th>case</th><th class=\"num\">compilations</th><th class=\"num\">compiler ms</th>"))
    append(Html("<th>report</th><th>missing or incomplete</th></tr>"))
    for (entry in result.reports) {
        append(Html("<tr><td>") + entry.case + Html("</td><td class=\"num\">") + entry.compilations + Html("</td><td class=\"num\">") + ("%.1f".format(entry.compilerMs)) + Html("</td>"))
        append(Html("<td><a href=\"/jobs/") + files + Html("/files/") + entry.report + Html("\">") + esc(entry.report) + Html("</a>"))
        entry.tasks?.let { append(Html(" · <a href=\"/jobs/") + files + Html("/files/") + it + Html("\">tasks</a>")) }
        append(Html("</td>"))
        append(Html("<td>") + (if (entry.complete) Html("<span class=\"muted\">none</span>") else (Html("<span class=\"PARTIAL\">") + (esc(entry.markers.joinToString(", "))) + Html("</span>"))) + Html("</td></tr>"))
    }
    append(Html("</table></div>"))
}

/** Why case [index]'s processes were ended ([Termination]), a line each, with when it happened on hover. */
private fun terminations(files: JobDirView, index: Int): Html = files.terminations[index].orEmpty().joinHtml { line ->
    val at = line.substringBefore(' ').toLongOrNull()
    val title = at?.let { (Html(" title=\"") + java.time.Instant.ofEpochMilli(it) + Html("\"")) }.orEmpty()
    (Html("<br><small class=\"muted\"") + title + Html(">") + (esc(if (at == null) line else line.substringAfter(' '))) + Html("</small>"))
}

private fun duration(from: Long?, to: Long?): String {
    if (from == null) return ""
    return clock(((to ?: now()) - from) / 1000)
}

private fun benchSection(job: Job, cases: List<CaseResult>, arms: List<PlannedArm>): Html = buildHtml {
    append(Html("<h2>Bench results</h2><p>Repository <code>") + esc(job.experiment.repository) + Html("</code> · "))
    append(Html("<a href=\"/experiments/") + job.id + Html("/metrics.csv\">metrics CSV</a> · "))
    append(Html("<a href=\"/experiments/") + job.id + Html("/cases\">case records</a> · <a href=\"/jobs/") + job.id + Html("/files\">all files</a></p>"))
    for (case in cases) {
        val result = BenchRuns.result(case.record?.toString())
        append(Html("<h3>") + esc(case.problem.problem) + Html(" · ") + esc(case.arm) + Html("</h3><p>") + esc(result?.status?.name ?: case.status.name))
        arms.firstOrNull { it.arm.label == case.arm }?.let { append(Html(" · commit <code>") + (esc(it.sha.take(12))) + Html("</code>")) }
        case.seed?.let { append(Html(" · seed ") + it) }
        append(Html(" · repeat ") + (case.repeat + 1) + Html("</p>"))
        result?.message?.let { append(Html("<p>") + esc(it) + Html("</p>")) }
        if (result != null && result.metrics.isNotEmpty()) {
            append(Html("<table><tr><th>Metric</th><th>Value</th><th>Unit</th><th>Direction</th></tr>"))
            result.metrics.forEach { metric -> append(Html("<tr><td>") + esc(metric.name) + Html("</td><td>") + metric.value + Html("</td><td>") + esc(metric.unit) + Html("</td><td>") + metric.direction.name.lowercase() + Html("</td></tr>")) }
            append(Html("</table>"))
        }
        result?.artifacts?.forEach { artifact ->
            val path = artifact.path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
            append(Html("<p><a href=\"/jobs/") + job.id + Html("/files/cases/") + case.index + Html("/out/") + path + Html("\">") + esc(artifact.path) + Html("</a></p>"))
        }
    }
}
