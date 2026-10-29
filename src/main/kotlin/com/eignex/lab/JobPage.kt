package com.eignex.lab

import java.net.URLEncoder
import java.time.Instant

/** One job: its settings, controls while it is unfinished, and every command with its output. */
internal fun jobPage(
    config: Config,
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
    files: JobDirView = JobDirView.read(config, job),
): String = buildString {
    val active = job.status.isActive
    append(head("${job.id} ${job.name} · eignex lab", live = active))
    append("<header><h1>${job.id} ${esc(job.name)}</h1>")
    job.experiment.description.takeIf { it.isNotBlank() }?.let { append("<p class=\"description\">${esc(it)}</p>") }
    append("</header>")
    append("<div id=\"job\" data-live>")
    append("<dl class=\"meta\">")
    append("<dt>status</dt><dd>${statusCell(job, null, nodes)}</dd>")
    if (job.backend == Backend.NOMAD) append("<dt>execution</dt><dd>Nomad · <a href=\"/jobs/${job.id}/execution\">sessions and generations</a> · <a href=\"/nodes\">nodes</a></dd>")
    for (report in preparation.filter { it.preparation != null }) {
        append("<dt>preparation</dt><dd>${preparationDetails(report)}</dd>")
    }
    append("<dt>repository</dt><dd>${esc(job.experiment.repository)}</dd>")
    append("<dt>ref</dt><dd>${refText(config, job)}</dd>")
    append("<dt>progress</dt><dd>${progress(job)}</dd>")
    append("<dt>created</dt><dd>${ago(job.createdAt)}</dd>")
    if (job.startedAt != null) append("<dt>started</dt><dd>${ago(job.startedAt)}</dd>")
    if (job.finishedAt != null) append("<dt>finished</dt><dd>${ago(job.finishedAt)}</dd>")
    append("<dt>elapsed</dt><dd>${elapsed(job)}</dd>")
    append("<dt>where</dt><dd>${hostCell(config, job, hosts, nodes, files.instances)}</dd>")
    job.experiment.nodeTags?.let { selector ->
        append("<dt>node tags</dt><dd>")
        if (selector.require.isNotEmpty()) append("require ${esc(selector.require.sorted().joinToString())}")
        if (selector.exclude.isNotEmpty()) append("; exclude ${esc(selector.exclude.sorted().joinToString())}")
        append("</dd>")
    }
    if (hosts.size > 1 || hosts.any { it.host != RecordedHost.Local }) {
        // One job's iterations may run on different machines, every arm of one iteration on the same one.
        append("<dt>hosts</dt><dd>${hosts.joinToString("<br>") { esc(it.text()).markup }}")
        append("<br><small class=\"muted\">each iteration's arms ran on one machine; timing across iterations mixes machines</small></dd>")
    }
    job.experiment.capped.takeIf { it.isNotEmpty() }?.let { caps ->
        append("<dt>capped</dt><dd>${caps.joinToString("<br>") { esc(it).markup }}</dd>")
    }
    job.experiment.compilationRecording?.let { mode ->
        val recording = if (mode == Experiments.COMPILATION_JFR_LOG) "compilation and HotSpot log" else "compilation recording"
        append("<dt>instrumented</dt><dd><span class=\"PARTIAL\">$recording</span>: every compilation of each ")
        append("case's CLI is recorded, so its times are no runtime measurement</dd>")
    }
    append("<dt>parallel</dt><dd>${job.parallel}</dd><dt>priority</dt><dd>${job.priority}</dd>")
    append("<dt>files</dt><dd><a href=\"/jobs/${job.id}/files\">all files</a>")
    for (log in listOf("setup.log", "job.log")) {
        if (log in files.logs) append(" · <a href=\"/jobs/${job.id}/files/$log\">$log</a>")
    }
    append("</dd>")
    holds.maxOfOrNull { it.expiresAt }?.let { end ->
        append("<dt>held</dt><dd>held for download until ${clockTime(end)}: the disk's trim leaves its files alone</dd>")
    }
    append("</dl>")
    job.error?.let { append("<pre class=\"error\">${esc(it)}</pre>") }
    if (job.backend == Backend.NOMAD && (job.status == Status.FAILED || job.status == Status.CANCELLED)) {
        append("<div class=\"actions\"><button onclick=\"act('/jobs/${job.id}/retry', null, 'Queue job ${job.id} again?')\">retry</button>")
        append("<span class=\"muted\">queues its unfinished cases again; finished ones are kept</span></div>")
    }
    if (active) {
        val id = job.id
        append("<div class=\"actions\">")
        append(
            if (job.paused) {
                "<button onclick=\"act('/jobs/$id/resume')\">resume</button>"
            } else {
                "<button onclick=\"act('/jobs/$id/pause')\">pause</button>"
            },
        )
        append("<label>priority <input id=\"priority\" type=\"number\" value=\"${job.priority}\"></label>")
        append("<button onclick=\"act('/jobs/$id/priority', {priority: +document.getElementById('priority').value})\">set</button>")
        append("<label>parallel <input id=\"parallel\" type=\"number\" min=\"1\" max=\"${config.maxParallel}\" value=\"${job.parallel}\"></label>")
        append("<button onclick=\"act('/jobs/$id/parallel', {parallel: +document.getElementById('parallel').value})\">set</button>")
        if (!job.cancelRequested) {
            append("<button class=\"danger\" onclick=\"act('/jobs/$id/cancel', null, 'Cancel job $id?')\">cancel</button>")
        }
        append("</div>")
    }
    if (previous != null) {
        append("<p>Previous run of this schedule: <a href=\"/jobs/$previous\">$previous</a> · ")
        append("<a href=\"/compare?jobs=$previous,${job.id}\">compare with it</a> · ")
        append("<a href=\"/trend${query("name" to job.origin.series)}\">trend</a></p>")
    }
    val spec = job.experiment
    if (spec.isAnalysis) {
        append(analysisSection(job, spec, files))
    } else if (spec.bench != null) {
        if (cases.any { it.problem.benchCase?.attributes?.get("recordSchema") == "solver-v1" })
            append(experimentSection(config, job.id, arms, cases, references))
        append(benchSection(job, cases, arms))
    } else if (spec.isProfile || spec.isScript) {
        append(singleCaseSection(job, spec, files))
    } else {
        append(experimentSection(config, job.id, arms, cases, references))
    }
    if (!showCommands) {
        append("<p><a href=\"/jobs/${job.id}?commands\">every case's command</a></p></div>")
        append(SCRIPT)
        append("</body></html>")
        return@buildString
    }
    val commands = if (failedOnly) job.commands.filter { it.status == Status.FAILED } else job.commands
    append("<h2>Commands")
    if (failedOnly) {
        append(" <small>${commands.size} failed of ${job.commands.size} · <a href=\"/jobs/${job.id}\">show all</a></small>")
    } else if (job.failed > 0) {
        append(" <small><a href=\"/jobs/${job.id}?failed\">show the ${job.failed} failed</a></small>")
    }
    append("</h2><div class=\"scroll\"><table><tr><th>#</th><th>command</th><th>status</th><th>host</th><th>started</th>")
    append("<th>elapsed</th><th>output</th></tr>")
    val legacyHost = if (job.experiment.host == Experiments.AWS_HOST) RecordedHost.UnknownCloud else RecordedHost.Local
    for (command in commands) {
        val exit = command.exitCode?.takeIf { it != 0 }?.let { "<br><small>exit $it</small>" } ?: ""
        append("<tr><td>${command.index}</td><td><code>${esc(command.cmd)}</code></td>")
        append("<td class=\"${command.status}\">${command.status}$exit${terminations(files, command.index)}</td>")
        // Where it ran; a case started before the lab recorded that ran on its job's host.
        val host = command.host ?: legacyHost.takeIf { command.status != Status.QUEUED && command.status != Status.CANCELLED }
        append("<td>${host?.let { "<code>${esc(it.text)}</code>" }.orEmpty()}</td>")
        append("<td>${ago(command.startedAt)}</td><td>${duration(command.startedAt, command.finishedAt)}</td><td>")
        if (command.status != Status.QUEUED) {
            append(output(job.id, files, "${command.index}.out", command.status == Status.RUNNING))
            append("<br>")
            append(output(job.id, files, "${command.index}.err", command.status == Status.RUNNING))
        }
        append("</td></tr>")
    }
    append("</table></div></div>")
    append(SCRIPT)
    append("</body></html>")
}

