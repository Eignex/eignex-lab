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
import java.util.Locale
import kotlinx.html.*

internal fun number(value: Double) =
    if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) value.toLong().toString()
    else value.toString()

internal fun name(problem: Problem) =
    if (problem.suite.isEmpty()) problem.problem else "${problem.suite}/${problem.problem}"

internal const val SEARCH_LIMIT = 200

internal fun problemLink(collection: String, problem: String) =
    "/problem${query("collection" to collection, "problem" to problem)}"

/**
 * A filter dropdown; [options] are (value, label), [submitOnChange] preserves immediate filters by
 * default.
 */
internal fun select(
    name: String,
    any: String,
    options: List<Pair<String, String>>,
    chosen: String?,
    submitOnChange: Boolean = true,
): Html = buildHtml {
    select {
        this.name = name
        if (submitOnChange) attributes["onchange"] = "this.form.submit()"
        option {
            value = "${any}"
        }
        for ((value, label) in options) option {
            this.value = value
            selected = value == chosen
            +label
        }
    }
}

/** The GitHub page of [sha] when [repoUrl] is on GitHub. */
internal fun commitUrl(repoUrl: String, sha: String): String? =
    Regex("github\\.com[:/]([^/]+/[^/]+?)(\\.git)?/?$").find(repoUrl)?.let {
        "https://github.com/${it.groupValues[1]}/commit/${sha}"
    }

/**
 * [sha] shortened, linked to its GitHub page ([commitUrl]) when [repoUrl] has one, with the whole
 * sha as its title.
 */
internal fun commitLink(repoUrl: String, sha: String): Html {
    val short = htmlText(sha.take(9))
    val url = commitUrl(repoUrl, sha) ?: return short
    return buildHtml {
        a(href = (url)) {
            attributes["title"] = (sha)
            emit(short)
        }
    }
}

/**
 * An error as one line: a ref the mirror lacks reads as such, anything else is cut to its first
 * line.
 */
internal fun shortError(error: String): String =
    Regex("rev-parse --verify '(.+)\\^\\{commit\\}'").find(error)?.let {
        "unknown ref: ${it.groupValues[1]}"
    } ?: error.lineSequence().first().let { if (it.length > 120) it.take(120) + "…" else it }

internal fun summary(
    status: StatusSettings,
    host: HostReport,
    active: List<Job>,
    nodes: Map<Long, List<JobNode>>,
    /**
     * Free on the data directory's volume, and why the runner holds its queue ([Volumes.hold]),
     * read before rendering.
     */
    free: Long?,
    held: String?,
): Html {
    val activities = active.associate { it.id to jobActivity(it, nodes[it.id].orEmpty()) }
    val waiting = activities.values.count { it == "WAITING" }
    val checking = activities.values.count { it == "CHECKING" }
    val running = active.count { it.status == Status.RUNNING } - waiting - checking
    val queued = active.count { it.status == Status.QUEUED }
    // A low disk on any volume the lab writes to, or a missing one, holds the queue
    // ([Volumes.hold]).
    val disk =
        htmlText(free?.let { "${size(it)} free" } ?: "free space unknown") +
            (held?.let {
                buildHtml {
                    text(" · ")
                    span(classes = "FAILED") {
                        text("queue held: ")
                        emit(it)
                    }
                }
            } ?: buildHtml {}) +
            buildHtml {
                text(" (")
                a(href = "/disk") {
                    text("what uses it")
                }
                text(")")
            }
    val counts =
        "${running} running · ${queued} queued${if (waiting > 0) " · ${waiting} waiting for capacity" else ""}${if (checking > 0) " · ${checking} checking results" else ""}"
    return buildHtml {
        div(classes = "queue-overview") {
            attributes["role"] = "group"
            attributes["aria-label"] = (counts)
            for ((count, label, cls) in
                listOf(
                    Triple(running, "Running", "RUNNING"),
                    Triple(queued, "Queued", "QUEUED"),
                    Triple(waiting, "Waiting for capacity", "capacity-wait"),
                    Triple(checking, "Checking results", "QUEUED"),
                )) {
                div(classes = "queue-stat ${cls}") {
                    strong { emit(count) }
                    span { emit(label) }
                }
            }
        }
        div(classes = "queue-capacity muted small") {
            span { emit(disk) }
            span {
                text("Local host: ")
                emit(host.host)
                text(" · ")
                emit(host.cores)
                text(" cores · ")
                emit(NodeTags.hardwareProfile(host.cpu.tags))
            }
        }
    }
}

