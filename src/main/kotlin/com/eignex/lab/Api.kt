package com.eignex.lab

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.DefaultJson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.accept
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

@Serializable
data class ParallelSpec(val parallel: Int)

@Serializable
data class PrioritySpec(val priority: Int)

@Serializable
data class DescriptionSpec(val description: String)

@Serializable
data class HostSpec(val host: String, val machines: Int? = null)

@Serializable
data class MachinesSpec(val machines: Int? = null)

/** An experiment rerun on every new commit of [ref]: each run is [experiment] with every arm at that commit. */
@Serializable
data class ScheduleSpec(
    val name: String,
    val ref: String,
    val experiment: ExperimentSpec,
    val intervalSec: Long = 3600,
)

/** A change to a schedule: each field set replaces its value; [nextCheckInSec] sets when it is next checked. */
@Serializable
data class ScheduleUpdate(val experiment: ExperimentSpec? = null, val intervalSec: Long? = null, val nextCheckInSec: Long? = null)

@Serializable
data class Created(val id: Long)

@Serializable
data class ReferenceHit(val collection: String, val problem: String, val reference: Reference)

@Serializable
data class FileEntry(val path: String, val bytes: Long)

@Serializable
data class Health(val ok: Boolean, val queued: Int, val running: Int, val freeBytes: Long, val host: HostReport)

fun serveApi(config: Config, store: Store, host: HostReport) {
    embeddedServer(Netty, port = config.port) { api(config, store, host) }.start(wait = true)
}

