package com.eignex.lab

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.delete
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
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Serializable
data class ParallelSpec(val parallel: Int)

@Serializable
data class PrioritySpec(val priority: Int)

@Serializable
data class DescriptionSpec(val description: String)

/** Which reference rows `POST /references/retire` marks stale: [solver]'s on [collection], of [budgetMs] when given. */
@Serializable
data class RetireSpec(val collection: String, val solver: String, val budgetMs: Long? = null)

@Serializable
data class RetireResult(val retired: Int)

@Serializable
data class HostSpec(val host: String, val machines: Int? = null)

/** Read and ignored: kept so an old client's call still parses. */
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

/** How far a trend view got: the runs it shows, and the finished runs whose points are still being computed. */
@Serializable
data class TrendProgress(val runs: Int, val computing: Int)

/** What the trend page of schedule [name] shows, its points as far as [cache] has them ([Trend.runs]). */
internal class TrendView(
    val name: String?,
    val schedules: List<String>,
    val shown: List<TrendRun>,
    /** Runs left out because they ran an earlier experiment than the schedule's current one. */
    val earlier: Int,
    val all: Boolean,
    /** Finished runs whose points are still being computed in the background. */
    val computing: Int,
)

internal fun trendView(store: Store, cache: TrendCache, requested: String?, allRequested: Boolean): TrendView {
    // A schedule of reference runs regenerates the reference; its trend says nothing about klause.
    val schedules = store.schedules()
        .filter { schedule -> Experiments.arms(schedule.experiment).none { it.values["backend"] == REFERENCE_BACKEND } }
    val name = requested?.takeIf { it.isNotBlank() } ?: schedules.firstOrNull()?.name
    // By default only the runs of the schedule's current experiment, and only those are read: a run of an
    // earlier one measured something else, and plotted alongside reads as a regression.
    // One line whether or not a run was source-checked ([Experiments.trendFingerprint]); each run says which.
    val current = schedules.firstOrNull { it.name == name }?.let { Experiments.trendFingerprint(it.experiment) }
    val all = allRequested || current == null
    val computing = ArrayList<Long>()
    val pointless = ArrayList<Long>()
    val runs = name?.let { Trend.runs(store, it, cache, experiment = current.takeUnless { all }, missing = computing, pointless = pointless) }
        .orEmpty()
    // The problems the latest run of that spec planned: a set redrawn since is another line, as an edit is.
    val latest = runs.lastOrNull { it.experiment == current }?.spec
    val shown = if (all) runs else runs.filter { it.spec == latest }
    val total = if (all) runs.size else name?.let { Trend.scheduled(store, it).size } ?: 0
    return TrendView(name, schedules.map { it.name }, shown, maxOf(0, total - shown.size - computing.size - pointless.size), all,
        computing.size)
}

/** A queued job's [id], with [notes] on what the lab made of its spec, such as a profile's time limit (none for most),
 *  [placement], where the lab placed it, in words, and [host], the same as `local`, `cloud` or `auto`
 *  ([Experiments.hostWord]). The job's own `experiment.host` keeps the stored `lab`, `aws` or `auto`. */
@Serializable
data class Created(val id: Long, val notes: List<String>? = null, val placement: String? = null, val host: String? = null)

@Serializable
data class ReferenceHit(val collection: String, val problem: String, val reference: Reference)

@Serializable
data class FileEntry(val path: String, val bytes: Long)

@Serializable
data class Health(
    val ok: Boolean,
    val queued: Int,
    val running: Int,
    /** Free on the data directory's volume; [volumes] has every volume the lab writes to. */
    val freeBytes: Long,
    val host: HostReport,
    /** Why the runner holds its queue ([Volumes.hold]), a low disk or a missing volume; null when it does not. */
    val held: String? = null,
    val volumes: List<VolumeReport> = emptyList(),
)

fun serveApi(config: Config, store: Store, host: HostReport) {
    embeddedServer(Netty, port = config.port) { api(config, store, host) }.start(wait = true)
}