/**
 * Where a job runs, as the lab placed it: the local machine alone, shared by the local machine and
 * cloud nodes, or the cloud alone; while it runs, the hosts on it now ("local + 2 nodes": the local
 * machine when it works on it, and the nodes) and each host's part of it ([hosts]).
 */
internal fun hostCell(
    job: Job,
    hosts: List<HostProgress> = emptyList(),
    nodes: List<JobNode> = emptyList(),
): Html {
    val spec = job.experiment
    if (job.backend == Backend.NOMAD && job.status.isActive) {
        val where =
            when {
                nodes.isNotEmpty() && nodes.all { it.local } -> "local"
                nodes.isNotEmpty() && nodes.none { it.local } -> "cloud"
                nodes.isNotEmpty() -> "local + cloud"
                else -> spec.host.word
            }
        val detail =
            nodes
                .joinHtml(buildHtml { br {} }) { node ->
                    val href =
                        node.anchor?.let {
                            "/nodes#${URLEncoder.encode(it, "UTF-8").replace("+", "%20")}"
                        } ?: "/jobs/${job.id}/execution"
                    buildHtml {
                        a(href = (href)) {
                            attributes["title"] = "Nomad node ${node.id}"
                            emit(node.name)
                        }
                        emit(
                            node.profile
                                ?.let {
                                    buildHtml {
                                        br {}
                                        emit(it)
                                    }
                                }
                                .orEmpty(),
                        )
                    }
                }
                .ifEmpty {
                    when {
                        jobActivity(job, nodes) == "CHECKING" -> "checking results"
                        !job.paused && !job.cancelRequested -> "waiting for a node"
                        else -> ""
                    }
                }
        return buildHtml {
            span(classes = "where${if (where == "cloud") " aws" else ""}") {
                emit(where)
            }
            emit(
                if (detail.isEmpty()) ""
                else
                    buildHtml {
                        br {}
                        small { emit(detail) }
                    },
            )
        }
    }
    if (spec.host == Experiments.AUTO_HOST) {
        val where = buildHtml {
            span(classes = "where") {
                attributes["title"] =
                    "shared by the lab: the local machine and cloud nodes each run whole iterations of it"
                text("local or cloud")
            }
        }
        if (job.status != Status.RUNNING) return where
        val on = Waiting.hostsText(job.local, 0)
        val parts = hosts.takeIf { it.size > 1 }?.joinToString(" · ") { it.text() }
        return buildHtml {
            emit(where)
            br {}
            small {
                emit(on)
                emit(
                    parts?.let {
                        buildHtml {
                            br {}
                            emit(it)
                        }
                    } ?: "",
                )
            }
        }
    }
    if (spec.host != Experiments.AWS_HOST) {
        val note =
            when {
                job.status == Status.QUEUED ->
                    buildHtml {
                        text(" ")
                        small(classes = "muted") {
                            text("only")
                        }
                    }
                else -> ""
            }
        return buildHtml {
            span(classes = "where") {
                text("local")
            }
            emit(note)
        }
    }
    val detail =
        when {
            // A cancel-requested or paused job waits for nothing: no node takes it.
            job.status == Status.QUEUED && !job.cancelRequested && !job.paused ->
                "waiting for a node"
            else -> null
        }
    return buildHtml {
        span(classes = "where aws") {
            text("cloud")
        }
        emit(
            detail?.let {
                buildHtml {
                    br {}
                    small { emit(it) }
                }
            } ?: "",
        )
    }
}

