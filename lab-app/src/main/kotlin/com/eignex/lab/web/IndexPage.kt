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
import kotlinx.html.*

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
    /**
     * Free on the data directory's volume, and why the runner holds its queue, read before
     * rendering ([PageQueries.index]).
     */
    free: Long? = runCatching { freeBytes(status.paths.dataDir) }.getOrNull(),
    held: String? = runCatching { Volumes.hold(status.paths, status.minFreeBytes) }.getOrNull(),
    loading: Boolean = false,
): Html =
    page("eignex lab", live = true, script = PageScripts.queue) {
        val front = before == null && name == null
        header {
            h1 { text("Queue") }
            div {
                attributes["id"] = "summary"
                attributes["data-live"] = ""
                emit(
                    if (loading) buildHtml { text("Loading queue…") }
                    else summary(status, host, active, nodes, free, held),
                )
            }
        }
        p(classes = "muted small") {
            attributes["id"] = "queue-snapshot"
            attributes["data-live"] = ""

            comment("queue-snapshot")
        }
        nav(classes = "queue-sections") {
            attributes["aria-label"] = "Queue sections"
            if (front) {
                a(href = "#active-section") {
                    text("Active jobs")
                }
                a(href = "#schedules-section") {
                    text("Schedules")
                }
            }
            a(href = "#history-section") {
                text("History")
            }
            a(href = "/nodes") {
                text("Nodes & capacity")
            }
        }
        if (front) {
            // One pass over the active jobs: the per-row estimates and the summary's totals read
            // the same numbers.
            val estimates =
                active
                    .mapNotNull { job ->
                        Estimates.estimate(
                                job,
                                caseHistory,
                                status.local.cores,
                                status.local.maxParallel,
                            )
                            ?.let { job.id to it }
                    }
                    .toMap()
            section {
                attributes["id"] = "active-section"
                attributes["aria-labelledby"] = "active-heading"
                h2 {
                    attributes["id"] = "active-heading"
                    text("Active jobs")
                }
                div {
                    attributes["id"] = "work"
                    attributes["data-live"] = ""
                    emit(
                        if (loading) buildHtml { text("Loading…") }
                        else workSummary(status, active, estimates),
                    )
                }
                div {
                    attributes["id"] = "active"
                    attributes["data-live"] = ""
                    emit(
                        if (loading) buildHtml { text("Loading…") }
                        else
                            activeTable(
                                status,
                                active,
                                queueOrder,
                                estimates,
                                waiting,
                                hosts,
                                nodes,
                            ),
                    )
                }
            }
            section {
                attributes["id"] = "schedules-section"
                attributes["aria-labelledby"] = "schedules-heading"
                details(classes = "schedule-panel") {
                    attributes["data-open-on-hash"] = "schedules-section"
                    summary {
                        h2 {
                            attributes["id"] = "schedules-heading"
                            text("Schedules")
                        }
                        text(" ")
                        span(classes = "muted small") {
                            attributes["id"] = "schedule-count"
                            attributes["data-live"] = ""

                            emit(
                                if (loading) "Loading…"
                                else "${schedules.size} recurring schedules",
                            )
                        }
                    }
                    div {
                        attributes["id"] = "schedules"
                        attributes["data-live"] = ""
                        emit(
                            if (loading) buildHtml { text("Loading…") }
                            else if (schedules.isEmpty()) buildHtml { p { text("No schedules.") } }
                            else schedulesTable(schedules),
                        )
                    }
                }
            }
        }
        section {
            attributes["id"] = "history-section"
            attributes["aria-labelledby"] = "history-heading"
            h2 {
                attributes["id"] = "history-heading"
                emit(
                    if (name == null) buildHtml { text("History") }
                    else
                        buildHtml {
                            text("Runs of ")
                            emit(name)
                            text(" ")
                            small {
                                a(href = "/") {
                                    text("all jobs")
                                }
                            }
                        },
                )
            }
            div(classes = "tools history-tools") {
                label(classes = "history-search") {
                    attributes["for"] = "q"
                    span { text("Search history") }
                    input {
                        attributes["id"] = "q"
                        attributes["type"] = "search"
                        attributes["placeholder"] = "Name, ID, ref or description"
                        attributes["aria-controls"] = "history"
                    }
                }
                div(classes = "history-chips") {
                    attributes["role"] = "group"
                    attributes["aria-label"] = "Filter history by status"
                    for (chip in listOf("all", "failed", "done", "cancelled", "active")) {
                        if (chip == "active" && front) continue
                        button(classes = "chip${if (chip == "all") " on" else ""}") {
                            attributes["type"] = "button"

                            attributes["aria-pressed"] = (chip == "all").toString()
                            attributes["data-chip"] = (chip)
                            emit(chip.replaceFirstChar { it.uppercase() })
                        }
                    }
                }
                button {
                    attributes["type"] = "button"
                    attributes["id"] = "clear-history"
                    attributes["hidden"] = ""
                    text("Clear filters")
                }
            }
            p(classes = "muted small") {
                attributes["id"] = "history-count"

                attributes["role"] = "status"
            }
            p(classes = "muted small") {
                text("Filters apply to the runs loaded below.")
            }
            div {
                attributes["id"] = "history"
                attributes["data-live"] = ""
                val shown = if (front) history.filter { it.status.isTerminal } else history
                emit(
                    if (loading) buildHtml { p { text("Loading history…") } }
                    else
                        jobTable(
                            status,
                            shown,
                            emptyMap(),
                            filterable = true,
                            hosts = hosts,
                            nodes = nodes,
                        ),
                )
                p(classes = "empty-state") {
                    attributes["id"] = "history-empty"

                    attributes["hidden"] = ""
                    text(
                        "No runs match these filters. Try another search or clear the filters.",
                    )
                }
                p(classes = "pager") {
                    if (before != null) {
                        a(href = "/${query("name" to name)}") {
                            text("newest")
                        }
                        text(" ")
                    }
                    if (history.size == limit)
                        a(
                            href =
                                "/${(query(
                                                "name" to name,
                                                "before" to history.last().id.toString(),
                                            ))
                                            }",
                        ) {
                            text("older")
                        }
                }
            }
        }
    }