fun Application.api(config: Config, store: Store, host: HostReport) {
    install(ContentNegotiation) { json() }
    // Every page and call needs the password, as HTTP Basic with any user name; browsers ask for it once and resend it.
    // A write a page of another site made the browser send is refused first, password or not ([sameSite]).
    val gate = config.passwordHash?.let { PasswordGate(it) }
    install(io.ktor.server.application.createApplicationPlugin("Password") {
        onCall { call ->
            val headers = call.request.headers
            // A handled call is not routed on.
            if (!sameSite(call.request.httpMethod.value, headers["Sec-Fetch-Site"], headers[HttpHeaders.Origin], headers[HttpHeaders.Host])) {
                call.respondText("cross-site request refused", status = HttpStatusCode.Forbidden)
                return@onCall
            }
            when (gate?.check(headers[HttpHeaders.Authorization], client(call)) ?: Admission.ADMIT) {
                Admission.ADMIT -> Unit
                Admission.THROTTLE -> call.respondText("too many failed passwords; try again in a minute", status = HttpStatusCode.TooManyRequests)
                Admission.REFUSE -> {
                    call.response.headers.append(HttpHeaders.WWWAuthenticate, "Basic realm=\"eignex lab\", charset=\"UTF-8\"")
                    call.respondText("password required", status = HttpStatusCode.Unauthorized)
                }
            }
        }
    })
    install(StatusPages) {
        exception<IllegalArgumentException> { call, e -> call.respondText(e.message ?: "bad request", status = HttpStatusCode.BadRequest) }
    }
    val trendCache = TrendCache()
    val referenceCache = ReferenceCache(store)
    // The finished runs the queue page's estimates learn from, reread at most once a minute.
    val estimates = EstimateHistory(store, config.cores)
    val disk = DiskUsage(config, active = { store.active(withCommands = false).map { it.id }.toSet() }).also { it.start() }
    // Only branches are asked about here, from GitHub; the runner checks each commit on its mirror when it plans.
    val trusts = java.util.concurrent.ConcurrentHashMap<String, Trust>()
    fun trust(repository: String) = trusts.computeIfAbsent(repository) {
        Trust(config.trustedUsers, Repositories.source(config, repository), git = { 1 to "" },
            github = { path -> Trust.githubApi(config.githubTokenFile.toFile().takeIf { it.isFile }?.readText()?.trim())(path) })
    }
    referenceCache.warm()
    // Every schedule's finished runs computed before anyone asks, so the first trend load after a restart is quick too.
    Trend.warm(store, trendCache)
    routing {
        get("/") {
            val before = call.parameters["before"]?.toLong()
            val name = call.parameters["name"]?.takeIf { it.isNotBlank() }
            val history = store.jobs(PAGE_SIZE, before, name, withCommands = false)
            val active = store.active(withCommands = false)
            // Why a queued job waits is `lab placement`'s ([Waiting]); the page shows only its place in the queue.
            val page = indexPage(config, host, active, store.schedules(), history, PAGE_SIZE, before, name,
                store.queue(nodesUp = config.aws != null && nodesUp(readPoolReport(config), now())), estimates::lookup, emptyMap(),
                store.hostProgress(active.filter { it.status == Status.RUNNING }.map { it.id }))
            call.respondText(page, ContentType.Text.Html)
        }
        get("/disk") {
            val holds = store.holds()
            val database = withContext(Dispatchers.IO) { runCatching { store.database() }.getOrNull() }
            val volumes = withContext(Dispatchers.IO) { Volumes.report(config) }
            val held = withContext(Dispatchers.IO) { runCatching { Volumes.hold(config) }.getOrNull() }
            if (call.wantsHtml()) {
                call.respondText(diskPage(disk.latest, holds, database, volumes, held), ContentType.Text.Html)
            } else {
                call.respond((disk.latest ?: DiskReport(0, freeBytes(config.dataDir), emptyList()))
                    .copy(holds = holds, database = database, volumes = volumes, held = held))
            }
        }
        get("/health") {
            val jobs = store.active(withCommands = false)
            val queued = jobs.count { it.status == Status.QUEUED }
            val running = jobs.count { it.status == Status.RUNNING }
            val (held, volumes) = withContext(Dispatchers.IO) { runCatching { Volumes.hold(config) }.getOrNull() to Volumes.report(config) }
            call.respond(Health(true, queued, running, freeBytes(config.dataDir), host, held, volumes))
        }
        get("/jobs") {
            // Each job's command counts, not its commands, unless ?commands=true: a job has hundreds. ?active=true lists
            // every queued and running job, oldest first, however old, instead of the newest.
            val commands = call.parameters["commands"] == "true"
            if (call.parameters["active"] == "true") return@get call.respond(store.active(withCommands = commands))
            call.respond(store.jobs(call.parameters["limit"]?.toInt() ?: PAGE_SIZE, call.parameters["before"]?.toLong(), call.parameters["name"],
                withCommands = commands))
        }
        // Where each active job may run and why a queued one waits ([Waiting]), by the rules the runner and the cloud
        // worker follow; read-only.
        get("/placement") {
            call.respond(withContext(Dispatchers.IO) { Waiting.report(config, store, estimates::lookup) })
        }
        get("/placement/{id}") {
            val id = call.parameters["id"]!!.toLong()
            val placement = withContext(Dispatchers.IO) {
                Waiting.report(config, store, estimates::lookup).jobs.firstOrNull { it.id == id }
                    ?: store.jobsByIds(listOf(id)).firstOrNull()?.let {
                        Waiting.placement(config, it, Waiting.view(config, store), null, estimates::lookup, store.hostProgress(listOf(id))[id].orEmpty())
                    }
            }
            if (placement == null) call.respond(HttpStatusCode.NotFound, "no such job") else call.respond(placement)
        }
        // An experiment is queued as a job with no commands; the runner plans its cases when it first sets it up.
        post("/experiments") {
            // The lab places every job itself ([Placement]): a submitted host or machine count is read but not followed.
            val body = analysisDefaults(call.receive<JsonObject>())
            // Every job fits the local machine: cores and parallel over its limits are cut down to them, and the notes
            // say so.
            val given = Experiments.capToLocal(DefaultJson.decodeFromJsonElement(ExperimentSpec.serializer(), body).copy(capped = emptyList()),
                config.cores, config.maxParallel)
            val spec = given.copy(host = Placement.host(given, given.name, config), auto = false, machines = null)
            requireDescription(spec)
            requireHost(spec, config)
            Experiments.validate(spec, config.maxParallel, config.cores)
            // An analysis needs its job's captures: refused now, saying why, when they are gone or the job has not ended.
            spec.analyze?.let { analyze ->
                val job = analyze.job
                val refusal = withContext(Dispatchers.IO) {
                    when {
                        analyze.kind == AnalyzeSpec.CHECK -> com.eignex.lab.checker.CaseChecks.refusal(store.job(checkNotNull(job)))
                        job != null -> CompilationReports.refusal(store.job(job), config.jobDir(job))
                        else -> CaptureArchives.refusal(analyze, config)
                    }
                }
                val what = if (job != null) "job $job" else "archive ${analyze.archive}"
                if (refusal != null) {
                    call.respond(HttpStatusCode.Conflict, "cannot analyse $what: $refusal")
                    return@post
                }
                val id = store.create(spec.name, DEFAULT_REF, emptyList(), 1, spec.priority, spec)
                val note = if (analyze.kind == AnalyzeSpec.CHECK) "checks job $job's solution claims again on the local machine; no solver runs"
                    else if (job != null) "analyses job $job's retained captures on the local machine; no solver runs"
                    else "checks the lab-local archive against its sha256, verifies it and analyses its captures on the local machine; no solver runs"
                call.respond(HttpStatusCode.Created, Created(id, listOf(note), placementNote(spec.host), Experiments.hostWord(spec.host)))
                return@post
            }
            // One question is one experiment: a batch of an active one is refused ([Experiments.splitOf]).
            Experiments.splitOf(spec, store.active(withCommands = false))?.let { split ->
                call.respond(HttpStatusCode.Conflict, Experiments.splitMessage(split))
                return@post
            }
            val arms = Experiments.arms(spec)
            for (ref in arms.map { it.ref }.distinct()) requireRef(ref, Repositories.source(config, spec.repository), trust(spec.repository))
            val id = store.create(spec.name, arms.first().ref, emptyList(), spec.parallel ?: config.defaultParallel, spec.priority, spec)
            val notes = (if (spec.isProfile) {
                listOf("time limit: ${Experiments.profileLimitText(spec)}, at most ${Experiments.duration(Experiments.PROFILE_MAX_TIMEOUT_MS)}") +
                    Experiments.profileCaps(spec).map { "capped: $it" }
            } else emptyList()).plus(spec.capped.map { "capped: $it" }).ifEmpty { null }
            val ignored = listOf("host", "machines").filter { it in body }
            call.respond(HttpStatusCode.Created, Created(id, notes, placementNote(spec.host) +
                if (ignored.isEmpty()) "" else "; ${ignored.joinToString(" and ")} ignored: the lab places jobs", Experiments.hostWord(spec.host)))
        }
        get("/experiments/{id}/arms") { call.respond(store.arms(call.parameters["id"]!!.toLong())) }
        get("/trend") {
            val view = trendView(store, trendCache, call.parameters["name"], call.parameters["all"] != null)
            if (call.wantsHtml()) {
                val repository = store.schedules().firstOrNull { it.name == view.name }?.experiment?.repository ?: Repositories.DEFAULT
                call.respondText(trendPage(view.name, view.shown, Repositories.url(repository), view.schedules, view.earlier, view.all, view.computing),
                    ContentType.Text.Html)
            } else {
                call.respond(view.shown)
            }
        }
        get("/trend/progress") {
            // What the trend page polls while points are computing, instead of reloading itself whole.
            val view = trendView(store, trendCache, call.parameters["name"], call.parameters["all"] != null)
            call.respond(TrendProgress(view.shown.size, view.computing))
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
                val board = referenceCache.scoreboard(call.parameters)
                val page = referencesPage(referenceCache.coverage(), filter, found, total, referenceCache.conflicts(), board)
                call.respondText(page, ContentType.Text.Html)
            } else {
                call.respond(found.map { (key, r) -> ReferenceHit(key.first, key.second, r) })
            }
        }
        // Retire a solver's rows on a collection, of one budget if given ([Store.retireReferenceRows]): kept as history,
        // left out of every comparison, and replaced by the solver's next row on each problem.
        post("/references/retire") {
            val spec = call.receive<RetireSpec>()
            require(spec.collection.isNotBlank() && spec.solver.isNotBlank()) { "collection and solver are required" }
            require(spec.budgetMs == null || spec.budgetMs > 0) { "budgetMs must be positive" }
            val retired = store.retireReferenceRows(spec.collection, spec.solver, spec.budgetMs)
            println("api: retired $retired reference rows of ${spec.solver} on ${spec.collection}" + (spec.budgetMs?.let { " at $it ms" } ?: ""))
            call.respond(RetireResult(retired))
        }
        get("/problem") {
            val collection = requireNotNull(call.parameters["collection"]) { "collection is required" }
            val problem = requireNotNull(call.parameters["problem"]) { "problem is required" }
            // Each case's record is read whole, so a problem many jobs ran (hundreds of cases) shows its newest unless
            // all are asked for.
            val limit = if (call.parameters["all"] != null) null else PROBLEM_RUNS
            val runs = store.problemRuns(collection, problem, limit)
            val total = if (limit != null && runs.size >= limit) store.problemRunCount(collection, problem) else runs.size
            val report = ProblemReport(collection, problem, store.referenceRows(collection, problem), runs, total)
            if (call.wantsHtml()) call.respondText(problemPage(report, config.repoUrl), ContentType.Text.Html) else call.respond(report)
        }
        get("/experiments/{id}/reference") {
            val id = call.parameters["id"]!!.toLong()
            val cases = store.cases(id)
            val references = store.references(cases.map { it.problem.collection to it.problem.problem }, sources = References.sources(cases))
            call.respond(References.compare(store.arms(id).map { it.arm.label }, cases, references))
        }
        get("/compare") {
            val ids = requireNotNull(call.parameters["jobs"]) { "jobs=<id>,<id>… is required" }.split(',').map { it.trim().toLong() }
            require(ids.size in 2..MAX_COMPARED) { "compare 2 to $MAX_COMPARED experiments" }
            val jobs = ids.map { id -> requireNotNull(store.job(id)?.takeIf { it.experiment != null }) { "job $id is not an experiment" } }
            // Arms are named by their job, so the same arm of two runs stays two columns.
            val arms = jobs.flatMap { job -> store.arms(job.id).map { it.copy(arm = it.arm.copy(label = "${job.id} ${it.arm.label}")) } }
            val cases = jobs.flatMap { job -> store.cases(job.id).map { it.copy(arm = "${job.id} ${it.arm}") } }
            val references = store.references(cases.map { it.problem.collection to it.problem.problem }, sources = References.sources(cases))
            call.respondText(comparePage(config, jobs, arms, cases, references), ContentType.Text.Html)
        }
        get("/experiments/{id}/cases") { call.respond(store.cases(call.parameters["id"]!!.toLong())) }
        // Every case whole, as JSON Lines: its record with its final witness, its build provenance and the lab's verdict
        // as `labCheck`, its producer record as the database keeps it, cut to its time to best ([StoredRecords]). It is
        // read and written a page of cases at a time, so a job of thousands of cases with large witnesses streams out
        // without being held in memory, and the store is free between pages.
        get("/experiments/{id}/cases.jsonl") {
            val id = call.parameters["id"]!!.toLong()
            call.respondTextWriter(ContentType("application", "x-ndjson")) {
                var from = 0
                while (true) {
                    val page = withContext(Dispatchers.IO) { store.cases(id, from, CASES_PAGE) }
                    for (case in page) write(DefaultJson.encodeToString(CaseResult.serializer(), case) + "\n")
                    flush()
                    if (page.size < CASES_PAGE) break
                    from = page.last().index + 1
                }
            }
        }
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
                        references = store.references(cases.map { it.problem.collection to it.problem.problem }, sources = References.sources(cases)),
                        showCommands = !experiment || "commands" in call.parameters || "failed" in call.parameters,
                        previous = if (experiment && '@' in job.name) previousRun(store, job) else null,
                        holds = store.holds().filter { it.job == job.id },
                        hosts = store.hostProgress(listOf(job.id))[job.id].orEmpty(),
                    )
                    call.respondText(page, ContentType.Text.Html)
                }
                else -> {
                    val dir = config.jobDir(job.id)
                    call.respond(job.copy(commands = job.commands.map { it.copy(terminations = Termination.read(dir, it.index)) }))
                }
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
        // Start a cloud node by hand ([ManualLaunch]): the runner's cloud worker launches it on its next poll.
        post("/cloud/nodes") {
            val aws = AwsConfig.load(config.dataDir.resolve("aws").resolve("aws.properties"))
            if (aws == null) {
                call.respond(HttpStatusCode.Conflict, "the lab has no cloud settings: run deploy/aws-setup.sh")
                return@post
            }
            val text = call.receiveText()
            val start = if (text.isBlank()) NodeStart() else runCatching { DefaultJson.decodeFromString(NodeStart.serializer(), text) }
                .getOrElse { throw IllegalArgumentException("the body is {\"size\"?, \"market\"?}: ${it.message}") }
            // Who asked, for the log: the page's button (a browser) or the client (curl), so a launch nobody expected
            // can be traced.
            val agent = call.request.headers["User-Agent"].orEmpty()
            val by = if (agent.startsWith("curl/")) "client" else if (agent.isNotEmpty()) "page" else "unknown"
            val (status, message) = ManualLaunch.request(config, aws, readPoolReport(config), start, System.currentTimeMillis(), by)
            println("api: manual launch from the $by (${start.size ?: "queue size"}, ${start.market ?: "spot"}): $status $message")
            call.respond(HttpStatusCode.fromValue(status), message)
        }
        post("/update") {
            config.updateRequest.toFile().writeText("${System.currentTimeMillis()}\n")
            call.respond(HttpStatusCode.Accepted, "update requested")
        }
        // The runner cleans Docker up ([DockerCleanup]) once it is between jobs and no container runs.
        post("/docker-cleanup") {
            if (!config.requireDocker) return@post call.respond(HttpStatusCode.Conflict, "docker is off on this lab (LAB_DOCKER=none)")
            config.dockerCleanupRequest.toFile().writeText("${System.currentTimeMillis()}\n")
            call.respond(HttpStatusCode.Accepted, "docker cleanup requested: it runs between jobs")
        }
        post("/jobs/{id}/retry") {
            val id = call.parameters["id"]!!.toLong()
            val retried = store.retry(id)
            // It may go anywhere again, wherever it was taken last time: a planned one's commands are rendered for the
            // host that runs each case ([Runner.render]).
            store.job(id)?.takeIf { retried }?.let { job ->
                if (job.commands.isEmpty()) job.experiment?.let { store.place(id, Placement.host(it, job.name, config, store.schedules())) }
                else Placement.release(store, config, id)
            }
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
        // An operator's override of where a queued, unplanned job may run; the lab places jobs itself ([Placement]).
        post("/jobs/{id}/host") {
            val id = call.parameters["id"]!!.toLong()
            // `local` and `cloud` are stored as `lab` and `aws`, the names older jobs and clients use.
            val host = Experiments.storedHost(call.receive<HostSpec>().host)
            val job = store.job(id)
            val experiment = requireNotNull(job?.experiment) { "no such experiment" }
            requireHost(experiment.copy(host = host), config)
            val moved = store.place(id, host)
            call.respond(
                if (moved) HttpStatusCode.OK else HttpStatusCode.Conflict,
                if (moved) "operator override: ${placementNote(host)} (the lab places jobs itself; agents need not)"
                else "only a queued job that has not been planned can change host",
            )
        }
        // Kept so old clients get an answer: the node pool decides how many machines work on a job.
        post("/jobs/{id}/machines") {
            call.receive<MachinesSpec>()
            call.respond(HttpStatusCode.OK, "ignored: the lab places jobs, and the cloud node pool sizes itself to the queue (see maxInstances)")
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
            requireRef(spec.ref, Repositories.source(config, spec.experiment.repository), trust(spec.experiment.repository))
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
        // A download's hold on a job's files: the trim leaves the job alone until it is released or lapses ([Hold]).
        // `?ttlSec=` asks for less than the 2 h a hold lasts, never more; a long download renews it instead.
        post("/jobs/{id}/hold") {
            val id = call.jobId(store) ?: return@post
            call.respond(HttpStatusCode.Created, store.hold(id, holdMs(call.parameters["ttlSec"])))
        }
        post("/jobs/{id}/hold/{lease}") {
            val id = call.jobId(store) ?: return@post
            val hold = store.renewHold(id, call.parameters["lease"]!!, holdMs(call.parameters["ttlSec"]))
            if (hold == null) call.respond(HttpStatusCode.NotFound, "no such hold, or it lapsed") else call.respond(hold)
        }
        delete("/jobs/{id}/hold/{lease}") {
            val id = call.jobId(store) ?: return@delete
            val released = store.releaseHold(id, call.parameters["lease"]!!)
            call.respond(if (released) HttpStatusCode.OK else HttpStatusCode.NotFound, if (released) "released" else "no such hold")
        }
        // A job's files live on the job files' volume ([Config.jobsDir]): while it is not mounted, or does not answer, they
        // are unavailable, which is said as such rather than as missing files.
        get("/jobs/{id}/files") {
            val id = call.jobId(store) ?: return@get
            if (call.jobFilesMissing(config)) return@get
            val files = jobFiles(config.jobDir(id))
            if (call.wantsHtml()) call.respondText(filesPage(id, files), ContentType.Text.Html) else call.respond(files)
        }
        get("/jobs/{id}/files/{path...}") {
            val id = call.jobId(store) ?: return@get
            if (call.jobFilesMissing(config)) return@get
            val tail = call.parameters["tail"]?.let {
                requireNotNull(it.toLongOrNull()?.takeIf { bytes -> bytes in 0..MAX_TAIL_BYTES }) { "tail must be 0 to $MAX_TAIL_BYTES bytes" }
            }
            val file = jobFile(config.jobDir(id), call.parameters.getAll("path").orEmpty())?.toFile()
            when {
                file == null -> call.respond(HttpStatusCode.NotFound, "no such file")
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
/** Jobs a page of the history, and a `/jobs` list by default, shows. */
private const val PAGE_SIZE = 50
private const val MAX_COMPARED = 6
/** Cases `/experiments/{id}/cases.jsonl` reads from the store at once. */
private const val CASES_PAGE = 32
/** The most a `?tail=` read returns, so one request cannot pull a whole large file into memory. */
private const val MAX_TAIL_BYTES = 16L * 1024 * 1024

/** A hold's length from its `ttlSec` parameter: [JobTrim.HOLD_MS] when none is given, and never more. */
private fun holdMs(ttlSec: String?): Long {
    if (ttlSec == null) return JobTrim.HOLD_MS
    val seconds = ttlSec.toLongOrNull()
    require(seconds != null && seconds in 1..JobTrim.HOLD_MS / 1000) { "ttlSec must be 1 to ${JobTrim.HOLD_MS / 1000} seconds" }
    return seconds * 1000
}

/** The worktrees job [id] built, its own and those of its other commits; the mirror forgets them at its next prune. */
private fun worktrees(config: Config, id: Long): List<File> =
    config.worktree(id).toFile().parentFile.listFiles { f -> f.name == "$id" || f.name.startsWith("$id@") }.orEmpty().toList()

/** A job that may run on a cloud node needs the lab's cloud settings, runs klause only, and fits a node. */
private fun requireHost(spec: ExperimentSpec, config: Config) {
    require(spec.host in setOf(Experiments.LAB_HOST, Experiments.AWS_HOST, Experiments.AUTO_HOST)) { "host must be local, cloud or auto (or lab or aws, their older names)" }
    if (spec.host == Experiments.LAB_HOST) return
    val aws = requireNotNull(config.aws) {
        "the lab has no cloud settings: run deploy/aws-setup.sh"
    }
    require(Experiments.arms(spec).none { it.values["backend"] == REFERENCE_BACKEND }) {
        "reference runs stay on the local machine, which has the reference solvers"
    }
    require(spec.problemList.isEmpty()) { "a cloud experiment selects its problems" }
    val cores = Experiments.arms(spec).maxOf { it.cores }
    requireNotNull(aws.sizeFor(cores)) {
        "its cases need $cores cores; the largest instance within the ${aws.vcpuQuota}-vCPU quota has ${aws.sizes.last().cores}"
    }
}

/** What placing a job on [host] means, for the submitter. */
private fun placementNote(host: String): String = when (host) {
    Experiments.AWS_HOST -> "placed on the cloud: a cloud node runs it"
    Experiments.AUTO_HOST -> "placed by the lab: the local machine when it is free, else a cloud node"
    else -> "placed locally"
}

/** An experiment says what it is for, so the queue and the history read as more than names. */
/**
 * [body] with an analysis job's name and description filled in when it gives none, so `{"analyze": {"job": 12}}`, or
 * `{"analyze": {"archive": …, "sha256": …}}`, is a whole submission; any other body as it is.
 */
internal fun analysisDefaults(given: JsonObject): JsonObject {
    // `{"recheck": {"job": N}}` is an analysis of kind `check` ([AnalyzeSpec.CHECK]).
    val body = (given["recheck"] as? JsonObject)?.let { recheck ->
        val job = recheck["job"] ?: return given
        val defaults = mapOf(
            "name" to kotlinx.serialization.json.JsonPrimitive("recheck-$job"),
            "description" to kotlinx.serialization.json.JsonPrimitive("Check job $job's solution claims again with the lab's source checkers"),
        )
        JsonObject(defaults.filterKeys { it !in given } + (given - "recheck") +
            ("analyze" to JsonObject(mapOf("job" to job, "kind" to kotlinx.serialization.json.JsonPrimitive(AnalyzeSpec.CHECK)))))
    } ?: given
    val analyze = body["analyze"] as? JsonObject ?: return body
    val job = (analyze["job"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    val archive = (analyze["archive"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    val sha = (analyze["sha256"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    val defaults = when {
        job != null -> mapOf(
            "name" to kotlinx.serialization.json.JsonPrimitive("analyze-$job"),
            "description" to kotlinx.serialization.json.JsonPrimitive("Rerun the lab's compilation analyser over job $job's retained captures"),
        )
        archive != null -> mapOf(
            "name" to kotlinx.serialization.json.JsonPrimitive("analyze-archive-${sha.orEmpty().take(12)}"),
            "description" to kotlinx.serialization.json.JsonPrimitive("Run the lab's compilation analyser over the captures in the pinned archive " +
                archive.substringAfterLast('/')),
        )
        else -> return body
    }
    return JsonObject(defaults.filterKeys { it !in body } + body)
}

private fun requireDescription(spec: ExperimentSpec) = require(spec.description.isNotBlank()) {
    "description is required: a sentence or two on what the experiment tests and why, e.g. \"A/B of #2232's " +
        "continuation scheduler against main on the sweep set, to see whether it closes the uf250 gap\""
}

private fun validateScheduled(experiment: ExperimentSpec, config: Config) {
    requireDescription(experiment)
    // A schedule's host says only whether its runs may go to the cloud ([Placement.cloudOptIn]); the lab places them.
    require(Experiments.storedHost(experiment.host) in setOf(Experiments.LAB_HOST, Experiments.AWS_HOST, Experiments.AUTO_HOST)) {
        "host must be local, cloud or auto (or lab or aws, their older names)"
    }
    Experiments.validate(experiment, config.maxParallel, config.cores)
    require(Experiments.arms(experiment).none { "ref" in it.values }) {
        "a scheduled experiment runs every arm at the schedule's ref; drop ref from its configs"
    }
}

/** The latest finished experiment queued by the same schedule before [job], whose name is `<schedule>@<sha>`. */
private fun previousRun(store: Store, job: Job): Long? =
    store.jobs(limit = PAGE_SIZE, before = job.id, name = job.name.substringBefore('@'), withCommands = false)
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
/**
 * Fail unless [ref] names a branch or tag on origin, and, for a branch, unless only trusted users pushed to it
 * ([Trust.requireBranch]): a refused branch is refused before it queues. A commit named by its sha is checked when
 * the job is planned, as every commit is, and so is a branch when GitHub cannot be asked now.
 */
private suspend fun requireRef(ref: String, repoUrl: String, trust: Trust) {
    if (!Regex("[A-Za-z0-9._/-]+").matches(ref) || Regex("[0-9a-f]{7,40}").matches(ref)) return
    var branch = false
    val known = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("git", "ls-remote", repoUrl, "refs/heads/$ref", "refs/tags/$ref")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply { environment()["GIT_TERMINAL_PROMPT"] = "0" }
            .start()
        // Waited on before reading: the answer is a line or two, well inside the pipe buffer, and a hung remote
        // must not hold the request past the timeout.
        if (!process.waitFor(LS_REMOTE_SEC, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            true
        } else {
            val listed = process.inputStream.bufferedReader().readText()
            branch = listed.lines().any { it.endsWith("refs/heads/$ref") }
            process.exitValue() != 0 || listed.isNotBlank()
        }
    }
    require(known) { "unknown ref: $ref (no such branch or tag on origin; is it pushed?)" }
    if (!branch) return
    val refusal = withContext(Dispatchers.IO) { runCatching { trust.requireBranch(ref) }.exceptionOrNull() }
    if (refusal is UntrustedCommit) throw refusal
}

private fun requireParallel(parallel: Int, config: Config) =
    require(parallel in 1..config.maxParallel) { "parallel must be between 1 and ${config.maxParallel}" }

/**
 * Who sent [call], for the password gate's failure limits: the peer's address, or, for a request the relay forwarded
 * from this machine's loopback, the last address in `X-Forwarded-For`, which the relay itself appended.
 */
private fun client(call: ApplicationCall): String {
    val peer = call.request.local.remoteAddress
    val forwarded = call.request.headers["X-Forwarded-For"]?.split(',')?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    return if (forwarded != null && (peer == "127.0.0.1" || peer == "::1" || peer == "0:0:0:0:0:0:0:1" || peer == "localhost")) forwarded else peer
}

/** The job the `{id}` parameter names; a malformed id is answered 400 and an unknown job 404, and both give null. */
private suspend fun ApplicationCall.jobId(store: Store): Long? {
    val id = parameters["id"]?.toLongOrNull()
    when {
        id == null -> respond(HttpStatusCode.BadRequest, "job id must be a number")
        store.job(id) == null -> respond(HttpStatusCode.NotFound, "no such job")
        else -> return id
    }
    return null
}

/**
 * Whether the job files cannot be used ([Volumes.jobFiles]): their volume is missing, or does not answer within the
 * probe's timeout ([VolumeAccess]). Then the call is answered 503 with why, at once while a probe is known to be stuck,
 * rather than leave a request thread blocked on the volume.
 */
private suspend fun ApplicationCall.jobFilesMissing(config: Config): Boolean {
    val problem = withContext(Dispatchers.IO) { Volumes.jobFiles(config) } ?: return false
    respondText("job files unavailable: $problem", status = HttpStatusCode.ServiceUnavailable)
    return true
}

/**
 * The regular files under job directory [root], relative to it. Symbolic links are neither listed nor followed: a
 * job's files are what it wrote there, and a link could lead anywhere on the machine.
 */
internal fun jobFiles(root: Path): List<FileEntry> {
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    return Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .map { FileEntry(root.relativize(it).joinToString("/"), Files.size(it)) }
            .toList()
    }.sortedBy { it.path }
}

/**
 * The regular file [segments] name under job directory [root], or null when there is none. A segment that is empty,
 * `.` or `..`, or holds a slash, a backslash or a NUL fails as a bad request, whether it came plain or
 * percent-encoded. A file reached through a symbolic link is served only when the link resolves inside [root] too.
 */
internal fun jobFile(root: Path, segments: List<String>): Path? {
    require(segments.isNotEmpty() && segments.none { it in setOf("", ".", "..") || it.any { c -> c in "/\\\u0000" } }) {
        "bad file path"
    }
    val realRoot = runCatching { root.toRealPath() }.getOrNull() ?: return null
    val file = runCatching { realRoot.resolve(segments.joinToString("/")).toRealPath() }.getOrNull() ?: return null
    return file.takeIf { it.startsWith(realRoot) && Files.isRegularFile(it) }
}

/** The last [bytes] of a file that may still be growing. */
private fun tail(file: File, bytes: Long): String = RandomAccessFile(file, "r").use { raf ->
    val start = maxOf(0, raf.length() - bytes)
    raf.seek(start)
    val buffer = ByteArray((raf.length() - start).toInt())
    raf.readFully(buffer)
    String(buffer)
}

/** The reference scoreboard the Reference tab leads with: a run, how its problems are grouped and filtered, and its board. */
/**
 * The reference page's reads, kept between loads: the coverage and conflict tables scan every reference row, and a
 * scoreboard scores its run's every case. Once a reference row changes or a job finishes, a kept read is still served
 * and recomputed on one background thread, so a page load never waits on a recompute it has an answer for. A
 * scoreboard of a run still going is not kept.
 */
internal class ReferenceCache(private val store: Store) {
    private class Kept<T>(val version: String, val value: T)

    private class Tables(val coverage: List<ReferenceCoverage>, val conflicts: List<Disagreement>)

    @Volatile private var tables: Kept<Tables>? = null

    private val boards = object : LinkedHashMap<String, Kept<ScoreboardView>>(BOARDS, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Kept<ScoreboardView>>) = size > BOARDS
    }

    private val refreshing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val background = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "reference-refresh").apply { isDaemon = true }
    }

    private fun version() = "${store.referencesStamp()}/${store.lastFinished()}"

    private fun later(key: String, work: () -> Unit) {
        if (!refreshing.add(key)) return
        background.execute {
            try {
                work()
            } catch (e: Exception) {
                println("reference refresh of $key failed: ${e.message}")
            } finally {
                refreshing.remove(key)
            }
        }
    }

    private fun computeTables(version: String) = Kept(version, Tables(store.referenceCoverage(), store.referenceConflicts())).also { tables = it }

    private fun tables(): Tables {
        val version = version()
        val kept = tables ?: return computeTables(version).value
        if (kept.version != version) later("tables") { computeTables(version()) }
        return kept.value
    }

    fun coverage(): List<ReferenceCoverage> = tables().coverage

    fun conflicts(): List<Disagreement> = tables().conflicts

    /** [scoreboardView] of [params], kept per [params] and served stale while it is recomputed. */
    fun scoreboard(params: io.ktor.http.Parameters): ScoreboardView {
        val key = params.entries().sortedBy { it.key }.joinToString("&") { (k, v) -> "$k=${v.joinToString(",")}" }
        val version = version()
        val kept = synchronized(boards) { boards[key] }
        if (kept != null) {
            if (kept.version != version) later("board $key") { board(key, params, version()) }
            return kept.value
        }
        return board(key, params, version)
    }

    private val inputs = object : LinkedHashMap<String, Kept<ScoreInputs>>(INPUTS, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Kept<ScoreInputs>>) = size > INPUTS
    }

    /** A finished run's [ScoreInputs], kept until a reference row changes: a new filter or grouping then only regroups. */
    private fun inputs(job: Job, label: String?): ScoreInputs {
        if (job.status in ACTIVE) return scoreInputs(store, job.id, label)
        val key = "${job.id}/$label/${job.finishedAt}"
        val stamp = store.referencesStamp().toString()
        synchronized(inputs) { inputs[key]?.takeIf { it.version == stamp }?.let { return it.value } }
        return scoreInputs(store, job.id, label).also { synchronized(inputs) { inputs[key] = Kept(stamp, it) } }
    }

    private fun board(key: String, params: io.ktor.http.Parameters, version: String): ScoreboardView =
        scoreboardView(store, params, ::inputs).also { view ->
            if (view.job?.status !in ACTIVE) synchronized(boards) { boards[key] = Kept(version, view) }
        }

    /** The default page's reads, computed before anyone asks so the first load after a restart is quick too. */
    fun warm() = later("warm") {
        computeTables(version())
        board("", io.ktor.http.Parameters.Empty, version())
    }

    private companion object {
        const val BOARDS = 64
        const val INPUTS = 8
        const val LOAD_FACTOR = 0.75f
    }
}

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
    /** The arm's Klause solution claims by how far each was checked against its source ([WitnessCheck]). */
    val witnesses: Map<WitnessCheck, Int> = emptyMap(),
)

/** The scoreboard for [params]: `job` (default the latest klause-reference run), `arm`, `by`, a filter per dimension
 *  named by it, and `group` with `against` to open a cell. */
/** What a scoreboard of one arm of a run reads: its cases, and their problems' reference rows and features. */
internal class ScoreInputs(
    val cases: List<CaseResult>,
    val references: Map<Pair<String, String>, List<Reference>>,
    val features: Map<Pair<String, String>, ProblemFeatures>,
)

internal fun scoreInputs(store: Store, job: Long, label: String?): ScoreInputs {
    val cases = store.scoringCases(job).filter { it.arm == label }
    val keys = cases.map { it.problem.collection to it.problem.problem }.distinct()
    return ScoreInputs(cases, References.matching(store.currentReferenceRows(keys), cases), store.features(keys))
}

internal fun scoreboardView(
    store: Store,
    params: io.ktor.http.Parameters,
    inputs: (Job, String?) -> ScoreInputs = { job, label -> scoreInputs(store, job.id, label) },
): ScoreboardView {
    val schedules = store.schedules().filter { schedule -> Experiments.arms(schedule.experiment).none { it.values["backend"] == REFERENCE_BACKEND } }
    val runs = schedules.mapNotNull { schedule ->
        store.jobs(limit = 20, name = schedule.name, withCommands = false).firstOrNull { it.name.startsWith("${schedule.name}@") && it.status == Status.DONE }
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
    val (cases, references, features) = inputs(job, label).let { Triple(it.cases, it.references, it.features) }
    val problems = cases.map { it.problem }.distinct()
    val profiles = Scoreboard.profiles(cases, features)
    val options = Dimension.entries.associateWith { d ->
        problems.flatMap { p -> Scoreboard.values(d, p, features[p.collection to p.problem], references[p.collection to p.problem].orEmpty(), profiles[p]) }
            .distinct().sorted()
    }
    val board = Scoreboard.board(cases, arm, references, features, by, filters, scoring)
    val cell = params["against"]?.let { against -> (params["group"] ?: "all") to against }
    val opened = cell?.let { (group, against) -> Scoreboard.problems(cases, arm, references, features, by, group, against, filters, scoring) }.orEmpty()
    val witnesses = cases.mapNotNull { Outcome.of(it.record) }.filter { it.claimedSolution }.mapNotNull { it.witness }
        .groupingBy { it }.eachCount()
    return ScoreboardView(runs, job, planned.map { it.arm.label }, label, by, filters, scoring, options, board, cell, opened, witnesses)
}

/** The newest cases the problem page shows unless `all` is asked for. */
private const val PROBLEM_RUNS = 20