/**
 * A job's status with its notes: cancelling, paused, its place in the queue, its priority and its
 * error. Why a queued one waits is left to `lab placement`.
 */
internal fun statusCell(job: Job, position: Int?, nodes: List<JobNode>? = null): Html {
    val activity = nodes?.let { jobActivity(job, it) }
    val partial = job.status == Status.DONE && job.failed > 0
    val label =
        if (partial) {
            buildHtml {
                text("DONE · ")
                a(href = "/jobs/${job.id}?failed") {
                    emit(job.failed)
                    text(" failed")
                }
            }
        } else {
            activity ?: job.status.name
        }
    val notes =
        listOfNotNull(
            "cancelling".takeIf { job.cancelRequested && job.status.isActive },
            buildHtml {
                span(classes = "badge") {
                    text("paused")
                }
            }
                .takeIf { job.paused && job.status.isActive },
            "#${position} in the queue".takeIf { position != null },
            "priority ${job.priority}".takeIf { job.priority != 0 },
            "waiting for compatible capacity".takeIf { activity == "WAITING" },
            "ingesting results and checking outputs".takeIf { activity == "CHECKING" },
            job.error?.let {
                buildHtml {
                    span {
                        attributes["title"] = (it)
                        emit(shortError(it))
                    }
                }
            },
        )
    val cls =
        when {
            partial -> "PARTIAL"
            activity in setOf("WAITING", "CHECKING") -> "QUEUED"
            else -> job.status.name
        }
    return buildHtml {
        span(classes = (cls)) {
            emit(label)
        }
        emit(
            notes.joinHtml {
                buildHtml {
                    br {}
                    small { emit(it) }
                }
            },
        )
    }
}

/** The ref and the commit it ran, without repeating a ref that is the commit itself. */
internal fun refText(repoUrl: String, job: Job): Html {
    val isSha = Regex("[0-9a-f]{7,40}").matches(job.ref)
    val sha = job.sha ?: job.ref.takeIf { isSha }
    val commit = sha?.let { full ->
        val spec = job.experiment
        val repository =
            if (!spec.isBench && !spec.isProfile) repoUrl else Repositories.url(spec.repository)
        commitLink(repository, full)
    }
    return buildHtml {
        code {
            emit(
                listOfNotNull(htmlText(job.ref).takeUnless { isSha }, commit).joinHtml(
                    buildHtml { text(" ") },
                ) {
                    it
                },
            )
        }
    }
}

internal fun progress(job: Job): Html = buildHtml {
    val total = job.total.coerceAtLeast(1)
    val running = job.running
    fun part(cls: String, count: Int): Html =
        if (count == 0) buildHtml {}
        else
            buildHtml {
                i(classes = (cls)) {
                    attributes["style"] = "width:${100.0 * count / total}%"
                }
            }
    span(classes = "bar") {
        attributes["role"] = "img"
        attributes["aria-label"] =
            "${job.done} of ${job.total} completed, ${job.failed} failed, $running running"
        emit(part("ok", job.done - job.failed))
        emit(part("bad", job.failed))
        emit(part("run", running))
    }
    small {
        emit(job.done)
        text("/")
        emit(job.total)
        if (job.vcpus > 1) {
            text(" ")
            emit(job.vcpus)
            text(" vCPU")
        }
    }
}

internal fun output(jobId: Long, files: JobDirView, name: String, running: Boolean): Html {
    val bytes =
        files.outputs[name]
            ?: return buildHtml {
                span(classes = "muted") {
                    emit(name.substringAfter('.'))
                }
            }
    val link = if (running) "${name}?tail=${TAIL_BYTES}" else name
    return buildHtml {
        a(href = "/jobs/${jobId}/files/${link}") {
            emit(name.substringAfter('.'))
        }
        text(" ")
        small(classes = "muted") {
            emit(size(bytes))
        }
    }
}

private const val TAIL_BYTES = 65_536

