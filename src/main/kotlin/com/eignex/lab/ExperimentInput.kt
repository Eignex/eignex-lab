package com.eignex.lab

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** Recording modes run one case at a time unless the caller explicitly supplies a parallel limit to validate. */
internal fun experimentInput(body: JsonObject): JsonObject {
    val input = body["measurement"] ?: return body
    val mode = Json.decodeFromJsonElement(Measurement.serializer(), input)
    return if (mode != Measurement.NONE && "parallel" !in body) JsonObject(body + ("parallel" to JsonPrimitive(1))) else body
}

/** Schedules use exactly the same input vocabulary as immediate runs. */
internal fun scheduleInput(body: JsonObject): JsonObject {
    val experiment = body["experiment"] ?: return body
    if (experiment == JsonNull) return body
    require(experiment is JsonObject) { "experiment must be an object" }
    return JsonObject(body + ("experiment" to experimentInput(experiment)))
}
