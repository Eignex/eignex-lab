package com.eignex.lab

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Expand the public measurement mode into the existing stored fields, preserving old jobs and fingerprints. */
internal fun experimentInput(body: JsonObject): JsonObject {
    val input = body["measurement"] ?: return body
    val mode = (input as? JsonPrimitive)?.takeIf { it.isString }?.content
    require(mode in setOf("none", "resources", "jfr", "compilation", "compilation+log")) {
        "measurement must be none, resources, jfr, compilation or compilation+log"
    }
    require(body["profile"] == null || body["profile"] == JsonNull) {
        "measurement selects experiment CLI recording; a profile entry point controls its own recordings"
    }
    val fields = mapOf(
        "profileCli" to JsonPrimitive(mode in setOf("jfr", "compilation", "compilation+log")),
        "measureCliResources" to JsonPrimitive(mode == "resources"),
        "profileCompilation" to when (mode) {
            "compilation" -> JsonPrimitive("jfr")
            "compilation+log" -> JsonPrimitive("jfr+log")
            else -> JsonNull
        },
    )
    for ((key, value) in fields) require(key !in body || body[key] == value) {
        "$key conflicts with measurement=$mode; use measurement alone or matching legacy settings"
    }
    return JsonObject(body - "measurement" + fields +
        if (mode != "none" && "parallel" !in body) mapOf("parallel" to JsonPrimitive(1)) else emptyMap())
}

/** Schedules use exactly the same input vocabulary as immediate runs. */
internal fun scheduleInput(body: JsonObject): JsonObject {
    val experiment = body["experiment"] ?: return body
    if (experiment == JsonNull) return body
    require(experiment is JsonObject) { "experiment must be an object" }
    return JsonObject(body + ("experiment" to experimentInput(experiment)))
}