fun Application.api(config: Config, store: Store, host: HostReport) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, e -> call.respondText(e.message ?: "bad request", status = HttpStatusCode.BadRequest) }
    }
    val trendCache = TrendCache()
    // Every schedule's finished runs computed before anyone asks, so the first trend load after a restart is quick too.
    for (schedule in store.schedules()) {
        val experiment = Experiments.fingerprint(schedule.experiment)
        trendCache.refreshLater("${schedule.name}/$experiment") { Trend.runs(store, schedule.name, trendCache, experiment = experiment) }
    }
    routing {
        get("/") {
            val before = call.parameters["before"]?.toLong()
            val name = call.parameters["name"]?.takeIf { it.isNotBlank() }
            val history = store.jobs(PAGE_SIZE, before, name)
            val queues = listOf(Experiments.LAB_HOST, Experiments.AWS_HOST).associateWith { store.queueOrder(it) }
            val page = indexPage(config, host, store.active(), store.schedules(), history, PAGE_SIZE, before, name, queues)
            call.respondText(page, ContentType.Text.Html)
        }
        get("/health") {
            val jobs = store.jobs()
            val queued = jobs.count { it.status == Status.QUEUED }
            val running = jobs.count { it.status == Status.RUNNING }
            call.respond(Health(true, queued, running, freeBytes(config.dataDir), host))
        }
        get("/jobs") {
            call.respond(store.jobs(call.parameters["limit"]?.toInt() ?: 200, call.parameters["before"]?.toLong(), call.parameters["name"]))
        }
        // An experiment is queued as a job with no commands; the runner plans its cases when it first sets it up.
        post("/experiments") {
            // An experiment that names no host runs on AWS whenever it can there, and on the lab machine otherwise.
            val body = call.receive<JsonObject>()
            val given = DefaultJson.decodeFromJsonElement(ExperimentSpec.serializer(), body)
            val spec = if ("host" in body) given else given.copy(host = defaultHost(given, config))
            requireDescription(spec)
            requireHost(spec, config)
            // An AWS job's cores are checked against the instance sizes, in requireHost; parallel there is per instance.
            Experiments.validate(spec, if (spec.host == Experiments.AWS_HOST) Int.MAX_VALUE else config.maxParallel,
                if (spec.host == Experiments.AWS_HOST) Int.MAX_VALUE else config.cores)
            val arms = Experiments.arms(spec)
            for (ref in arms.map { it.ref }.distinct()) requireRef(ref, config)
            val id = store.create(spec.name, arms.first().ref, emptyList(), spec.parallel ?: config.defaultParallel, spec.priority, spec)
            call.respond(HttpStatusCode.Created, Created(id))
        }
        get("/experiments/{id}/arms") { call.respond(store.arms(call.parameters["id"]!!.toLong())) }
        get("/trend") {
            // A schedule of reference runs regenerates the reference; its trend says nothing about klause.
            val schedules = store.schedules()
                .filter { schedule -> Experiments.arms(schedule.experiment).none { it.values["backend"] == REFERENCE_BACKEND } }
            val name = call.parameters["name"]?.takeIf { it.isNotBlank() } ?: schedules.firstOrNull()?.name
            // By default only the runs of the schedule's current experiment, and only those are read: a run of an
            // earlier one measured something else, and plotted alongside reads as a regression.
            val current = schedules.firstOrNull { it.name == name }?.let { Experiments.fingerprint(it.experiment) }
            val all = call.parameters["all"] != null || current == null
            val runs = name?.let { Trend.runs(store, it, trendCache, experiment = current.takeUnless { all }) }.orEmpty()
            // The problems the latest run of that spec planned: a set redrawn since is another line, as an edit is.
            val latest = runs.lastOrNull { it.experiment == current }?.spec
            val shown = if (all) runs else runs.filter { it.spec == latest }
            val total = if (all) runs.size else name?.let { Trend.scheduled(store, it).size } ?: 0
            if (call.wantsHtml()) {
                call.respondText(trendPage(name, shown, config.repoUrl, schedules.map { it.name }, maxOf(0, total - shown.size), all),
                    ContentType.Text.Html)
            } else {
                call.respond(shown)
            }
        }
        get("/references") {
            fun param(name: String) = call.parameters[name]?.takeIf { it.isNotBlank() }
            val filter = ReferenceFilter(
                text = param("q"),
                solver = param("solver"),
                collection = param("collection"),
                verdict = param("verdict")?.let { v -> ReferenceVerdict.entries.firstOrNull { it.name.equals(v, ignoreCase = true) } },
            )
            val (found, total) = if (filter.isEmpty) emptyList<Pair<Pair<String, String>, Reference>>() to 0 else store.searchReferences(filter, SEARCH_LIMIT)
            if (call.wantsHtml()) {
                val board = scoreboardView(store, call.parameters)
                val page = referencesPage(store.referenceCoverage(), filter, found, total, store.referenceConflicts(), board)
                call.respondText(page, ContentType.Text.Html)
            } else {
                call.respond(found.map { (key, r) -> ReferenceHit(key.first, key.second, r) })
            }
        }
        get("/problem") {
            val collection = requireNotNull(call.parameters["collection"]) { "collection is required" }
            val problem = requireNotNull(call.parameters["problem"]) { "problem is required" }
            val report = ProblemReport(collection, problem, store.referenceRows(collection, problem), store.problemRuns(collection, problem))
            if (call.wantsHtml()) call.respondText(problemPage(report, config.repoUrl), ContentType.Text.Html) else call.respond(report)
        }
        get("/experiments/{id}/reference") {
            val id = call.parameters["id"]!!.toLong()
            val cases = store.cases(id)
            val references = store.references(cases.map { it.problem.collection to it.problem.problem })
            call.respond(References.compare(store.arms(id).map { it.arm.label }, cases, references))
        }
        get("/compare") {
            val ids = requireNotNull(call.parameters["jobs"]) { "jobs=<id>,<id>… is required" }.split(',').map { it.trim().toLong() }
            require(ids.size in 2..MAX_COMPARED) { "compare 2 to $MAX_COMPARED experiments" }
            val jobs = ids.map { id -> requireNotNull(store.job(id)?.takeIf { it.experiment != null }) { "job $id is not an experiment" } }
            // Arms are named by their job, so the same arm of two runs stays two columns.
            val arms = jobs.flatMap { job -> store.arms(job.id).map { it.copy(arm = it.arm.copy(label = "${job.id} ${it.arm.label}")) } }
            val cases = jobs.flatMap { job -> store.cases(job.id).map { it.copy(arm = "${job.id} ${it.arm}") } }
            val references = store.references(cases.map { it.problem.collection to it.problem.problem })
            call.respondText(comparePage(config, jobs, arms, cases, references), ContentType.Text.Html)
        }
        get("/experiments/{id}/cases") { call.respond(store.cases(call.parameters["id"]!!.toLong())) }
        get("/experiments/{id}/stats") {
            val id = call.parameters["id"]!!.toLong()
            call.respond(Stats.of(store.arms(id).map { it.arm.label }, store.cases(id)))
        }
        get("/experiments/{id}/cases.csv") {
            call.respondText(Results.casesCsv(store.cases(call.parameters["id"]!!.toLong())), ContentType.Text.CSV)
        }
        get("/experiments/{id}/bench.csv") {
            val arm = requireNotNull(call.parameters["arm"]) { "arm is required" }
            val seed = call.parameters["seed"]?.toLong()
            call.respondText(Results.benchCsv(store.cases(call.parameters["id"]!!.toLong()), arm, seed), ContentType.Text.CSV)
        }
        get("/jobs/{id}") {
            val job = store.job(call.parameters["id"]!!.toLong())
            when {
                job == null -> call.respond(HttpStatusCode.NotFound, "no such job")
                call.wantsHtml() -> {
                    val experiment = job.experiment != null
                    val cases = if (experiment) store.cases(job.id) else emptyList()
                    val page = jobPage(
                        config, job, "failed" in call.parameters,
                        arms = if (experiment) store.arms(job.id) else emptyList(),
                        cases = cases,
                        references = store.references(cases.map { it.problem.collection to it.problem.problem }),
                        showCommands = !experiment || "commands" in call.parameters || "failed" in call.parameters,
                        previous = if (experiment && '@' in job.name) previousRun(store, job) else null,
                    )
                    call.respondText(page, ContentType.Text.Html)
                }
                else -> call.respond(job)
            }
        }
        // A long poll: the answer comes when the job ends, or after timeoutSec with the job as it stands, so a
        // client learns of the end without polling over the network.
        get("/jobs/{id}/wait") {
            val id = call.parameters["id"]!!.toLong()
            val timeoutSec = (call.parameters["timeoutSec"]?.toLong() ?: DEFAULT_WAIT_SEC).coerceIn(1, MAX_WAIT_SEC)
            val deadline = now() + timeoutSec * 1000
            while (true) {
                val job = store.job(id)
                if (job == null) {
                    call.respond(HttpStatusCode.NotFound, "no such job")
                    break
                }
                if (job.status in ENDED || now() >= deadline) {
                    call.respond(job)
                    break
                }
                delay(WAIT_POLL_MS)
            }
        }
        post("/jobs/{id}/cancel") {
            val id = call.parameters["id"]!!.toLong()
            when (store.requestCancel(id)) {
                // A queued job may have been set up before it yielded; the runner will not take it again to clean up.
                CancelOutcome.CANCELLED -> {
                    worktrees(config, id).forEach { deleteTree(it.toPath()) }
                    call.respond(HttpStatusCode.OK, "cancelled before it ran again")
                }
                CancelOutcome.REQUESTED -> call.respond(HttpStatusCode.Accepted, "cancel requested")
                CancelOutcome.FINISHED -> call.respond(HttpStatusCode.Conflict, "job already finished")
                CancelOutcome.MISSING -> call.respond(HttpStatusCode.NotFound, "no such job")
            }
        }
        // Its directory goes too, and any worktree a failed setup left; the mirror forgets those at its next prune.
        // The runner fetches within seconds and, when the lab's upstream moved, updates between the running job's cases.
        post("/update") {
            config.updateRequest.toFile().writeText("${System.currentTimeMillis()}\n")
            call.respond(HttpStatusCode.Accepted, "update requested")
        }
        post("/jobs/{id}/retry") {
            val retried = store.retry(call.parameters["id"]!!.toLong())
            call.respond(if (retried) HttpStatusCode.OK else HttpStatusCode.Conflict, if (retried) "queued again" else "only a failed or cancelled job can be retried")
        }
        post("/jobs/{id}/delete") {
            val id = call.parameters["id"]!!.toLong()
            if (!store.deleteJob(id)) {
                call.respond(HttpStatusCode.Conflict, "no such job, or it has not ended")
            } else {
                (worktrees(config, id) + config.jobDir(id).toFile()).forEach { deleteTree(it.toPath()) }
                call.respond(HttpStatusCode.OK, "deleted")
            }
        }
        post("/jobs/{id}/parallel") {
            val spec = call.receive<ParallelSpec>()
            requireParallel(spec.parallel, config)
            val found = store.setParallel(call.parameters["id"]!!.toLong(), spec.parallel)
            call.respond(if (found) HttpStatusCode.OK else HttpStatusCode.NotFound, if (found) "parallel ${spec.parallel}" else "no such job")
        }
        post("/jobs/{id}/host") {
            val id = call.parameters["id"]!!.toLong()
            val spec = call.receive<HostSpec>()
            val job = store.job(id)
            val experiment = requireNotNull(job?.experiment) { "no such experiment" }
            requireHost(experiment.copy(host = spec.host, machines = spec.machines.takeIf { spec.host == Experiments.AWS_HOST }), config)
            val moved = store.setHost(id, spec.host, spec.machines)
            call.respond(
                if (moved) HttpStatusCode.OK else HttpStatusCode.Conflict,
                if (moved) "runs on ${spec.host}" else "only a queued job that has not been planned can change host",
            )
        }
        post("/jobs/{id}/machines") {
            val id = call.parameters["id"]!!.toLong()
            val spec = call.receive<MachinesSpec>()
            val experiment = requireNotNull(store.job(id)?.experiment) { "no such experiment" }
            requireHost(experiment.copy(machines = spec.machines), config)
            val set = store.setMachines(id, spec.machines)
            call.respond(
                if (set) HttpStatusCode.OK else HttpStatusCode.Conflict,
                if (set) "machines ${spec.machines ?: "as many as are free"}" else "only an AWS job that has not ended takes a machine count",
            )
        }
        post("/jobs/{id}/description") {
            val spec = call.receive<DescriptionSpec>()
            require(spec.description.isNotBlank()) { "description must not be blank" }
            val found = store.setDescription(call.parameters["id"]!!.toLong(), spec.description.trim())
            call.respond(if (found) HttpStatusCode.OK else HttpStatusCode.NotFound, if (found) "described" else "no such experiment")
        }
        post("/jobs/{id}/priority") {
            val spec = call.receive<PrioritySpec>()
            val found = store.setPriority(call.parameters["id"]!!.toLong(), spec.priority)
            call.respond(if (found) HttpStatusCode.OK else HttpStatusCode.NotFound, if (found) "priority ${spec.priority}" else "no such job")
        }
        // A running job stops starting commands and goes back to the queue once the running ones end.
        post("/jobs/{id}/pause") {
            val paused = store.setPaused(call.parameters["id"]!!.toLong(), true)
            call.respond(if (paused) HttpStatusCode.OK else HttpStatusCode.Conflict, if (paused) "paused" else "no unfinished job")
        }
        post("/jobs/{id}/resume") {
            val resumed = store.setPaused(call.parameters["id"]!!.toLong(), false)
            call.respond(if (resumed) HttpStatusCode.OK else HttpStatusCode.Conflict, if (resumed) "resumed" else "no unfinished job")
        }
        post("/schedules") {
            val spec = call.receive<ScheduleSpec>()
            require(spec.name.isNotBlank() && spec.ref.isNotBlank() && !spec.ref.startsWith("-")) { "name and ref are required" }
            require(spec.intervalSec >= MIN_SCHEDULE_SEC) { "intervalSec must be at least $MIN_SCHEDULE_SEC" }
            validateScheduled(spec.experiment, config)
            requireRef(spec.ref, config)
            val id = store.createSchedule(spec.name, spec.ref, spec.experiment, spec.intervalSec)
            call.respond(HttpStatusCode.Created, Created(id))
        }
        get("/schedules") { call.respond(store.schedules()) }
        post("/schedules/{id}") {
            val spec = call.receive<ScheduleUpdate>()
            require(spec.intervalSec == null || spec.intervalSec >= MIN_SCHEDULE_SEC) { "intervalSec must be at least $MIN_SCHEDULE_SEC" }
            require(spec.nextCheckInSec == null || spec.nextCheckInSec >= 0) { "nextCheckInSec must not be negative" }
            spec.experiment?.let { validateScheduled(it, config) }
            val nextCheckAt = spec.nextCheckInSec?.let { System.currentTimeMillis() + it * 1000 }
            val updated = store.updateSchedule(call.parameters["id"]!!.toLong(), spec.experiment, spec.intervalSec, nextCheckAt)
            call.respond(if (updated) HttpStatusCode.OK else HttpStatusCode.NotFound, if (updated) "updated" else "no such schedule")
        }
        // The runner's schedule thread finds it due within seconds; a push hook or relay can call this instead of waiting.
        post("/schedules/{id}/check") {
            val found = store.checkNow(call.parameters["id"]!!.toLong())
            call.respond(if (found) HttpStatusCode.Accepted else HttpStatusCode.NotFound, if (found) "checking" else "no such schedule")
        }
        // A run now, even at a commit the schedule already ran: what a rerun of an unmoved branch needs.
        post("/schedules/{id}/launch") {
            val found = store.launchNow(call.parameters["id"]!!.toLong())
            call.respond(if (found) HttpStatusCode.Accepted else HttpStatusCode.NotFound, if (found) "launching" else "no such schedule")
        }
        post("/schedules/{id}/delete") {
            val deleted = store.deleteSchedule(call.parameters["id"]!!.toLong())
            call.respond(if (deleted) HttpStatusCode.OK else HttpStatusCode.NotFound, if (deleted) "deleted" else "no such schedule")
        }
        get("/jobs/{id}/files") {
            val id = call.parameters["id"]!!.toLong()
            val root = config.jobDir(id).toFile()
            val files = root.walkTopDown().filter { it.isFile }.map { FileEntry(it.relativeTo(root).path, it.length()) }.sortedBy { it.path }.toList()
            if (call.wantsHtml()) call.respondText(filesPage(id, files), ContentType.Text.Html) else call.respond(files)
        }
        get("/jobs/{id}/files/{path...}") {
            val root = config.jobDir(call.parameters["id"]!!.toLong()).toFile().canonicalFile
            val file = File(root, call.parameters.getAll("path")!!.joinToString("/")).canonicalFile
            require(file.toPath().startsWith(root.toPath())) { "path escapes the job directory" }
            val tail = call.parameters["tail"]?.toLong()
            when {
                !file.isFile -> call.respond(HttpStatusCode.NotFound, "no such file")
                tail != null -> call.respondText(tail(file, tail), ContentType.Text.Plain)
                // Logs and command output open in the browser instead of downloading.
                file.extension in TEXT_FILES -> call.respond(LocalFileContent(file, ContentType.Text.Plain.withCharset(Charsets.UTF_8)))
                else -> call.respondFile(file)
            }
        }
    }
}