/** Where a job's remaining work may run, as the work summary splits it. */
private enum class WorkKind(val label: String) {
    LOCAL("local only"),
    TOWARD_CLOUD("local or cloud, toward cloud"),
    CLOUD("cloud"),
}

private fun workKind(status: StatusSettings, job: Job): WorkKind =
    when (Placement.where(job.experiment.host)) {
        Placement.LOCAL_OR_CLOUD -> WorkKind.TOWARD_CLOUD
        Placement.CLOUD -> WorkKind.CLOUD
        else -> WorkKind.LOCAL
    }

/**
 * The block above the Active table: the work scheduled, in estimated core-hours ([estimates], by
 * job) split by where it may run, and the time the local machine alone would take to drain what it
 * may run: the sum of those jobs' own wall times, as the local machine runs one job at a time.
 * Then, with cloud settings, the launch gauge ([launchGauge]). Paused jobs and ones whose cancel
 * was requested wait for nothing, so they are not counted.
 */
internal fun workSummary(
    status: StatusSettings,
    active: List<Job>,
    estimates: Map<Long, WorkEstimate>,
): Html = buildHtml {
    val counted = active.filter { !it.paused && !it.cancelRequested }
    val split =
        counted
            .groupBy { workKind(status, it) }
            .mapValues { (_, jobs) -> jobs.sumOf { estimates[it.id]?.coreHours ?: 0.0 } }
    val total = split.values.sum()
    val local =
        counted
            .filter { workKind(status, it) != WorkKind.CLOUD }
            .sumOf { estimates[it.id]?.localHours ?: 0.0 }
    div(classes = "work") {
        div {
            b { text("Work scheduled") }
            text(" ")
            if (total <= 0.0) {
                span(classes = "muted") {
                    text("none")
                }
            } else {
                text("≈")
                emit(coreHoursText(total))
                text(" core-h")
                if (local > 0) {
                    text(" ")
                    span(classes = "muted") {
                        text("· ~")
                        emit(wallText(local))
                        text(" for the local machine alone")
                    }
                }
            }
        }
        val parts =
            WorkKind.entries.mapNotNull { kind ->
                split[kind]?.takeIf { it > 0 }?.let { kind to it }
            }
        if (parts.any { it.first != WorkKind.LOCAL }) {
            div(classes = "muted small") {
                emit(
                    parts.joinHtml(buildHtml { text(" · ") }) { (kind, hours) ->
                        val title =
                            when (kind) {
                                WorkKind.LOCAL -> "placed on the local machine, or taken by it"
                                WorkKind.TOWARD_CLOUD ->
                                    "eligible for compatible Nomad cloud capacity"
                                WorkKind.CLOUD -> "placed on or running on cloud nodes"
                            }
                        buildHtml {
                            span {
                                attributes["title"] = (title)
                                emit(kind.label)
                                text(" ")
                                emit(coreHoursText(hours))
                            }
                        }
                    },
                )
            }
        }
        div(classes = "muted small") {
            text("Nomad · ")
            a(href = "/nodes") {
                text("nodes and capacity")
            }
        }
    }
}

