package com.eignex.lab

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The queue's front page: what runs and waits, the schedules, and the history, newest first. */
internal fun indexPage(
    config: Config,
    host: HostReport,
    active: List<Job>,
    schedules: List<Schedule>,
    history: List<Job>,
    limit: Int,
    before: Long?,
    name: String?,
    /** The queued jobs in queue order ([Store.queue]), wherever each may run. */
    queueOrder: List<Long> = emptyList(),
    /** The earlier run a job's estimate learns from ([EstimateHistory.lookup]). */
    caseHistory: (Job) -> CaseHistory? = { null },
    /** Why each queued job waits ([Waiting.waiting]), by job. */
    waiting: Map<Long, String> = emptyMap(),
    /** Each running job's hosts and their parts ([Store.hostProgress]), by job. */
    hosts: Map<Long, List<HostProgress>> = emptyMap(),
    nodes: Map<Long, List<JobNode>> = emptyMap(),
): String = buildString {
    val front = before == null && name == null
    append(head("eignex lab", live = before == null))
    append("<header><h1>Queue</h1>")
    append("<p id=\"summary\" data-live class=\"muted\">${summary(config, host, active)}</p></header>")
    if (front) {
        // One pass over the active jobs: the per-row estimates and the summary's totals read the same numbers.
        val estimates = active.mapNotNull { job ->
            Estimates.estimate(job, caseHistory, config.cores, config.maxParallel)?.let { job.id to it }
        }.toMap()
        append("<section><h2>Active</h2><div id=\"work\" data-live>${workSummary(config, active, estimates)}</div>")
        append("<div id=\"active\" data-live>${activeTable(config, active, queueOrder, estimates, waiting, hosts, nodes)}</div></section>")
        if (schedules.isNotEmpty()) {
            append("<section><h2>Schedules</h2><div id=\"schedules\" data-live>${schedulesTable(schedules)}</div></section>")
        }
    }
    append("<section><h2>")
    append(if (name == null) "History" else "Runs of ${esc(name)} <small><a href=\"/\">all jobs</a></small>")
    append("</h2><div class=\"tools\"><input id=\"q\" type=\"search\" placeholder=\"filter by name or ref\">")
    for (chip in listOf("all", "failed", "done", "cancelled", "active")) {
        if (chip == "active" && front) continue
        append("<button class=\"chip${if (chip == "all") " on" else ""}\" data-chip=\"$chip\">$chip</button>")
    }
    append("</div><div id=\"history\" data-live>")
    val shown = if (front) history.filter { it.status !in ACTIVE } else history
    append(jobTable(config, shown, emptyMap(), filterable = true))
    append("<p class=\"pager\">")
    if (before != null) append("<a href=\"/${query("name" to name)}\">newest</a> ")
    if (history.size == limit) append("<a href=\"/${query("name" to name, "before" to history.last().id.toString())}\">older</a>")
    append("</p></div></section>")
    append(SCRIPT)
    append("</body></html>")
}

