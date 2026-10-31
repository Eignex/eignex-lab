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
    status: StatusSettings,
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
    free: Long? = runCatching { freeBytes(status.paths.dataDir) }.getOrNull(),
    held: String? = runCatching { Volumes.hold(status.paths, status.minFreeBytes) }.getOrNull(),
): Html = buildHtml {
    val front = before == null && name == null
    append(head("eignex lab", live = before == null))
    append(Html("<header><h1>Queue</h1>"))
    append(Html("<p id=\"summary\" data-live class=\"muted\">") + summary(status, host, active, nodes, free, held) + Html("</p></header>"))
    if (front) {
        // One pass over the active jobs: the per-row estimates and the summary's totals read the same numbers.
        val estimates = active.mapNotNull { job ->
            Estimates.estimate(job, caseHistory, status.local.cores, status.local.maxParallel)?.let { job.id to it }
        }.toMap()
        append(Html("<section><h2>Active</h2><div id=\"work\" data-live>") + workSummary(status, active, estimates) + Html("</div>"))
        append(Html("<div id=\"active\" data-live>") + activeTable(status, active, queueOrder, estimates, waiting, hosts, nodes, instances) + Html("</div></section>"))
        if (schedules.isNotEmpty()) {
            append(Html("<section><h2>Schedules</h2><div id=\"schedules\" data-live>") + schedulesTable(schedules) + Html("</div></section>"))
        }
    }
    append(Html("<section><h2>"))
    append(if (name == null) Html("History") else (Html("Runs of ") + esc(name) + Html(" <small><a href=\"/\">all jobs</a></small>")))
    append(Html("</h2><div class=\"tools\"><input id=\"q\" type=\"search\" placeholder=\"filter by name or ref\">"))
    for (chip in listOf("all", "failed", "done", "cancelled", "active")) {
        if (chip == "active" && front) continue
        append(Html("<button class=\"chip") + (if (chip == "all") " on" else "") + Html("\" data-chip=\"") + chip + Html("\">") + chip + Html("</button>"))
    }
    append(Html("</div><div id=\"history\" data-live>"))
    val shown = if (front) history.filter { it.status.isTerminal } else history
    append(jobTable(status, shown, emptyMap(), filterable = true, hosts = hosts, nodes = nodes, instances = instances))
    append(Html("<p class=\"pager\">"))
    if (before != null) append(Html("<a href=\"/") + query("name" to name) + Html("\">newest</a> "))
    if (history.size == limit) append(Html("<a href=\"/") + (query("name" to name, "before" to history.last().id.toString())) + Html("\">older</a>"))
    append(Html("</p></div></section>"))
    append(Html(SCRIPT))
    append(Html("</body></html>"))
}

/** Where a job's remaining work may run, as the work summary splits it. */
private enum class WorkKind(val label: String) {
    LOCAL("local only"),
    TOWARD_CLOUD("local or cloud, toward cloud"),
    CLOUD("cloud"),
}

