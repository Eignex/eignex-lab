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
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** The reference rows: searching them with the scoreboard, retiring a solver's, and one problem's runs. */
internal fun Route.referenceRoutes(services: ApiServices) {
    val config = services.config
    val store = services.store
    get("/references") {
        fun param(name: String) = call.parameters[name]?.takeIf { it.isNotBlank() }
        val filter = ReferenceFilter(
            text = param("q"),
            solver = param("solver"),
            collection = param("collection"),
            verdict = param("verdict")?.let { v -> ReferenceVerdict.entries.firstOrNull { it.name.equals(v, ignoreCase = true) } },
        )
        val (found, total) = io { if (filter.isEmpty) emptyList<Pair<ProblemKey, Reference>>() to 0 else store.references.search(filter, SEARCH_LIMIT) }
        if (call.wantsHtml()) {
            val page = io {
                val board = services.references.scoreboard(call.parameters)
                referencesPage(services.references.coverage(), filter, found, total, services.references.conflicts(), board)
            }
            call.respondHtml(page)
        } else {
            call.respond(found.map { (key, r) -> ReferenceHit(key.collection, key.problem, r) })
        }
    }
    // Retire a solver's rows on a collection, of one budget if given ([ReferenceRepository.retire]): kept as history,
    // left out of every comparison, and replaced by the solver's next row on each problem.
    post("/references/retire") {
        val spec = call.receive<RetireSpec>()
        require(spec.collection.isNotBlank() && spec.solver.isNotBlank()) { "collection and solver are required" }
        require(spec.budgetMs == null || spec.budgetMs > 0) { "budgetMs must be positive" }
        val retired = io { store.references.retire(spec.collection, spec.solver, spec.budgetMs) }
        println("api: retired $retired reference rows of ${spec.solver} on ${spec.collection}" + (spec.budgetMs?.let { " at $it ms" } ?: ""))
        call.respond(RetireResult(retired))
    }
    get("/problem") {
        val collection = call.parameters["collection"] ?: throw ApiError.Invalid("collection is required")
        val problem = call.parameters["problem"] ?: throw ApiError.Invalid("problem is required")
        // Each case's record is read whole, so a problem many jobs ran (hundreds of cases) shows its newest unless
        // all are asked for.
        val limit = if (call.parameters["all"] != null) null else PROBLEM_RUNS
        val report = io {
            val runs = store.problemRuns(collection, problem, limit)
            val total = if (limit != null && runs.size >= limit) store.problemRunCount(collection, problem) else runs.size
            ProblemReport(collection, problem, store.references.rows(collection, problem), runs, total)
        }
        if (call.wantsHtml()) call.respondHtml(problemPage(report, config.repoUrl)) else call.respond(report)
    }
}

/** The newest cases the problem page shows unless `all` is asked for. */
private const val PROBLEM_RUNS = 20