private val ENDED = setOf(Status.DONE, Status.FAILED, Status.CANCELLED)
private const val DEFAULT_WAIT_SEC = 600L
private const val MAX_WAIT_SEC = 3600L
private const val WAIT_POLL_MS = 2000L
private const val MIN_SCHEDULE_SEC = 60L
private const val PAGE_SIZE = 200
private const val MAX_COMPARED = 6

/** The worktrees job [id] built, its own and those of its other commits; the mirror forgets them at its next prune. */
private fun worktrees(config: Config, id: Long): List<File> =
    config.worktree(id).toFile().parentFile.listFiles { f -> f.name == "$id" || f.name.startsWith("$id@") }.orEmpty().toList()

/** An AWS experiment needs the lab's AWS settings, runs klause only, and fits its instances. */
private fun requireHost(spec: ExperimentSpec, config: Config) {
    require(spec.host in setOf(Experiments.LAB_HOST, Experiments.AWS_HOST)) { "host must be lab or aws" }
    if (spec.host != Experiments.AWS_HOST) {
        require(spec.machines == null) { "machines is for host aws" }
        return
    }
    val aws = requireNotNull(config.aws) { "the lab has no AWS settings: run deploy/aws-setup.sh" }
    require(Experiments.arms(spec).none { it.values["backend"] == REFERENCE_BACKEND }) {
        "reference runs stay on the lab machine, which has the reference solvers"
    }
    require(spec.problemList.isEmpty()) { "an AWS experiment selects its problems" }
    val cores = Experiments.arms(spec).maxOf { it.cores }
    val size = requireNotNull(aws.sizeFor(cores)) {
        "its cases need $cores cores; the largest instance within the ${aws.vcpuQuota}-vCPU quota has ${aws.sizes.last().cores}"
    }
    val most = aws.vcpuQuota / size.vcpus
    spec.machines?.let { require(it in 1..most) { "machines must be between 1 and $most for ${size.type} within the quota" } }
}

