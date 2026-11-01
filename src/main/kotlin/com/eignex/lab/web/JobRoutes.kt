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

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** The jobs: listing and reading them, waiting on one, what an operator does to one ([JobService]), and download holds. */
internal fun Route.jobRoutes(services: ApiServices) {
    val config = services.config
    val store = services.store
    val jobs = services.jobs
    get("/jobs") {
        // Each job's command counts, not its commands, unless ?commands=true: a job has hundreds. ?active=true lists
        // every queued and running job, oldest first, however old, instead of the newest.
        val commands = call.parameters["commands"] == "true"
        val limit = call.parameters["limit"]?.let { it.toIntOrNull() ?: throw ApiError.Invalid("limit must be a number") } ?: PAGE_SIZE
        val before = call.parameters["before"]?.let { it.toLongOrNull() ?: throw ApiError.Invalid("before must be a number") }
        call.respond(io {
            if (call.parameters["active"] == "true") store.active(withCommands = commands)
            else store.jobs(limit, before, call.parameters["name"], withCommands = commands)
        })
    }
    get("/jobs/{id}") {
        val job = io { call.job(store) }
        if (call.wantsHtml()) {
            val page = io {
                val cases = store.plans.cases(job.id)
                jobPage(
                    config.status, job, "failed" in call.parameters,
                    arms = store.plans.arms(job.id),
                    cases = cases,
                    preparation = if (job.backend == Backend.NOMAD) store.execution.reports(job.id).filter { it.session.executionClass == ExecutionClass.PREPARE }.takeLast(1) else emptyList(),
                    references = store.references.matching(cases.map { it.problem.referenceKey }, sources = References.sources(cases)),
                    showCommands = "commands" in call.parameters || "failed" in call.parameters,
                    previous = if (job.origin is JobOrigin.Scheduled) PageQueries(services).previousRun(job) else null,
                    holds = store.holds.active().filter { it.job == job.id },
                    hosts = store.hostProgress(listOf(job.id))[job.id].orEmpty(),
                    nodes = if (job.backend == Backend.NOMAD && job.status.isActive)
                        jobNodes(config.status, store, listOf(job.id), services.capacitySettings.get())[job.id].orEmpty() else emptyList(),
                    files = JobDirView.read(config.paths, job),
                    parallelism = if (job.backend == Backend.NOMAD && job.status.isActive)
                        store.execution.parallelism(listOf(job.id))[job.id] ?: (0 to 0) else null,
                )
            }
            call.respondHtml(page)
        } else {
            val dir = config.jobDir(job.id)
            val terminations = io { Volumes.jobFiles(config.paths) == null }
            call.respond(if (!terminations) job else io { job.copy(commands = job.commands.map { it.copy(terminations = Termination.read(dir, it.index)) }) })
        }
    }
    // A long poll: the answer comes when the job ends, or after timeoutSec with the job as it stands, so a
    // client learns of the end without polling over the network.
    get("/jobs/{id}/wait") {
        val id = call.longParam()
        val timeoutSec = (call.parameters["timeoutSec"]?.let { it.toLongOrNull() ?: throw ApiError.Invalid("timeoutSec must be a number") }
            ?: DEFAULT_WAIT_SEC).coerceIn(1, MAX_WAIT_SEC)
        val deadline = now() + timeoutSec * 1000
        while (true) {
            val job = io { store.job(id) } ?: throw ApiError.NotFound("no such job $id")
            if (job.status.isTerminal || now() >= deadline) return@get call.respond(job)
            delay(WAIT_POLL_MS)
        }
    }
    post("/jobs/{id}/cancel") { call.respondAction(jobs.cancel(call.longParam())) }
    post("/jobs/{id}/retry") { call.respondAction(jobs.retry(call.longParam())) }
    // Its directory goes too, and any worktree a failed setup left; the mirror forgets those at its next prune.
    post("/jobs/{id}/delete") { call.respondAction(jobs.delete(call.longParam())) }
    // `parallel` is the retired name of `vcpus`, kept for older clients.
    for (path in listOf("/jobs/{id}/vcpus", "/jobs/{id}/parallel")) post(path) {
        val id = call.longParam()
        call.respondAction(io { jobs.setVcpus(id, call.receive<VcpusSpec>().value) })
    }
    post("/jobs/{id}/tags") {
        val id = call.longParam()
        val body = call.receive<JsonObject>()
        require(body.keys == setOf("tags")) { "provide only tags: an array of strings; [] restores auto placement" }
        call.respondAction(jobs.retag(id, body["tags"]))
    }
    post("/jobs/{id}/host") {
        throw ApiError.Gone("host overrides are retired; use /jobs/{id}/tags with {\"tags\": []} for auto placement")
    }
    post("/jobs/{id}/machines") {
        throw ApiError.Gone("machine overrides are retired; use tags for capabilities; the cloud pool scales automatically")
    }
    post("/jobs/{id}/description") {
        val id = call.longParam()
        val spec = call.receive<DescriptionSpec>()
        call.respondAction(io { jobs.describe(id, spec.description) })
    }
    post("/jobs/{id}/priority") {
        val id = call.longParam()
        val spec = call.receive<PrioritySpec>()
        call.respondAction(io { jobs.setPriority(id, spec.priority) })
    }
    // A running job stops starting commands and goes back to the queue once the running ones end.
    post("/jobs/{id}/pause") { call.respondAction(io { jobs.setPaused(call.longParam(), true) }) }
    post("/jobs/{id}/resume") { call.respondAction(io { jobs.setPaused(call.longParam(), false) }) }
    // A download's hold on a job's files: the trim leaves the job alone until it is released or lapses ([Hold]).
    // `?ttlSec=` asks for less than the 2 h a hold lasts, never more; a long download renews it instead.
    post("/jobs/{id}/hold") {
        val job = io { call.job(store) }
        call.respond(HttpStatusCode.Created, io { store.holds.take(job.id, holdMs(call.parameters["ttlSec"])) })
    }
    post("/jobs/{id}/hold/{lease}") {
        val job = io { call.job(store) }
        val hold = io { store.holds.renew(job.id, call.parameters["lease"]!!, holdMs(call.parameters["ttlSec"])) }
            ?: throw ApiError.NotFound("no such hold, or it lapsed")
        call.respond(hold)
    }
    delete("/jobs/{id}/hold/{lease}") {
        val job = io { call.job(store) }
        if (!io { store.holds.release(job.id, call.parameters["lease"]!!) }) throw ApiError.NotFound("no such hold")
        call.respond(HttpStatusCode.OK, "released")
    }
}

/** A hold's length from its `ttlSec` parameter: [JobTrim.HOLD_MS] when none is given, and never more. */
private fun holdMs(ttlSec: String?): Long {
    if (ttlSec == null) return JobTrim.HOLD_MS
    val seconds = ttlSec.toLongOrNull()
    require(seconds != null && seconds in 1..JobTrim.HOLD_MS / 1000) { "ttlSec must be 1 to ${JobTrim.HOLD_MS / 1000} seconds" }
    return seconds * 1000
}

private const val DEFAULT_WAIT_SEC = 600L
private const val MAX_WAIT_SEC = 3600L
private const val WAIT_POLL_MS = 2000L