/**
 * A row's remaining work: its estimated core-hours, for work the local machine may run the time the
 * local machine would take at the job's own concurrency, and where the time a case came from
 * ([WorkEstimate.note]).
 */
private fun estimateText(status: StatusSettings, job: Job, estimate: WorkEstimate?): Html {
    if (estimate == null || estimate.coreHours <= 0) return buildHtml {}
    val local =
        if (workKind(status, job) == WorkKind.CLOUD) ""
        else " · ~${wallText(estimate.localHours)} locally"
    val title =
        when (estimate.source) {
            EstimateSource.MEASURED -> "from the mean time of this job's finished cases"
            EstimateSource.HISTORY ->
                "from the case count and mean case time of run ${estimate.history}, the last done run of this experiment"
            EstimateSource.TIMEOUT ->
                "from each case's whole timeout on its cores: no finished run of this experiment measured its cases"
        }
    return buildHtml {
        div(classes = "est") {
            attributes["title"] = (title)
            text("≈")
            emit(coreHoursText(estimate.coreHours))
            text(" core-h")
            emit(local)
            text(" ")
            span(classes = "muted") {
                text("(")
                emit(estimate.note)
                text(")")
            }
        }
    }
}

private fun activeTable(
    status: StatusSettings,
    active: List<Job>,
    queueOrder: List<Long>,
    estimates: Map<Long, WorkEstimate>,
    waiting: Map<Long, String>,
    hosts: Map<Long, List<HostProgress>> = emptyMap(),
    nodes: Map<Long, List<JobNode>> = emptyMap(),
): Html {
    if (active.isEmpty())
        return buildHtml {
            p(classes = "muted") {
                text("Nothing running or queued.")
            }
        }
    // One queue by priority, whatever runs each job: the local machine and the cloud nodes take
    // from it in this order
    // as the lab places jobs, each passing over those it may not run. Higher priority lists first,
    // running or not.
    val positions = queueOrder.withIndex().associate { (index, id) -> id to index + 1 }
    val ordered =
        active.sortedWith(
            compareByDescending<Job> { it.priority }
                .thenBy { it.status != Status.RUNNING }
                .thenBy { positions[it.id] ?: Int.MAX_VALUE }
                .thenBy { it.id },
        )
    return jobTable(
        status,
        ordered,
        positions,
        filterable = false,
        estimates = estimates,
        waiting = waiting,
        hosts = hosts,
        nodes = nodes,
    )
}

