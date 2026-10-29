package com.eignex.lab

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * The operator's view of Nomad capacity and the manual capacity leases, behind the API's password and same-site gate:
 * no worker route can create a lease.
 */
internal fun Route.capacityRoutes(services: ApiServices) {
    val config = services.config
    val store = services.store
    get("/capacity") {
        call.respond(io { readCapacityReport(config) } ?: throw ApiError.Unavailable("Nomad capacity has not reported"))
    }
    get("/nodes") {
        val page = io {
            nodesPage(config, services.host, readCapacityReport(config), store.execution.capacityLeases(), services.capacitySettings.get(),
                readResultJournalReport(config.dataDir))
        }
        call.respondText(page, ContentType.Text.Html)
    }
    get("/journal") {
        call.respond(io { readResultJournalReport(config.dataDir) } ?: throw ApiError.Unavailable("Result journal has not reported"))
    }
    get("/capacity/view") { call.respondRedirect("/nodes") }
    get("/capacity/leases") { call.respond(io { store.execution.capacityLeases() }) }
    post("/capacity/leases") {
        if (config.nomadCapacitySettings == null) throw ApiError.Conflict("Nomad capacity is not configured")
        val settings = services.capacitySettings.get() ?: throw ApiError.Unavailable("the Nomad capacity settings could not be read")
        val request = call.receive<CapacityRequest>()
        val pool = settings.pools.singleOrNull { it.id == request.pool } ?: throw ApiError.NotFound("unknown pool ${request.pool}")
        require(request.nodes in 1..pool.maxNodes && request.ttlSec in 1..3600) { "capacity requests must fit the pool limit and expire within one hour" }
        require(request.nodes.toLong() * pool.vcpus <= settings.maxVcpus) { "capacity request exceeds the global vCPU limit" }
        call.respond(HttpStatusCode.Accepted, io { store.execution.requestCapacity(pool.id, request.nodes, request.ttlSec * 1000) })
    }
    delete("/capacity/leases/{id}") {
        val id = call.parameters["id"]!!
        if (!io { store.execution.releaseCapacity(id) }) throw ApiError.NotFound("no such lease")
        call.respond(HttpStatusCode.NoContent)
    }
    get("/jobs/{id}/execution") {
        val job = io { call.job(store) }
        val reports = io { store.execution.reports(job.id) }
        if (call.wantsHtml()) call.respondText(executionPage(job.id, reports), ContentType.Text.Html) else call.respond(reports)
    }
}
