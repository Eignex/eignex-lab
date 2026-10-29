package com.eignex.lab

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import kotlinx.serialization.json.*
import io.ktor.server.routing.*
import kotlinx.coroutines.withContext

/**
 * Private capacity endpoints use their own secret; a worker credential grants no capacity access. They answer their
 * own failures, as [ErrorBody] JSON, before the worker listener's error pages could see them: a bad query is 400, an
 * unknown pool 404, and a collector that cannot answer 503.
 */
fun Application.capacityApi(collector: CapacityCollector, journal: (() -> ResultJournal.Status)? = null) {
    routing {
        route("/capacity") {
            intercept(io.ktor.server.application.ApplicationCallPipeline.Call) {
                withContext(CoordinatorIo.capacity) {
                    val token = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ").orEmpty()
                    if (!collector.authorized(token)) { call.respond(HttpStatusCode.Unauthorized); finish() }
                    else try {
                        proceed()
                    } catch (e: ApiError) {
                        call.respond(e.status, ErrorBody(e.message ?: e.status.description)); finish()
                    } catch (e: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, ErrorBody(e.message ?: "invalid capacity request")); finish()
                    } catch (e: IllegalStateException) {
                        call.respond(HttpStatusCode.ServiceUnavailable, ErrorBody("capacity unavailable", e.message)); finish()
                    }
                }
            }
            suspend fun ApplicationCall.query(value: String?) {
                val pool = requireNotNull(Regex("lab_capacity_desired_nodes\\{pool=\"([a-z][a-z0-9-]{0,47})\"\\}").matchEntire(value.orEmpty())) { "unsupported capacity query" }.groupValues[1]
                val sample = collector.snapshot()
                val count = sample.decision.desired[pool] ?: throw ApiError.NotFound("unknown pool $pool")
                respond(buildJsonObject {
                    put("status", "success")
                    putJsonObject("data") {
                        put("resultType", "vector")
                        putJsonArray("result") { add(buildJsonObject {
                            putJsonObject("metric") { put("__name__", "lab_capacity_desired_nodes"); put("pool", pool) }
                            putJsonArray("value") { add(JsonPrimitive(sample.generatedAt / 1000.0)); add(JsonPrimitive(count.toString())) }
                        }) }
                    }
                })
            }
            get("/api/v1/query") { call.query(call.request.queryParameters["query"]) }
            post("/api/v1/query") { call.query(call.receiveParameters()["query"]) }
            get("/grant/{pool}") { call.respond(collector.grant(requireNotNull(call.parameters["pool"]))) }
            get("/snapshot") { call.respond(collector.snapshot()) }
            get("/journal") {
                val status = journal?.invoke()
                if (status == null) call.respond(HttpStatusCode.ServiceUnavailable) else call.respond(ResultJournalReport(now(), status))
            }
            get("/metrics") { call.respondText(collector.metrics() + journal?.invoke()?.metrics().orEmpty(), ContentType.Text.Plain) }
        }
    }
}
