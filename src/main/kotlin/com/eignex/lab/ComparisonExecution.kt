package com.eignex.lab

import kotlinx.serialization.json.*

/** Accepted worker observations, separate from repository-reported controls and changing resource counters. */
internal object ComparisonExecution {
    fun record(text: String?, observation: ResourceObservation?, receipt: String?, release: String): String? {
        val record = text?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return text
        if ("comparisonControls" !in record && (record["solver"] as? JsonPrimitive)?.content != "klause") return text
        val clean = record - "labExecution"
        if (observation == null || receipt == null) return JsonObject(clean).toString()
        val frame = buildJsonObject {
            put("enforcement", observation.enforcement)
            put("budget", Json.encodeToJsonElement(ResourceBudget.serializer(), observation.budget))
            observation.cpuMax?.let { put("cpuMax", it) }
            observation.memoryMax?.let { put("memoryMax", it) }
            observation.cpuIdentity?.let { put("cpuIdentity", it) }
            observation.iterationParallel?.let { put("iterationParallel", it) }
            observation.iterationCases?.let { put("iterationCases", it) }
            put("javaVersion", observation.javaVersion)
            put("os", observation.os)
            put("arch", observation.arch)
            put("release", release)
        }
        return JsonObject(clean + ("labExecution" to buildJsonObject {
            put("policy", "worker-receipt-v1")
            put("receiptSha256", receipt)
            put("frame", frame)
        })).toString()
    }

    fun frame(record: JsonObject): JsonObject? {
        val receipt = record["labExecution"] as? JsonObject ?: return null
        if ((receipt["policy"] as? JsonPrimitive)?.content != "worker-receipt-v1" ||
            (receipt["receiptSha256"] as? JsonPrimitive)?.content?.matches(SHA256) != true) return null
        val frame = receipt["frame"] as? JsonObject ?: return null
        if (REQUIRED.any { (frame[it] as? JsonPrimitive)?.content.let { value -> value.isNullOrBlank() || value == "null" } }) return null
        if ((frame["enforcement"] as? JsonPrimitive)?.content != "cgroup-v2-hard" ||
            (frame["cpuIdentity"] as? JsonPrimitive)?.content?.matches(SHA256) != true) return null
        val budget = frame["budget"] as? JsonObject ?: return null
        val cpu = (budget["cpuMillis"] as? JsonPrimitive)?.intOrNull ?: return null
        val memory = (budget["memoryMiB"] as? JsonPrimitive)?.intOrNull ?: return null
        val parallel = (budget["parallel"] as? JsonPrimitive)?.intOrNull ?: return null
        val iteration = frame["iterationParallel"]?.jsonPrimitive?.intOrNull ?: return null
        val cases = frame["iterationCases"]?.jsonPrimitive?.intOrNull ?: return null
        val quota = frame["cpuMax"]?.jsonPrimitive?.content?.split(Regex("\\s+")) ?: return null
        val ticks = quota.getOrNull(0)?.toLongOrNull() ?: return null
        val period = quota.getOrNull(1)?.toLongOrNull() ?: return null
        if (cpu <= 0 || memory <= 0 || parallel <= 0 || iteration !in 1..parallel || cases <= 0 || quota.size != 2 ||
            ticks <= 0 || period <= 0 || frame["memoryMax"]?.jsonPrimitive?.longOrNull != memory * 1024L * 1024) return null
        val actual = ticks.toDouble() / period * 1000
        if (actual < cpu * .95 || actual > cpu + .01) return null
        return frame
    }

    fun series(record: JsonObject): String = frame(record)?.let { ExecutionStore.digest(it.toString()) } ?: "execution-unqualified"

    private val REQUIRED = setOf("enforcement", "cpuMax", "memoryMax", "cpuIdentity", "iterationParallel", "iterationCases",
        "javaVersion", "os", "arch", "release")
    private val SHA256 = Regex("[0-9a-f]{64}")
}