private fun jobTable(
    status: StatusSettings,
    jobs: List<Job>,
    positions: Map<Long, Int>,
    filterable: Boolean,
    estimates: Map<Long, WorkEstimate>? = null,
    waiting: Map<Long, String> = emptyMap(),
    hosts: Map<Long, List<HostProgress>> = emptyMap(),
    nodes: Map<Long, List<JobNode>> = emptyMap(),
): Html = buildHtml {
    if (jobs.isEmpty())
        return buildHtml {
            p(classes = "muted") {
                text("No jobs.")
            }
        }
    div(classes = "scroll") {
        attributes["role"] = "region"
        attributes["aria-label"] = (if (filterable) "History jobs" else "Active jobs")
        attributes["tabindex"] = "0"
        table(classes = "jobs-table") {
            thead {
                tr {
                    th {
                        attributes["scope"] = "col"
                        text("ID")
                    }
                    th {
                        attributes["scope"] = "col"
                        text("Job")
                    }
                    th {
                        attributes["scope"] = "col"
                        text("Ref")
                    }
                    th {
                        attributes["scope"] = "col"
                        text("Location")
                    }
                    th {
                        attributes["scope"] = "col"
                        text("Status")
                    }
                    th {
                        attributes["scope"] = "col"
                        text("Progress")
                    }
                    th {
                        attributes["scope"] = "col"
                        text("Created")
                    }
                    th {
                        attributes["scope"] = "col"
                        attributes["title"] = "Time spent running; queue waits excluded"
                        text("Run time")
                    }
                    th {
                        attributes["scope"] = "col"
                        span(classes = "sr-only") {
                            text("Files")
                        }
                    }
                }
            }
            tbody {
                for (job in jobs) {
                    tr {
                        if (filterable) {
                            attributes["data-state"] =
                                when {
                                    job.status == Status.FAILED || job.failed > 0 -> "failed"
                                    job.status.isActive -> "active"
                                    else -> job.status.name.lowercase()
                                }
                            attributes["data-text"] =
                                listOfNotNull(
                                        job.id.toString(),
                                        job.name,
                                        job.ref,
                                        job.sha,
                                        job.experiment.description,
                                    )
                                    .joinToString(" ")
                                    .lowercase()
                        }
                        td {
                            a(href = "/jobs/${job.id}") {
                                emit(job.id)
                            }
                        }
                        val base = job.origin.series
                        td(classes = "job-name") {
                            a(classes = "plain job-link", href = "/jobs/${job.id}") {
                                emit(job.name)
                            }
                            text(" ")
                            span(classes = "kind") {
                                emit(kindOf(job))
                            }
                            text(" ")
                            a(classes = "series-link small", href = "/${query("name" to base)}") {
                                attributes["aria-label"] = "All runs of ${base}"
                                text("all runs")
                            }
                            job.experiment.description
                                .takeIf { it.isNotBlank() }
                                ?.let {
                                    div(classes = "desc") {
                                        attributes["title"] = (it)
                                        emit(it)
                                    }
                                }
                        }
                        td(classes = "job-ref") {
                            emit(refText(status.repoUrl, job))
                        }
                        td(classes = "job-location") {
                            emit(
                                hostCell(job, hosts[job.id].orEmpty(), nodes[job.id].orEmpty()),
                            )
                        }
                        td(classes = "job-status") {
                            emit(statusCell(job, positions[job.id], nodes[job.id].orEmpty()))
                        }
                        td {
                            emit(progress(job))
                            emit(
                                estimates?.let { estimateText(status, job, it[job.id]) }.orEmpty(),
                            )
                        }
                        td(classes = "job-time") {
                            emit(ago(job.createdAt))
                        }
                        td(classes = "job-time") {
                            emit(elapsed(job))
                        }
                        td {
                            a(href = "/jobs/${job.id}/files") {
                                text("files")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * What kind of job [job] is, in a word, which a queue row shows instead of its commands: `profile`
 * for a profile job, `custom` for one of the retired script jobs, `a/b` for an experiment of
 * several arms, `schedule` for a scheduled run, `confirm` for a confirmation, `reference solve` for
 * a run of the reference solvers, `run` for any other experiment.
 */
internal fun kindOf(job: Job): String {
    val spec = job.experiment
    when (spec.work) {
        is Work.Profile -> return "profile"
        is Work.Script -> return "custom"
        is Work.Bench,
        is Work.Analysis,
        Work.Recipe -> {}
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
    div(classes = "scroll") {
        attributes["role"] = "region"
        attributes["aria-label"] = "Recurring schedules"
        attributes["tabindex"] = "0"
        table(classes = "schedules-table") {
            thead {
                tr {
                    th {
                        attributes["scope"] = "col"
                        text("Name")
                    }
                    th {
                        attributes["scope"] = "col"
                        text("Ref")
                    }
                    th {
                        attributes["scope"] = "col"
                        text("Every")
                    }
                    th {
                        attributes["scope"] = "col"
                        text("Arms")
                    }
                    th {
                        attributes["scope"] = "col"
                        text("Last run")
                    }
                    th {
                        attributes["scope"] = "col"
                        text("Checked")
                    }
                    th {
                        attributes["scope"] = "col"
                        text("Next check")
                    }
                    th {
                        attributes["scope"] = "col"
                        span(classes = "sr-only") {
                            text("Actions")
                        }
                    }
                }
            }
            tbody {
                for (schedule in schedules) {
                    val blocked = schedule.blockedReason()
                    tr {
                        td {
                            a(classes = "plain", href = "/${query("name" to schedule.name)}") {
                                emit(schedule.name)
                            }
                            text(" ")
                            small {
                                a(href = "/trend${query("name" to schedule.name)}") {
                                    text("trend")
                                }
                            }
                            if (schedule.priority != 0) {
                                br {}
                                small {
                                    text("priority ")
                                    emit(schedule.priority)
                                }
                            }
                            if (blocked != null) {
                                br {}
                                small {
                                    text("blocked: ")
                                    emit(blocked)
                                }
                            }
                        }
                        td { code { emit(schedule.ref) } }
                        td { emit(span(schedule.intervalSec)) }
                        val arms =
                            runCatching { Experiments.arms(schedule.experiment).size }
                                .getOrNull()
                                ?.toString() ?: "invalid"
                        td {
                            emit(arms)
                            emit(if (schedule.vcpus > 0) " ${schedule.vcpus} vCPU" else "")
                        }
                        td {
                            if (schedule.lastJob != null) {
                                a(href = "/jobs/${schedule.lastJob}") {
                                    emit(schedule.lastJob)
                                }
                                text(" ")
                                code { emit(schedule.lastSha?.take(9) ?: "") }
                            } else {
                                span(classes = "muted") {
                                    text("never")
                                }
                            }
                        }
                        td { emit(ago(schedule.checkedAt)) }
                        td {
                            val next =
                                schedule.checkedAt?.let { it + schedule.intervalSec * 1000 - now() }
                                    ?: 0
                            emit(if (next <= 0) "due" else "in ${span(next / 1000)}")
                        }
                        td {
                            if (blocked != null) {
                                span(classes = "muted") {
                                    text("edit schedule to resume")
                                }
                            } else if (schedule.launchRequested) {
                                span(classes = "muted") {
                                    text("launching")
                                }
                            } else {
                                // The prompt rides in a data attribute, never inside the JavaScript
                                // string, so a name cannot break out of it.
                                button {
                                    attributes["data-confirm"] =
                                        "Queue a run of ${schedule.name} now?"
                                    attributes["onclick"] =
                                        "act('/schedules/${schedule.id}/launch', null, this.dataset.confirm)"
                                    text("launch now")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