/** One job: its settings, controls while it is unfinished, and every command with its output. */
internal fun jobPage(
    config: Config,
    job: Job,
    failedOnly: Boolean,
    arms: List<PlannedArm> = emptyList(),
    cases: List<CaseResult> = emptyList(),
    showCommands: Boolean = true,
    previous: Long? = null,
    references: Map<Pair<String, String>, Reference> = emptyMap(),
    /** The downloads' holds on this job that have not lapsed ([Hold]). */
    holds: List<Hold> = emptyList(),
    /** Why the job waits, while it is queued ([Waiting.waiting]). */
    waiting: String? = null,
    /** Each host's part of the job ([Store.hostProgress]). */
    hosts: List<HostProgress> = emptyList(),
    nodes: List<JobNode> = emptyList(),
): String = buildString {
    val dir = config.jobDir(job.id).toFile()
    val active = job.status in ACTIVE
    append(head("${job.id} ${job.name} · eignex lab", live = active))
    append("<header><h1>${job.id} ${esc(job.name)}</h1>")
    job.experiment?.description?.takeIf { it.isNotBlank() }?.let { append("<p class=\"description\">${esc(it)}</p>") }
    append("</header>")
    append("<div id=\"job\" data-live>")
    append("<dl class=\"meta\">")
    append("<dt>status</dt><dd>${statusCell(job, null)}</dd>")
    if (job.backend == "nomad") append("<dt>execution</dt><dd>Nomad · <a href=\"/jobs/${job.id}/execution\">sessions and generations</a> · <a href=\"/nodes\">nodes</a></dd>")
    append("<dt>repository</dt><dd>${esc(job.experiment?.repository ?: Repositories.DEFAULT)}</dd>")
    append("<dt>ref</dt><dd>${refText(config, job)}</dd>")
    append("<dt>progress</dt><dd>${progress(job)}</dd>")
    append("<dt>created</dt><dd>${ago(job.createdAt)}</dd>")
    if (job.startedAt != null) append("<dt>started</dt><dd>${ago(job.startedAt)}</dd>")
    if (job.finishedAt != null) append("<dt>finished</dt><dd>${ago(job.finishedAt)}</dd>")
    append("<dt>elapsed</dt><dd>${elapsed(job)}</dd>")
    // A cloud case's own phase: a case RUNNING long past its timeout is then seen waiting on SSH, not solving.
    val phases = job.commands.mapNotNull { command -> command.phase?.let { command.index to it } }
    if (phases.isNotEmpty()) {
        append("<dt>running</dt><dd>")
        append(phases.joinToString("<br>") { (index, phase) -> "case $index: ${esc(phaseText(phase, now()))}" })
        append("</dd>")
    }
    append("<dt>where</dt><dd>${hostCell(config, job, hosts, nodes)}</dd>")
    job.experiment?.nodeTags?.let { selector ->
        append("<dt>node tags</dt><dd>")
        if (selector.require.isNotEmpty()) append("require ${esc(selector.require.sorted().joinToString())}")
        if (selector.exclude.isNotEmpty()) append("; exclude ${esc(selector.exclude.sorted().joinToString())}")
        append("</dd>")
    }
    if (hosts.size > 1 || hosts.any { it.host != RecordedHost.LOCAL }) {
        // One job's iterations may run on different machines, every arm of one iteration on the same one.
        append("<dt>hosts</dt><dd>${hosts.joinToString("<br>") { esc(it.text()) }}")
        append("<br><small class=\"muted\">each iteration's arms ran on one machine; timing across iterations mixes machines</small></dd>")
    }
    job.experiment?.capped?.takeIf { it.isNotEmpty() }?.let { caps ->
        append("<dt>capped</dt><dd>${caps.joinToString("<br>") { esc(it) }}</dd>")
    }
    job.experiment?.compilationRecording?.let { mode ->
        val recording = if (mode == Experiments.COMPILATION_JFR_LOG) "compilation and HotSpot log" else "compilation recording"
        append("<dt>instrumented</dt><dd><span class=\"PARTIAL\">$recording</span>: every compilation of each ")
        append("case's CLI is recorded, so its times are no runtime measurement</dd>")
    }
    append("<dt>parallel</dt><dd>${job.parallel}</dd><dt>priority</dt><dd>${job.priority}</dd>")
    append("<dt>files</dt><dd><a href=\"/jobs/${job.id}/files\">all files</a>")
    for (log in listOf("setup.log", "job.log")) {
        if (File(dir, log).isFile) append(" · <a href=\"/jobs/${job.id}/files/$log\">$log</a>")
    }
    append("</dd>")
    holds.maxOfOrNull { it.expiresAt }?.let { end ->
        append("<dt>held</dt><dd>held for download until ${clockTime(end)}: the disk's trim leaves its files alone</dd>")
    }
    append("</dl>")
    job.error?.let { append("<pre class=\"error\">${esc(it)}</pre>") }
    if (job.backend == "nomad" && (job.status == Status.FAILED || job.status == Status.CANCELLED)) {
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
        append("<a href=\"/trend${query("name" to job.name.substringBefore('@'))}\">trend</a></p>")
    }
    val spec = job.experiment
    if (spec != null && spec.isAnalysis) {
        append(analysisSection(config, job, spec))
    } else if (spec != null && (spec.isProfile || spec.isScript)) {
        append(singleCaseSection(config, job, spec))
    } else if (spec != null) {
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
    val legacyHost = if (job.experiment?.host == Experiments.AWS_HOST) RecordedHost.CLOUD else RecordedHost.LOCAL
    for (command in commands) {
        val exit = command.exitCode?.takeIf { it != 0 }?.let { "<br><small>exit $it</small>" } ?: ""
        append("<tr><td>${command.index}</td><td><code>${esc(command.cmd)}</code></td>")
        val phase = command.phase?.let { "<br><small>${esc(phaseText(it, now()))}</small>" } ?: ""
        append("<td class=\"${command.status}\">${command.status}$exit$phase${terminations(dir, command.index)}</td>")
        // Where it ran; a case started before the lab recorded that ran on its job's host.
        val host = command.host ?: legacyHost.takeIf { command.status != Status.QUEUED && command.status != Status.CANCELLED }
        append("<td>${host?.let { "<code>${esc(it)}</code>" }.orEmpty()}</td>")
        append("<td>${ago(command.startedAt)}</td><td>${duration(command.startedAt, command.finishedAt)}</td><td>")
        if (command.status != Status.QUEUED) {
            append(output(job.id, dir, "${command.index}.out", command.status == Status.RUNNING))
            append("<br>")
            append(output(job.id, dir, "${command.index}.err", command.status == Status.RUNNING))
        }
        append("</td></tr>")
    }
    append("</table></div></div>")
    append(SCRIPT)
    append("</body></html>")
}

/** An experiment's results: each arm's totals and score, the disagreements, and every problem across the arms. */
private fun experimentSection(
    config: Config,
    job: Long?,
    arms: List<PlannedArm>,
    cases: List<CaseResult>,
    references: Map<Pair<String, String>, Reference> = emptyMap(),
): String = buildString {
    if (arms.isEmpty()) {
        append("<p class=\"muted\">Not planned yet: the runner selects the problems and writes the cases when it sets the job up.</p>")
        return@buildString
    }
    val labels = arms.map { it.arm.label }
    val comparison = Compare.compare(labels, cases)
    val stats = Stats.of(labels, cases)
    val seeds = cases.map { it.seed }.distinct().sortedBy { it ?: Long.MIN_VALUE }
    val repeats = (cases.maxOfOrNull { it.repeat } ?: 0) + 1
    val total = cases.groupBy { it.arm }
    // One arm has nothing to compare against, and a reference run is the reference, so neither shows the comparison.
    val compared = arms.size > 1
    val referenceRun = arms.all { it.arm.values["backend"] == REFERENCE_BACKEND }
    if (referenceRun) {
        append("<p class=\"note\">A reference run: each problem solved by its format's reference solver, every verdict added ")
        append("to the <a href=\"/references\">reference results</a> as it lands, where a stronger one already there stays.</p>")
    }
    if (mixesInstrumented(comparison.arms)) {
        append("<p class=\"error\">This mixes instrumented runs (compilation measurement, marked below) with uninstrumented ones: their ")
        append("times, PAR-2, time ratios and time-split scores measure the instrumentation, not the solver. Compare runtime ")
        append("between uninstrumented runs only.</p>")
    } else if (comparison.arms.any { it.instrumented > 0 }) {
        append("<p class=\"note\">Instrumented runs (compilation measurement): every compilation was recorded, so times here include ")
        append("that overhead and are no runtime measurement. Each case's compilation report is among its files.</p>")
    }
    if (mixesValidation(comparison.arms)) {
        append("<p class=\"error\">These arms ran under different validation policies (source-checked or unchecked, marked ")
        append("below): an unchecked solver report counts as a solution or proof just as a source-checked witness does, and a ")
        append("check that rejected or could not decide a claim counts it as no solution. Solved, proven and score differences ")
        append("between them may measure the checking, not the solver; compare claims between arms of one policy.</p>")
    }
    append("<h2>${if (compared) "Arms" else "Results"} <small>${cases.map { it.problem }.distinct().size} problems")
    if (seeds.size > 1) append(" × ${seeds.size} seeds")
    if (repeats > 1) append(" × $repeats repeats")
    if (job != null) append(" · <a href=\"/experiments/$job/cases.csv\">cases.csv</a>")
    append("</small></h2>")
    append("<div class=\"scroll\"><table><tr><th>arm</th><th>commit</th><th>build / validation</th><th class=\"num\">done</th><th class=\"num\">solved</th>")
    append("<th class=\"num\">proven</th><th class=\"num\">unsupported</th><th class=\"num\">errors</th><th class=\"num\">PAR-2 s</th>")
    if (compared) append("<th class=\"num\">score</th><th>vs ${esc(labels.first())}</th>")
    append(if (job != null) "<th>bench csv</th></tr>" else "</tr>")
    val paired = stats.paired.associateBy { it.label }
    for ((index, planned) in arms.withIndex()) {
        val summary = comparison.arms[index]
        val armStats = stats.arms[index]
        val commit = commitUrl(config.repoUrl, planned.sha)?.let { "<a href=\"$it\">${planned.sha.take(9)}</a>" } ?: planned.sha.take(9)
        val versus = paired[summary.label]?.let(::versus) ?: "<span class=\"muted\">baseline</span>"
        val instrumented = if (summary.instrumented > 0) " <span class=\"PARTIAL\" title=\"compilation measurement: times are no runtime measurement\">instrumented</span>" else ""
        append("<tr><td><b>${esc(summary.label)}</b>$instrumented${witnessLabel(summary)}<br><small class=\"muted\">${esc(describe(planned.arm))}</small></td>")
        append("<td><code>$commit</code></td><td>${provenance(summary)}</td>")
        append("<td class=\"num\">${summary.cases}/${total[summary.label]?.size ?: 0}</td>")
        append("<td class=\"num\">${estimate(armStats.solved, "%.1f")}</td><td class=\"num\">${summary.proven}</td>")
        append("<td class=\"num\">${if (summary.unsupported > 0) "<span class=\"PARTIAL\">${summary.unsupported}</span>" else "0"}</td>")
        append("<td class=\"num\">${if (summary.errors > 0) "<span class=\"FAILED\">${summary.errors}</span>" else "0"}</td>")
        append("<td class=\"num\">${estimate(armStats.par2, "%.2f")}</td>")
        if (compared) append("<td class=\"num\">${estimate(armStats.score, "%.1f")}</td><td>$versus</td>")
        if (job != null) {
            append("<td>")
            append(seeds.joinToString(" ") { seed ->
                val name = if (seed == null) "csv" else "seed $seed"
                "<a href=\"/experiments/$job/bench.csv${query("arm" to summary.label, "seed" to seed?.toString())}\">$name</a>"
            })
            append("</td>")
        }
        append("</tr>")
    }
    append("</table></div>")
    append("<p class=\"muted\"><small>Intervals are 95%, bootstrapped over problems, a problem's seeds and repeats averaged. ")
    append("PAR-2 charges an unsolved run twice its budget.")
    if (compared) {
        append(" The time ratio is the geometric mean of an arm's PAR-2 time over the first arm's, on the problems either ")
        append("solved; its p is a Wilcoxon signed-rank test. Equal results whose times differ by less than 0.25 s or 10% are a ")
        append("tie. Better and worse count problems by the score, with a sign test. p below 0.05 is bold.")
    }
    append("</small></p>")
    if (comparison.disagreements.isNotEmpty()) {
        append("<h2 class=\"FAILED\">Disagreements <small>${comparison.disagreements.size}</small></h2><ul class=\"error\">")
        for (d in comparison.disagreements) append("<li><code>${esc(name(d.problem))}</code>: ${esc(d.reason)}</li>")
        append("</ul>")
    }
    if (stats.noise.isNotEmpty()) append(noiseTable(stats.noise))
    if (references.isNotEmpty() && !referenceRun) append(referenceSection(labels, cases, references))
    append(problemGrid(labels, cases, if (referenceRun) emptyMap() else references, compared))
}

/** Each arm against the lab's reference results: who solved what, optima, gaps, and where they contradict. */
private fun referenceSection(labels: List<String>, cases: List<CaseResult>, references: Map<Pair<String, String>, Reference>): String =
    buildString {
        val comparison = References.compare(labels, cases, references)
        append("<h2>Against the reference <small>${references.size} of ${cases.map { it.problem }.distinct().size} problems have ")
        append("a reference verdict · <a href=\"/references\">reference results</a></small></h2>")
        append("<div class=\"scroll\"><table><tr><th>arm</th><th class=\"num\">covered</th><th class=\"num\">both solved</th>")
        append("<th class=\"num\">only the arm</th><th class=\"num\">only the reference</th><th class=\"num\">proven optima reached</th>")
        append("<th class=\"num\">beats its best</th><th class=\"num\">worse incumbent</th><th class=\"num\">mean gap</th></tr>")
        for (r in comparison.summaries) {
            append("<tr><td><b>${esc(r.arm)}</b></td><td class=\"num\">${r.covered}</td><td class=\"num\">${r.bothSolved}</td>")
            append("<td class=\"num\">${if (r.armOnly > 0) "<span class=\"DONE\">${r.armOnly}</span>" else "0"}</td>")
            append("<td class=\"num\">${if (r.referenceOnly > 0) "<span class=\"FAILED\">${r.referenceOnly}</span>" else "0"}</td>")
            append("<td class=\"num\">${r.optimaMatched}/${r.provenOptima}</td><td class=\"num\">${r.better}</td>")
            append("<td class=\"num\">${if (r.worse > 0) "<span class=\"PARTIAL\">${r.worse}</span>" else "0"}</td>")
            append("<td class=\"num\">${r.meanGap?.let { "%.1f%%".format(it * 100) } ?: "–"}</td></tr>")
        }
        append("</table></div><p class=\"muted\"><small>The reference ran under its own budget, so this compares verdicts ")
        append("and objectives, not speed. Mean gap is the relative distance to the reference objective where the arm is worse.</small></p>")
        if (comparison.shortfalls.isNotEmpty()) {
            append("<details><summary><b>Worse incumbents</b> <small class=\"muted\">${comparison.shortfalls.size} problems where ")
            append("an arm's best objective is worse than the reference's, the largest gap first</small></summary>")
            append("<div class=\"scroll\"><table><tr><th>arm</th><th>problem</th><th class=\"num\">objective</th>")
            append("<th class=\"num\">reference</th><th class=\"num\">gap</th></tr>")
            for (f in comparison.shortfalls) {
                append("<tr><td>${esc(f.arm)}</td><td><a class=\"plain\" href=\"${problemLink(f.problem.collection, f.problem.problem)}\">")
                append("<code>${esc(name(f.problem))}</code></a></td><td class=\"num\">${number(f.objective)}</td>")
                append("<td class=\"num\">${number(f.reference)}${if (f.referenceProven) " <small class=\"muted\">proven</small>" else ""}</td>")
                append("<td class=\"num\">${"%.1f%%".format(f.gap * 100)}</td></tr>")
            }
            append("</table></div></details>")
        }
        if (comparison.disagreements.isNotEmpty()) {
            append("<h2 class=\"FAILED\">Disagreements with the reference <small>${comparison.disagreements.size}</small></h2><ul class=\"error\">")
            for (d in comparison.disagreements) append("<li><code>${esc(name(d.problem))}</code>: ${esc(d.reason)}</li>")
            append("</ul>")
        }
    }

private fun provenance(summary: ArmSummary): String = buildString {
    if (summary.buildFingerprints.isEmpty() && summary.validationPolicies.isEmpty() && summary.missingProvenance == 0) {
        append("<span class=\"muted\">pending</span>")
        return@buildString
    }
    if (summary.buildFingerprints.size > 1 || summary.validationPolicies.size > 1) {
        append("<span class=\"PARTIAL\">mixed provenance</span><br>")
    }
    append(summary.buildFingerprints.joinToString("<br>") { "<code title=\"${esc(it)}\">${esc(it.take(12))}</code>" })
    if (summary.validationPolicies.isNotEmpty()) {
        append("<br><small>${summary.validationPolicies.joinToString(", ") { esc(it) }}</small>")
    }
    if (summary.missingProvenance > 0) {
        append("<br><span class=\"PARTIAL\">records missing provenance: ${summary.missingProvenance}</span>")
    }
}

/** An arm against the baseline: the time ratio with its interval and test, then better and worse with theirs. */
private fun versus(p: Paired): String {
    val ratio = if (p.problems == 0) "<span class=\"muted\">no problem solved by either</span>" else
        "${"%.2f".format(p.timeRatio.value)}× <small class=\"muted\">${"%.2f".format(p.timeRatio.low)}–${"%.2f".format(p.timeRatio.high)}</small> " +
            "${pValue(p.wilcoxonP)} <small class=\"muted\">n=${p.problems}</small>"
    return "$ratio<br><span class=\"DONE\">${p.better} better</span> · <span class=\"FAILED\">${p.worse} worse</span> ${pValue(p.signP)}"
}

private fun estimate(e: Estimate, format: String) =
    "${format.format(e.value)} <small class=\"muted\">${format.format(e.low)}–${format.format(e.high)}</small>"

private fun pValue(p: Double): String {
    val text = if (p < 0.001) "p<0.001" else "p=${"%.3f".format(p)}"
    return if (p < SIGNIFICANT) "<b>$text</b>" else "<small>$text</small>"
}

/** Problems whose runs of one arm disagree, the most unsettled first. */
private fun noiseTable(noise: List<Noise>): String = buildString {
    append("<h2>Noisy problems <small>${noise.size}: runs that disagree on the verdict, or whose time to best spreads over ")
    append("25% of its mean</small></h2><div class=\"scroll\"><table><tr><th>problem</th><th>arm</th>")
    append("<th class=\"num\">runs</th><th class=\"num\">verdicts</th><th class=\"num\">time spread</th></tr>")
    for (n in noise.take(NOISE_ROWS)) {
        append("<tr><td><code>${esc(name(n.problem))}</code></td><td>${esc(n.arm)}</td><td class=\"num\">${n.runs}</td>")
        append("<td class=\"num\">${if (n.verdicts > 1) "<span class=\"FAILED\">${n.verdicts}</span>" else "1"}</td>")
        append("<td class=\"num\">${"%.0f".format(n.spread * 100)}%</td></tr>")
    }
    append("</table></div>")
    if (noise.size > NOISE_ROWS) append("<p class=\"muted\">${noise.size - NOISE_ROWS} more in cases.csv</p>")
}

private const val SIGNIFICANT = 0.05
private const val NOISE_ROWS = 50

/** One row per problem, one cell per arm with its outcome on each seed; the arm that scores best on a row is marked. */
private fun problemGrid(
    labels: List<String>,
    cases: List<CaseResult>,
    references: Map<Pair<String, String>, Reference> = emptyMap(),
    compared: Boolean = true,
): String = buildString {
    val byProblem = cases.groupBy { it.problem }
    append("<h2>Problems")
    if (compared) append(" <small><label><input type=\"checkbox\" id=\"differ\"> only where arms differ</label></small>")
    append("</h2>")
    append("<div class=\"scroll\"><table class=\"grid\"><tr><th>problem</th>")
    for (label in labels) append("<th>${esc(label)}</th>")
    if (references.isNotEmpty()) append("<th class=\"ref\">reference</th>")
    append("</tr>")
    for ((problem, ofProblem) in byProblem) {
        val outcomes = ofProblem.groupBy { it.arm }.mapValues { (_, cs) -> cs.sortedBy { it.seed ?: Long.MIN_VALUE } }
        val verdicts = labels.map { label -> outcomes[label].orEmpty().map { verdict(Outcome.of(it.record), it.status) } }
        val points = labels.associateWith { label ->
            ofProblem.filter { it.arm == label }.sumOf { mine ->
                val a = Outcome.of(mine.record) ?: return@sumOf 0.0
                ofProblem.filter { it.arm != label && it.seed == mine.seed && it.repeat == mine.repeat }.sumOf { other -> Outcome.of(other.record)?.let { Compare.points(a, it) } ?: 0.0 }
            }
        }
        val best = points.values.maxOrNull()?.takeIf { top -> points.values.any { it < top } }
        val label = "<code>${esc(name(problem))}</code>"
        val cell = if (problem.collection.isEmpty()) label else "<a class=\"plain\" href=\"${problemLink(problem.collection, problem.problem)}\">$label</a>"
        append("<tr data-differ=\"${verdicts.distinct().size > 1}\"><td>$cell</td>")
        for (label in labels) {
            val cls = if (best != null && points[label] == best) " class=\"best\"" else ""
            append("<td$cls>")
            append(outcomes[label].orEmpty().joinToString("<br>") { case ->
                val outcome = Outcome.of(case.record)
                val time = outcome?.takeIf { it.rank > 0 }?.let { " <small class=\"muted\">${"%.2f".format(it.timeMs / 1000.0)}s</small>" }.orEmpty()
                verdict(outcome, case.status) + time
            })
            append("</td>")
        }
        if (references.isNotEmpty()) append("<td class=\"ref\">${referenceVerdict(references[problem.collection to problem.problem])}</td>")
        append("</tr>")
    }
    append("</table></div>")
}

/** A reference verdict in a word, the solver beside it and its time against its own budget on hover. */
private fun referenceVerdict(r: Reference?): String {
    if (r == null) return "<span class=\"muted\">–</span>"
    val word = when {
        r.feasible == false -> "infeasible"
        r.feasible == null -> "unknown"
        r.objective != null -> number(r.objective) + if (r.proven) "*" else ""
        else -> "sat"
    }
    val title = "${r.solver}: ${"%.2f".format(r.elapsedMs / 1000.0)}s of a ${r.budgetMs / 1000}s budget"
    return "<span title=\"${esc(title)}\">$word</span> <small class=\"muted\">${esc(r.solver)}</small>"
}

/** One outcome in a word: the objective (starred when proven), sat, infeasible, unknown, unsupported, a load error, or
 *  the case's status when it left no record; a reason shows on hover. A Klause solution claim carries how far its
 *  witness was checked ([witnessMark]). */
internal fun verdict(outcome: Outcome?, status: Status): String = outcomeWord(outcome, status) + witnessMark(outcome)

private fun outcomeWord(outcome: Outcome?, status: Status): String = when {
    outcome == null -> "<span class=\"${status.name}\">${status.name.lowercase()}</span>"
    outcome.error -> "<span class=\"FAILED\">error</span>"
    outcome.unsupported != null -> "<span class=\"PARTIAL\" title=\"${esc(outcome.unsupported)}\">unsupported</span>"
    outcome.loadError != null -> "<span class=\"FAILED\" title=\"${esc(outcome.loadError)}\">load error</span>"
    SourceValidation.rejects(outcome.sourceValidation) ->
        "<span class=\"FAILED\" title=\"${esc(outcome.sourceValidationReason.orEmpty())}\">invalid solution</span>"
    outcome.floatApproximation && !outcome.accepted ->
        "<span class=\"PARTIAL\" title=\"${esc(outcome.sourceValidationReason.orEmpty())}\">unchecked grid</span>"
    outcome.floatApproximation && outcome.feasible == true ->
        "<span title=\"source-checked grid witness; source optimality unproved\">" +
            (outcome.objective?.let(::number) ?: "sat") + " <small class=\"muted\">grid</small></span>"
    outcome.feasible == false -> "infeasible"
    outcome.feasible == null -> "<span class=\"muted\">unknown</span>"
    outcome.optimize && outcome.objective != null -> number(outcome.objective) + if (outcome.proven) "*" else ""
    else -> "sat"
}

/**
 * How far a Klause solution claim was checked against its original source, as a small word beside its verdict:
 * `checked` for a witness the source accepted, `unchecked` for the solver's own report (with the reason when a check
 * was asked for and its checker could not run), `check indeterminate` for a check that could not decide, the reason and
 * policy on hover. An accepted witness is a valid solution; a proof of
 * optimality beside it (the star) is still the solver's own claim. Nothing for an outcome with no solution claim, for
 * another solver's, or where the verdict already says it (an invalid solution, a float grid).
 */
internal fun witnessMark(outcome: Outcome?): String {
    if (outcome == null || outcome.error || outcome.unsupported != null || outcome.loadError != null) return ""
    if (!outcome.claimedSolution || outcome.floatApproximation) return ""
    val policy = outcome.validationPolicy?.let { " (policy $it)" }.orEmpty()
    return when (outcome.witness) {
        WitnessCheck.ACCEPTED -> " <small class=\"DONE\" title=\"${esc("witness accepted against the original source$policy: " +
            "the solution is valid; a proof of optimality is the solver's own claim")}\">checked</small>"
        WitnessCheck.UNCHECKED -> {
            val why = when (outcome.sourceValidation) {
                "unknown", "not-run" -> "the source checker could not run: ${outcome.sourceValidationReason}"
                "unsupported" -> "no external checker covers it: ${outcome.sourceValidationReason}"
                else -> "no independent source check was asked for or performed"
            }
            " <small class=\"muted\" title=\"${esc("the solver's own report$policy: $why")}\">unchecked</small>"
        }
        WitnessCheck.INDETERMINATE -> {
            val reported = buildList {
                add("reported ${(outcome.reportedObjective ?: outcome.objective?.let(::number))?.let { "objective $it" } ?: "a solution"}")
                if (outcome.reportedProven || outcome.proven) add("proven")
            }.joinToString(", ")
            val reason = outcome.sourceValidationReason ?: "no result recorded"
            " <small class=\"PARTIAL\" title=\"${esc("the source check could not decide$policy: $reason; $reported")}\">" +
                "check indeterminate</small>"
        }
        WitnessCheck.INVALID, null -> ""
    }
}

/** An arm's witness checks in a word beside its label, the counts on hover; nothing when it claimed no solution. */
private fun witnessLabel(summary: ArmSummary): String {
    val counts = summary.witnesses
    if (counts.isEmpty()) return ""
    val title = esc(WitnessCheck.entries.filter { (counts[it] ?: 0) > 0 }.joinToString(", ") { "${counts[it]} ${it.word}" } +
        " Klause solutions; a checked witness is a valid solution, not a checked proof")
    return when {
        (counts[WitnessCheck.ACCEPTED] ?: 0) > 0 || (counts[WitnessCheck.INDETERMINATE] ?: 0) > 0 ->
            " <span class=\"DONE\" title=\"$title\">source-checked</span>"
        else -> " <span class=\"muted\" title=\"$title\">unchecked</span>"
    }
}

private fun number(value: Double) = if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()

private fun name(problem: Problem) = if (problem.suite.isEmpty()) problem.problem else "${problem.suite}/${problem.problem}"

private fun describe(arm: Arm) = arm.values.toSortedMap().entries.joinToString(" ") { (k, v) -> "$k=$v" }

/**
 * Several experiments side by side, each arm named by its job: a scheduled run against the one before it, or any
 * experiments over the same problems. Problems pair up by suite and name, seeds by value.
 */
