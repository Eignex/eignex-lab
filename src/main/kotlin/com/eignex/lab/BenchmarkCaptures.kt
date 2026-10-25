package com.eignex.lab

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class BenchmarkMetric(val name: String, val value: Double, val unit: String, val maximum: Double? = null)

@Serializable
data class BenchmarkValidation(val backend: String, val inputSha256: String, val metrics: List<BenchmarkMetric>)

@Serializable
data class BenchmarkRow(
    val case: JsonObject,
    val target: String,
    val status: String,
    val validation: BenchmarkValidation? = null,
    val metrics: List<BenchmarkMetric> = emptyList(),
    val raw: String? = null,
    val reason: String? = null,
) {
    val id: String get() = requireNotNull(case["id"]?.jsonPrimitive?.contentOrNull) { "capture case has no id" }
    val validated: Boolean get() = status == "passed" && validation != null && validation.backend.isNotBlank() &&
        validation.inputSha256.matches(Regex("[0-9a-f]{64}")) && validation.metrics.isNotEmpty() &&
        validation.metrics.all { it.value.isFinite() && (it.maximum == null || it.value <= it.maximum) }
}

@Serializable
data class BenchmarkCapture(
    val repository: String,
    val status: String,
    val manifest: JsonObject,
    val rows: List<BenchmarkRow>,
    val measurement: Measurement = Measurement.NONE,
)

@Serializable
data class BenchmarkDelta(
    val caseId: String,
    val target: String,
    val metric: String,
    val unit: String,
    val baseline: Double? = null,
    val candidate: Double? = null,
    val difference: Double? = null,
    val ratio: Double? = null,
    val baselineError: Double? = null,
    val candidateError: Double? = null,
    val reason: String? = null,
)

@Serializable
private data class BenchmarkResults(val schemaVersion: Int, val status: String, val results: List<BenchmarkRow>)

object BenchmarkCaptures {
    private val json = Json { ignoreUnknownKeys = true }
    private val directory = listOf("cases", "0", "out", "report")
    private const val MAX_REPORT_BYTES = 8L * 1024 * 1024

    fun available(root: Path): Boolean = listOf("manifest.json", "results.json").all { jobFile(root, directory + it) != null }

    fun read(root: Path, repository: String, measurement: Measurement = Measurement.NONE): BenchmarkCapture? {
        val manifest = readFile(root, "manifest.json") ?: return null
        val results = readFile(root, "results.json") ?: return null
        return try {
            val identity = json.parseToJsonElement(manifest).jsonObject
            require(identity["schemaVersion"]?.jsonPrimitive?.intOrNull == 1) { "unsupported benchmark manifest schema" }
            val report = json.decodeFromString<BenchmarkResults>(results)
            require(report.schemaVersion == 1) { "unsupported benchmark results schema" }
            val keys = report.results.map {
                require(it.id.isNotBlank() && it.target.isNotBlank()) { "benchmark case and target must be named" }
                it.id to it.target
            }
            require(keys.distinct().size == keys.size) { "duplicate benchmark case and target" }
            for (row in report.results) {
                for (metrics in listOf(row.metrics, row.validation?.metrics.orEmpty())) {
                    require(metrics.all { it.value.isFinite() && (it.maximum == null || it.maximum.isFinite()) }) { "nonfinite benchmark metric" }
                    require(metrics.map { it.name to it.unit }.distinct().size == metrics.size) { "duplicate benchmark metric" }
                }
            }
            BenchmarkCapture(repository, report.status, identity, report.results, measurement)
        } catch (failure: Exception) {
            throw IllegalArgumentException("invalid benchmark capture: ${failure.message}", failure)
        }
    }

