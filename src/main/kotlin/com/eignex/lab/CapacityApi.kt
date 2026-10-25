package com.eignex.lab

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import kotlinx.serialization.json.*
import io.ktor.server.routing.*

/** Read-only collector endpoints use their own secret; a worker credential grants no capacity access. */
fun Application.capacityApi(collector: CapacityCollector) {
    routing {
        route("/capacity") {
            intercept(io.ktor.server.application.ApplicationCallPipeline.Call) {
                val token = call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ").orEmpty()
                if (!collector.authorized(token)) { call.respond(HttpStatusCode.Unauthorized); finish() }
            }
            suspend fun ApplicationCall.query(value: String?) {
                val pool = requireNotNull(Regex("lab_capacity_desired_nodes\\{pool=\"([a-z][a-z0-9-]{0,47})\"\\}").matchEntire(value.orEmpty())) { "unsupported capacity query" }.groupValues[1]
                val sample = collector.snapshot()
                val count = requireNotNull(sample.decision.desired[pool]) { "unknown pool" }
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
            get("/snapshot") { call.respond(collector.snapshot()) }
            get("/metrics") { call.respondText(collector.metrics(), ContentType.Text.Plain) }
        }
    }
}
