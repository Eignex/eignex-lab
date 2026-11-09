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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun number(value: Double) = if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()

internal fun name(problem: Problem) = if (problem.suite.isEmpty()) problem.problem else "${problem.suite}/${problem.problem}"

internal const val SEARCH_LIMIT = 200

internal fun problemLink(collection: String, problem: String) = "/problem${query("collection" to collection, "problem" to problem)}"

/** A filter dropdown; [options] are (value, label), [submitOnChange] preserves immediate filters by default. */
internal fun select(name: String, any: String, options: List<Pair<String, String>>, chosen: String?, submitOnChange: Boolean = true): Html = buildHtml {
    append(Html("<select name=\"") + name + Html("\"") + (if (submitOnChange) Html(" onchange=\"this.form.submit()\"") else Html("")) + Html("><option value=\"\">") + esc(any) + Html("</option>"))
    for ((value, label) in options) {
        append(Html("<option value=\"") + esc(value) + Html("\"") + (if (value == chosen) " selected" else "") + Html(">") + esc(label) + Html("</option>"))
    }
    append(Html("</select>"))
}

/** The GitHub page of [sha] when [repoUrl] is on GitHub. */
internal fun commitUrl(repoUrl: String, sha: String): String? =
    Regex("github\\.com[:/]([^/]+/[^/]+?)(\\.git)?/?$").find(repoUrl)
        ?.let { "https://github.com/${it.groupValues[1]}/commit/${sha}" }

/** [sha] shortened, linked to its GitHub page ([commitUrl]) when [repoUrl] has one, with the whole sha as its title. */
internal fun commitLink(repoUrl: String, sha: String): Html {
    val short = esc(sha.take(9))
    val url = commitUrl(repoUrl, sha) ?: return short
    return (Html("<a href=\"") + esc(url) + Html("\" title=\"") + esc(sha) + Html("\">") + short + Html("</a>"))
}

/** An error as one line: a ref the mirror lacks reads as such, anything else is cut to its first line. */
internal fun shortError(error: String): String =
    Regex("rev-parse --verify '(.+)\\^\\{commit\\}'").find(error)?.let { "unknown ref: ${it.groupValues[1]}" }
        ?: error.lineSequence().first().let { if (it.length > 120) it.take(120) + "…" else it }

internal fun summary(
    status: StatusSettings,
    host: HostReport,
    active: List<Job>,
    nodes: Map<Long, List<JobNode>>,
    /** Free on the data directory's volume, and why the runner holds its queue ([Volumes.hold]), read before rendering. */
    free: Long?,
    held: String?,
): Html {
    val activities = active.associate { it.id to jobActivity(it, nodes[it.id].orEmpty()) }
    val waiting = activities.values.count { it == "WAITING" }
    val checking = activities.values.count { it == "CHECKING" }
    val running = active.count { it.status == Status.RUNNING } - waiting - checking
    val queued = active.count { it.status == Status.QUEUED }
    // A low disk on any volume the lab writes to, or a missing one, holds the queue ([Volumes.hold]).
    val disk = esc(free?.let { "${size(it)} free" } ?: "free space unknown") +
        (held?.let { Html(" · <span class=\"FAILED\">queue held: ") + esc(it) + Html("</span>") } ?: Html("")) + Html(" (<a href=\"/disk\">what uses it</a>)")
    val counts = "${running} running · ${queued} queued" + (if (waiting > 0) " · ${waiting} waiting for capacity" else "") +
        (if (checking > 0) " · ${checking} checking results" else "")
    return buildHtml {
        append(Html("<div class=\"queue-overview\" role=\"group\" aria-label=\"") + esc(counts) + Html("\">"))
        for ((count, label, cls) in listOf(
            Triple(running, "Running", "RUNNING"), Triple(queued, "Queued", "QUEUED"),
            Triple(waiting, "Waiting for capacity", "capacity-wait"), Triple(checking, "Checking results", "QUEUED"),
        )) {
            append(Html("<div class=\"queue-stat ") + cls + Html("\"><strong>") + count + Html("</strong><span>") + label + Html("</span></div>"))
        }
        append(Html("</div><div class=\"queue-capacity muted small\"><span>") + disk + Html("</span><span>Local host: ") + esc(host.host) + Html(" · ") + host.cores + Html(" cores · ") +
            esc(NodeTags.hardwareProfile(host.cpu.tags)) + Html("</span></div>"))
    }
}

/**
 * Where a job runs, as the lab placed it: the local machine alone, shared by the local machine and cloud nodes, or the
 * cloud alone; while it runs, the hosts on it now ("local + 2 nodes": the local machine when it works on it, and the
 * nodes) and each host's part of it ([hosts]).
 */