/** AWS when [spec] could run there, see [requireHost]; the lab machine otherwise. */
private fun defaultHost(spec: ExperimentSpec, config: Config): String =
    runCatching { requireHost(spec.copy(host = Experiments.AWS_HOST), config) }
        .fold({ Experiments.AWS_HOST }, { Experiments.LAB_HOST })

/** An experiment says what it is for, so the queue and the history read as more than names. */
private fun requireDescription(spec: ExperimentSpec) = require(spec.description.isNotBlank()) {
    "description is required: a sentence or two on what the experiment tests and why, e.g. \"A/B of #2232's " +
        "continuation scheduler against main on the sweep set, to see whether it closes the uf250 gap\""
}

private fun validateScheduled(experiment: ExperimentSpec, config: Config) {
    requireDescription(experiment)
    Experiments.validate(experiment, config.maxParallel, config.cores)
    require(Experiments.arms(experiment).none { "ref" in it.values }) {
        "a scheduled experiment runs every arm at the schedule's ref; drop ref from its configs"
    }
}

/** The latest finished experiment queued by the same schedule before [job], whose name is `<schedule>@<sha>`. */
private fun previousRun(store: Store, job: Job): Long? =
    store.jobs(limit = PAGE_SIZE, before = job.id, name = job.name.substringBefore('@'))
        .firstOrNull { it.experiment != null && it.status == Status.DONE && '@' in it.name }?.id
