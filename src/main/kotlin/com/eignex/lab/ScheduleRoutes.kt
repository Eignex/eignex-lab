package com.eignex.lab

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** The schedules: creating, listing, changing and deleting them, and running one now ([ScheduleService]). */
internal fun Route.scheduleRoutes(services: ApiServices) {
    val schedules = services.schedules
    post("/schedules") {
        val id = schedules.create(call.receive<JsonObject>())
        call.respond(HttpStatusCode.Created, Created(id))
    }
    get("/schedules") {
        call.respond(JsonArray(io { services.store.schedules.all() }.map { schedule ->
            JsonObject(Json.encodeToJsonElement(Schedule.serializer(), schedule).jsonObject +
                ("experiment" to scheduleExperiment(schedule.experiment)) +
                schedule.blockedReason()?.let { mapOf("blockedReason" to JsonPrimitive(it)) }.orEmpty())
        }))
    }
    post("/schedules/{id}") {
        val id = call.longParam()
        val body = call.receive<JsonObject>()
        call.respondAction(io { schedules.update(id, body) })
    }
    post("/schedules/{id}/check") { call.respondAction(io { schedules.checkNow(call.longParam()) }) }
    post("/schedules/{id}/launch") { call.respondAction(io { schedules.launchNow(call.longParam()) }) }
    post("/schedules/{id}/delete") { call.respondAction(io { schedules.delete(call.longParam()) }) }
}