/** A profile job, or a retired script job: what it ran, its exit, and what it left in `$OUT`, each readable with the job's files. */
private fun singleCaseSection(job: Job, spec: ExperimentSpec, files: JobDirView): String = buildString {
    val profile = spec.profile
    if (profile != null) {
        append("<h2>Profile</h2><p>Runs <code>${esc(profile.script ?: profile.main)}</code>")
        if (profile.args.isNotEmpty()) append(" with <code>${esc(profile.args.joinToString(" "))}</code>")
        append(" from <code>${esc(spec.repository)}</code> at the job's commit")
        if (profile.script == null) append(", recording JFR to <code>profile.jfr</code> in <code>${"$"}OUT</code>")
        else append("; its output files are kept in <code>${"$"}OUT</code>")
        append(".</p>")
        append("<p>Time limit: ${esc(Experiments.profileLimitText(spec))} (${Experiments.duration(Experiments.PROFILE_TIMEOUT_MS)} unless the spec's ")
        append("<code>timeout</code> says, at most ${Experiments.duration(Experiments.PROFILE_MAX_TIMEOUT_MS)}); at it the main gets SIGTERM, and SIGKILL ${Experiments.STOP_GRACE_SEC} s later if still running.</p>")
        Experiments.profileRunTimeoutSec(profile)?.let { append("<p>Whole-job active-time limit: $it seconds, including setup and cleanup.</p>") }
        Experiments.profileCaps(spec).forEach { append("<p class=\"muted\">Capped: ${esc(it)}.</p>") }
    } else {
        append("<h2>Script</h2><p class=\"muted\">A retired script job: the lab no longer runs these.</p>")
    }
    spec.scriptFile?.takeIf { it.isNotBlank() }?.let { append("<p>Runs <code>${esc(it)}</code> from the repository at the job's commit.</p>") }
    spec.script?.takeIf { it.isNotBlank() }?.let { append("<pre class=\"script\">${esc(it)}</pre>") }
    val exit = files.exit
    append("<p>")
    append(if (exit == null) "<span class=\"muted\">not finished</span>" else "exit <b>${esc(exit)}</b>")
    val limited = exit == "timeout" || files.terminations[0].orEmpty().any { line ->
        line.substringAfter(' ').let { it.startsWith("timeout:") || it.startsWith("deadline:") }
    }
    if (limited) append(" · <b>stopped at its time limit</b>; what it wrote before then is kept")
    append(terminations(files, 0))
    append(" · <a href=\"/jobs/${job.id}/files/0.out\">stdout</a> · <a href=\"/jobs/${job.id}/files/0.err\">stderr</a>")
    val out = files.produced
    if (out.isNotEmpty()) {
        append(" · <b>${"$"}OUT</b>: ")
        append(out.joinToString(", ") { "<a href=\"/jobs/${job.id}/files/${esc(pathUrl(it))}\"><code>${esc(it.removePrefix("cases/0/out/"))}</code></a>" })
    }
    append("</p>")
}