    fun compare(baseline: BenchmarkCapture, candidate: BenchmarkCapture): List<BenchmarkDelta> {
        val commonReason = incompatibleRuns(baseline, candidate)
        val previous = baseline.rows.associateBy { it.id to it.target }
        return candidate.rows.flatMap { row ->
            val before = previous[row.id to row.target]
            val reason = commonReason ?: when {
                before == null -> "case or target is absent from the baseline"
                !before.validated || !row.validated -> "both rows must pass quality validation"
                before.case != row.case -> "case definitions differ"
                caseTiming(baseline, row.id) == null || caseTiming(baseline, row.id) != caseTiming(candidate, row.id) -> "case timing differs or is missing"
                row.target == "native" && (baseline.manifest["nativeBuildSettings"].missing() ||
                    baseline.manifest["nativeBuildSettings"] != candidate.manifest["nativeBuildSettings"]) -> "Native build settings differ or are missing"
                before.validation?.inputSha256 != row.validation?.inputSha256 -> "input fingerprints differ"
                before.validation?.backend != row.validation?.backend -> "resolved backends differ"
                else -> null
            }
            val baseMetrics = before?.let(::comparableMetrics).orEmpty().associateBy { it.name to it.unit }
            val metrics = comparableMetrics(row)
            if (metrics.isEmpty()) listOf(BenchmarkDelta(row.id, row.target, "—", "", reason = reason ?: "no numeric metrics"))
            else metrics.map { metric ->
                val base = baseMetrics[metric.name to metric.unit]
                val refusal = reason ?: if (base == null) "metric or unit is absent from the baseline" else null
                val difference = if (refusal == null) (metric.value - base!!.value).takeIf { it.isFinite() } else null
                val ratio = if (refusal == null && base!!.value != 0.0) (metric.value / base.value).takeIf { it.isFinite() } else null
                val arithmeticReason = when {
                    refusal != null -> refusal
                    difference == null -> "difference exceeds the numeric range"
                    base?.value == 0.0 -> "ratio is undefined for a zero baseline; difference is shown"
                    ratio == null -> "ratio exceeds the numeric range; difference is shown"
                    else -> null
                }
                BenchmarkDelta(
                    row.id, row.target, metric.name, metric.unit,
                    baseline = base?.value,
                    candidate = metric.value,
                    difference = difference,
                    ratio = ratio,
                    baselineError = before?.metrics?.firstOrNull { it.name == "${metric.name}Error" && it.unit == metric.unit }?.value,
                    candidateError = row.metrics.firstOrNull { it.name == "${metric.name}Error" && it.unit == metric.unit }?.value,
                    reason = arithmeticReason,
                )
            }
        }
    }

    private fun comparableMetrics(row: BenchmarkRow): List<BenchmarkMetric> =
        row.metrics.filter { it.name in setOf("time", "allocation", "throughput", "threadScaling", "parallelEfficiency") } +
            row.validation?.metrics.orEmpty().map { it.copy(name = "quality/${it.name}") }

    private fun incompatibleRuns(baseline: BenchmarkCapture, candidate: BenchmarkCapture): String? {
        if (baseline.measurement != Measurement.NONE || candidate.measurement != Measurement.NONE) return "Lab recordings must be disabled for performance comparisons"
        if (baseline.repository != candidate.repository) return "repositories differ"
        if (baseline.status != "passed" || candidate.status != "passed") return "both captures must be complete passes"
        val left = baseline.manifest
        val right = candidate.manifest
        if (listOf(left, right).any { it["workingTreeStatus"]?.jsonPrimitive?.contentOrNull != "" }) return "source must be committed with a clean working tree"
        if (listOf(left, right).any { it["revision"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() }) return "source revision is missing"
        val leftOptions = left["options"] as? JsonObject ?: return "baseline settings are missing"
        val rightOptions = right["options"] as? JsonObject ?: return "candidate settings are missing"
        if (leftOptions["preset"]?.jsonPrimitive?.contentOrNull == "smoke" || rightOptions["preset"]?.jsonPrimitive?.contentOrNull == "smoke") return "smoke captures verify wiring and are not performance baselines"
        for (key in listOf("preset", "mode", "allocation")) {
            if (leftOptions[key] == null || leftOptions[key] != rightOptions[key]) return "capture $key settings differ or are missing"
        }
        for (key in listOf("instrumented", "jmhVersion")) {
            if (left[key].missing() || left[key] != right[key]) return "$key differs or is missing"
        }
        val leftRuntime = left["runtime"] as? JsonObject ?: return "baseline hardware is missing"
        val rightRuntime = right["runtime"] as? JsonObject ?: return "candidate hardware is missing"
        for (key in listOf("javaVersion", "javaVendor", "javaArguments", "os", "architecture", "availableProcessors", "cpu", "memory", "affinity")) {
            val value = leftRuntime[key]?.jsonPrimitive?.contentOrNull
            if (value == null || (key != "javaArguments" && value.isBlank()) || value == "unavailable" || leftRuntime[key] != rightRuntime[key]) return "runtime/hardware $key differs or is unknown"
        }
        return null
    }

    private fun caseTiming(capture: BenchmarkCapture, id: String) =
        (capture.manifest["caseTimings"] as? JsonObject)?.get(id)?.takeUnless { it.missing() }
            ?: capture.manifest["timing"]?.takeUnless { it.missing() }

    private fun JsonElement?.missing() = this == null || this == JsonNull

    private fun readFile(root: Path, name: String): String? {
        val file = jobFile(root, directory + name) ?: return null
        require(Files.size(file) <= MAX_REPORT_BYTES) { "benchmark report is too large" }
        return Files.readString(file)
    }
}
