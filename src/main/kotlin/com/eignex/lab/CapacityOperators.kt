package com.eignex.lab

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files

@Serializable data class CapacityRequest(val pool: String, val nodes: Int, val ttlSec: Long = 900)

/** Installed inside the ordinary API's password and same-site gate. No worker route can create a lease. */
internal fun Application.operatorCapacityApi(config: Config, store: Store, host: HostReport) {
    routing {
        get("/capacity") {
            val snapshot = readCapacityReport(config)
            if (snapshot == null) call.respond(HttpStatusCode.ServiceUnavailable, "Nomad capacity has not reported") else call.respond(snapshot)
        }
        get("/nodes") {
            val settings = runCatching { config.nomadCapacitySettings?.let { Json.decodeFromString<CapacitySettings>(Files.readString(it)) } }.getOrNull()
            call.respondText(nodesPage(config, host, readCapacityReport(config), store.execution.capacityLeases(), settings, readResultJournalReport(config)), ContentType.Text.Html)
        }
        get("/journal") {
            val report = readResultJournalReport(config)
            if (report == null) call.respond(HttpStatusCode.ServiceUnavailable, "Result journal has not reported") else call.respond(report)
        }
        get("/capacity/view") { call.respondRedirect("/nodes") }
        get("/capacity/leases") { call.respond(store.execution.capacityLeases()) }
        post("/capacity/leases") {
            val path = requireNotNull(config.nomadCapacitySettings) { "Nomad capacity is not configured" }
            val settings = Json.decodeFromString<CapacitySettings>(Files.readString(path))
            val request = call.receive<CapacityRequest>()
            val pool = requireNotNull(settings.pools.singleOrNull { it.id == request.pool }) { "unknown pool" }
            require(request.nodes in 1..pool.maxNodes && request.ttlSec in 1..3600) { "capacity requests must fit the pool limit and expire within one hour" }
            require(request.nodes.toLong() * pool.vcpus <= settings.maxVcpus) { "capacity request exceeds the global vCPU limit" }
            call.respond(HttpStatusCode.Accepted, store.execution.requestCapacity(pool.id, request.nodes, request.ttlSec * 1000))
        }
        delete("/capacity/leases/{id}") {
            if (store.execution.releaseCapacity(call.parameters["id"]!!)) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound)
        }
        get("/jobs/{id}/execution") {
            val id = call.parameters["id"]!!.toLong()
            if (store.job(id) == null) call.respond(HttpStatusCode.NotFound)
            else if (call.request.headers[HttpHeaders.Accept]?.contains("text/html") == true)
                call.respondText(executionPage(id, store.execution.reports(id)), ContentType.Text.Html)
            else call.respond(store.execution.reports(id))
        }
    }
}

internal fun readCapacityReport(config: Config): CapacitySnapshot? = runCatching {
    Json.decodeFromString<CapacitySnapshot>(Files.readString(config.nomadCapacityReport)).let { it.copy(ageMs = (now() - it.generatedAt).coerceAtLeast(0)) }
}.getOrNull()

@Serializable
data class ExecutionSessionReport(val session: ExecutionSession, val phase: String?, val generation: Int?, val committed: Int, val observation: String?, val observedAt: Long?)