/** An analysis job: the job whose captures it analysed, and the reports it wrote, once it has. */
private fun analysisSection(job: Job, spec: ExperimentSpec, files: JobDirView): String = buildString {
    val analyze = spec.analyze ?: return@buildString
    if (analyze.kind == AnalyzeSpec.CHECK) {
        append("<h2>Recheck</h2><p>The lab's source checkers, run again over the stored records of job ")
        append("<a href=\"/jobs/${analyze.job}\">${analyze.job}</a>: each Solver solution claim in a format the lab checks gets a ")
        append("fresh verdict, which its pages, trend and comparisons then read. Nothing was built and no solver ran.</p>")
        val result = files.recheck
        if (result == null) {
            append("<p class=\"muted\">${if (job.status.isActive) "Not finished." else "No result written: see job.log."}</p>")
        } else {
            val states = result.states.entries.joinToString(", ") { "${it.value} ${esc(it.key)}" }.ifEmpty { "none" }
            append("<p>${result.checked} of ${result.cases} cases checked: $states; policies ${esc(result.policies.joinToString())}.</p>")
        }
        return@buildString
    }
    if (analyze.archive != null) {
        val link = if ("://" in analyze.archive) "<a href=\"${esc(analyze.archive)}\">${esc(analyze.archive.substringAfterLast('/'))}</a>"
            else "<code>${esc(analyze.archive)}</code>"
        append("<h2>Analysis</h2><p>The lab's ${esc(analyze.kind)} analyser, run over the captures in the pinned archive $link ")
        append("(sha256 <code>${esc(analyze.sha256.orEmpty())}</code>), checked against its pin and verified against its manifest, ")
        append("writing each case's ${CompilationReports.REPORT} into this job's files. Nothing was built and no solver ran.</p>")
    } else {
        append("<h2>Analysis</h2><p>The lab's ${esc(analyze.kind)} analyser, run again over the retained captures of job ")
        append("<a href=\"/jobs/${analyze.job}\">${analyze.job}</a>, writing each case's ${CompilationReports.REPORT} afresh. Nothing ")
        append("was built and no solver ran.</p>")
    }
    // A schema 1 analysis.json, from before archives, reads too: it always names its job.
    val result = files.analysis
    if (result == null) {
        append("<p class=\"muted\">${if (job.status.isActive) "Not finished." else "No reports written: see job.log."}</p>")
        return@buildString
    }
    result.archive?.let { archive ->
        append("<p>Archive <code>${esc(archive.name)}</code>: ${archive.bytes} bytes, ${archive.verifiedFiles} files verified against manifest ")
        append("<code>${esc(archive.manifest.substringAfterLast('/'))}</code> (sha256 <code>${esc(archive.manifestSha256)}</code>)")
        archive.campaign?.let { append(", campaign <code>${esc(it)}</code>") }
        append(".</p>")
    }
    val files = result.job ?: job.id
    append("<div class=\"scroll\"><table><tr><th>case</th><th class=\"num\">compilations</th><th class=\"num\">compiler ms</th>")
    append("<th>report</th><th>missing or incomplete</th></tr>")
    for (entry in result.reports) {
        append("<tr><td>${entry.case}</td><td class=\"num\">${entry.compilations}</td><td class=\"num\">${"%.1f".format(entry.compilerMs)}</td>")
        append("<td><a href=\"/jobs/$files/files/${entry.report}\">${esc(entry.report)}</a>")
        entry.tasks?.let { append(" · <a href=\"/jobs/$files/files/$it\">tasks</a>") }
        append("</td>")
        append("<td>${if (entry.complete) "<span class=\"muted\">none</span>" else "<span class=\"PARTIAL\">${esc(entry.markers.joinToString(", "))}</span>"}</td></tr>")
    }
    append("</table></div>")
}