private fun workKind(status: StatusSettings, job: Job): WorkKind = when (Placement.where(job.experiment.host)) {
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
internal fun workSummary(status: StatusSettings, active: List<Job>, estimates: Map<Long, WorkEstimate>): Html = buildHtml {
    val counted = active.filter { !it.paused && !it.cancelRequested }
    val split = counted.groupBy { workKind(status, it) }.mapValues { (_, jobs) -> jobs.sumOf { estimates[it.id]?.coreHours ?: 0.0 } }
    val total = split.values.sum()
    val local = counted.filter { workKind(status, it) != WorkKind.CLOUD }.sumOf { estimates[it.id]?.localHours ?: 0.0 }
    append(Html("<div class=\"work\"><div><b>Work scheduled</b> "))
    if (total <= 0.0) {
        append(Html("<span class=\"muted\">none</span>"))
    } else {
        append(Html("≈") + coreHoursText(total) + Html(" core-h"))
        if (local > 0) append(Html(" <span class=\"muted\">· ~") + wallText(local) + Html(" for the local machine alone</span>"))
    }
    append(Html("</div>"))
    val parts = WorkKind.entries.mapNotNull { kind -> split[kind]?.takeIf { it > 0 }?.let { kind to it } }
    if (parts.any { it.first != WorkKind.LOCAL }) {
        append(Html("<div class=\"muted small\">"))
        append(parts.joinHtml(Html(" · ")) { (kind, hours) ->
            val title = when (kind) {
                WorkKind.LOCAL -> "placed on the local machine, or taken by it"
                WorkKind.TOWARD_CLOUD -> "eligible for compatible Nomad cloud capacity"
                WorkKind.CLOUD -> "placed on or running on cloud nodes"
            }
            (Html("<span title=\"") + title + Html("\">") + kind.label + Html(" ") + coreHoursText(hours) + Html("</span>"))
        })
        append(Html("</div>"))
    }
    append(Html("<div class=\"muted small\">Nomad · <a href=\"/nodes\">nodes and capacity</a></div>"))
    append(Html("</div>"))
}

/**
 * A row's remaining work: its estimated core-hours, for work the local machine may run the time the local machine would
 * take at the job's own concurrency, and where the time a case came from ([WorkEstimate.note]).
 */
private fun estimateText(status: StatusSettings, job: Job, estimate: WorkEstimate?): Html {
    if (estimate == null || estimate.coreHours <= 0) return Html("")
    val local = if (workKind(status, job) == WorkKind.CLOUD) "" else " · ~${wallText(estimate.localHours)} locally"
    val title = when (estimate.source) {
        EstimateSource.MEASURED -> "from the mean time of this job's finished cases"
        EstimateSource.HISTORY -> "from the case count and mean case time of run ${estimate.history}, the last done run of this experiment"
        EstimateSource.TIMEOUT -> "from each case's whole timeout on its cores: no finished run of this experiment measured its cases"
    }
    return (Html("<div class=\"est\" title=\"") + title + Html("\">≈") + coreHoursText(estimate.coreHours) + Html(" core-h") + local + Html(" ")) +
        (Html("<span class=\"muted\">(") + esc(estimate.note) + Html(")</span></div>"))
}

private fun activeTable(
    status: StatusSettings, active: List<Job>, queueOrder: List<Long>, estimates: Map<Long, WorkEstimate>, waiting: Map<Long, String>,
    hosts: Map<Long, List<HostProgress>> = emptyMap(),
    nodes: Map<Long, List<JobNode>> = emptyMap(),
    instances: Map<Long, List<String>> = emptyMap(),
): Html {
    if (active.isEmpty()) return Html("<p class=\"muted\">Nothing running or queued.</p>")
    // One queue by priority, whatever runs each job: the local machine and the cloud nodes take from it in this order
    // as the lab places jobs, each passing over those it may not run. Higher priority lists first, running or not.
    val positions = queueOrder.withIndex().associate { (index, id) -> id to index + 1 }
    val ordered = active.sortedWith(compareByDescending<Job> { it.priority }.thenBy { it.status != Status.RUNNING }
        .thenBy { positions[it.id] ?: Int.MAX_VALUE }.thenBy { it.id })
    return jobTable(status, ordered, positions, filterable = false, estimates = estimates, waiting = waiting, hosts = hosts, nodes = nodes,
        instances = instances)
}

private fun jobTable(
    status: StatusSettings, jobs: List<Job>, positions: Map<Long, Int>, filterable: Boolean, estimates: Map<Long, WorkEstimate>? = null,
    waiting: Map<Long, String> = emptyMap(), hosts: Map<Long, List<HostProgress>> = emptyMap(),
    nodes: Map<Long, List<JobNode>> = emptyMap(),
    instances: Map<Long, List<String>> = emptyMap(),
): Html = buildHtml {
    if (jobs.isEmpty()) return Html("<p class=\"muted\">No jobs.</p>")
    append(Html("<div class=\"scroll\"><table><tr><th>id</th><th>name</th><th>ref</th><th>where</th><th>status</th><th>progress</th>"))
    append(Html("<th>created</th><th>elapsed</th><th></th></tr>"))
    for (job in jobs) {
        if (filterable) {
            val state = when {
                job.status == Status.FAILED || job.failed > 0 -> "failed"
                job.status.isActive -> "active"
                else -> job.status.name.lowercase()
            }
            val text = esc(listOfNotNull(job.name, job.ref, job.sha, job.experiment.description).joinToString(" ").lowercase())
            append(Html("<tr data-state=\"") + state + Html("\" data-text=\"") + text + Html("\">"))
        } else {
            append(Html("<tr>"))
        }
        append(Html("<td><a href=\"/jobs/") + job.id + Html("\">") + job.id + Html("</a></td>"))
        val base = job.origin.series
        append(Html("<td><a class=\"plain\" href=\"/") + query("name" to base) + Html("\" title=\"all runs of ") + esc(base) + Html("\">") + esc(job.name) + Html("</a>"))
        append(Html(" <span class=\"kind\">") + kindOf(job) + Html("</span>"))
        job.experiment.description.takeIf { it.isNotBlank() }?.let {
            append(Html("<div class=\"desc\" title=\"") + esc(it) + Html("\">") + esc(it) + Html("</div>"))
        }
        append(Html("</td>"))
        append(Html("<td>") + refText(status.repoUrl, job) + Html("</td><td>") + (hostCell(job, hosts[job.id].orEmpty(), nodes[job.id].orEmpty(), instances[job.id].orEmpty())) + Html("</td><td>") + (statusCell(job, positions[job.id], nodes[job.id].orEmpty())) + Html("</td>"))
        append(Html("<td>") + progress(job) + (estimates?.let { estimateText(status, job, it[job.id]) }.orEmpty()) + Html("</td>"))
        append(Html("<td>") + ago(job.createdAt) + Html("</td><td>") + elapsed(job) + Html("</td>"))
        append(Html("<td><a href=\"/jobs/") + job.id + Html("/files\">files</a></td></tr>"))
    }
    append(Html("</table></div>"))
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
        is Work.Script -> return "custom"
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

private fun schedulesTable(schedules: List<Schedule>): Html = buildHtml {
    append(Html("<div class=\"scroll\"><table><tr><th>name</th><th>ref</th><th>every</th><th>arms</th>"))
    append(Html("<th>last run</th><th>checked</th><th>next check</th><th></th></tr>"))
    for (schedule in schedules) {
        val blocked = schedule.blockedReason()
        append(Html("<tr><td><a class=\"plain\" href=\"/") + query("name" to schedule.name) + Html("\">") + esc(schedule.name) + Html("</a>"))
        append(Html(" <small><a href=\"/trend") + query("name" to schedule.name) + Html("\">trend</a></small>"))
        if (schedule.priority != 0) append(Html("<br><small>priority ") + schedule.priority + Html("</small>"))
        if (blocked != null) append(Html("<br><small>blocked: ") + esc(blocked) + Html("</small>"))
        append(Html("</td><td><code>") + esc(schedule.ref) + Html("</code></td><td>") + span(schedule.intervalSec) + Html("</td>"))
        val arms = runCatching { Experiments.arms(schedule.experiment).size }.getOrNull()?.toString() ?: "invalid"
        append(Html("<td>") + arms + (if (schedule.parallel > 1) " ×${schedule.parallel}" else "") + Html("</td><td>"))
        if (schedule.lastJob != null) {
            append(Html("<a href=\"/jobs/") + schedule.lastJob + Html("\">") + schedule.lastJob + Html("</a> <code>") + (schedule.lastSha?.take(9) ?: "") + Html("</code>"))
        } else {
            append(Html("<span class=\"muted\">never</span>"))
        }
        append(Html("</td><td>") + ago(schedule.checkedAt) + Html("</td><td>"))
        val next = schedule.checkedAt?.let { it + schedule.intervalSec * 1000 - now() } ?: 0
        append(esc(if (next <= 0) "due" else "in ${span(next / 1000)}"))
        append(Html("</td><td>"))
        if (blocked != null) {
            append(Html("<span class=\"muted\">edit schedule to resume</span>"))
        } else if (schedule.launchRequested) {
            append(Html("<span class=\"muted\">launching</span>"))
        } else {
            // The prompt rides in a data attribute, never inside the JavaScript string, so a name cannot break out of it.
            append(Html("<button data-confirm=\"Queue a run of ") + esc(schedule.name) + Html(" now?\" onclick=\"act('/schedules/") + schedule.id + Html("/launch', null, this.dataset.confirm)\">launch now</button>"))
        }
        append(Html("</td></tr>"))
    }
    append(Html("</table></div>"))
}