private const val LS_REMOTE_SEC = 15L
private val TEXT_FILES = setOf("", "out", "err", "log", "txt", "sha", "pid")

/** A browser asks for a page; `?json` or any API client gets the data. */
private fun ApplicationCall.wantsHtml() = "json" !in parameters && request.accept()?.contains("text/html") == true

/**
 * Refuse a branch or tag name origin does not have, so a typo fails at submit and not later in the queue. A commit
 * hash or a ref expression cannot be checked without fetching, and an origin that does not answer proves nothing:
 * those pass, and the runner reports them when it resolves the ref.
 */
private suspend fun requireRef(ref: String, config: Config) {
    if (!Regex("[A-Za-z0-9._/-]+").matches(ref) || Regex("[0-9a-f]{7,40}").matches(ref)) return
    val known = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("git", "ls-remote", config.repoUrl, "refs/heads/$ref", "refs/tags/$ref")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply { environment()["GIT_TERMINAL_PROMPT"] = "0" }
            .start()
        // Waited on before reading: the answer is a line or two, well inside the pipe buffer, and a hung remote
        // must not hold the request past the timeout.
        if (!process.waitFor(LS_REMOTE_SEC, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            true
        } else {
            process.exitValue() != 0 || process.inputStream.bufferedReader().readText().isNotBlank()
        }
    }
    require(known) { "unknown ref: $ref (no such branch or tag on origin; is it pushed?)" }
}