/** Why case [index]'s processes were ended ([Termination]), a line each, with when it happened on hover. */
private fun terminations(files: JobDirView, index: Int): String = files.terminations[index].orEmpty().joinToString("") { line ->
    val at = line.substringBefore(' ').toLongOrNull()
    val title = at?.let { " title=\"${java.time.Instant.ofEpochMilli(it)}\"" }.orEmpty()
    "<br><small class=\"muted\"$title>${esc(if (at == null) line else line.substringAfter(' '))}</small>"
}

private fun duration(from: Long?, to: Long?): String {
    if (from == null) return ""
    return clock(((to ?: now()) - from) / 1000)
}

private fun benchSection(job: Job, cases: List<CaseResult>, arms: List<PlannedArm>): String = buildString {
    append("<h2>Bench results</h2><p>Repository <code>${esc(job.experiment.repository)}</code> · ")
    append("<a href=\"/experiments/${job.id}/metrics.csv\">metrics CSV</a> · ")
    append("<a href=\"/experiments/${job.id}/cases\">case records</a> · <a href=\"/jobs/${job.id}/files\">all files</a></p>")
    for (case in cases) {
        val result = BenchRuns.result(case.record?.toString())
        append("<h3>${esc(case.problem.problem)} · ${esc(case.arm)}</h3><p>${esc(result?.status?.name ?: case.status.name)}")
        arms.firstOrNull { it.arm.label == case.arm }?.let { append(" · commit <code>${esc(it.sha.take(12))}</code>") }
        case.seed?.let { append(" · seed $it") }
        append(" · repeat ${case.repeat + 1}</p>")
        result?.message?.let { append("<p>${esc(it)}</p>") }
        if (result != null && result.metrics.isNotEmpty()) {
            append("<table><tr><th>Metric</th><th>Value</th><th>Unit</th><th>Direction</th></tr>")
            result.metrics.forEach { metric -> append("<tr><td>${esc(metric.name)}</td><td>${metric.value}</td><td>${esc(metric.unit)}</td><td>${metric.direction.name.lowercase()}</td></tr>") }
            append("</table>")
        }
        result?.artifacts?.forEach { artifact ->
            val path = artifact.path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
            append("<p><a href=\"/jobs/${job.id}/files/cases/${case.index}/out/$path\">${esc(artifact.path)}</a></p>")
        }
    }
}