internal fun hostCell(
    job: Job,
    hosts: List<HostProgress> = emptyList(),
    nodes: List<JobNode> = emptyList(),
): Html {
    val spec = job.experiment
    if (job.backend == Backend.NOMAD && job.status.isActive) {
        val where = when {
            nodes.isNotEmpty() && nodes.all { it.local } -> "local"
            nodes.isNotEmpty() && nodes.none { it.local } -> "cloud"
            nodes.isNotEmpty() -> "local + cloud"
            else -> spec.host.word
        }
        val detail = nodes.joinHtml(Html("<br>")) { node ->
            val href = node.anchor?.let { "/nodes#" + URLEncoder.encode(it, "UTF-8").replace("+", "%20") } ?: "/jobs/${job.id}/execution"
            (Html("<a href=\"") + esc(href) + Html("\" title=\"Nomad node ") + esc(node.id) + Html("\">") + esc(node.name) + Html("</a>")) +
                node.profile?.let { (Html("<br>") + esc(it)) }.orEmpty()
        }.ifEmpty { when {
            jobActivity(job, nodes) == "CHECKING" -> "checking results"
            !job.paused && !job.cancelRequested -> "waiting for a node"
            else -> ""
        } }
        return (Html("<span class=\"where") + (if (where == "cloud") " aws" else "") + Html("\">") + esc(where) + Html("</span>")) +
            if (detail.isEmpty()) "" else (Html("<br><small>") + detail + Html("</small>"))
    }
    if (spec.host == Experiments.AUTO_HOST) {
        val where = Html("<span class=\"where\" title=\"shared by the lab: the local machine and cloud nodes each run whole iterations of it\">local or cloud</span>")
        if (job.status != Status.RUNNING) return where
        val on = Waiting.hostsText(job.local, 0)
        val parts = hosts.takeIf { it.size > 1 }?.joinToString(" · ") { it.text() }
        return (Html("") + where + Html("<br><small>") + esc(on)) +
            (parts?.let { (Html("<br>") + esc(it)) } ?: "") + Html("</small>")
    }
    if (spec.host != Experiments.AWS_HOST) {
        val note = when {
            job.status == Status.QUEUED -> Html(" <small class=\"muted\">only</small>")
            else -> ""
        }
        return (Html("<span class=\"where\">local</span>") + note)
    }
    val detail = when {
        // A cancel-requested or paused job waits for nothing: no node takes it.
        job.status == Status.QUEUED && !job.cancelRequested && !job.paused -> "waiting for a node"
        else -> null
    }
    return Html("<span class=\"where aws\">cloud</span>") +
        (detail?.let { (Html("<br><small>") + esc(it) + Html("</small>")) } ?: "")
}

/** A job's status with its notes: cancelling, paused, its place in the queue, its priority and its error. Why a queued
 *  one waits is left to `lab placement`. */
internal fun statusCell(job: Job, position: Int?, nodes: List<JobNode>? = null): Html {
    val activity = nodes?.let { jobActivity(job, it) }
    val partial = job.status == Status.DONE && job.failed > 0
    val label = if (partial) {
        (Html("DONE · <a href=\"/jobs/") + job.id + Html("?failed\">") + job.failed + Html(" failed</a>"))
    } else {
        activity ?: job.status.name
    }
    val notes = listOfNotNull(
        "cancelling".takeIf { job.cancelRequested && job.status.isActive },
        Html("<span class=\"badge\">paused</span>").takeIf { job.paused && job.status.isActive },
        "#${position} in the queue".takeIf { position != null },
        "priority ${job.priority}".takeIf { job.priority != 0 },
        "waiting for compatible capacity".takeIf { activity == "WAITING" },
        "ingesting results and checking outputs".takeIf { activity == "CHECKING" },
        job.error?.let { (Html("<span title=\"") + esc(it) + Html("\">") + (esc(shortError(it))) + Html("</span>")) },
    )
    val cls = when { partial -> "PARTIAL"; activity in setOf("WAITING", "CHECKING") -> "QUEUED"; else -> job.status.name }
    return (Html("<span class=\"") + cls + Html("\">") + label + Html("</span>")) + notes.joinHtml { (Html("<br><small>") + it + Html("</small>")) }
}

/** The ref and the commit it ran, without repeating a ref that is the commit itself. */
internal fun refText(repoUrl: String, job: Job): Html {
    val isSha = Regex("[0-9a-f]{7,40}").matches(job.ref)
    val sha = job.sha ?: job.ref.takeIf { isSha }
    val commit = sha?.let { full ->
        val spec = job.experiment
        val repository = if (!spec.isBench && !spec.isProfile) repoUrl
            else Repositories.url(spec.repository)
        commitLink(repository, full)
    }
    return Html("<code>") + listOfNotNull(esc(job.ref).takeUnless { isSha }, commit).joinHtml(Html(" ")) { it } + Html("</code>")
}