private fun requireParallel(parallel: Int, config: Config) =
    require(parallel in 1..config.maxParallel) { "parallel must be between 1 and ${config.maxParallel}" }

/** The last [bytes] of a file that may still be growing. */
private fun tail(file: File, bytes: Long): String = RandomAccessFile(file, "r").use { raf ->
    val start = maxOf(0, raf.length() - bytes)
    raf.seek(start)
    val buffer = ByteArray((raf.length() - start).toInt())
    raf.readFully(buffer)
    String(buffer)
}

/** The reference scoreboard the Reference tab leads with: a run, how its problems are grouped and filtered, and its board. */
internal class ScoreboardView(
    /** The latest finished run of each schedule, the run picker's choices. */
    val runs: List<Job>,
    val job: Job?,
    val arms: List<String>,
    val arm: String?,
    val by: Dimension,
    val filters: Map<Dimension, String>,
    val scoring: Scoring,
    /** Every value each dimension takes over the run's problems, for the filters. */
    val options: Map<Dimension, List<String>>,
    val board: Board?,
    /** A cell's problems, when one was opened: its group and reference solver, and the problems worst first. */
    val cell: Pair<String, String>?,
    val problems: List<ScoreProblem>,
)

/** The scoreboard for [params]: `job` (default the latest klause-reference run), `arm`, `by`, a filter per dimension
 *  named by it, and `group` with `against` to open a cell. */
