package com.eignex.lab

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.DefaultJson
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonObject

/** Submitting experiments ([ExperimentService]) and reading their plans, cases and results in every format. */
internal fun Route.experimentRoutes(services: ApiServices) {
    val config = services.config
    val store = services.store
    // An experiment is queued as a job with no commands; the runner plans its cases when it first sets it up.
    post("/experiments") {
        when (val submission = services.experiments.submit(call.receive<JsonObject>())) {
            is Submission.Queued -> call.respond(HttpStatusCode.Created, submission.created)
            is Submission.Refused -> throw ApiError.Conflict(submission.reason)
            is Submission.SplitConflict -> throw ApiError.Conflict(submission.message)
        }
    }
    get("/experiments/{id}/arms") { call.respond(io { store.plans.arms(call.longParam()) }) }
    get("/experiments/{id}/reference") {
        val id = call.longParam()
        call.respond(io {
            val cases = store.plans.cases(id)
            val references = store.references.matching(cases.map { it.problem.referenceKey }, sources = References.sources(cases))
            References.compare(store.plans.arms(id).map { it.arm.label }, cases, references)
        })
    }
    get("/compare") {
        val ids = (call.parameters["jobs"] ?: throw ApiError.Invalid("jobs=<id>,<id>… is required"))
            .split(',').map { it.trim().toLongOrNull() ?: throw ApiError.Invalid("jobs must be numbers") }
        if (ids.size !in 2..MAX_COMPARED) throw ApiError.Invalid("compare 2 to $MAX_COMPARED experiments")
        val html = call.wantsHtml()
        val compared = io {
            val jobs = ids.map { id -> store.job(id) ?: throw ApiError.NotFound("no job $id") }
            // Arms are named by their job, so the same arm of two runs stays two columns.
            val arms = jobs.flatMap { job -> store.plans.arms(job.id).map { it.copy(arm = it.arm.copy(label = "${job.id} ${it.arm.label}")) } }
            val cases = jobs.flatMap { job -> store.plans.cases(job.id).map { it.copy(arm = "${job.id} ${it.arm}") } }
            val references = store.references.matching(cases.map { it.problem.referenceKey }, sources = References.sources(cases))
            if (html) comparePage(config, jobs, arms, cases, references)
            else References.compare(arms.map { it.arm.label }, cases, references)
        }
        // A browser gets the page; an API client the same comparison `/experiments/{id}/reference` gives of one job.
        if (compared is String) call.respondText(compared, ContentType.Text.Html) else call.respond(compared)
    }
    get("/experiments/{id}/metrics.csv") {
        call.respondText(io { BenchRuns.metricsCsv(store.plans.cases(call.longParam())) }, ContentType.Text.CSV)
    }
    get("/experiments/{id}/cases") { call.respond(io { store.plans.cases(call.longParam()) }) }
    // Every case whole, as JSON Lines: its record with its final witness, its build provenance and the lab's verdict
    // as `labCheck`, its producer record as the database keeps it, cut to its time to best ([StoredRecords]). It is
    // read and written a page of cases at a time, so a job of thousands of cases with large witnesses streams out
    // without being held in memory, and the store is free between pages.
    get("/experiments/{id}/cases.jsonl") {
        val id = call.longParam()
        call.respondTextWriter(ContentType("application", "x-ndjson")) {
            var from = 0
            while (true) {
                val page = io { store.plans.cases(id, from, CASES_PAGE) }
                for (case in page) write(DefaultJson.encodeToString(CaseResult.serializer(), case) + "\n")
                flush()
                if (page.size < CASES_PAGE) break
                from = page.last().index + 1
            }
        }
    }
    get("/experiments/{id}/stats") {
        val id = call.longParam()
        call.respond(io { Stats.of(store.plans.arms(id).map { it.arm.label }, store.plans.cases(id)) })
    }
    get("/experiments/{id}/cases.csv") {
        call.respondText(io { Results.casesCsv(store.plans.cases(call.longParam())) }, ContentType.Text.CSV)
    }
    get("/experiments/{id}/bench.csv") {
        val id = call.longParam()
        val arm = call.parameters["arm"] ?: throw ApiError.Invalid("arm is required")
        val seed = call.parameters["seed"]?.let { it.toLongOrNull() ?: throw ApiError.Invalid("seed must be a number") }
        call.respondText(io { Results.benchCsv(store.plans.cases(id), arm, seed) }, ContentType.Text.CSV)
    }
}

private const val MAX_COMPARED = 6

/** Cases `/experiments/{id}/cases.jsonl` reads from the store at once. */
private const val CASES_PAGE = 32
