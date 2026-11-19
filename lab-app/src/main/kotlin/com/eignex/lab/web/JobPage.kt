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
import kotlinx.html.*

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
    /**
     * What the job's directory holds ([JobDirView.read]); the route reads it before rendering,
     * behind the volume probe.
     */
    files: JobDirView = JobDirView.read(status.paths, job, showCommands, failedOnly),
    parallelism: Pair<Int, Int>? = null,
): Html =
    page("${job.id} ${job.name} · eignex lab", live = job.status.isActive) {
        val active = job.status.isActive
        header {
            h1 {
                emit(job.id)
                text(" ")
                emit(job.name)
            }
            job.experiment.description
                .takeIf { it.isNotBlank() }
                ?.let {
                    p(classes = "description") {
                        emit(it)
                    }
                }
        }
        div {
            attributes["id"] = "job"
            attributes["data-live"] = ""
            section(classes = "job-overview") {
                attributes["aria-label"] = "Job overview"
                div {
                    span(classes = "eyebrow") {
                        text("Status")
                    }
                    emit(statusCell(job, null, nodes))
                }
                div {
                    span(classes = "eyebrow") {
                        text("Case progress")
                    }
                    emit(progress(job))
                }
                div {
                    span(classes = "eyebrow") {
                        text("Elapsed")
                    }
                    strong { emit(elapsed(job)) }
                }
                div {
                    span(classes = "eyebrow") {
                        text("Repository / ref")
                    }
                    emit(job.experiment.repository)
                    br {}
                    emit(refText(status.repoUrl, job))
                }
            }
            waiting?.let {
                p(classes = "note") {
                    emit(it)
                }
            }
            nav(classes = "section-links") {
                attributes["aria-label"] = "Job sections"
                a(href = "#results") {
                    text("Results")
                }
                a(href = "#job-details") {
                    text("Execution details")
                }
                if (
                    job.experiment.work == Work.Recipe ||
                        cases.any {
                            it.problem.benchCase?.attributes?.get("recordSchema") == "solver-v1"
                        }
                )
                    a(href = "#problems") {
                        text("Problems")
                    }
                if (job.experiment.work is Work.Bench)
                    a(href = "#bench-results") {
                        text("Case metrics")
                    }
                a(href = "/jobs/${job.id}/files") {
                    text("Files & logs")
                }
            }
            details(classes = "panel job-details") {
                attributes["id"] = "job-details"

                summary {
                    text("Execution details ")
                    span(classes = "muted") {
                        text("· preparation, placement and configuration")
                    }
                }
                dl(classes = "meta") {
                    if (job.backend == Backend.NOMAD) {
                        dt { text("execution") }
                        dd {
                            text("Nomad · ")
                            a(href = "/jobs/${job.id}/execution") {
                                text("sessions and generations")
                            }
                            text(" · ")
                            a(href = "/nodes") {
                                text("nodes")
                            }
                        }
                    }
                    for (report in preparation.filter { it.preparation != null }) {
                        dt { text("preparation") }
                        dd { emit(preparationDetails(report)) }
                    }
                    dt { text("created") }
                    dd { emit(ago(job.createdAt)) }
                    if (job.startedAt != null) {
                        dt { text("started") }
                        dd { emit(ago(job.startedAt)) }
                    }
                    if (job.finishedAt != null) {
                        dt { text("finished") }
                        dd { emit(ago(job.finishedAt)) }
                    }
                    dt { text("where") }
                    dd { emit(hostCell(job, hosts, nodes)) }
                    job.experiment.nodeTags?.let { selector ->
                        dt { text("node tags") }
                        dd {
                            if (selector.require.isNotEmpty()) {
                                text("require ")
                                emit(selector.require.sorted().joinToString())
                            }
                            if (selector.exclude.isNotEmpty()) {
                                text("; exclude ")
                                emit(selector.exclude.sorted().joinToString())
                            }
                        }
                    }
                    if (hosts.size > 1 || hosts.any { it.host != RecordedHost.Local }) {
                        // One job's iterations may run on different machines, every arm of one
                        // iteration on the same one.
                        dt { text("hosts") }
                        dd {
                            emit(hosts.joinHtml(buildHtml { br {} }) { htmlText(it.text()) })
                            br {}
                            small(classes = "muted") {
                                text(
                                    "each iteration's arms ran on one machine; timing across iterations mixes machines",
                                )
                            }
                        }
                    }
                    dt { text("vcpus") }
                    dd { emit(job.vcpus) }
                    dt { text("priority") }
                    dd { emit(job.priority) }
                    dt { text("files") }
                    dd {
                        a(href = "/jobs/${job.id}/files") {
                            text("all files")
                        }
                        for (log in listOf("setup.log", "job.log")) {
                            if (log in files.logs) {
                                text(" · ")
                                a(href = "/jobs/${job.id}/files/${log}") {
                                    emit(log)
                                }
                            }
                        }
                    }
                    holds
                        .maxOfOrNull { it.expiresAt }
                        ?.let { end ->
                            dt { text("held") }
                            dd {
                                text("held for download until ")
                                emit(clockTime(end))
                                text(": the disk's trim leaves its files alone")
                            }
                        }
                }
            }
            dl(classes = "meta job-notices") {
                job.experiment.capped
                    .takeIf { it.isNotEmpty() }
                    ?.let { caps ->
                        dt { text("capped") }
                        dd { emit(caps.joinHtml(buildHtml { br {} }) { htmlText(it) }) }
                    }
                if (job.experiment.measurement == Measurement.NATIVE_STARTUP) {
                    dt { text("instrumented") }
                    dd { text("native startup sampling: times are no runtime measurement") }
                }
                job.experiment.compilationRecording?.let { mode ->
                    val recording =
                        if (mode == Experiments.COMPILATION_JFR_LOG) "compilation and HotSpot log"
                        else "compilation recording"
                    dt { text("instrumented") }
                    dd {
                        span(classes = "PARTIAL") {
                            emit(recording)
                        }
                        text(": every compilation of each ")
                        text("case's CLI is recorded, so its times are no runtime measurement")
                    }
                }
            }
            parallelism
                ?.takeIf { active }
                ?.let { (executing, pending) ->
                    p {
                        emit(executing)
                        text(" executing / ")
                        emit(
                            Experiments.caseConcurrency(
                                job.vcpus,
                                Experiments.caseCpus(job.experiment),
                            ),
                        )
                        text(" requested slots")
                        if (pending > 0) {
                            text(" · ")
                            emit(pending)
                            text(" awaiting allocation")
                        }
                        text(" · ")
                        a(href = "/jobs/${job.id}/execution") {
                            text("worker details")
                        }
                    }
                }
            job.error?.let {
                pre(classes = "error") {
                    emit(it)
                }
            }
            if (
                job.backend == Backend.NOMAD &&
                    (job.status == Status.FAILED || job.status == Status.CANCELLED)
            ) {
                div(classes = "actions job-actions") {
                    button {
                        attributes["onclick"] =
                            "act('/jobs/${job.id}/retry', null, 'Queue job ${job.id} again?')"
                        text("retry")
                    }
                    span(classes = "muted") {
                        text("queues its unfinished cases again; finished ones are kept")
                    }
                }
            }
            if (active) {
                val id = job.id
                div(classes = "actions job-actions") {
                    emit(
                        if (job.paused) {
                            buildHtml {
                                button {
                                    attributes["onclick"] = "act('/jobs/${id}/resume')"
                                    text("resume")
                                }
                            }
                        } else {
                            buildHtml {
                                button {
                                    attributes["onclick"] = "act('/jobs/${id}/pause')"
                                    text("pause")
                                }
                            }
                        },
                    )
                    label {
                        text("priority ")
                        input {
                            attributes["aria-label"] = "Job priority"
                            attributes["id"] = "priority"
                            attributes["type"] = "number"
                            attributes["value"] = (job.priority).toString()
                        }
                    }
                    button {
                        attributes["onclick"] =
                            "act('/jobs/${id}/priority', {priority: +document.getElementById('priority').value})"
                        attributes["aria-label"] = "Set priority"
                        text("set")
                    }
                    label {
                        text("vcpus ")
                        input {
                            attributes["aria-label"] = "Job vCPUs"
                            attributes["id"] = "vcpus"
                            attributes["type"] = "number"
                            attributes["min"] = "1"
                            attributes["value"] = (job.vcpus).toString()
                        }
                    }
                    button {
                        attributes["onclick"] =
                            "act('/jobs/${id}/vcpus', {vcpus: +document.getElementById('vcpus').value})"
                        attributes["aria-label"] = "Set vCPUs"
                        text("set")
                    }
                    if (!job.cancelRequested) {
                        button(classes = "danger") {
                            attributes["onclick"] =
                                "act('/jobs/${id}/cancel', null, 'Cancel job ${id}?')"
                            text("cancel")
                        }
                    }
                }
            }
            if (previous != null) {
                p {
                    text("Previous run of this schedule: ")
                    a(href = "/jobs/${previous}") {
                        emit(previous)
                    }
                    text(" · ")
                    a(href = "/compare?jobs=${previous},${job.id}") {
                        text("compare with it")
                    }
                    text(" · ")
                    a(href = "/trend${query("name" to job.origin.series)}") {
                        text("trend")
                    }
                }
            }
            div {
                attributes["id"] = "results"
            }
            val spec = job.experiment
            when (spec.work) {
                is Work.Analysis -> run { emit(analysisSection(job, spec, files)) }
                is Work.Bench -> {
                    if (
                        job.origin is JobOrigin.Confirmation &&
                            job.status.isTerminal &&
                            BenchTransitions.enabled(cases)
                    ) {
                        val transitions = Confirm.confirmed(cases)
                        p {
                            text("Confirmed benchmark transitions: ")
                            emit(transitions.benchmarkFailed.size)
                            text(" failures, ")
                            emit(transitions.benchmarkRecovered.size)
                            text(" recoveries; ")
                            emit(transitions.metricLost.size)
                            text(" metric regressions, ")
                            emit(transitions.metricGained.size)
                            text(" improvements.")
                        }
                    }
                    if (
                        cases.any {
                            it.problem.benchCase?.attributes?.get("recordSchema") == "solver-v1"
                        }
                    )
                        emit(experimentSection(status.repoUrl, job.id, arms, cases, references))
                    emit(benchSection(job, cases, arms))
                }
                is Work.Profile,
                is Work.Script -> run { emit(singleCaseSection(job, spec, files)) }
                Work.Recipe ->
                    run { emit(experimentSection(status.repoUrl, job.id, arms, cases, references)) }
            }
            if (!showCommands) {
                p {
                    a(href = "/jobs/${job.id}?commands") {
                        text("every case's command")
                    }
                }
                return@div
            }
            val commands =
                if (failedOnly) job.commands.filter { it.status == Status.FAILED } else job.commands
            h2 {
                attributes["id"] = "commands"
                text("Commands")
                if (failedOnly) {
                    text(" ")
                    small {
                        emit(commands.size)
                        text(" failed of ")
                        emit(job.commands.size)
                        text(" · ")
                        a(href = "/jobs/${job.id}") {
                            text("show all")
                        }
                    }
                } else if (job.failed > 0) {
                    text(" ")
                    small {
                        a(href = "/jobs/${job.id}?failed") {
                            text("show the ")
                            emit(job.failed)
                            text(" failed")
                        }
                    }
                }
            }
            div(classes = "scroll") {
                table {
                    tr {
                        th { text("#") }
                        th { text("command") }
                        th { text("status") }
                        th { text("host") }
                        th { text("started") }
                        th { text("elapsed") }
                        th { text("output") }
                    }
                    val legacyHost =
                        if (job.experiment.host == Experiments.AWS_HOST) RecordedHost.UnknownCloud
                        else RecordedHost.Local
                    for (command in commands) {
                        val exit =
                            command.exitCode
                                ?.takeIf { it != 0 }
                                ?.let {
                                    buildHtml {
                                        br {}
                                        small {
                                            text("exit ")
                                            emit(it)
                                        }
                                    }
                                } ?: ""
                        tr {
                            td { emit(command.index) }
                            td { code { emit(command.cmd) } }
                            td(classes = (command.status).toString()) {
                                emit(command.status)
                                emit(exit)
                                emit(terminations(files, command.index))
                            }
                            // Where it ran; a case started before the lab recorded that ran on its
                            // job's host.
                            val host =
                                command.host
                                    ?: legacyHost.takeIf {
                                        command.status != Status.QUEUED &&
                                            command.status != Status.CANCELLED
                                    }
                            td {
                                emit(
                                    host?.let { buildHtml { code { emit(it.text) } } }.orEmpty(),
                                )
                            }
                            td { emit(ago(command.startedAt)) }
                            td { emit(duration(command.startedAt, command.finishedAt)) }
                            td {
                                if (command.status != Status.QUEUED) {
                                    emit(
                                        output(
                                            job.id,
                                            files,
                                            "${command.index}.out",
                                            command.status == Status.RUNNING,
                                        ),
                                    )
                                    br {}
                                    emit(
                                        output(
                                            job.id,
                                            files,
                                            "${command.index}.err",
                                            command.status == Status.RUNNING,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

/**
 * A profile job, or a retired script job: what it ran, its exit, and what it left in `$OUT`, each
 * readable with the job's files.
 */
private fun singleCaseSection(job: Job, spec: ExperimentSpec, files: JobDirView): Html = buildHtml {
    val profile = spec.work.profile
    if (profile != null) {
        h2 { text("Profile") }
        p {
            text("Runs ")
            code { emit(profile.script ?: profile.main) }
            if (profile.args.isNotEmpty()) {
                text(" with ")
                code { emit(profile.args.joinToString(" ")) }
            }
            text(" from ")
            code { emit(spec.repository) }
            text(" at the job's commit")
            if (profile.script == null) {
                text(", recording JFR to ")
                code { text("profile.jfr") }
                text(" in ")
                code {
                    emit("$")
                    text("OUT")
                }
            } else {
                text("; its output files are kept in ")
                code {
                    emit("$")
                    text("OUT")
                }
            }
            text(".")
        }
        p {
            text("Time limit: ")
            emit(Experiments.profileLimitText(spec))
            text(" (")
            emit(Experiments.duration(Experiments.PROFILE_TIMEOUT_MS))
            text(" unless the spec's ")
            code { text("timeout") }
            text(" says, at most ")
            emit(Experiments.duration(Experiments.PROFILE_MAX_TIMEOUT_MS))
            text("); at it the main gets SIGTERM, and SIGKILL ")
            emit(Experiments.STOP_GRACE_SEC)
            text(" s later if still running.")
        }
        Experiments.profileRunTimeoutSec(profile)?.let {
            p {
                text("Whole-job active-time limit: ")
                emit(it)
                text(" seconds, including setup and cleanup.")
            }
        }
        Experiments.profileCaps(spec).forEach {
            p(classes = "muted") {
                text("Capped: ")
                emit(it)
                text(".")
            }
        }
    } else {
        h2 { text("Script") }
        p(classes = "muted") {
            text("A retired script job: the lab no longer runs these.")
        }
    }
    (spec.work as? Work.Script)?.let { script ->
        script.file?.let {
            p {
                text("Runs ")
                code { emit(it) }
                text(" from the repository at the job's commit.")
            }
        }
        script.script?.let {
            pre(classes = "script") {
                emit(it)
            }
        }
    }
    val exit = files.exit
    p {
        emit(
            if (exit == null)
                buildHtml {
                    span(classes = "muted") {
                        text("not finished")
                    }
                }
            else
                buildHtml {
                    text("exit ")
                    b { emit(exit) }
                },
        )
        val limited =
            exit == "timeout" ||
                files.terminations[0].orEmpty().any { line ->
                    line.substringAfter(' ').let {
                        it.startsWith("timeout:") || it.startsWith("deadline:")
                    }
                }
        if (limited) {
            text(" · ")
            b { text("stopped at its time limit") }
            text("; what it wrote before then is kept")
        }
        emit(terminations(files, 0))
        text(" · ")
        a(href = "/jobs/${job.id}/files/0.out") {
            text("stdout")
        }
        text(" · ")
        a(href = "/jobs/${job.id}/files/0.err") {
            text("stderr")
        }
        val out = files.produced
        if (out.isNotEmpty()) {
            text(" · ")
            b {
                emit("$")
                text("OUT")
            }
            text(": ")
            emit(
                out.joinHtml(buildHtml { text(", ") }) {
                    buildHtml {
                        a(href = "/jobs/${job.id}/files/${pathUrl(it)}") {
                            code { emit(it.removePrefix("cases/0/out/")) }
                        }
                    }
                },
            )
        }
    }
}

/** An analysis job: the job whose captures it analysed, and the reports it wrote, once it has. */
private fun analysisSection(job: Job, spec: ExperimentSpec, files: JobDirView): Html = buildHtml {
    val analyze = spec.work.analysis ?: return@buildHtml
    if (analyze.kind == AnalyzeSpec.CHECK) {
        h2 { text("Recheck") }
        p {
            text("The lab's source checkers, run again over the stored records of job ")
            a(href = "/jobs/${analyze.job}") {
                emit(analyze.job)
            }
            text(": available source checkers check supported solution claims and retain a ")
            text(
                "fresh verdict, which its pages, trend and comparisons then read. Nothing was built and no solver ran.",
            )
            text(" A checker is optional: unsupported formats are reported explicitly. Attempts without a replacement receipt retain earlier verdicts.")
        }
        val result = files.recheck
        if (result == null) {
            p(classes = "muted") {
                emit(
                    if (job.status.isActive) "Not finished." else "No result written: see job.log.",
                )
            }
        } else {
            val states =
                result.states.entries
                    .joinToString(", ") { "${it.value} ${it.key}" }
                    .ifEmpty { "none" }
            p {
                emit(result.checked)
                text(" of ")
                emit(result.cases)
                text(" completed cases have replacement lab verdicts; recheck outcomes: ")
                emit(states)
                text("; policies ")
                emit(result.policies.joinToString())
                text(".")
            }
            if (result.outcomes.isNotEmpty()) {
                table {
                    thead {
                        tr { th { text("Case") }; th { text("Outcome") }; th { text("Replacement receipt") }; th { text("Reason") } }
                    }
                    tbody {
                        for (outcome in result.outcomes) tr {
                            td { emit(outcome.index) }
                            td { text(outcome.state) }
                            td { text(if (outcome.receiptWritten) "yes" else "no") }
                            td { text(outcome.reason) }
                        }
                    }
                }
            }
        }
        return@buildHtml
    }
    if (analyze.archive != null) {
        val link =
            if ("://" in analyze.archive)
                buildHtml {
                    a(href = (analyze.archive)) {
                        emit(analyze.archive.substringAfterLast('/'))
                    }
                }
            else buildHtml { code { emit(analyze.archive) } }
        h2 { text("Analysis") }
        p {
            text("The lab's ")
            emit(analyze.kind)
            text(" analyser, run over the captures in the pinned archive ")
            emit(link)
            text(" ")
            text("(sha256 ")
            code { emit(analyze.sha256.orEmpty()) }
            text("), checked against its pin and verified against its manifest, ")
            text("writing each case's ")
            emit(CompilationReports.REPORT)
            text(" into this job's files. Nothing was built and no solver ran.")
        }
    } else {
        h2 { text("Analysis") }
        p {
            text("The lab's ")
            emit(analyze.kind)
            text(" analyser, run again over the retained captures of job ")
            a(href = "/jobs/${analyze.job}") {
                emit(analyze.job)
            }
            text(", writing each case's ")
            emit(CompilationReports.REPORT)
            text(" afresh. Nothing ")
            text("was built and no solver ran.")
        }
    }
    // A schema 1 analysis.json, from before archives, reads too: it always names its job.
    val result = files.analysis
    if (result == null) {
        p(classes = "muted") {
            emit(
                if (job.status.isActive) "Not finished." else "No reports written: see job.log.",
            )
        }
        return@buildHtml
    }
    result.archive?.let { archive ->
        p {
            text("Archive ")
            code { emit(archive.name) }
            text(": ")
            emit(archive.bytes)
            text(" bytes, ")
            emit(archive.verifiedFiles)
            text(" files verified against manifest ")
            code { emit(archive.manifest.substringAfterLast('/')) }
            text(" (sha256 ")
            code { emit(archive.manifestSha256) }
            text(")")
            archive.campaign?.let {
                text(", campaign ")
                code { emit(it) }
            }
            text(".")
        }
    }
    val files = result.job ?: job.id
    div(classes = "scroll") {
        table {
            tr {
                th { text("case") }
                th(classes = "num") {
                    text("compilations")
                }
                th(classes = "num") {
                    text("compiler ms")
                }
                th { text("report") }
                th { text("missing or incomplete") }
            }
            for (entry in result.reports) {
                tr {
                    td { emit(entry.case) }
                    td(classes = "num") {
                        emit(entry.compilations)
                    }
                    td(classes = "num") {
                        emit("%.1f".format(entry.compilerMs))
                    }
                    td {
                        a(href = "/jobs/${files}/files/${entry.report}") {
                            emit(entry.report)
                        }
                        entry.tasks?.let {
                            text(" · ")
                            a(href = "/jobs/${files}/files/${it}") {
                                text("tasks")
                            }
                        }
                    }
                    td {
                        emit(
                            if (entry.complete)
                                buildHtml {
                                    span(classes = "muted") {
                                        text("none")
                                    }
                                }
                            else
                                buildHtml {
                                    span(classes = "PARTIAL") {
                                        emit(entry.markers.joinToString(", "))
                                    }
                                },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Why case [index]'s processes were ended ([Termination]), a line each, with when it happened on
 * hover.
 */
private fun terminations(files: JobDirView, index: Int): Html =
    files.terminations[index].orEmpty().joinHtml { line ->
        val at = line.substringBefore(' ').toLongOrNull()
        buildHtml {
            br {}
            small(classes = "muted") {
                at?.let { title = java.time.Instant.ofEpochMilli(it).toString() }
                +(if (at == null) line else line.substringAfter(' '))
            }
        }
    }

private fun duration(from: Long?, to: Long?): String {
    if (from == null) return ""
    return clock(((to ?: now()) - from) / 1000)
}

private fun benchSection(job: Job, cases: List<CaseResult>, arms: List<PlannedArm>): Html =
    buildHtml {
        h2 { text("Bench results") }
        p {
            text("Repository ")
            code { emit(job.experiment.repository) }
            text(" · ")
            a(href = "/experiments/${job.id}/metrics.csv") {
                text("metrics CSV")
            }
            text(" · ")
            a(href = "/experiments/${job.id}/cases") {
                text("case records")
            }
            text(" · ")
            a(href = "/jobs/${job.id}/files") {
                text("all files")
            }
        }
        section {
            attributes["id"] = "bench-results"
            attributes["data-result-list"] = ""
            attributes["data-result-limit"] = "25"
            val results = cases.map { it to it.record?.let(BenchRuns::result) }
            emit(
                resultFilters(
                    "bench",
                    cases.map { it.arm }.distinct(),
                    results
                        .map { (case, result) -> result?.status?.name ?: case.status.name }
                        .distinct(),
                ),
            )
            for ((case, result) in results) {
                val path = case.problem.problem
                val shortName =
                    if (path.substringAfterLast('/').toLongOrNull() != null)
                        path.split('/').takeLast(3).joinToString("/")
                    else path.substringAfterLast('/')
                details(classes = "case-result") {
                    attributes["id"] = "case-${case.index}"
                    attributes["data-result-row"] = ""
                    attributes["data-search"] =
                        ("${case.problem.problem} ${case.arm} ${case.seed ?: ""} ${case.repeat + 1}")

                    attributes["data-status"] =
                        (result?.status?.name ?: case.status.name)
                    attributes["data-arm"] = (case.arm)
                    summary {
                        span(classes = "case-name") {
                            attributes["title"] = (case.problem.problem)
                            emit(shortName)
                        }
                        span(classes = "badge") {
                            emit(case.arm)
                        }
                        span(classes = "muted") {
                            case.seed?.let {
                                text("seed ")
                                emit(it)
                                text(" · ")
                            }
                            text("repeat ")
                            emit(case.repeat + 1)
                        }
                        span(classes = (case.status.name)) {
                            emit(result?.status?.name ?: case.status.name)
                        }
                        small(classes = "muted") {
                            emit(result?.metrics?.size ?: 0)
                            text(" metrics · ")
                            emit(result?.artifacts?.size ?: 0)
                            text(" files")
                        }
                    }
                    div(classes = "case-body") {
                        h3 {
                            emit(case.problem.problem)
                            text(" · ")
                            emit(case.arm)
                        }
                        p {
                            emit(result?.status?.name ?: case.status.name)
                            arms
                                .firstOrNull { it.arm.label == case.arm }
                                ?.let {
                                    text(" · commit ")
                                    code { emit(it.sha.take(12)) }
                                }
                            case.seed?.let {
                                text(" · seed ")
                                emit(it)
                            }
                            text(" · repeat ")
                            emit(case.repeat + 1)
                        }
                        result?.message?.let { p { emit(it) } }
                        if (result != null && result.metrics.isNotEmpty()) {
                            div(classes = "scroll") {
                                table {
                                    tr {
                                        th { text("Metric") }
                                        th { text("Value") }
                                        th { text("Unit") }
                                        th { text("Direction") }
                                    }
                                    result.metrics.forEach { metric ->
                                        tr {
                                            td { emit(metric.name) }
                                            td { emit(metric.value) }
                                            td { emit(metric.unit) }
                                            td { emit(metric.direction.name.lowercase()) }
                                        }
                                    }
                                }
                            }
                        }
                        ul(classes = "artifact-links") {
                            result?.artifacts?.forEach { artifact ->
                                val path =
                                    artifact.path.split('/').joinToString("/") {
                                        java.net.URLEncoder.encode(it, Charsets.UTF_8)
                                            .replace("+", "%20")
                                    }
                                li {
                                    a(
                                        href =
                                            "/jobs/${job.id}/files/cases/${case.index}/out/${path}",
                                    ) {
                                        emit(artifact.path)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            p(classes = "muted") {
                attributes["data-result-count"] = ""
                attributes["aria-live"] = "polite"
            }
            button {
                attributes["type"] = "button"
                attributes["data-result-more"] = ""
                attributes["hidden"] = ""
                text("Show 25 more cases")
            }
            p {
                attributes["data-result-empty"] = ""
                attributes["hidden"] = ""
                text("No matching cases.")
            }
        }
    }