internal fun scoreboardView(store: Store, params: io.ktor.http.Parameters): ScoreboardView {
    val schedules = store.schedules().filter { schedule -> Experiments.arms(schedule.experiment).none { it.values["backend"] == REFERENCE_BACKEND } }
    val runs = schedules.mapNotNull { schedule ->
        store.jobs(limit = 20, name = schedule.name).firstOrNull { it.name.startsWith("${schedule.name}@") && it.status == Status.DONE }
    }
    val job = params["job"]?.toLongOrNull()?.let(store::job)
        ?: runs.firstOrNull { it.name.startsWith("klause-reference@") } ?: runs.firstOrNull()
    val by = params["by"]?.let { v -> Dimension.entries.firstOrNull { it.name.equals(v, ignoreCase = true) } } ?: Dimension.CLASS
    val filters = Dimension.entries.mapNotNull { d -> params[d.name.lowercase()]?.takeIf { it.isNotBlank() }?.let { d to it } }.toMap()
    val scoring = if (params["scoring"].equals("incomplete", ignoreCase = true)) Scoring.INCOMPLETE else Scoring.COMPLETE
    if (job == null) return ScoreboardView(runs, null, emptyList(), null, by, filters, scoring, emptyMap(), null, null, emptyList())
    val planned = store.arms(job.id)
    val label = params["arm"]?.takeIf { a -> planned.any { it.arm.label == a } } ?: planned.firstOrNull()?.arm?.label
    val arm = planned.firstOrNull { it.arm.label == label }?.arm
        ?: return ScoreboardView(runs, job, emptyList(), null, by, filters, scoring, emptyMap(), null, null, emptyList())
    val cases = store.cases(job.id).filter { it.arm == label }
    val keys = cases.map { it.problem.collection to it.problem.problem }.distinct()
    val references = store.currentReferenceRows(keys)
    val features = store.features(keys)
    val problems = cases.map { it.problem }.distinct()
    val profiles = Scoreboard.profiles(cases, features)
    val options = Dimension.entries.associateWith { d ->
        problems.flatMap { p -> Scoreboard.values(d, p, features[p.collection to p.problem], references[p.collection to p.problem].orEmpty(), profiles[p]) }
            .distinct().sorted()
    }
    val board = Scoreboard.board(cases, arm, references, features, by, filters, scoring)
    val cell = params["against"]?.let { against -> (params["group"] ?: "all") to against }
    val opened = cell?.let { (group, against) -> Scoreboard.problems(cases, arm, references, features, by, group, against, filters, scoring) }.orEmpty()
    return ScoreboardView(runs, job, planned.map { it.arm.label }, label, by, filters, scoring, options, board, cell, opened)
}
