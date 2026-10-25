package com.eignex.lab

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Decode pre-measurement database records without accepting retired keys in new submissions. */
internal fun storedExperiment(text: String): ExperimentSpec {
    val body = Json.parseToJsonElement(text).jsonObject
    val retired = setOf("profileCli", "measureCliResources", "profileCompilation")
    if (retired.none { it in body }) return Json.decodeFromJsonElement(ExperimentSpec.serializer(), body)
    fun enabled(key: String): Boolean = body[key]?.takeUnless { it == JsonNull }?.let {
        requireNotNull(it.jsonPrimitive.booleanOrNull) { "stored $key must be boolean" }
    } ?: false
    val compilation = body["profileCompilation"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
    val mode = when (compilation) {
        "jfr" -> Measurement.COMPILATION
        "jfr+log" -> Measurement.COMPILATION_LOG
        null -> when {
            enabled("profileCli") -> Measurement.JFR
            enabled("measureCliResources") -> Measurement.RESOURCES
            else -> Measurement.NONE
        }
        else -> error("unknown stored compilation mode: $compilation")
    }
    val canonical = body - retired + if ("measurement" in body) emptyMap() else mapOf(
        "measurement" to Json.encodeToJsonElement(Measurement.serializer(), mode),
    )
    return Json.decodeFromJsonElement(ExperimentSpec.serializer(), JsonObject(canonical))
}

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
