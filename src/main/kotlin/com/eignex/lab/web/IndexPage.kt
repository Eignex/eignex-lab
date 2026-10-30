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
    /** The cloud instances each job ran on ([JobDirView.instances]), by job. */
    instances: Map<Long, List<String>> = emptyMap(),
    /** Free on the data directory's volume, and why the runner holds its queue, read before rendering ([PageQueries.index]). */
    free: Long? = runCatching { freeBytes(config.dataDir) }.getOrNull(),
    held: String? = runCatching { Volumes.hold(config) }.getOrNull(),
): String = buildString {
    val front = before == null && name == null
    append(head("eignex lab", live = before == null))
    append("<header><h1>Queue</h1>")
    append("<p id=\"summary\" data-live class=\"muted\">${summary(config, host, active, nodes, free, held)}</p></header>")
    if (front) {
        // One pass over the active jobs: the per-row estimates and the summary's totals read the same numbers.
        val estimates = active.mapNotNull { job ->
            Estimates.estimate(job, caseHistory, config.cores, config.maxParallel)?.let { job.id to it }
        }.toMap()
        append("<section><h2>Active</h2><div id=\"work\" data-live>${workSummary(config, active, estimates)}</div>")
        append("<div id=\"active\" data-live>${activeTable(config, active, queueOrder, estimates, waiting, hosts, nodes, instances)}</div></section>")
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
    val shown = if (front) history.filter { it.status.isTerminal } else history
    append(jobTable(config, shown, emptyMap(), filterable = true, hosts = hosts, nodes = nodes, instances = instances))
    append("<p class=\"pager\">")
    if (before != null) append("<a href=\"/${query("name" to name)}\">newest</a> ")
    if (history.size == limit) append("<a href=\"/${query("name" to name, "before" to history.last().id.toString())}\">older</a>")
    append("</p></div></section>")
    append(SCRIPT)
    append("</body></html>")
}

/** Where a job's remaining work may run, as the work summary splits it. */
private enum class WorkKind(val label: String) {
    LOCAL("local only"),
    TOWARD_CLOUD("local or cloud, toward cloud"),
    CLOUD("cloud"),
}

private fun workKind(config: Config, job: Job): WorkKind = when (Placement.where(job.experiment.host)) {
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
    instances: Map<Long, List<String>> = emptyMap(),
): String {
    if (active.isEmpty()) return "<p class=\"muted\">Nothing running or queued.</p>"
    // One queue by priority, whatever runs each job: the local machine and the cloud nodes take from it in this order
    // as the lab places jobs, each passing over those it may not run.
    val positions = queueOrder.withIndex().associate { (index, id) -> id to index + 1 }
    val ordered = active.sortedWith(compareBy<Job> { it.status != Status.RUNNING }.thenBy { positions[it.id] ?: Int.MAX_VALUE })
    return jobTable(config, ordered, positions, filterable = false, estimates = estimates, waiting = waiting, hosts = hosts, nodes = nodes,
        instances = instances)
}

private fun jobTable(
    config: Config, jobs: List<Job>, positions: Map<Long, Int>, filterable: Boolean, estimates: Map<Long, WorkEstimate>? = null,
    waiting: Map<Long, String> = emptyMap(), hosts: Map<Long, List<HostProgress>> = emptyMap(),
    nodes: Map<Long, List<JobNode>> = emptyMap(),
    instances: Map<Long, List<String>> = emptyMap(),
): String = buildString {
    if (jobs.isEmpty()) return "<p class=\"muted\">No jobs.</p>"
    append("<div class=\"scroll\"><table><tr><th>id</th><th>name</th><th>ref</th><th>where</th><th>status</th><th>progress</th>")
    append("<th>created</th><th>elapsed</th><th></th></tr>")
    for (job in jobs) {
        if (filterable) {
            val state = when {
                job.status == Status.FAILED || job.failed > 0 -> "failed"
                job.status.isActive -> "active"
                else -> job.status.name.lowercase()
            }
            val text = esc(listOfNotNull(job.name, job.ref, job.sha, job.experiment.description).joinToString(" ").lowercase())
            append("<tr data-state=\"$state\" data-text=\"$text\">")
        } else {
            append("<tr>")
        }
        append("<td><a href=\"/jobs/${job.id}\">${job.id}</a></td>")
        val base = job.origin.series
        append("<td><a class=\"plain\" href=\"/${query("name" to base)}\" title=\"all runs of ${esc(base)}\">${esc(job.name)}</a>")
        append(" <span class=\"kind\">${kindOf(job)}</span>")
        job.experiment.description.takeIf { it.isNotBlank() }?.let {
            append("<div class=\"desc\" title=\"${esc(it)}\">${esc(it)}</div>")
        }
        append("</td>")
        append("<td>${refText(config, job)}</td><td>${hostCell(config, job, hosts[job.id].orEmpty(), nodes[job.id].orEmpty(), instances[job.id].orEmpty())}</td><td>${statusCell(job, positions[job.id], nodes[job.id].orEmpty())}</td>")
        append("<td>${progress(job)}${estimates?.let { estimateText(config, job, it[job.id]) }.orEmpty()}</td>")
        append("<td>${ago(job.createdAt)}</td><td>${elapsed(job)}</td>")
        append("<td><a href=\"/jobs/${job.id}/files\">files</a></td></tr>")
    }
    append("</table></div>")
}

/**
 * What kind of job [job] is, in a word, which a queue row shows instead of its commands: `profile` for a profile job,
 * `custom` for one of the retired script jobs,
 * `a/b` for an experiment of several arms, `schedule` for a scheduled run, `confirm` for a
 * confirmation, `reference solve` for a run of the reference solvers, `run` for any other experiment.
 */
internal fun kindOf(job: Job): String {
    val spec = job.experiment
    when (spec.work) {
        is Work.Profile -> return "profile"
        Work.Script -> return "custom"
        is Work.Bench, is Work.Analysis, Work.Recipe -> {}
    }
    if (job.origin is JobOrigin.Confirmation) return "confirm"
    val arms = runCatching { Experiments.arms(spec) }.getOrDefault(emptyList())
    return when {
        arms.any { it.values["backend"] == REFERENCE_BACKEND } -> "reference solve"
        arms.size > 1 -> "a/b"
        job.origin is JobOrigin.Scheduled -> "schedule"
        else -> "run"
    }
}

private fun schedulesTable(schedules: List<Schedule>): String = buildString {
    append("<div class=\"scroll\"><table><tr><th>name</th><th>ref</th><th>every</th><th>arms</th>")
    append("<th>last run</th><th>checked</th><th>next check</th><th></th></tr>")
    for (schedule in schedules) {
        val blocked = schedule.blockedReason()
        append("<tr><td><a class=\"plain\" href=\"/${query("name" to schedule.name)}\">${esc(schedule.name)}</a>")
        append(" <small><a href=\"/trend${query("name" to schedule.name)}\">trend</a></small>")
        if (schedule.priority != 0) append("<br><small>priority ${schedule.priority}</small>")
        if (blocked != null) append("<br><small>blocked: ${esc(blocked)}</small>")
        append("</td><td><code>${esc(schedule.ref)}</code></td><td>${span(schedule.intervalSec)}</td>")
        val arms = runCatching { Experiments.arms(schedule.experiment).size }.getOrNull()?.toString() ?: "invalid"
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
        if (blocked != null) {
            append("<span class=\"muted\">edit schedule to resume</span>")
        } else if (schedule.launchRequested) {
            append("<span class=\"muted\">launching</span>")
        } else {
            // The prompt rides in a data attribute, never inside the JavaScript string, so a name cannot break out of it.
            append("<button data-confirm=\"Queue a run of ${esc(schedule.name)} now?\" onclick=\"act('/schedules/${schedule.id}/launch', null, this.dataset.confirm)\">launch now</button>")
        }
        append("</td></tr>")
    }
    append("</table></div>")
}