internal fun progress(job: Job): Html = buildHtml {
    val total = job.total.coerceAtLeast(1)
    val running = job.running
    fun part(cls: String, count: Int): Html = if (count == 0) Html("") else
        Html("<i class=\"") + cls + Html("\" style=\"width:") + (100.0 * count / total) + Html("%\"></i>")
    append(Html("<span class=\"bar\" role=\"img\" aria-label=\""))
    append(esc("${job.done} of ${job.total} completed, ${job.failed} failed, $running running"))
    append(Html("\">"))
    append(part("ok", job.done - job.failed))
    append(part("bad", job.failed))
    append(part("run", running))
    append(Html("</span><small>") + job.done + Html("/") + job.total)
    if (job.vcpus > 1) append(Html(" ") + job.vcpus + Html(" vCPU"))
    append(Html("</small>"))
}

internal fun output(jobId: Long, files: JobDirView, name: String, running: Boolean): Html {
    val bytes = files.outputs[name] ?: return (Html("<span class=\"muted\">") + name.substringAfter('.') + Html("</span>"))
    val link = if (running) "${name}?tail=${TAIL_BYTES}" else name
    return (Html("<a href=\"/jobs/") + jobId + Html("/files/") + link + Html("\">") + name.substringAfter('.') + Html("</a> <small class=\"muted\">") + size(bytes) + Html("</small>"))
}

private const val TAIL_BYTES = 65_536

internal fun preparationDetails(report: ExecutionSessionReport, at: Long = now()): Html {
    val status = report.preparation ?: return Html("")
    val snapshot = status.progress
    val age = (at - status.updatedAt).coerceAtLeast(0)
    val live = report.phase == "preparing" && report.session.desired != Desired.STOP && age <= 5000
    fun seconds(ms: Long) = String.format(Locale.ROOT, "%.2f s", ms / 1000.0)
    return buildHtml {
        append(Html("<strong>") + (esc(PreparationTrace.labels.getValue(snapshot.stage))) + Html("</strong>"))
        append(Html(" · ") + (seconds(snapshot.stageElapsedMs + if (live) age else 0)) + Html(" in phase"))
        if (report.phase == "preparing" && age > 5000) append(Html(" · last update ") + seconds(age) + Html(" ago"))
        append(Html("<br>"))
        append(snapshot.timings.filterValues { it > 0 }.entries.joinHtml(Html(" · ")) {
            esc(PreparationTrace.labels.getValue(it.key)) + Html(": ") + seconds(it.value)
        })
        status.diagnostics?.let { evidence ->
            append(Html("<details><summary>Last preparation diagnostics (provisional)</summary><pre>") +
                esc(kotlinx.serialization.json.Json.encodeToString(PreparationDiagnostics.serializer(), evidence)) + Html("</pre></details>"))
        }
        if (snapshot.caches.isNotEmpty()) {
            append(Html("<br>"))
            append(snapshot.caches.entries.joinHtml(Html(" · ")) { (name, event) ->
                buildHtml {
                    append(Html("") + (esc(PreparationTrace.cacheLabels.getValue(name))) + Html(": latest ") + event.outcome + Html(" (") + (esc(PreparationTrace.cacheReasons.getValue(event.reason))) + Html(")"))
                    if (event.hits.toLong() + event.misses + event.bypasses > 1) append(Html(", ") + event.hits + Html(" hits / ") + event.misses + Html(" misses / ") + event.bypasses + Html(" bypasses"))
                    if (event.verificationMs > 0) append(Html(", verification ") + seconds(event.verificationMs))
                    if (event.waitingMs > 0) append(Html(", waiting ") + seconds(event.waitingMs))
                    if (event.checkedFiles > 0) append(Html(", checked ") + event.checkedFiles + Html(" files"))
                    if (event.hashedFiles > 0) append(Html(", rehashed ") + event.hashedFiles + Html(" files / ") + event.hashedBytes + Html(" bytes"))
                }
            })
        }
    }
}

/** Small native controls reused by the problem grid and benchmark case disclosures. */
internal fun resultFilters(id: String, arms: List<String> = emptyList(), statuses: List<String> = emptyList()): Html = buildHtml {
    append(Html("<div class=\"result-filters\"><label>Search <input type=\"search\" id=\"") + id + Html("-query\" data-result-query placeholder=\"Problem, path or case\"></label>"))
    if (arms.isNotEmpty()) {
        append(Html("<label>Arm <select id=\"") + id + Html("-arm\" data-result-arm><option value=\"\">All arms</option>"))
        arms.forEach { append(Html("<option>") + it + Html("</option>")) }
        append(Html("</select></label>"))
    }
    if (statuses.isNotEmpty()) {
        append(Html("<label>Status <select id=\"") + id + Html("-status\" data-result-status><option value=\"\">All statuses</option>"))
        statuses.forEach { append(Html("<option>") + it + Html("</option>")) }
        append(Html("</select></label>"))
    }
    append(Html("</div>"))
}