internal fun preparationDetails(report: ExecutionSessionReport, at: Long = now()): Html {
    val status = report.preparation ?: return buildHtml {}
    val snapshot = status.progress
    val age = (at - status.updatedAt).coerceAtLeast(0)
    val live = report.phase == "preparing" && report.session.desired != Desired.STOP && age <= 5000
    fun seconds(ms: Long) = String.format(Locale.ROOT, "%.2f s", ms / 1000.0)
    return buildHtml {
        strong { emit(PreparationTrace.labels.getValue(snapshot.stage)) }
        text(" · ")
        emit(seconds(snapshot.stageElapsedMs + if (live) age else 0))
        text(" in phase")
        if (report.phase == "preparing" && age > 5000) {
            text(" · last update ")
            emit(seconds(age))
            text(" ago")
        }
        br {}
        emit(
            snapshot.timings
                .filterValues { it > 0 }
                .entries
                .joinHtml(buildHtml { text(" · ") }) {
                    htmlText(PreparationTrace.labels.getValue(it.key)) +
                        buildHtml { text(": ") } +
                        seconds(it.value)
                },
        )
        status.diagnostics?.let { evidence ->
            details {
                summary { text("Last preparation diagnostics (provisional)") }
                pre {
                    emit(
                        kotlinx.serialization.json.Json.encodeToString(
                            PreparationDiagnostics.serializer(),
                            evidence,
                        ),
                    )
                }
            }
        }
        if (snapshot.caches.isNotEmpty()) {
            br {}
            emit(
                snapshot.caches.entries.joinHtml(buildHtml { text(" · ") }) { (name, event) ->
                    buildHtml {
                        emit(PreparationTrace.cacheLabels.getValue(name))
                        text(": latest ")
                        emit(event.outcome)
                        text(" (")
                        emit(PreparationTrace.cacheReasons.getValue(event.reason))
                        text(")")
                        if (event.hits.toLong() + event.misses + event.bypasses > 1) {
                            text(", ")
                            emit(event.hits)
                            text(" hits / ")
                            emit(event.misses)
                            text(" misses / ")
                            emit(event.bypasses)
                            text(" bypasses")
                        }
                        if (event.verificationMs > 0) {
                            text(", verification ")
                            emit(seconds(event.verificationMs))
                        }
                        if (event.waitingMs > 0) {
                            text(", waiting ")
                            emit(seconds(event.waitingMs))
                        }
                        if (event.checkedFiles > 0) {
                            text(", checked ")
                            emit(event.checkedFiles)
                            text(" files")
                        }
                        if (event.hashedFiles > 0) {
                            text(", rehashed ")
                            emit(event.hashedFiles)
                            text(" files / ")
                            emit(event.hashedBytes)
                            text(" bytes")
                        }
                    }
                },
            )
        }
    }
}

/** Small native controls reused by the problem grid and benchmark case disclosures. */
internal fun resultFilters(
    id: String,
    arms: List<String> = emptyList(),
    statuses: List<String> = emptyList(),
): Html = buildHtml {
    div(classes = "result-filters") {
        label {
            text("Search ")
            input {
                attributes["type"] = "search"
                attributes["id"] = (id) + "-query"
                attributes["data-result-query"] = ""
                attributes["placeholder"] = "Problem, path or case"
            }
        }
        if (arms.isNotEmpty()) {
            label {
                text("Arm ")
                select {
                    attributes["id"] = (id) + "-arm"
                    attributes["data-result-arm"] = ""
                    option {
                        attributes["value"] = ""
                        text("All arms")
                    }
                    arms.forEach { option { emit(it) } }
                }
            }
        }
        if (statuses.isNotEmpty()) {
            label {
                text("Status ")
                select {
                    attributes["id"] = (id) + "-status"
                    attributes["data-result-status"] = ""
                    option {
                        attributes["value"] = ""
                        text("All statuses")
                    }
                    statuses.forEach { option { emit(it) } }
                }
            }
        }
    }
}
