package com.eignex.lab

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** Read historical recording and placement fields without accepting those fields in new submissions. */
internal fun storedExperiment(text: String): ExperimentSpec {
    // Placement bookkeeping a historical recipe may carry; the lab no longer reads it.
    val body = JsonObject(Json.parseToJsonElement(text).jsonObject - setOf("auto", "machines"))
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

private val RETIRED_PLACEMENT = setOf("nodeTags", "host", "auto", "machines")

internal fun inputTags(value: JsonElement?): NodeSelector {
    if (value == null) return NodeSelector()
    require(value is JsonArray && value.all { it is JsonPrimitive && it.isString }) { "tags must be an array of strings; prefix exclusions with !" }
    return NodeTags.selector(value.map { it.jsonPrimitive.content })
}

/** New submissions have one selector field; the internal recipe format remains compatible with pinned workers. */
internal fun experimentInput(body: JsonObject): JsonObject {
    val retired = body.keys.intersect(RETIRED_PLACEMENT)
    require(retired.isEmpty()) { "unsupported placement fields: ${retired.sorted().joinToString()}; use tags: [\"arch:x86_64\", \"!spot\"], or omit tags for auto placement" }
    val selector = inputTags(body["tags"])
    var canonical = JsonObject((body - "tags") + if (NodeTags.labels(selector).isEmpty()) emptyMap() else
        mapOf("nodeTags" to Json.encodeToJsonElement(NodeSelector.serializer(), selector)))
    // Recording modes default to one case at a time.
    val input = canonical["measurement"] ?: return canonical
    val mode = Json.decodeFromJsonElement(Measurement.serializer(), input)
    if (mode != Measurement.NONE && "parallel" !in canonical) canonical = JsonObject(canonical + ("parallel" to JsonPrimitive(1)))
    return canonical
}

/** Schedules use exactly the same input vocabulary as immediate runs. */
internal fun scheduleInput(body: JsonObject): JsonObject {
    val experiment = body["experiment"] ?: return body
    if (experiment == JsonNull) return body
    require(experiment is JsonObject) { "experiment must be an object" }
    val canonical = experimentInput(experiment)
    val selector = canonical["nodeTags"]?.let { Json.decodeFromJsonElement(NodeSelector.serializer(), it) }
    val local = "local" in selector?.require.orEmpty() || "cloud" in selector?.exclude.orEmpty()
    // Preserve the existing local-schedule recipe and fingerprint when converting its public location tag.
    val retained = if (local) selector?.copy(require = selector.require - "local", exclude = selector.exclude - "cloud") else selector
    val fields = (canonical - "nodeTags") + if (NodeTags.labels(retained).isEmpty()) emptyMap() else
        mapOf("nodeTags" to Json.encodeToJsonElement(NodeSelector.serializer(), checkNotNull(retained)))
    return JsonObject(body + ("experiment" to JsonObject(fields +
        ("host" to JsonPrimitive(if (local) Experiments.LAB_HOST else Experiments.AUTO_HOST)))))
}

/** Schedule responses can be edited and submitted again using the same public vocabulary. */
internal fun scheduleExperiment(spec: ExperimentSpec): JsonObject {
    val selector = if (Experiments.storedHost(spec.host) == Experiments.LAB_HOST)
        (spec.nodeTags ?: NodeSelector()).let { it.copy(require = it.require + "local") } else spec.nodeTags
    val labels = NodeTags.labels(selector)
    val body = Json.encodeToJsonElement(ExperimentSpec.serializer(), spec).jsonObject - RETIRED_PLACEMENT
    return JsonObject(body + if (labels.isEmpty()) emptyMap() else
        mapOf("tags" to JsonArray(labels.map(::JsonPrimitive))))
}