internal fun comparePage(
    config: Config,
    jobs: List<Job>,
    arms: List<PlannedArm>,
    cases: List<CaseResult>,
    references: Map<Pair<String, String>, Reference> = emptyMap(),
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

private val trendJson = Json { encodeDefaults = true }

/**
 * How a schedule's runs moved over its commits: solved and proven shares with the solved interval, PAR-2 time, and
 * solved share per suite, against commit or date. The runs ride in the page as JSON; a small script draws the charts.
 */
internal fun trendPage(
    name: String?,
    runs: List<TrendRun>,
    repoUrl: String,
    schedules: List<String> = emptyList(),
    /** Runs left out because they ran an earlier experiment than the schedule's current one. */
    earlier: Int = 0,
    all: Boolean = false,
    /** Finished runs whose points are still being computed in the background, left out until they are. */
    computing: Int = 0,
): String = buildString {
    // Not live for the page-wide refresh, which refetches the whole page: the charts have no live parts, and a page whose
    // points are computing polls a small progress call instead ([trendPoll]).
    append(head("${name ?: "regression"} · eignex lab", live = false, tab = Tab.REGRESSION))
    if (name == null) {
        append("<header><h1>Regression</h1></header><p class=\"muted\">No schedule yet: <code>lab schedule</code> sets one up, ")
        append("and its runs chart here, one point per commit.</p></body></html>")
        return@buildString
    }
    append("<header><h1>Regression: ${esc(name)}</h1>")
    if (schedules.size > 1) {
        append("<div class=\"tools\">")
        for (other in schedules) {
            append("<a class=\"chip${if (other == name) " on" else ""}\" href=\"/trend${query("name" to other)}\">${esc(other)}</a>")
        }
        append("</div>")
    }
    append("<p class=\"muted\">${runs.size} runs · <a href=\"/${query("name" to name)}\">its jobs</a>")
    if (computing > 0) append(" · $computing more being computed, shown as they are ready")
    if (earlier > 0) {
        append(" · $earlier earlier runs of a different experiment left out: <a href=\"/trend${query("name" to name, "all" to "1")}\">show them</a>")
    }
    val specs = runs.map { it.spec }.distinct().size
    if (runs.any { it.sourceChecked } && runs.any { !it.sourceChecked }) {
        append(" · runs marked unchecked report the solver's own claims; checked ones asked for source-witness validation, ")
        append("a separate experiment")
    }
    if (all && specs > 1) {
        append(" · $specs experiments, each change marked by a dashed line: <a href=\"/trend${query("name" to name)}\">current only</a>")
    }
    append("</p></header>")
    if (runs.isEmpty()) {
        append("<p class=\"muted\">")
        append(when {
            computing > 0 -> "Computing ${if (all) "its runs" else "this experiment's runs"}; they appear here as they are ready."
            all -> "No run of this schedule has results yet."
            else -> "No run of this schedule's current experiment has results yet."
        })
        append("</p>${trendPoll(runs.size, computing)}</body></html>")
        return@buildString
    }
    append("<div class=\"tools\" role=\"group\" aria-label=\"x-axis\"><span class=\"muted\">x-axis</span>")
    append("<button class=\"chip on\" data-x=\"commit\">commit</button><button class=\"chip\" data-x=\"date\">date</button></div>")
    append("<h2>Solved and proven <small>problems out of those each run ran (a problem's seeds and repeats averaged); band: 95% interval of solved</small></h2>")
    append("<div class=\"legend\"><span><i style=\"background:var(--series-1)\"></i>solved</span>")
    append("<span><i style=\"background:var(--series-2)\"></i>proven</span>")
    append("<span><i class=\"hollow\"></i>run still going</span>")
    append("<span><i class=\"flag\"></i>disagrees with the reference</span></div>")
    append("<div class=\"chart\" id=\"chart-solved\"></div>")
    append("<h2>PAR-2 time <small>mean seconds per problem, an unsolved one charged twice its budget; lower is better</small></h2>")
    append("<div class=\"chart\" id=\"chart-par2\"></div>")
    append("<h2>Solved by theme <small>problems solved in each theme the sweep set was drawn by (a problem of several themes counts in each); each axis tops out at the theme's size</small></h2><div class=\"multiples\" id=\"suites\"></div>")
    append("<h2>Runs</h2><div class=\"scroll\"><table><tr><th>job</th><th>commit</th><th>when</th>")
    append("<th class=\"num\">problems</th><th class=\"num\">solved</th><th class=\"num\">proven</th>")
    append("<th class=\"num\">unsupported</th><th class=\"num\">errors</th><th class=\"num\">disagreements</th>")
    append("<th class=\"num\" title=\"problems solved to a worse objective than the reference\">worse incumbent</th>")
    append("<th class=\"num\" title=\"against the run before: problems lost and gained, the sign test, and what reruns confirmed\">")
    append("vs previous</th>")
    append("<th class=\"num\">PAR-2 s</th></tr>")
    for (run in runs.asReversed()) {
        val commit = commitUrl(repoUrl, run.sha)?.let { "<a href=\"$it\">${run.sha.take(9)}</a>" } ?: run.sha.take(9)
        append("<tr><td><a href=\"/jobs/${run.job}\">${run.job}</a>${if (run.finished) "" else " <small class=\"muted\">running</small>"}")
        append("${trendWitness(run)}</td>")
        append("<td><code>$commit</code></td><td>${ago(run.at)}</td><td class=\"num\">${run.problems}</td>")
        append("<td class=\"num\">${percent(run.solved.value)} <small class=\"muted\">${percent(run.solved.low)}–${percent(run.solved.high)}</small></td>")
        append("<td class=\"num\">${percent(run.proven)}</td><td class=\"num\">${run.unsupported}</td><td class=\"num\">${run.errors}</td>")
        append("<td class=\"num\">")
        append(if (run.disagreements > 0) "<a class=\"FAILED\" href=\"/jobs/${run.job}\">${run.disagreements}</a>" else "0")
        append("</td>")
        append("<td class=\"num\"><a class=\"plain\" href=\"/jobs/${run.job}\">${run.worse}</a></td>")
        append("<td class=\"num\">${flipCell(run)}</td>")
        append("<td class=\"num\">${"%.2f".format(run.par2.value)}</td></tr>")
    }
    append("</table></div><div id=\"tip\" class=\"tip\" hidden></div>")
    append("<script type=\"application/json\" id=\"trend-data\">")
    // Every field, defaults included: the script reads each run's fields without guarding for absent ones.
    append(trendJson.encodeToString(runs).replace("</", "<\\/"))
    append("</script>")
    append(trendPoll(runs.size, computing))
    append(SCRIPT)
    append(TREND_SCRIPT)
    append("</body></html>")
}

/** Whether a trend run's solution claims were checked against their sources, as a small word with its meaning on hover. */
private fun trendWitness(run: TrendRun): String =
    if (run.sourceChecked) {
        " <small class=\"DONE\" title=\"asked for source-witness validation: its solutions in checked formats are source-checked\">checked</small>"
    } else {
        " <small class=\"muted\" title=\"no source-witness validation asked for: its solutions are the solver's own reports\">unchecked</small>"
    }

/** A run against the one before it: lost and gained, the sign test when it is telling, and what reruns confirmed. */
private fun flipCell(run: TrendRun): String = buildString {
    val lost = run.lost ?: return "–"
    val gained = run.gained ?: 0
    append("feasibility −$lost +$gained")
    append("; proof −${run.proofLost ?: 0} +${run.proofGained ?: 0}")
    append("; objective −${run.objectiveLost ?: 0} +${run.objectiveGained ?: 0}")
    run.flipP?.takeIf { it < FLIP_SIGNIFICANCE }?.let { append(" <small>p=%.3f</small>".format(it)) }
    val job = run.confirmJob ?: return@buildString
    val confirmedLost = run.confirmedLost
    append(" · <a href=\"/jobs/$job\">")
    append(
        if (confirmedLost == null) {
            "confirming"
        } else {
            val cls = if (confirmedLost > 0 || (run.confirmedProofLost ?: 0) > 0 || (run.confirmedObjectiveLost ?: 0) > 0) " class=\"FAILED\"" else ""
            "<span$cls>confirmed feasibility −$confirmedLost +${run.confirmedGained ?: 0}; " +
                "proof −${run.confirmedProofLost ?: 0} +${run.confirmedProofGained ?: 0}; " +
                "objective −${run.confirmedObjectiveLost ?: 0} +${run.confirmedObjectiveGained ?: 0}</span>"
        },
    )
    append("</a>")
}

/** Below this, a run's flips against the one before lean one way more than chance would. */
private const val FLIP_SIGNIFICANCE = 0.05

private fun percent(share: Double) = "%.1f%%".format(share * 100)

/** Draws the trend charts from the embedded runs, and redraws them when the x-axis or the width changes. */
private const val TREND_SCRIPT = """<script>
(function () {
  // A run still going is charted as projected: its shares so far, of the problems the last finished run of the same
  // problems ran, overall and per theme, so its counts sit beside the others' instead of dropping every line. Without
  // such a run to scale by, it is charted only once it ends. The table below lists it as it goes.
  const all = JSON.parse(document.getElementById('trend-data').textContent);
  const runs = all.map(function (r, i) {
    if (r.finished) return r;
    const base = all.slice(0, i).reverse().find(function (p) { return p.finished && p.spec === r.spec; });
    if (!base) return null;
    const themes = {};
    for (const t of Object.keys(base.themes || {})) {
      const own = (r.themes || {})[t];
      if (own) themes[t] = {problems: base.themes[t].problems, solved: own.solved};
    }
    return Object.assign({}, r, {problems: base.problems, themes: themes, projectedFrom: r.problems});
  }).filter(Boolean);
  const tip = document.getElementById('tip');
  const NS = 'http://www.w3.org/2000/svg';
  let xMode = 'commit';
  try { xMode = localStorage.getItem('trend-x') || 'commit'; } catch (e) {}
  // The small multiples are by theme, the groups the sweep set was drawn by.
  const themes = Array.from(new Set(runs.flatMap(function (r) { return Object.keys(r.themes || {}); }))).sort();

  function el(name, attrs, parent) {
    const node = document.createElementNS(NS, name);
    for (const k in attrs) node.setAttribute(k, attrs[k]);
    if (parent) parent.appendChild(node);
    return node;
  }
  function pct(v) { return (v * 100).toFixed(1) + '%'; }
  function when(ms) {
    const d = new Date(ms);
    function two(n) { return String(n).padStart(2, '0'); }
    return two(d.getMonth() + 1) + '-' + two(d.getDate()) + ' ' + two(d.getHours()) + ':' + two(d.getMinutes());
  }
  function niceMax(v) {
    if (!(v > 0)) return 1;
    const p = Math.pow(10, Math.floor(Math.log10(v)));
    for (const m of [1, 2, 2.5, 5, 10]) if (m * p >= v) return m * p;
    return 10 * p;
  }

  // series: [{name, color, value(run), band(run)?, label?}]; y: {max, ticks, fmt}
  function draw(host, series, y, opts) {
    host.innerHTML = '';
    const W = Math.max(host.clientWidth, 240), H = opts.height;
    const m = {l: 46, r: opts.endLabels ? 92 : 36, t: 10, b: 28};
    const pw = W - m.l - m.r, ph = H - m.t - m.b;
    const svg = el('svg', {width: W, height: H, viewBox: '0 0 ' + W + ' ' + H, role: 'img', 'aria-label': opts.title}, host);
    const times = runs.map(function (r) { return r.at; });
    const t0 = Math.min.apply(null, times), t1 = Math.max.apply(null, times);
    function x(i) {
      if (runs.length === 1) return m.l + pw / 2;
      if (xMode === 'date') return m.l + (t1 === t0 ? pw / 2 : (runs[i].at - t0) / (t1 - t0) * pw);
      return m.l + i / (runs.length - 1) * pw;
    }
    // Lines join runs left to right: by time on the date axis, where runs need not have ended in job order.
    const order = runs.map(function (r, i) { return i; });
    if (xMode === 'date') order.sort(function (a, b) { return runs[a].at - runs[b].at; });
    // An axis may start above zero ([min]), so a small chart's few problems of movement fill its height.
    const lo = y.min || 0;
    function yy(v) { return m.t + ph - (Math.max(lo, Math.min(v, y.max)) - lo) / ((y.max - lo) || 1) * ph; }
    // Dots shrink as runs crowd together, and lose their outline, which would cut the line into beads.
    const spacing = runs.length > 1 ? pw / (runs.length - 1) : pw;
    const dot = Math.max(1.5, Math.min(4, spacing / 3));
    const outline = spacing >= 10 ? 2 : 0;
    for (const t of y.ticks) {
      el('line', {x1: m.l, x2: m.l + pw, y1: yy(t), y2: yy(t), class: 'grid'}, svg);
      el('text', {x: m.l - 6, y: yy(t) + 4, 'text-anchor': 'end', class: 'tick'}, svg).textContent = y.fmt(t);
    }
    // A run of a different experiment than the one before it starts a new line, behind a dashed marker.
    for (let k = 1; k < order.length; k++) {
      if (runs[order[k]].spec === runs[order[k - 1]].spec) continue;
      const bx = (x(order[k]) + x(order[k - 1])) / 2;
      el('line', {x1: bx, x2: bx, y1: m.t, y2: m.t + ph, stroke: 'var(--muted)', 'stroke-dasharray': '4 4'}, svg);
    }
    if (xMode === 'date') {
      const n = Math.max(2, Math.min(5, runs.length, Math.floor(pw / 110)));
      for (let k = 0; k < n; k++) {
        const t = n === 1 ? t0 : t0 + (t1 - t0) * k / (n - 1);
        const px = t1 === t0 ? m.l + pw / 2 : m.l + (t - t0) / (t1 - t0) * pw;
        el('text', {x: px, y: H - 8, 'text-anchor': 'middle', class: 'tick'}, svg).textContent = when(t);
      }
    } else {
      const every = Math.max(1, Math.ceil(runs.length / Math.max(1, Math.floor(pw / 64))));
      runs.forEach(function (r, i) {
        if (i % every === 0 || i === runs.length - 1) {
          el('text', {x: x(i), y: H - 8, 'text-anchor': 'middle', class: 'tick mono'}, svg).textContent = r.sha.slice(0, 7);
        }
      });
    }
    for (const s of series) {
      if (s.band) {
        const pts = order.map(function (i) { const b = s.band(runs[i]); return b ? [x(i), yy(b[0]), yy(b[1]), runs[i].spec] : null; }).filter(Boolean);
        const segments = [];
        for (const p of pts) {
          if (!segments.length || segments[segments.length - 1][0][3] !== p[3]) segments.push([]);
          segments[segments.length - 1].push(p);
        }
        for (const seg of segments.filter(function (g) { return g.length > 1; })) {
          const d = 'M' + seg.map(function (p) { return p[0] + ',' + p[2]; }).join('L') +
            'L' + seg.slice().reverse().map(function (p) { return p[0] + ',' + p[1]; }).join('L') + 'Z';
          el('path', {d: d, fill: s.color, 'fill-opacity': 0.1, stroke: 'none'}, svg);
        }
      }
      const pts = order.map(function (i) { const v = s.value(runs[i]); return v == null ? null : [x(i), yy(v), runs[i]]; }).filter(Boolean);
      if (pts.length > 1) {
        const d = pts.map(function (p, k) { return (k > 0 && pts[k - 1][2].spec === p[2].spec ? 'L' : 'M') + p[0] + ',' + p[1]; }).join('');
        el('path', {d: d, fill: 'none', stroke: s.color,
          'stroke-width': 2, 'stroke-linejoin': 'round', 'stroke-linecap': 'round'}, svg);
      }
      for (const p of pts) {
        el('circle', {cx: p[0], cy: p[1], r: dot, fill: p[2].finished ? s.color : 'var(--bg)',
          stroke: p[2].finished ? (outline ? 'var(--bg)' : 'none') : s.color, 'stroke-width': outline || 1}, svg);
      }
      // A run that contradicts the reference is ringed where it does: on the solved chart, and on the chart of the
      // suite its disagreeing problems are in, never on every chart.
      if (s === series[0] && opts.flagged) {
        for (const p of pts) {
          if (opts.flagged(p[2])) el('circle', {cx: p[0], cy: p[1], r: 8, fill: 'none', stroke: 'var(--bad)', 'stroke-width': 2}, svg);
        }
      }
      if (opts.endLabels && pts.length) {
        const last = pts[pts.length - 1];
        el('text', {x: last[0] + 10, y: last[1] + 4, class: 'endlabel'}, svg).textContent = s.name + ' ' + y.fmt(s.value(last[2]));
      }
    }
    const cross = el('line', {y1: m.t, y2: m.t + ph, class: 'cross', visibility: 'hidden'}, svg);
    const hit = el('rect', {x: m.l - 8, y: 0, width: pw + 16, height: H, fill: 'transparent'}, svg);
    function nearest(evt) {
      const box = svg.getBoundingClientRect();
      const px = evt.clientX - box.left;
      let best = 0;
      runs.forEach(function (r, i) { if (Math.abs(x(i) - px) < Math.abs(x(best) - px)) best = i; });
      return best;
    }
    hit.addEventListener('pointermove', function (evt) {
      const i = nearest(evt);
      cross.setAttribute('x1', x(i)); cross.setAttribute('x2', x(i)); cross.setAttribute('visibility', 'visible');
      show(runs[i], evt, opts.suite);
    });
    hit.addEventListener('pointerleave', function () { cross.setAttribute('visibility', 'hidden'); tip.hidden = true; });
    hit.addEventListener('click', function (evt) { location.href = '/jobs/' + runs[nearest(evt)].job; });
    hit.style.cursor = 'pointer';
  }

  function show(r, evt, suite) {
    const rows = [
      ['commit', r.sha.slice(0, 9)], ['when', when(r.at) + (r.finished ? '' : ' (running)')], ['job', '#' + r.job],
      ['witnesses', r.sourceChecked ? 'source-checked' : 'unchecked solver reports'],
      ['solved', (r.solved.value * r.problems).toFixed(1) + ' of ' + r.problems + ' (' + pct(r.solved.value) + ')' +
        (r.projectedFrom != null ? ', projected from the ' + r.projectedFrom + ' done so far' : '')],
      ['proven', (r.proven * r.problems).toFixed(1) + ' (' + pct(r.proven) + ')'], ['PAR-2', r.par2.value.toFixed(2) + ' s'],
      ['unsupported', r.unsupported], ['errors', r.errors], ['disagreements', r.disagreements], ['worse incumbent', r.worse], ['problems', r.problems],
      ['vs previous', r.lost == null ? '–' : '−' + r.lost + ' +' + r.gained + (r.flipP != null ? ' (p=' + r.flipP.toFixed(3) + ')' : '')],
      ['proof vs previous', r.proofLost == null ? '–' : '−' + r.proofLost + ' +' + r.proofGained],
      ['objective vs previous', r.objectiveLost == null ? '–' : '−' + r.objectiveLost + ' +' + r.objectiveGained],
      ['confirmed proof', r.confirmedProofLost == null ? '–' : '−' + r.confirmedProofLost + ' +' + r.confirmedProofGained],
      ['confirmed objective', r.confirmedObjectiveLost == null ? '–' : '−' + r.confirmedObjectiveLost + ' +' + r.confirmedObjectiveGained],
      ['confirmed', r.confirmJob == null ? '–' : r.confirmedLost == null ? 'running' : '−' + r.confirmedLost + ' +' + r.confirmedGained],
    ];
    const group = suite && (r.themes || {})[suite];
    if (group) rows.unshift([suite, Math.round(group.solved * group.problems) + ' of ' + group.problems + ' (' + pct(group.solved) + ')']);
    tip.innerHTML = rows.map(function (kv) { return '<div><span>' + kv[0] + '</span><b>' + kv[1] + '</b></div>'; }).join('');
    tip.hidden = false;
    const w = tip.offsetWidth, h = tip.offsetHeight;
    let left = evt.pageX + 14, top = evt.pageY - h - 10;
    if (left + w > window.scrollX + document.documentElement.clientWidth - 8) left = evt.pageX - w - 14;
    if (top < window.scrollY + 8) top = evt.pageY + 16;
    tip.style.left = left + 'px'; tip.style.top = top + 'px';
  }

  function render() {
    // Counts, not shares: a share of a handful of problems swings by a fifth on one flip, and hides how many moved.
    function countAxis(max) {
      const m = Math.max(1, max);
      const ticks = m <= 4 ? Array.from({length: m + 1}, function (_, i) { return i; })
        : [0, Math.round(m / 4), Math.round(m / 2), Math.round(3 * m / 4), m];
      return {max: m, ticks: Array.from(new Set(ticks)), fmt: function (v) { return String(Math.round(v)); }};
    }
    // A small chart's axis tops out at the theme's size, so it reads as solved out of how many, and starts a problem
    // below the fewest it ever solved, so a single flip still shows.
    function rangeAxis(values, size) {
      if (!values.length) return countAxis(size || 1);
      const max = Math.max(size, Math.ceil(Math.max.apply(null, values)));
      const min = Math.max(0, Math.min(max - 2, Math.floor(Math.min.apply(null, values)) - 1));
      const mid = Math.round((min + max) / 2);
      return {min: min, max: max, ticks: Array.from(new Set([min, mid, max])), fmt: function (v) { return String(Math.round(v)); }};
    }
    const most = Math.max.apply(null, runs.map(function (r) { return r.problems; }));
    draw(document.getElementById('chart-solved'), [
      {name: 'solved', color: 'var(--series-1)', value: function (r) { return r.solved.value * r.problems; },
        band: function (r) { return [r.solved.low * r.problems, r.solved.high * r.problems]; }},
      {name: 'proven', color: 'var(--series-2)', value: function (r) { return r.proven * r.problems; }},
    ], countAxis(most), {height: 260, endLabels: true, title: 'Problems solved and proven per run',
      flagged: function (r) { return r.disagreements > 0; }});
    const top = niceMax(Math.max.apply(null, runs.map(function (r) { return r.par2.high; })));
    draw(document.getElementById('chart-par2'), [
      {name: 'PAR-2', color: 'var(--series-1)', value: function (r) { return r.par2.value; },
        band: function (r) { return [r.par2.low, r.par2.high]; }},
    ], {max: top, ticks: [0, top / 4, top / 2, 3 * top / 4, top], fmt: function (v) { return (Math.round(v * 10) / 10) + 's'; }},
      {height: 220, endLabels: false, title: 'PAR-2 seconds per run'});
    const grid = document.getElementById('suites');
    grid.innerHTML = '';
    for (const s of themes) {
      const cell = document.createElement('div');
      cell.className = 'multiple';
      cell.innerHTML = '<div class="mtitle">' + s + '</div>';
      const plot = document.createElement('div');
      cell.appendChild(plot);
      grid.appendChild(cell);
      const value = function (r) { const g = (r.themes || {})[s]; return g ? g.solved * g.problems : null; };
      const seen = runs.map(value).filter(function (v) { return v != null; });
      const size = Math.max.apply(null, runs.map(function (r) { const g = (r.themes || {})[s]; return g ? g.problems : 0; }));
      draw(plot, [{name: s, color: 'var(--series-1)', value: value}], rangeAxis(seen, size),
        {height: 130, endLabels: false, title: s + ' problems solved per run', suite: s,
          flagged: function (r) { return ((r.disagreementsByTheme || {})[s] || 0) > 0; }});
    }
  }

  document.querySelectorAll('[data-x]').forEach(function (b) {
    b.classList.toggle('on', b.dataset.x === xMode);
    b.addEventListener('click', function () {
      xMode = b.dataset.x;
      try { localStorage.setItem('trend-x', xMode); } catch (e) {}
      document.querySelectorAll('[data-x]').forEach(function (o) { o.classList.toggle('on', o === b); });
      render();
    });
  });
  let pending = null;
  window.addEventListener('resize', function () { clearTimeout(pending); pending = setTimeout(render, 120); });
  render();
})();
</script>"""

/** The reference tab: what the lab's reference results cover, and a filtered search over them. */
internal fun referencesPage(
    coverage: List<ReferenceCoverage>,
    filter: ReferenceFilter,
    found: List<Pair<Pair<String, String>, Reference>>,
    total: Int,
    /** Problems two reference solvers contradict each other on. */
    conflicts: List<Disagreement> = emptyList(),
    /** A klause run against the references, which the tab leads with. */
    scoreboard: ScoreboardView? = null,
): String = buildString {
    val visible = coverage.filter { c ->
        QFNRA_BENCHMARKS.any { c.collection.startsWith(it) } || HIDDEN_COLLECTIONS.none { c.collection.startsWith(it) }
    }
    append(head("reference · eignex lab", live = false, tab = Tab.REFERENCE))
    append("<header><h1>Reference</h1><p class=\"muted\">${visible.sumOf { it.rows }} results from ")
    append("${visible.map { it.solver }.distinct().size} solvers over ${visible.map { it.collection }.distinct().size} collections")
    append("</p></header>")
    scoreboard?.let { append(scoreboardSection(it)) }
    append("<h2>Search</h2>")
    append("<form method=\"get\" action=\"/references\" class=\"tools filters\">")
    append("<input name=\"q\" type=\"search\" placeholder=\"problem or collection contains\" value=\"${esc(filter.text.orEmpty())}\">")
    append(select("solver", "every solver", coverage.map { it.solver }.distinct().sorted().map { it to it }, filter.solver))
    append(select("collection", "every collection", visible.map { it.collection }.distinct().sorted().map { it to it }, filter.collection))
    append(select("verdict", "any verdict", ReferenceVerdict.entries.map { it.name.lowercase() to it.label }, filter.verdict?.name?.lowercase()))
    append("<button>filter</button>")
    if (!filter.isEmpty) append("<a class=\"chip\" href=\"/references\">clear</a>")
    append("</form>")
    if (!filter.isEmpty) {
        append("<h2>Results <small>${if (total > found.size) "${found.size} of $total" else "$total"}</small></h2>")
        append("<div class=\"scroll\"><table><tr><th>collection</th><th>problem</th><th>verdict</th><th class=\"num\">time</th>")
        append("<th class=\"num\">budget</th></tr>")
        for ((key, r) in found) {
            append("<tr><td>${esc(key.first)}</td><td><a class=\"plain\" href=\"${problemLink(key.first, key.second)}\"><code>${esc(key.second)}</code></a></td>")
            append("<td>${referenceVerdict(r)}</td>")
            append("<td class=\"num\">${"%.2f".format(r.elapsedMs / 1000.0)}s</td><td class=\"num\">${r.budgetMs / 1000}s</td></tr>")
        }
        append("</table></div>")
    }
    if (conflicts.isNotEmpty()) {
        append("<h2 class=\"FAILED\">Solvers disagree <small>${conflicts.size} problems where two reference solvers ")
        append("contradict each other: one of them is wrong</small></h2><ul class=\"error\">")
        for (d in conflicts) {
            append("<li><a class=\"plain\" href=\"${problemLink(d.problem.collection, d.problem.problem)}\">")
            append("<code>${esc(d.problem.collection)}/${esc(d.problem.problem)}</code></a>: ${esc(d.reason)}</li>")
        }
        append("</ul>")
    }
    val shown = visible.filter { c ->
        (filter.solver == null || c.solver == filter.solver) && (filter.collection == null || c.collection == filter.collection)
    }
    append("<h2>Coverage${if (shown.size < visible.size) " <small>${shown.size} of ${visible.size} rows</small>" else ""}</h2>")
    if (coverage.isEmpty()) append("<p class=\"muted\">No reference results yet.</p>")
    append("<div class=\"scroll\"><table><tr><th>collection</th><th>solver</th><th class=\"num\">problems</th>")
    append("<th class=\"num\">decided</th><th class=\"num\">proven</th><th class=\"num\">infeasible</th><th>updated</th></tr>")
    // A family of collections reads as one row until it is opened; each family renders as a block where its first
    // member sorts, with its members right under it.
    val families = shown.groupBy { c -> FAMILIES.firstOrNull { (_, member) -> member(c.collection) }?.first }
    val drawn = HashSet<String>()
    for (c in shown) {
        val family = FAMILIES.firstOrNull { (_, member) -> member(c.collection) }?.first
        val members = families[family].orEmpty()
        if (family == null || members.size < 2) {
            append("<tr>${coverageCells(c)}</tr>")
            continue
        }
        if (!drawn.add(family)) continue
        append("<tr class=\"family\" onclick=\"document.querySelectorAll('tr[data-in=$family]').forEach(function (r) { r.hidden = !r.hidden; })\">")
        append("<td>▸ <b>$family</b> <small class=\"muted\">${members.size} collections</small></td>")
        append("<td>${esc(members.map { it.solver }.distinct().joinToString())}</td><td class=\"num\">${members.sumOf { it.rows }}</td>")
        append("<td class=\"num\">${members.sumOf { it.decided }}</td><td class=\"num\">${members.sumOf { it.proven }}</td>")
        append("<td class=\"num\">${members.sumOf { it.infeasible }}</td><td>${ago(members.maxOf { it.updatedAt })}</td></tr>")
        for (member in members) append("<tr data-in=\"$family\" hidden>${coverageCells(member)}</tr>")
    }
    append("</table></div>")
    append(SCRIPT)
    append("</body></html>")
}

/** A profile job, or a retired script job: what it ran, its exit, and what it left in `$OUT`, each readable with the job's files. */
private fun singleCaseSection(config: Config, job: Job, spec: ExperimentSpec): String = buildString {
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
    val dir = config.jobDir(job.id).toFile()
    val exit = dir.resolve("0.exit").takeIf { it.isFile }?.readText()?.trim()
    append("<p>")
    append(if (exit == null) "<span class=\"muted\">not finished</span>" else "exit <b>${esc(exit)}</b>")
    val limited = exit == "timeout" || Termination.read(dir.toPath(), 0).any { line ->
        line.substringAfter(' ').let { it.startsWith("timeout:") || it.startsWith("deadline:") }
    }
    if (limited) append(" · <b>stopped at its time limit</b>; what it wrote before then is kept")
    append(terminations(dir, 0))
    append(" · <a href=\"/jobs/${job.id}/files/0.out\">stdout</a> · <a href=\"/jobs/${job.id}/files/0.err\">stderr</a>")
    val out = dir.resolve("cases/0/out").walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.sorted().toList()
    if (out.isNotEmpty()) {
        append(" · <b>${"$"}OUT</b>: ")
        append(out.joinToString(", ") { "<a href=\"/jobs/${job.id}/files/$it\"><code>${esc(it.removePrefix("cases/0/out/"))}</code></a>" })
    }
    append("</p>")
}

/** An analysis job: the job whose captures it analysed, and the reports it wrote, once it has. */
private fun analysisSection(config: Config, job: Job, spec: ExperimentSpec): String = buildString {
    val analyze = spec.analyze ?: return@buildString
    if (analyze.kind == AnalyzeSpec.CHECK) {
        append("<h2>Recheck</h2><p>The lab's source checkers, run again over the stored records of job ")
        append("<a href=\"/jobs/${analyze.job}\">${analyze.job}</a>: each Klause solution claim in a format the lab checks gets a ")
        append("fresh verdict, which its pages, trend and comparisons then read. Nothing was built and no solver ran.</p>")
        val result = config.jobDir(job.id).resolve(com.eignex.lab.checker.CaseChecks.RESULT).toFile().takeIf { it.isFile }
            ?.let { runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(com.eignex.lab.checker.RecheckResult.serializer(), it.readText()) }.getOrNull() }
        if (result == null) {
            append("<p class=\"muted\">${if (job.status in ACTIVE) "Not finished." else "No result written: see job.log."}</p>")
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
    val result = config.jobDir(job.id).resolve(CompilationReports.ANALYSIS).toFile().takeIf { it.isFile }
        ?.let { runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(AnalysisResult.serializer(), it.readText()) }.getOrNull() }
    if (result == null) {
        append("<p class=\"muted\">${if (job.status in ACTIVE) "Not finished." else "No reports written: see job.log."}</p>")
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

/** A klause run against every reference solver: a row per group, a cell per solver, each cell klause's Borda share. */
private fun scoreboardSection(view: ScoreboardView): String = buildString {
    append("<h2>Against the references <small>pairwise Borda by the MiniZinc Challenge rule: ")
    append(if (view.scoring == Scoring.INCOMPLETE) "local-search class, proofs ignored and equal results split evenly"
        else "complete class, proofs first and equal results split by time when the run had the reference's budget on one core")
    append("</small></h2>")
    val job = view.job
    if (job == null) {
        append("<p class=\"muted\">No finished scheduled run to score yet.</p>")
        return@buildString
    }
    fun link(vararg changes: Pair<String, String?>): String {
        val current = linkedMapOf<String, String?>("job" to job.id.toString(), "arm" to view.arm, "by" to view.by.name.lowercase(),
            "scoring" to "incomplete".takeIf { view.scoring == Scoring.INCOMPLETE })
        for ((d, v) in view.filters) current[d.name.lowercase()] = v
        for ((k, v) in changes) current[k] = v
        return "/references${query(*current.entries.map { it.key to it.value }.toTypedArray())}"
    }
    append("<form method=\"get\" action=\"/references\" class=\"tools filters\">")
    val runChoices = (view.runs + job).distinctBy { it.id }.map { it.id.toString() to it.name }
    append(select("job", "run", runChoices, job.id.toString()))
    if (view.arms.size > 1) append(select("arm", "arm", view.arms.map { it to it }, view.arm))
    append(select("by", "group by", Dimension.entries.map { it.name.lowercase() to "by ${it.label}" }, view.by.name.lowercase()))
    for (d in Dimension.entries) {
        val values = view.options[d].orEmpty()
        if (values.size > 1) append(select(d.name.lowercase(), "any ${d.label}", values.map { it to it }, view.filters[d]))
    }
    append("<label><input type=\"checkbox\" name=\"scoring\" value=\"incomplete\"")
    if (view.scoring == Scoring.INCOMPLETE) append(" checked")
    append("> local-search scoring</label>")
    append("<button>show</button>")
    if (view.filters.isNotEmpty()) append("<a class=\"chip\" href=\"${link(*Dimension.entries.map { it.name.lowercase() to null }.toTypedArray())}\">clear filters</a>")
    append("</form>")
    val board = view.board ?: return@buildString
    if (board.total.problems == 0) {
        append("<p class=\"muted\">None of this run's problems has a reference verdict${if (view.filters.isEmpty()) "" else " under these filters"}.</p>")
        return@buildString
    }
    if (view.witnesses.isNotEmpty()) {
        val counts = WitnessCheck.entries.filter { (view.witnesses[it] ?: 0) > 0 }.joinToString(", ") { "${view.witnesses[it]} ${it.word}" }
        append("<p class=\"muted\">Klause's solutions in this run: $counts. ")
        if ((view.witnesses[WitnessCheck.UNCHECKED] ?: 0) > 0) {
            append("An unchecked solution is the solver's own report and scores as a source-checked one does. ")
        }
        append("A checked witness makes the solution valid, not its proof.</p>")
    }
    append("<div class=\"scroll\"><table class=\"board\"><tr><th>${esc(view.by.label)}</th><th class=\"num\">problems</th>")
    for (solver in board.solvers) append("<th class=\"num\">${esc(solver)}</th>")
    append("</tr>")
    for (row in listOf(board.total) + board.rows) {
        append("<tr${if (row === board.total) " class=\"total\"" else ""}><td>${esc(row.group)}</td><td class=\"num\">${row.problems}</td>")
        for (solver in board.solvers) append(scoreCell(row.cells[solver], link("group" to row.group, "against" to solver)))
        append("</tr>")
    }
    append("</table></div>")
    append("<p class=\"muted\">Each cell is klause's share of the points against that solver over the problems both have a ")
    append("result for; green above one half, red below. <i>best</i> takes the strongest trusted reference verdict on each problem. ")
    if (view.by == Dimension.CLASS) append("The class is klause's own profile of the model; a run that reported none takes one from the source's logic or the pipeline that solved it. ")
    append("Open a cell for its problems, klause's losses first.</p>")
    append("<p class=\"muted\">A cell reads klause's budget against the reference's. The score measures quality against stored ")
    append("reference verdicts: time splits an equal result only where the budgets are equal and klause ran on one core, and a cell ")
    append("marked <span class=\"PARTIAL\">quality only</span> has problems whose reference had another budget, which it does not ")
    append("correct for. For relative speed, run a lab a/b experiment with both solvers as arms at the same budget.</p>")
    val (group, against) = view.cell ?: return@buildString
    append("<h3>${esc(group)} against ${esc(against)} <small>${view.problems.size} problems · ")
    append("<a href=\"${link("group" to null, "against" to null)}\">close</a></small></h3>")
    append("<div class=\"scroll\"><table><tr><th>problem</th><th class=\"num\">klause</th><th class=\"num\">${esc(against)}</th>")
    append("<th class=\"num\">budgets</th><th>timed</th></tr>")
    for (p in view.problems) {
        val cls = when {
            p.klause > p.reference -> "DONE"
            p.klause < p.reference -> "FAILED"
            else -> "muted"
        }
        append("<tr><td><a class=\"plain\" href=\"${problemLink(p.problem.collection, p.problem.problem)}\"><code>${esc(p.problem.collection)}/")
        append("${esc(p.problem.problem)}</code></a></td><td class=\"num $cls\">${"%.2f".format(p.klause)}</td>")
        append("<td class=\"num\">${"%.2f".format(p.reference)}</td>")
        append("<td class=\"num\">${Scoreboard.seconds(p.budgetMs)} vs ${Scoreboard.seconds(p.referenceBudgetMs)}</td>")
        val differ = p.budgetMs != p.referenceBudgetMs
        append("<td class=\"${if (differ) "PARTIAL" else "muted"}\">${if (p.timed) "yes" else "no, ${esc(p.untimed.orEmpty())}"}</td></tr>")
    }
    append("</table></div>")
}

/**
 * One scoreboard cell: klause's share, tinted green above one half and red below, the stronger the further off, then
 * klause's budget against the reference's (a range when its problems had several) and a quality-only mark when they differ.
 */
private fun scoreCell(cell: ScoreCell?, href: String): String {
    val share = cell?.share ?: return "<td class=\"num muted\">${if (cell == null || cell.problems == 0) "" else "–"}</td>"
    val strength = (kotlin.math.abs(share - HALF_SHARE) * 2 * MAX_TINT).toInt()
    val tint = "background:color-mix(in srgb, var(--${if (share >= HALF_SHARE) "ok" else "bad"}) $strength%, transparent)"
    val perBudget = cell.referenceBudgets.entries.joinToString(", ") { (ms, n) -> "${Scoreboard.seconds(ms)} on $n" }
    return buildString {
        append("<td class=\"num\" style=\"$tint\"><a class=\"plain\" href=\"$href\" title=\"klause ${"%.1f".format(cell.klause)} ")
        append("against ${"%.1f".format(cell.reference)} over ${cell.problems} problems")
        if (cell.referenceBudgets.size > 1) append("; reference budgets: $perBudget")
        append("\">${"%.2f".format(share)} <small class=\"muted\">${cell.problems}</small>")
        budgets(cell)?.let { append("<br><small class=\"muted\">$it</small>") }
        append("</a>")
        if (cell.budgetsDiffer) append("<br><small class=\"PARTIAL\" title=\"${esc(qualityOnly(cell))}\">quality only, budgets differ</small>")
        append("</td>")
    }
}

/** A cell's budgets: klause's against the reference's, or the range of the reference's when they vary. */
internal fun budgets(cell: ScoreCell): String? {
    val mine = cell.budgetMs ?: return null
    val theirs = cell.referenceBudgets.keys.takeIf { it.isNotEmpty() } ?: return null
    val range = if (theirs.size == 1) Scoreboard.seconds(theirs.first())
        else "${Scoreboard.seconds(theirs.min()).removeSuffix(" s")}–${Scoreboard.seconds(theirs.max())}"
    return "${Scoreboard.seconds(mine)} vs $range"
}

/** Why a cell whose budgets differ compares quality alone, and which way the reference's budget leans. */
private fun qualityOnly(cell: ScoreCell): String {
    val mine = cell.budgetMs ?: 0
    val other = cell.referenceBudgets.filterKeys { it != mine }
    val lean = when {
        other.keys.all { it > mine } -> "the reference had a longer budget, so its proofs and objectives had more time"
        other.keys.all { it < mine } -> "the reference had a shorter budget, so klause's proofs and objectives had more time"
        else -> "the reference had longer budgets on some and shorter on others"
    }
    return "Time splits no equal result on the ${other.values.sum()} of ${cell.problems} problems whose reference verdict had " +
        "another budget than klause's ${Scoreboard.seconds(mine)}, and $lean. The score compares quality, not speed."
}

private const val HALF_SHARE = 0.5
private const val MAX_TINT = 45

internal const val SEARCH_LIMIT = 200

private val QFNRA_BENCHMARKS = listOf(
    "klause-bench/smoke-corpus/qfnra-v1/",
    "klause-bench/smoke-corpus/qfnra-source-canonical-v1/",
)
/** Test fixtures, not benchmarks: kept in the store, left out of the coverage view. */
private val HIDDEN_COLLECTIONS = listOf("klause-bench/smoke-corpus/", "klause-mzn-lib/")

/** Families of collections that coverage folds into one expandable row: a name and which collections it holds. */
private val FAMILIES: List<Pair<String, (String) -> Boolean>> = listOf(
    "satlib" to { it.startsWith("satlib-") },
    "qfnra" to { collection -> QFNRA_BENCHMARKS.any { collection.startsWith(it) } || collection == "smtlib-qf_nra" },
    "smtlib" to { it.startsWith("smtlib-") },
    "pb" to { it.startsWith("pb-") || it.startsWith("pb07-") },
    "orlib" to { it.startsWith("orlib-") },
    "miplib" to { it.startsWith("miplib") },
)

/** One collection's coverage cells: the collection (linked to its results), solver, and counts. */
private fun coverageCells(c: ReferenceCoverage): String {
    val link = "/references${query("collection" to c.collection, "solver" to c.solver)}"
    return "<td><a class=\"plain\" href=\"$link\">${esc(c.collection)}</a></td><td>${esc(c.solver)}</td>" +
        "<td class=\"num\">${c.rows}</td><td class=\"num\">${c.decided}</td><td class=\"num\">${c.proven}</td>" +
        "<td class=\"num\">${c.infeasible}</td><td>${ago(c.updatedAt)}</td>"
}

private fun problemLink(collection: String, problem: String) = "/problem${query("collection" to collection, "problem" to problem)}"

/** One problem: every reference solver's verdict on it, and every time the lab ran it. */
internal fun problemPage(report: ProblemReport, repoUrl: String): String = buildString {
    append(head("${report.problem} · eignex lab", live = report.runs.any { it.status in ACTIVE }, tab = Tab.REFERENCE))
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
            val commit = commitUrl(repoUrl, run.sha)?.let { "<a href=\"$it\">${run.sha.take(9)}</a>" } ?: run.sha.take(9)
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

/** A filter dropdown that submits its form on change; [options] are (value, label). */
private fun select(name: String, any: String, options: List<Pair<String, String>>, chosen: String?): String = buildString {
    append("<select name=\"$name\" onchange=\"this.form.submit()\"><option value=\"\">${esc(any)}</option>")
    for ((value, label) in options) {
        append("<option value=\"${esc(value)}\"${if (value == chosen) " selected" else ""}>${esc(label)}</option>")
    }
    append("</select>")
}

/** What takes the lab machine's disk, largest first, as [DiskUsage] last measured it, and the [database] as it is now. */
internal fun diskPage(
    report: DiskReport?,
    holds: List<Hold> = emptyList(),
    database: DatabaseReport? = null,
    volumes: List<VolumeReport> = emptyList(),
    /** Why the runner holds its queue ([Volumes.hold]); null when it does not. */
    held: String? = null,
): String = buildString {
    append(head("disk · eignex lab", live = false))
    append("<header><h1>Disk</h1></header>")
    if (held != null) append("<p class=\"FAILED\">The queue is held: ${esc(held)}</p>")
    if (volumes.isNotEmpty()) append(volumesSection(volumes))
    if (database != null) append(databaseSection(database))
    if (holds.isNotEmpty()) {
        append("<h2>Held for download</h2><p class=\"muted\">The trim leaves these jobs' files alone until each hold is ")
        append("released or lapses, however low the disk.</p>")
        append("<div class=\"scroll\"><table><tr><th>job</th><th>hold</th><th>taken</th><th>until</th></tr>")
        for (hold in holds) {
            append("<tr><td><a href=\"/jobs/${hold.job}\">${hold.job}</a></td><td><code>${esc(hold.id.take(8))}</code></td>")
            append("<td>${ago(hold.createdAt)}</td><td>${clockTime(hold.expiresAt)}</td></tr>")
        }
        append("</table></div>")
    }
    if (report == null) {
        append("<p class=\"muted\">Not measured yet: the first measurement runs 10 minutes after the API starts and can take a while.</p></body></html>")
        return@buildString
    }
    append("<h2>What uses it</h2><p class=\"muted\">${size(report.freeBytes)} free on the data directory's volume")
    append(" · measured ${ago(report.at)}, every 6 hours; the corpus and what is on another volume (the external drive) ")
    append("once a day, finished jobs once</p>")
    append("<div class=\"scroll\"><table><tr><th>what</th><th>path</th><th class=\"num\">size</th><th>as of</th></tr>")
    for (place in report.places) {
        append("<tr><td>${esc(place.label)}</td><td><code>${esc(place.path)}</code></td>")
        append("<td class=\"num\">${place.bytes?.let(::size) ?: "?"}</td><td>${ago(place.at ?: report.at)}</td></tr>")
    }
    append("</table></div>")
    if (report.largestJobs.isNotEmpty()) {
        append("<h2>Largest jobs</h2><div class=\"scroll\"><table><tr><th>job</th><th class=\"num\">size</th></tr>")
        for ((id, bytes) in report.largestJobs) {
            append("<tr><td><a href=\"/jobs/$id/files\">$id</a></td><td class=\"num\">${size(bytes)}</td></tr>")
        }
        append("</table></div>")
    }
    append(dockerSection(report))
    append("</body></html>")
}

/** The disk page's volumes: each file system the lab writes to, its free and total space, and the lab's directories on it. */
private fun volumesSection(volumes: List<VolumeReport>): String = buildString {
    append("<h2>Volumes</h2><div class=\"scroll\"><table><tr><th>volume</th><th class=\"num\">free</th><th class=\"num\">total</th>")
    append("<th>lab directories</th></tr>")
    for (volume in volumes) {
        append("<tr><td><code>${esc(volume.mount)}</code>")
        volume.problem?.let { append(" <span class=\"FAILED\">${esc(it)}</span>") }
        append("</td><td class=\"num\">${volume.freeBytes?.let(::size) ?: "?"}</td>")
        append("<td class=\"num\">${volume.totalBytes?.let(::size) ?: "?"}</td><td>")
        append(volume.paths.joinToString("<br>") { "${esc(it.label)} (${it.name}) <code>${esc(it.path)}</code>" })
        append("</td></tr>")
    }
    append("</table></div>")
}

/** The disk page's database part: the file's size and free pages, its kept provenances and the [DbCompaction]'s progress. */
private fun databaseSection(database: DatabaseReport): String = buildString {
    val compaction = database.compaction
    append("<h2>Database</h2><p>${size(database.bytes)}, ${size(database.freeBytes)} of it free pages a vacuum gives back")
    append(" · ${database.provenances} distinct build provenances kept once</p>")
    val progress = when {
        compaction.migratedAt != null -> "done ${ago(compaction.migratedAt)}"
        compaction.startedAt == null -> "not started"
        else -> "${compaction.scanned} of ${compaction.total ?: "?"} case records" +
            (compaction.total?.takeIf { it > 0 }?.let { " (${minOf(100, compaction.scanned * 100 / it)}%)" } ?: "")
    }
    append("<ul><li>compaction: ${esc(progress)}")
    if (compaction.changed > 0) {
        append(", ${compaction.changed} records ${size(compaction.bytesBefore)} → ${size(compaction.bytesAfter)}")
    }
    append("</li>")
    compaction.vacuum?.let { append("<li>vacuum ${ago(compaction.vacuumAt)}: ${esc(it)}</li>") }
    append("</ul>")
}

/** The disk page's Docker part: what Docker holds and what the last [DockerCleanup] did. Empty when neither is known. */
private fun dockerSection(report: DiskReport): String = buildString {
    val cleanup = report.dockerCleanup
    if (report.docker.isEmpty() && cleanup == null) return@buildString
    append("<h2>Docker</h2>")
    if (report.docker.isNotEmpty()) {
        append("<div class=\"scroll\"><table><tr><th>what</th><th class=\"num\">size</th><th class=\"num\">reclaimable</th></tr>")
        for (usage in report.docker) {
            append("<tr><td>${esc(usage.type)}</td><td class=\"num\">${usage.bytes?.let(::size) ?: "?"}</td>")
            append("<td class=\"num\">${usage.reclaimableBytes?.let(::size) ?: "?"}</td></tr>")
        }
        append("</table></div>")
    }
    if (cleanup == null) {
        append("<p class=\"muted\">No cleanup has run yet.</p>")
        return@buildString
    }
    val before = DockerCleanup.total(cleanup.dockerBefore)
    val after = DockerCleanup.total(cleanup.dockerAfter)
    append("<p>Last cleanup ${ago(cleanup.at)}, because ${esc(cleanup.why)}")
    if (before != null && after != null) append(": Docker ${size(before)} → ${size(after)}")
    if (cleanup.hostDiskBefore != null && cleanup.hostDiskAfter != null) {
        append(", Colima's disks on the host ${size(cleanup.hostDiskBefore)} → ${size(cleanup.hostDiskAfter)}")
    }
    append(".</p><ul>")
    append("<li>tags removed: ${if (cleanup.removedTags.isEmpty()) "none" else cleanup.removedTags.joinToString { "<code>${esc(it)}</code>" }}</li>")
    append("<li>dangling images pruned: ${cleanup.prunedImages}</li>")
    append("<li>build cache reclaimed: ${esc(cleanup.buildCacheReclaimed ?: "?")}</li>")
    cleanup.trimmed?.let { append("<li>fstrim: ${esc(it)}</li>") }
    for (note in cleanup.notes) append("<li class=\"muted\">${esc(note)}</li>")
    append("</ul>")
}

internal fun filesPage(jobId: Long, files: List<FileEntry>): String = buildString {
    append(head("$jobId files · eignex lab", live = false))
    append("<header><h1><a href=\"/jobs/$jobId\">$jobId</a> / files</h1></header>")
    if (files.isEmpty()) append("<p class=\"muted\">No files yet.</p>")
    append("<div class=\"scroll\"><table><tr><th>path</th><th class=\"num\">size</th></tr>")
    for (file in files) {
        append("<tr><td><a href=\"/jobs/$jobId/files/${pathUrl(file.path)}\"><code>${esc(file.path)}</code></a></td>")
        append("<td class=\"num\">${size(file.bytes)}</td></tr>")
    }
    append("</table></div></body></html>")
}

internal val ACTIVE = setOf(Status.QUEUED, Status.RUNNING)

/** The GitHub page of [sha] when [repoUrl] is on GitHub. */
internal fun commitUrl(repoUrl: String, sha: String): String? =
    Regex("github\\.com[:/]([^/]+/[^/]+?)(\\.git)?/?$").find(repoUrl)
        ?.let { "https://github.com/${it.groupValues[1]}/commit/$sha" }

/** An error as one line: a ref the mirror lacks reads as such, anything else is cut to its first line. */
internal fun shortError(error: String): String =
    Regex("rev-parse --verify '(.+)\\^\\{commit\\}'").find(error)?.let { "unknown ref: ${it.groupValues[1]}" }
        ?: error.lineSequence().first().let { if (it.length > 120) it.take(120) + "…" else it }

private fun summary(config: Config, host: HostReport, active: List<Job>): String {
    val running = active.count { it.status == Status.RUNNING }
    val queued = active.count { it.status == Status.QUEUED }
    val free = runCatching { freeBytes(config.dataDir) }.getOrNull()
    // A low disk on any volume the lab writes to, or a missing one, holds the queue ([Volumes.hold]).
    val held = runCatching { Volumes.hold(config) }.getOrNull()
    val disk = (free?.let { "${size(it)} free" } ?: "free space unknown") +
        (held?.let { " · <span class=\"FAILED\">queue held: ${esc(it)}</span>" } ?: "") + " (<a href=\"/disk\">what uses it</a>)"
    val accel = listOfNotNull(host.vendor, "simd".takeIf { host.simd }).joinToString(", ")
    return "$running running · $queued queued · $disk · ${esc(host.host)}, ${host.cores} cores" +
        (if (accel.isEmpty()) "" else ", ${esc(accel)}")
}

/** Where a job's remaining work may run, as the work summary splits it. */
private enum class WorkKind(val label: String) {
    LOCAL("local only"),
    TOWARD_CLOUD("local or cloud, toward cloud"),
    CLOUD("cloud"),
}

private fun workKind(config: Config, job: Job): WorkKind = when (Placement.where(job.experiment?.host)) {
    Placement.LOCAL_OR_CLOUD ->
        WorkKind.TOWARD_CLOUD
    Placement.CLOUD -> WorkKind.CLOUD
    else -> WorkKind.LOCAL
}

/**
 * The block above the Active table: the work scheduled, in estimated core-hours ([estimates], by job) split by where it
 * may run, and the time the local machine alone would take to drain what it may run: the sum of those jobs' own wall
 * times, as the local machine runs one job at a time. Then, with cloud settings, the launch gauge ([launchGauge]).
 * Paused jobs and ones whose cancel was requested wait for nothing, so they are not counted.
 */
internal fun workSummary(config: Config, active: List<Job>, estimates: Map<Long, WorkEstimate>): String = buildString {
    val counted = active.filter { !it.paused && !it.cancelRequested }
    val split = counted.groupBy { workKind(config, it) }.mapValues { (_, jobs) -> jobs.sumOf { estimates[it.id]?.coreHours ?: 0.0 } }
    val total = split.values.sum()
    val local = counted.filter { workKind(config, it) != WorkKind.CLOUD }.sumOf { estimates[it.id]?.localHours ?: 0.0 }
    append("<div class=\"work\"><div><b>Work scheduled</b> ")
    if (total <= 0.0) {
        append("<span class=\"muted\">none</span>")
    } else {
        append("≈${coreHoursText(total)} core-h")
        if (local > 0) append(" <span class=\"muted\">· ~${wallText(local)} for the local machine alone</span>")
    }
    append("</div>")
    val parts = WorkKind.entries.mapNotNull { kind -> split[kind]?.takeIf { it > 0 }?.let { kind to it } }
    if (parts.any { it.first != WorkKind.LOCAL }) {
        append("<div class=\"muted small\">")
        append(parts.joinToString(" · ") { (kind, hours) ->
            val title = when (kind) {
                WorkKind.LOCAL -> "placed on the local machine, or taken by it"
                WorkKind.TOWARD_CLOUD -> "eligible for compatible Nomad cloud capacity"
                WorkKind.CLOUD -> "placed on or running on cloud nodes"
            }
            "<span title=\"$title\">${kind.label} ${coreHoursText(hours)}</span>"
        })
        append("</div>")
    }
    append("<div class=\"muted small\">Nomad · <a href=\"/nodes\">nodes and capacity</a></div>")
    append("</div>")
}

/** Core-hours, one decimal under ten and whole above. */
internal fun coreHoursText(hours: Double): String = if (hours < 10) "%.1f".format(Locale.ROOT, hours) else "%.0f".format(Locale.ROOT, hours)

/** A wall time of [hours], rounded up to the minute: `14 min`, `3 h 20 min`, `2 d 5 h`. */
internal fun wallText(hours: Double): String {
    val minutes = kotlin.math.ceil(hours * 60).toLong()
    return when {
        minutes < 60 -> "$minutes min"
        minutes < 48 * 60 -> "${minutes / 60} h" + if (minutes % 60 != 0L) " ${minutes % 60} min" else ""
        else -> "${minutes / (24 * 60)} d ${minutes / 60 % 24} h"
    }
}

/**
 * A row's remaining work: its estimated core-hours, for work the local machine may run the time the local machine would
 * take at the job's own concurrency, and where the time a case came from ([WorkEstimate.note]).
 */
private fun estimateText(config: Config, job: Job, estimate: WorkEstimate?): String {
    if (estimate == null || estimate.coreHours <= 0) return ""
    val local = if (workKind(config, job) == WorkKind.CLOUD) "" else " · ~${wallText(estimate.localHours)} locally"
    val title = when (estimate.source) {
        EstimateSource.MEASURED -> "from the mean time of this job's finished cases"
        EstimateSource.HISTORY -> "from the case count and mean case time of run ${estimate.history}, the last done run of this experiment"
        EstimateSource.TIMEOUT -> "from each case's whole timeout on its cores: no finished run of this experiment measured its cases"
    }
    return "<div class=\"est\" title=\"$title\">≈${coreHoursText(estimate.coreHours)} core-h$local " +
        "<span class=\"muted\">(${esc(estimate.note)})</span></div>"
}

private fun activeTable(
    config: Config, active: List<Job>, queueOrder: List<Long>, estimates: Map<Long, WorkEstimate>, waiting: Map<Long, String>,
    hosts: Map<Long, List<HostProgress>> = emptyMap(),
    nodes: Map<Long, List<JobNode>> = emptyMap(),
): String {
    if (active.isEmpty()) return "<p class=\"muted\">Nothing running or queued.</p>"
    // One queue by priority, whatever runs each job: the local machine and the cloud nodes take from it in this order
    // as the lab places jobs, each passing over those it may not run.
    val positions = queueOrder.withIndex().associate { (index, id) -> id to index + 1 }
    val ordered = active.sortedWith(compareBy<Job> { it.status != Status.RUNNING }.thenBy { positions[it.id] ?: Int.MAX_VALUE })
    return jobTable(config, ordered, positions, filterable = false, estimates = estimates, waiting = waiting, hosts = hosts, nodes = nodes)
}

private fun jobTable(
    config: Config, jobs: List<Job>, positions: Map<Long, Int>, filterable: Boolean, estimates: Map<Long, WorkEstimate>? = null,
    waiting: Map<Long, String> = emptyMap(), hosts: Map<Long, List<HostProgress>> = emptyMap(),
    nodes: Map<Long, List<JobNode>> = emptyMap(),
): String = buildString {
    if (jobs.isEmpty()) return "<p class=\"muted\">No jobs.</p>"
    append("<div class=\"scroll\"><table><tr><th>id</th><th>name</th><th>ref</th><th>where</th><th>status</th><th>progress</th>")
    append("<th>created</th><th>elapsed</th><th></th></tr>")
    for (job in jobs) {
        if (filterable) {
            val state = when {
                job.status == Status.FAILED || job.failed > 0 -> "failed"
                job.status in ACTIVE -> "active"
                else -> job.status.name.lowercase()
            }
            val text = esc(listOfNotNull(job.name, job.ref, job.sha, job.experiment?.description).joinToString(" ").lowercase())
            append("<tr data-state=\"$state\" data-text=\"$text\">")
        } else {
            append("<tr>")
        }
        append("<td><a href=\"/jobs/${job.id}\">${job.id}</a></td>")
        val base = job.name.substringBefore('@')
        append("<td><a class=\"plain\" href=\"/${query("name" to base)}\" title=\"all runs of ${esc(base)}\">${esc(job.name)}</a>")
        append(" <span class=\"kind\">${kindOf(job)}</span>")
        job.experiment?.description?.takeIf { it.isNotBlank() }?.let {
            append("<div class=\"desc\" title=\"${esc(it)}\">${esc(it)}</div>")
        }
        append("</td>")
        append("<td>${refText(config, job)}</td><td>${hostCell(config, job, hosts[job.id].orEmpty(), nodes[job.id].orEmpty())}</td><td>${statusCell(job, positions[job.id])}</td>")
        append("<td>${progress(job)}${estimates?.let { estimateText(config, job, it[job.id]) }.orEmpty()}</td>")
        append("<td>${ago(job.createdAt)}</td><td>${elapsed(job)}</td>")
        append("<td><a href=\"/jobs/${job.id}/files\">files</a></td></tr>")
    }
    append("</table></div>")
}

/**
 * What kind of job [job] is, in a word, which a queue row shows instead of its commands: `profile` for a profile job,
 * `custom` for one of the retired script jobs,
 * `a/b` for an experiment of several arms, `sweep` and `reference` for those schedules' runs, `confirm` for a
 * confirmation, `reference solve` for a run of the reference solvers, `run` for any other experiment.
 */
internal fun kindOf(job: Job): String {
    val spec = job.experiment ?: return "commands"
    if (spec.isProfile) return "profile"
    if (spec.isScript) return "custom"
    if (Confirm.SUFFIX in job.name) return "confirm"
    val arms = runCatching { Experiments.arms(spec) }.getOrDefault(emptyList())
    val base = job.name.substringBefore('@')
    return when {
        arms.any { it.values["backend"] == REFERENCE_BACKEND } -> "reference solve"
        base.startsWith("status-sweep") -> "sweep"
        base.startsWith("klause-reference") -> "reference"
        arms.size > 1 -> "a/b"
        else -> "run"
    }
}

private fun schedulesTable(schedules: List<Schedule>): String = buildString {
    append("<div class=\"scroll\"><table><tr><th>name</th><th>ref</th><th>every</th><th>arms</th>")
    append("<th>last run</th><th>checked</th><th>next check</th><th></th></tr>")
    for (schedule in schedules) {
        append("<tr><td><a class=\"plain\" href=\"/${query("name" to schedule.name)}\">${esc(schedule.name)}</a>")
        append(" <small><a href=\"/trend${query("name" to schedule.name)}\">trend</a></small>")
        if (schedule.priority != 0) append("<br><small>priority ${schedule.priority}</small>")
        append("</td><td><code>${esc(schedule.ref)}</code></td><td>${span(schedule.intervalSec)}</td>")
        val arms = Experiments.arms(schedule.experiment).size
        append("<td>$arms${if (schedule.parallel > 1) " ×${schedule.parallel}" else ""}</td><td>")
        if (schedule.lastJob != null) {
            append("<a href=\"/jobs/${schedule.lastJob}\">${schedule.lastJob}</a> <code>${schedule.lastSha?.take(9) ?: ""}</code>")
        } else {
            append("<span class=\"muted\">never</span>")
        }
        append("</td><td>${ago(schedule.checkedAt)}</td><td>")
        val next = schedule.checkedAt?.let { it + schedule.intervalSec * 1000 - now() } ?: 0
        append(if (next <= 0) "due" else "in ${span(next / 1000)}")
        append("</td><td>")
        if (schedule.launchRequested) {
            append("<span class=\"muted\">launching</span>")
        } else {
            append("<button onclick=\"act('/schedules/${schedule.id}/launch', null, 'Queue a run of ${esc(schedule.name)} now?')\">launch now</button>")
        }
        append("</td></tr>")
    }
    append("</table></div>")
}

/**
 * Where a job runs, as the lab placed it: the local machine alone, shared by the local machine and cloud nodes, or the
 * cloud alone; while it runs, the hosts on it now ("local + 2 nodes": the local machine when it works on it, and the
 * nodes) and each host's part of it ([hosts]).
 */
private fun hostCell(config: Config, job: Job, hosts: List<HostProgress> = emptyList(), nodes: List<JobNode> = emptyList()): String {
    val spec = job.experiment
    if (job.backend == "nomad" && job.status in ACTIVE) {
        val where = when {
            nodes.isNotEmpty() && nodes.all { it.local } -> "local"
            nodes.isNotEmpty() && nodes.none { it.local } -> "cloud"
            nodes.isNotEmpty() -> "local + cloud"
            else -> Experiments.hostWord(spec?.host ?: Experiments.AUTO_HOST)
        }
        val detail = nodes.joinToString("<br>") { node ->
            val href = node.anchor?.let { "/nodes#" + URLEncoder.encode(it, "UTF-8").replace("+", "%20") } ?: "/jobs/${job.id}/execution"
            "<a href=\"${esc(href)}\" title=\"Nomad node ${esc(node.id)}\">${esc(node.name)}</a>" +
                node.profile?.let { "<br>${esc(it)}" }.orEmpty()
        }.ifEmpty { if (!job.paused && !job.cancelRequested) "waiting for a node" else "" }
        return "<span class=\"where${if (where == "cloud") " aws" else ""}\">${esc(where)}</span>" +
            if (detail.isEmpty()) "" else "<br><small>$detail</small>"
    }
    val instances = config.jobDir(job.id).resolve("aws-instances").toFile().takeIf { it.isFile }
        ?.readLines()?.filter { it.isNotBlank() }.orEmpty()
    if (spec?.host == Experiments.AUTO_HOST) {
        val where = "<span class=\"where\" title=\"shared by the lab: the local machine and cloud nodes each run whole iterations of it\">local or cloud</span>"
        if (job.status != Status.RUNNING) return where
        val on = Waiting.hostsText(job.local, instances.size)
        val parts = hosts.takeIf { it.size > 1 }?.joinToString(" · ") { it.text() }
        return "$where<br><small title=\"${esc(instances.joinToString { it.substringBefore(' ') })}\">${esc(on)}" +
            (parts?.let { "<br>${esc(it)}" } ?: "") + "</small>"
    }
    if (spec?.host != Experiments.AWS_HOST) {
        val note = when {
            spec?.auto == true -> " <small class=\"muted\" title=\"could have gone to a cloud node; the local machine took it\">taken</small>"
            spec != null && job.status == Status.QUEUED -> " <small class=\"muted\">only</small>"
            else -> ""
        }
        return "<span class=\"where\">local</span>$note"
    }
    // Each line: instance, type, and how it was bought (spot or on-demand).
    val shapes = instances.map { it.split(' ') }.groupingBy { "${it.getOrNull(1) ?: "instance"} ${it.getOrNull(2) ?: ""}".trim() }.eachCount()
    val detail = when {
        instances.isNotEmpty() -> "${instances.size} ${if (instances.size == 1) "node" else "nodes"}: " +
            shapes.entries.joinToString(", ") { (shape, n) -> if (n == 1) shape else "$n × $shape" }
        // A cancel-requested or paused job waits for nothing: no node takes it.
        job.status == Status.QUEUED && !job.cancelRequested && !job.paused -> "waiting for a node"
        else -> null
    }
    return "<span class=\"where aws\">cloud</span>" +
        (detail?.let { "<br><small title=\"${esc(instances.joinToString { it.substringBefore(' ') })}\">${esc(it)}</small>" } ?: "")
}

/** A job's status with its notes: cancelling, paused, its place in the queue, its priority and its error. Why a queued
 *  one waits is left to `lab placement`. */
private fun statusCell(job: Job, position: Int?): String {
    val partial = job.status == Status.DONE && job.failed > 0
    val label = if (partial) {
        "DONE · <a href=\"/jobs/${job.id}?failed\">${job.failed} failed</a>"
    } else {
        job.status.name
    }
    val notes = listOfNotNull(
        "cancelling".takeIf { job.cancelRequested && job.status in ACTIVE },
        "<span class=\"badge\">paused</span>".takeIf { job.paused && job.status in ACTIVE },
        "#$position in the queue".takeIf { position != null },
        "priority ${job.priority}".takeIf { job.priority != 0 },
        job.error?.let { "<span title=\"${esc(it)}\">${esc(shortError(it))}</span>" },
    )
    val cls = if (partial) "PARTIAL" else job.status.name
    return "<span class=\"$cls\">$label</span>" + notes.joinToString("") { "<br><small>$it</small>" }
}

/** The ref and the commit it ran, without repeating a ref that is the commit itself. */
private fun refText(config: Config, job: Job): String {
    val isSha = Regex("[0-9a-f]{7,40}").matches(job.ref)
    val sha = job.sha ?: job.ref.takeIf { isSha }
    val commit = sha?.let { full ->
        val short = full.take(9)
        commitUrl(Repositories.url(job.experiment?.repository ?: Repositories.DEFAULT), full)?.let { "<a href=\"$it\" title=\"$full\">$short</a>" } ?: short
    }
    return "<code>" + listOfNotNull(esc(job.ref).takeUnless { isSha }, commit).joinToString(" ") + "</code>"
}

private fun progress(job: Job): String {
    val total = job.total.coerceAtLeast(1)
    val running = job.running
    fun part(cls: String, count: Int) = if (count == 0) "" else "<i class=\"$cls\" style=\"width:${100.0 * count / total}%\"></i>"
    val bar = "<span class=\"bar\">${part("ok", job.done - job.failed)}${part("bad", job.failed)}${part("run", running)}</span>"
    val parallel = if (job.parallel > 1) " ×${job.parallel}" else ""
    return "$bar<small>${job.done}/${job.total}$parallel</small>"
}

/** Why case [index]'s processes were ended ([Termination]), a line each, with when it happened on hover. */
private fun terminations(dir: File, index: Int): String = Termination.read(dir.toPath(), index).joinToString("") { line ->
    val at = line.substringBefore(' ').toLongOrNull()
    val title = at?.let { " title=\"${java.time.Instant.ofEpochMilli(it)}\"" }.orEmpty()
    "<br><small class=\"muted\"$title>${esc(if (at == null) line else line.substringAfter(' '))}</small>"
}

private fun output(jobId: Long, dir: File, name: String, running: Boolean): String {
    val file = File(dir, name)
    if (!file.isFile) return "<span class=\"muted\">${name.substringAfter('.')}</span>"
    val link = if (running) "$name?tail=$TAIL_BYTES" else name
    return "<a href=\"/jobs/$jobId/files/$link\">${name.substringAfter('.')}</a> <small class=\"muted\">${size(file.length())}</small>"
}

private val clock = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

/** A time to come, as a clock time; the exact time is in the tooltip. */
private fun clockTime(millis: Long): String =
    "<span title=\"${stamp.format(Instant.ofEpochMilli(millis))}\">${clock.format(Instant.ofEpochMilli(millis))}</span>"

/** How long ago, for the last day, then the date; the exact time is in the tooltip. */
private fun ago(millis: Long?): String {
    if (millis == null) return ""
    val seconds = (now() - millis) / 1000
    val text = if (seconds < 86_400) "${span(seconds)} ago" else clock.format(Instant.ofEpochMilli(millis))
    return "<span title=\"${stamp.format(Instant.ofEpochMilli(millis))}\">$text</span>"
}

/** What the lab is doing with a running cloud case, in words: its phase and for how long, which run of the case it is
 *  when not the first, when the next run starts after a dropped connection, and the solve's own time once known. */
internal fun phaseText(phase: CasePhase, now: Long): String = buildString {
    append(
        when (phase.phase) {
            CasePhase.Phase.EXECUTING -> "executing"
            CasePhase.Phase.TRANSFERRING_OUTPUT -> "transferring output"
            CasePhase.Phase.RETRY_BACKOFF -> "SSH dropped, waiting to retry"
            CasePhase.Phase.TERMINATING -> "terminating"
        },
    )
    append(" for ${span(((now - phase.since) / 1000).coerceAtLeast(0))}")
    if (phase.phase == CasePhase.Phase.RETRY_BACKOFF) {
        append(", attempt ${phase.attempt} of ${phase.attempts} failed")
    } else if (phase.attempt > 1) {
        append(", attempt ${phase.attempt} of ${phase.attempts}")
    }
    phase.retryAt?.let { append(", next in ${span(((it - now) / 1000).coerceAtLeast(0))}") }
    phase.elapsedMs?.let { append(", solve took ${span(it / 1000)}") }
}

/** A job's running time as h:mm:ss, its waits in the queue left out. */
private fun elapsed(job: Job): String = if (job.startedAt == null) "" else clock(job.elapsedMs(now()) / 1000)

private fun clock(seconds: Long) = "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)

private fun span(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m"
    seconds < 86_400 -> "${seconds / 3600}h${if (seconds / 60 % 60 > 0) " ${seconds / 60 % 60}m" else ""}"
    else -> "${seconds / 86_400}d${if (seconds / 3600 % 24 > 0) " ${seconds / 3600 % 24}h" else ""}"
}

private fun duration(from: Long?, to: Long?): String {
    if (from == null) return ""
    return clock(((to ?: now()) - from) / 1000)
}

internal fun size(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    else -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
}

private fun query(vararg params: Pair<String, String?>): String = params
    .filter { it.second != null }
    .joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, Charsets.UTF_8)}" }
    .let { if (it.isEmpty()) "" else "?$it" }

private fun pathUrl(path: String) =
    path.split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }

private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private const val TAIL_BYTES = 65_536

/** The lab's views, as the tab bar shows them. */
internal enum class Tab(val label: String, val href: String) {
    QUEUE("Queue", "/"), NODES("Nodes", "/nodes"), REGRESSION("Regression", "/trend"), REFERENCE("Reference", "/references")
}

/**
 * While a trend page's points are still being computed ([trendPage]'s `computing`), it polls `/trend/progress` with its
 * own query and reloads once the server would show other than its [runs], so the points appear without the reader asking
 * again; it stops once nothing is computing. A page with nothing computing gets no script.
 */
private fun trendPoll(runs: Int, computing: Int): String = if (computing == 0) "" else """<script>
(function () {
  const poll = setInterval(async function () {
    if (document.hidden) return;
    try {
      const response = await fetch('/trend/progress' + location.search, {headers: {Accept: 'application/json'}});
      if (!response.ok) return;
      const progress = await response.json();
      if (progress.runs !== $runs) location.reload();
      else if (progress.computing === 0) clearInterval(poll);
    } catch (e) {}
  }, 5000);
})();
</script>"""

private fun head(title: String, live: Boolean, tab: Tab = Tab.QUEUE) = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>${esc(title)}</title><style>$STYLE</style></head>
<body${if (live) " data-refresh" else ""}><nav class="tabs"><a class="brand" href="/">eignex lab</a>${tabs(tab)}</nav>"""

private fun tabs(active: Tab) = Tab.entries.joinToString("") {
    "<a href=\"${it.href}\"${if (it == active) " class=\"on\" aria-current=\"page\"" else ""}>${it.label}</a>"
}

private const val STYLE = """
:root{--bg:#fff;--fg:#111;--muted:#666;--line:#ddd;--soft:#f3f3f3;--link:#0969da;
--run:#0550ae;--ok:#1a7f37;--warn:#9a6700;--bad:#cf222e;--off:#888;--series-1:#2a78d6;--series-2:#eb6834}
@media (prefers-color-scheme: dark){:root{--bg:#111;--fg:#ddd;--muted:#999;--line:#333;--soft:#222;--link:#58a6ff;
--run:#6cb6ff;--ok:#3fb950;--warn:#d29922;--bad:#f85149;--off:#888;--series-1:#3987e5;--series-2:#d95926}}
body{font:14px system-ui,sans-serif;margin:0 auto;max-width:1440px;padding:24px 32px 48px;background:var(--bg);color:var(--fg)}
@media (max-width:700px){body{padding:16px}}
a{color:var(--link)}a.plain{color:inherit;text-decoration:none}a.plain:hover{text-decoration:underline}
h1{margin:0 0 4px}h1 code{font-size:inherit}h1 a{color:inherit;text-decoration:none}h2{font-size:16px;margin:32px 0 10px}h2 small{font-weight:normal}
.muted{color:var(--muted)}p.description{margin:4px 0 0;max-width:80ch}
td .desc{color:var(--muted);font-size:.85em;max-width:60ch;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.scroll{overflow-x:auto}
table{border-collapse:collapse;width:100%}td,th{padding:6px 12px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}
th.num,td.num{text-align:right}tr.sub td{border-top:0;padding-top:0;font-size:12px}tr.sub div{margin:2px 0}
code{font-size:12px;word-break:break-all}
.RUNNING{color:var(--run)}.DONE{color:var(--ok)}.PARTIAL{color:var(--warn)}.FAILED{color:var(--bad)}.CANCELLED,.QUEUED{color:var(--off)}
.PARTIAL a{color:inherit}
.badge{background:var(--warn);color:var(--bg);border-radius:3px;padding:0 4px}
.where{border:1px solid var(--line);border-radius:3px;padding:0 4px;white-space:nowrap}.kind{font-size:.8em;color:var(--muted);border:1px solid var(--line);border-radius:3px;padding:0 4px;white-space:nowrap}.where.aws{border-color:var(--link);color:var(--link)}
.bar{display:flex;width:90px;height:6px;margin:6px 0 2px;background:var(--soft);border-radius:3px;overflow:hidden}
.bar i{display:block}.bar .ok{background:var(--ok)}.bar .bad{background:var(--bad)}.bar .run{background:var(--run)}
td .est{color:var(--muted);font-size:12px}.small{font-size:12px}.warn{color:var(--warn)}
.work{display:flex;flex-direction:column;gap:4px;margin:0 0 12px;line-height:1.5;overflow-wrap:anywhere}
.gauge{display:inline-block;width:120px;max-width:30vw;height:6px;background:var(--soft);border-radius:3px;overflow:hidden;vertical-align:middle}
.gauge i{display:block;height:100%;background:var(--run)}.gauge i.over{background:var(--warn)}
.tools{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:8px}.tools input{font:inherit;padding:3px 6px;min-width:200px}
button{font:inherit;padding:3px 10px;border:1px solid var(--line);border-radius:4px;background:var(--soft);color:var(--fg);cursor:pointer}
pre.script{background:var(--soft);padding:8px;overflow-x:auto;max-width:120ch}table.board tr.total td{font-weight:600;border-bottom:2px solid var(--line)}.chip.on{border-color:var(--link);color:var(--link)}button.danger{color:var(--bad);border-color:var(--bad)}
.actions{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin:12px 0}.actions input{width:4em;font:inherit}
dl.meta{display:grid;grid-template-columns:max-content 1fr;gap:4px 16px;margin:12px 0}dl.meta dt{color:var(--muted)}dl.meta dd{margin:0}
table.grid{width:auto}table.grid td{min-width:110px}
table.grid .ref{border-left:1px solid var(--line);color:var(--muted)}
table.grid td.best{background:color-mix(in srgb,var(--ok) 14%,transparent)}ul.error{color:var(--bad)}
pre.error{color:var(--bad);white-space:pre-wrap;word-break:break-all;background:var(--soft);padding:8px}
.pager{margin-top:12px}
p.note{background:var(--soft);border-left:3px solid var(--link);padding:8px 12px;margin:16px 0}
nav.tabs{display:flex;align-items:center;gap:4px;border-bottom:1px solid var(--line);margin:-8px 0 20px;flex-wrap:wrap}
nav.tabs a{padding:8px 14px;color:var(--muted);text-decoration:none;border-bottom:2px solid transparent;margin-bottom:-1px}
nav.tabs a:hover{color:var(--fg)}nav.tabs a.on{color:var(--fg);border-bottom-color:var(--link);font-weight:600}
nav.tabs a.brand{color:var(--fg);font-weight:700;padding-left:0;margin-right:12px}
a.chip{text-decoration:none;display:inline-block}
tr.family{cursor:pointer}tr.family:hover td{background:var(--soft)}
.filters{align-items:center}.filters input[type=checkbox]{min-width:0;margin:0 4px 0 0}.filters label{display:inline-flex;align-items:center;white-space:nowrap}.filters select{font:inherit;padding:3px 6px;background:var(--bg);color:var(--fg);border:1px solid var(--line);border-radius:4px}
#node-request{align-items:center;gap:12px}#node-request label{display:flex;align-items:center;gap:6px;flex-wrap:wrap}
#node-request select{font:inherit;padding:4px;max-width:100%;background:var(--bg);color:var(--fg);border:1px solid var(--line);border-radius:4px}
#node-request input{min-width:0}#node-request label:first-child{flex-basis:100%}#node-pool{min-width:0}
.chart svg,.multiple svg{display:block}.chart{width:100%}
.grid{stroke:var(--line);stroke-width:1}.tick{fill:var(--muted);font-size:11px}.tick.mono{font-family:ui-monospace,monospace}
.endlabel{fill:var(--fg);font-size:12px}.cross{stroke:var(--muted);stroke-width:1}
.legend{display:flex;gap:16px;font-size:12px;color:var(--muted);margin:-4px 0 6px}
.legend i{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:5px;vertical-align:-1px}
.legend i.hollow{border:2px solid var(--series-1);width:6px;height:6px}.legend i.flag{border:2px solid var(--bad);width:6px;height:6px;background:none}
.multiples{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:12px 20px}
.mtitle{font-size:12px;color:var(--muted);margin-bottom:2px}
.tip{position:absolute;z-index:5;background:var(--bg);border:1px solid var(--line);border-radius:6px;padding:6px 8px;
font-size:12px;box-shadow:0 2px 8px rgba(0,0,0,.15);pointer-events:none;min-width:180px}
.tip div{display:flex;justify-content:space-between;gap:12px}.tip span{color:var(--muted)}
"""

/** Filtering, actions and a refresh that swaps the live parts in place, so scrolling, a selection and a half-typed
 *  input survive it. A page stops refreshing once its fresh copy says nothing is live any more. */
private const val SCRIPT = """<script>
let chip = 'all';
function applyFilter() {
  const q = document.getElementById('q');
  const text = q ? q.value.trim().toLowerCase() : '';
  document.querySelectorAll('tr[data-state]').forEach(function (row) {
    row.hidden = !((!text || row.dataset.text.includes(text)) && (chip === 'all' || row.dataset.state === chip));
  });
}
document.getElementById('q')?.addEventListener('input', applyFilter);
function applyDiffer() {
  const only = document.getElementById('differ')?.checked;
  document.querySelectorAll('tr[data-differ]').forEach(function (row) { row.hidden = only && row.dataset.differ !== 'true'; });
}
document.addEventListener('change', function (e) { if (e.target.id === 'differ') applyDiffer(); });
document.querySelectorAll('[data-chip]').forEach(function (button) {
  button.addEventListener('click', function () {
    chip = button.dataset.chip;
    document.querySelectorAll('[data-chip]').forEach(function (b) { b.classList.toggle('on', b === button); });
    applyFilter();
  });
});
async function act(path, body, ask) {
  if (ask && !confirm(ask)) return;
  const response = await fetch(path, body
    ? {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)}
    : {method: 'POST'});
  if (!response.ok) alert(await response.text());
  location.reload();
}
let timer = null;
async function refresh() {
  if (document.hidden || String(getSelection()).length) return;
  if (document.activeElement && document.activeElement.closest('[data-live]')) return;
  try {
    const response = await fetch(location.href, {headers: {Accept: 'text/html'}});
    if (!response.ok) return;
    const fresh = new DOMParser().parseFromString(await response.text(), 'text/html');
    fresh.querySelectorAll('[data-live]').forEach(function (part) {
      const old = document.getElementById(part.id);
      if (old) old.innerHTML = part.innerHTML;
    });
    applyFilter();
    applyDiffer();
    if (!fresh.body.hasAttribute('data-refresh')) clearInterval(timer);
  } catch (e) {}
}
if (document.body.hasAttribute('data-refresh')) timer = setInterval(refresh, 10000);
</script>"""

/** Node inventory and temporary capacity controls for configured, provider-neutral pools. */
internal fun nodesPage(config: Config, host: HostReport, snapshot: CapacitySnapshot?, leases: List<ManualCapacityLease>, settings: CapacitySettings?): String = buildString {
    append(head("Nodes · eignex lab", live = true, tab = Tab.NODES))
    append("<header><h1>Nodes</h1><p class=\"muted\">Native execution and cloud workers</p></header>")
    append("<section id=\"local-node\"><h2>Local node</h2><p><b>${esc(host.host)}</b> · ${host.cores} logical CPUs · ${config.cores} cores available to the lab</p>")
    val tags = config.localNodeTags.sorted()
    append("<p>${esc(NodeTags.hardwareProfile(config.localNodeTags))}</p>")
    append("<details><summary>All ${tags.size} capability labels</summary><p class=\"muted small\">${esc(tags.joinToString(" · "))}</p></details>")
    append("</section>")
    val pools = settings?.pools ?: snapshot?.pools.orEmpty()
    val configured = settings != null
    append("<section><h2>Start nodes</h2>")
    if (configured) {
        append("<form id=\"node-request\" class=\"tools\"><label>Node pool <select id=\"node-pool\" name=\"pool\" required>")
        for (pool in pools) {
            val limit = minOf(pool.maxNodes, settings!!.maxVcpus / pool.vcpus)
            append("<option value=\"${esc(pool.id)}\" data-limit=\"$limit\"${if (limit == 0) " disabled" else ""}>${esc(NodeTags.hardwareProfile(pool.tags))} · ${pool.vcpus} vCPU / ${pool.memoryMiB / 1024.0} GiB · ${esc(pool.market.tag)} · ${esc(pool.id)}</option>")
        }
        append("</select></label> <label>Keep at least <input id=\"node-count\" name=\"nodes\" type=\"number\" min=\"1\" value=\"1\" required style=\"width:5em\"> nodes</label> ")
        append("<label>For <select name=\"ttlSec\"><option value=\"900\">15 minutes</option><option value=\"1800\">30 minutes</option><option value=\"3600\">1 hour</option></select></label> ")
        append("<button type=\"submit\">Start / keep nodes</button></form><p id=\"node-feedback\" role=\"status\"></p>")
        append("<p class=\"muted small\">The selected pool fixes the hardware capabilities and market. The count is a minimum total for that pool, including nodes already running. ")
        append("Requests share the global ${settings!!.maxVcpus} vCPU limit and expire automatically. Jobs can use these nodes; releasing a request lets automatic scaling retire idle capacity.</p>")
    } else append("<p class=\"muted\">Nomad capacity settings are unavailable; manual starts are disabled.</p>")
    append("</section><div id=\"node-capacity\" data-live><section><h2>Cloud pools</h2>")
    if (snapshot == null) append("<p class=\"warn\">Capacity has not reported. Requests take effect when fresh reports resume.</p>")
    else {
        append("<p class=\"${if (snapshot.ageMs > CapacityCollector.FRESH_MS || !snapshot.healthy || snapshot.failures.isNotEmpty()) "warn" else "muted"}\">Report age: ${snapshot.ageMs / 1000}s")
        if (snapshot.ageMs > CapacityCollector.FRESH_MS || !snapshot.healthy || snapshot.failures.isNotEmpty()) append(" · growth is held")
        append("</p>")
    }
    if (pools.isEmpty()) append("<p class=\"muted\">No cloud pools configured.</p>")
    else {
        append("<div class=\"scroll\"><table><thead><tr><th>Pool / class</th><th>Hardware</th><th>Architecture / SIMD</th><th>Market</th><th>Observed / desired / limit</th></tr></thead><tbody>")
        for (pool in pools) {
            append("<tr><td>${esc(pool.id)}<br><small class=\"muted\">${esc(pool.nodeClass)}</small></td><td>${pool.vcpus} vCPU / ${pool.memoryMiB} MiB</td>")
            append("<td>${esc(NodeTags.hardwareProfile(pool.tags))}</td><td>${esc(pool.market.tag)}</td><td>${snapshot?.nodes?.count { it.pool == pool.id } ?: "?"} / ${snapshot?.decision?.desired?.get(pool.id) ?: "?"} / ${pool.maxNodes}</td></tr>")
        }
        append("</tbody></table></div>")
    }
    append("</section><section><h2>Cloud nodes and allocations</h2>")
    if (snapshot?.nodes.isNullOrEmpty()) append("<p class=\"muted\">${if (snapshot == null) "Node inventory is unavailable." else "No cloud nodes running."}</p>")
    else {
        append("<div class=\"scroll\"><table><thead><tr><th>Node / pool</th><th>State</th><th>Sessions / experiments</th><th>Unreserved CPU / memory</th></tr></thead><tbody>")
        for (node in snapshot!!.nodes) {
            val pool = pools.singleOrNull { it.id == node.pool }
            val cpu = node.reservations.sumOf { it.budget.cpuMillis.toLong() }
            val memory = node.reservations.sumOf { it.budget.memoryMiB.toLong() }
            append("<tr id=\"node-${esc(node.id)}\"><td>${esc(node.id)}<br><small class=\"muted\">${esc(node.pool)}</small></td><td>${node.state}</td><td>")
            append(node.reservations.joinToString("<br>") { allocation ->
                (if (allocation.job > 0) "<a href=\"/jobs/${allocation.job}\">job ${allocation.job}</a>" else "external work") +
                    " · ${esc(allocation.id)} · ${allocation.budget.cpuMillis / 1000.0} vCPU / ${allocation.budget.memoryMiB} MiB"
            }.ifEmpty { "idle" })
            append("</td><td>${pool?.let { (it.cpuMillis - cpu).coerceAtLeast(0) / 1000.0 } ?: "?"} vCPU / ${pool?.let { (it.availableMemoryMiB - memory).coerceAtLeast(0) } ?: "?"} MiB</td></tr>")
        }
        append("</tbody></table></div>")
    }
    for (wait in snapshot?.decision?.waiting.orEmpty()) append("<p>${esc(wait.request)}: ${esc(wait.reason)}</p>")
    for ((pool, reason) in snapshot?.failures.orEmpty()) append("<p class=\"warn\">${esc(pool)}: ${esc(reason)}</p>")
    append("</section><section><h2>Manual capacity requests</h2>")
    if (leases.isEmpty()) append("<p class=\"muted\">No active requests.</p>")
    else {
        append("<table><thead><tr><th>Pool</th><th>Minimum nodes</th><th>Expires</th><th></th></tr></thead><tbody>")
        for (lease in leases) append("<tr><td>${esc(lease.pool)}</td><td>${lease.nodes}</td><td>${clockTime(lease.expiresAt)}</td><td><button type=\"button\" data-release-capacity=\"${esc(lease.id)}\">Release</button></td></tr>")
        append("</tbody></table>")
    }
    append("</section></div>")
    append(SCRIPT)
    append(NODES_SCRIPT)
    append("</body></html>")
}

private const val NODES_SCRIPT = """<script>
function nodeLimit() {
  const pool = document.getElementById('node-pool');
  const count = document.getElementById('node-count');
  if (!pool || !count) return;
  count.max = pool.selectedOptions[0]?.dataset.limit || '0';
  document.querySelector('#node-request button').disabled = Number(count.max) < 1;
}
nodeLimit();
document.getElementById('node-pool')?.addEventListener('change', nodeLimit);
document.addEventListener('submit', async function (event) {
  if (event.target.id !== 'node-request') return;
  event.preventDefault();
  const form = event.target, button = form.querySelector('button');
  const feedback = document.getElementById('node-feedback');
  button.disabled = true;
  feedback.textContent = 'Requesting capacity…';
  try {
    const data = new FormData(form);
    const response = await fetch('/capacity/leases', {method: 'POST', headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({pool: data.get('pool'), nodes: Number(data.get('nodes')), ttlSec: Number(data.get('ttlSec'))})});
    if (!response.ok) throw new Error(await response.text());
    feedback.textContent = 'Capacity requested. Nodes will start as the pool reconciles.';
    await refresh();
  } catch (error) { feedback.textContent = error.message; }
  finally { nodeLimit(); }
});
document.addEventListener('click', async function (event) {
  const button = event.target.closest('[data-release-capacity]');
  if (!button) return;
  button.disabled = true;
  try {
    const response = await fetch('/capacity/leases/' + encodeURIComponent(button.dataset.releaseCapacity), {method: 'DELETE'});
    if (!response.ok && response.status !== 404) throw new Error(await response.text());
    await refresh();
  } catch (error) { alert(error.message); }
  finally { button.disabled = false; }
});
</script>"""

internal fun executionPage(job: Long, reports: List<ExecutionSessionReport>): String = buildString {
    append(head("Execution · job $job", live = true))
    append("<header><h1>Execution · job $job</h1></header><div id=\"execution\" data-live>")
    append("<p><a href=\"/jobs/$job\">Experiment</a> · <a href=\"/jobs/$job/files/job.log\">Log</a> · <a href=\"/nodes\">Nodes</a></p>")
    append("<table><thead><tr><th>Session / allocation</th><th>Node</th><th>Phase / generation</th><th>Budget</th><th>Accepted iterations</th></tr></thead><tbody>")
    for (report in reports) {
        val session = report.session
        append("<tr><td>${esc(session.id)}<br>${esc(session.allocation ?: "pending")}</td><td>${esc(session.node ?: "pending")}</td>")
        append("<td>${esc(report.phase ?: session.desired)} / ${report.generation ?: 0}</td><td>${session.budget.cpuMillis / 1000.0} vCPU / ${session.budget.memoryMiB} MiB</td><td>${report.committed}</td></tr>")
        report.observation?.let { append("<tr><td colspan=\"5\">${esc(it)}</td></tr>") }
    }
    append("</tbody></table><p>Live case output is provisional and limited to the first 8 MiB per stream. Full output and captures appear after the paired iteration is accepted.</p></div></body></html>")
}
