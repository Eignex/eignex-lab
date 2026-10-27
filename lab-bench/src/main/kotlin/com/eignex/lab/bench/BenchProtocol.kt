package com.eignex.lab.bench

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** JSON contract shared by benchmark processes and their callers. */
public object BenchProtocol {
    /** Major protocol version. Incompatible versions must be rejected before executing work. */
    public const val VERSION: Int = 1

    /** Defaults are written explicitly; unknown fields allow additive protocol extensions. */
    public val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    internal fun requireVersion(version: Int) {
        require(version == VERSION) { "Unsupported bench protocol version $version; expected $VERSION" }
    }
}

/** Repository-owned suite identity, independent of its executable or build layout. */
@Serializable
public data class BenchDescriptor(
    public val id: String,
    public val description: String,
    public val protocolVersion: Int = BenchProtocol.VERSION,
) {
    init {
        BenchProtocol.requireVersion(protocolVersion)
        require(id.isNotBlank()) { "Suite id must not be blank" }
    }
}

/** Suite-specific selection parameters. Their names and meaning belong to the repository. */
@Serializable
public data class BenchRequest(
    public val parameters: Map<String, String> = emptyMap(),
    public val protocolVersion: Int = BenchProtocol.VERSION,
) {
    init { BenchProtocol.requireVersion(protocolVersion) }
}

/** Resource requirements for placement and outer process limits; the SDK does not enforce them. */
@Serializable
public data class BenchResources(
    public val cpus: Int = 1,
    public val memoryMiB: Long = 1024,
    public val timeoutSeconds: Long = 600,
    public val requiredTags: Set<String> = emptySet(),
) {
    init {
        require(cpus > 0) { "cpus must be positive" }
        require(memoryMiB > 0) { "memoryMiB must be positive" }
        require(timeoutSeconds > 0) { "timeoutSeconds must be positive" }
        require(requiredTags.none(String::isBlank)) { "Resource tags must not be blank" }
    }
}

/** One independently executable workload. Parameters should pin seeds, backends and measurement settings. */
@Serializable
public data class BenchCase @JvmOverloads constructor(
    public val id: String,
    public val parameters: Map<String, String> = emptyMap(),
    public val resources: BenchResources = BenchResources(),
    public val protocolVersion: Int = BenchProtocol.VERSION,
    public val inputs: List<BenchInput> = emptyList(),
    public val attributes: Map<String, String> = emptyMap(),
) {
    init {
        BenchProtocol.requireVersion(protocolVersion)
        require(id.isNotBlank()) { "Case id must not be blank" }
    }
}

/** Frozen selection with unique case identities, suitable for retention and replay. */
@Serializable
public data class BenchPlan(
    public val cases: List<BenchCase>,
    public val protocolVersion: Int = BenchProtocol.VERSION,
) {
    init {
        BenchProtocol.requireVersion(protocolVersion)
        require(cases.map { it.id }.distinct().size == cases.size) { "Case ids must be unique within a plan" }
    }
}

/** Whether execution produced measurements, was deliberately skipped, or failed. */
@Serializable
public enum class BenchStatus {
    @SerialName("success") SUCCESS,
    @SerialName("skipped") SKIPPED,
    @SerialName("failed") FAILED,
}

/** Comparison direction for a metric; NONE makes no claim about which value is preferable. */
@Serializable
public enum class MetricDirection {
    @SerialName("none") NONE,
    @SerialName("lower") LOWER,
    @SerialName("higher") HIGHER,
}

/** A finite reported measurement. The suite owns its unit, instrumentation and statistical meaning. */
@Serializable
public data class BenchMetric(
    public val name: String,
    public val value: Double,
    public val unit: String,
    public val direction: MetricDirection = MetricDirection.NONE,
) {
    init {
        require(name.isNotBlank()) { "Metric name must not be blank" }
        require(value.isFinite()) { "Metric value must be finite" }
        require(unit.isNotBlank()) { "Metric unit must not be blank" }
    }
}

/** A retained file, named relative to the case output directory with portable slash separators. */
@Serializable
public data class BenchArtifact(
    public val path: String,
    public val mediaType: String? = null,
    public val description: String = "",
) {
    init {
        require(path.isNotBlank() && '\\' !in path && ':' !in path &&
            path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "Artifact path must stay inside the output directory: $path"
        }
        require(path != "result.json") { "result.json is reserved for the execution result" }
    }
}

/** Generic measurements plus optional domain-specific data; this is not a solver verdict schema. */
@Serializable
public data class BenchResult(
    public val caseId: String,
    public val status: BenchStatus = BenchStatus.SUCCESS,
    public val metrics: List<BenchMetric> = emptyList(),
    public val artifacts: List<BenchArtifact> = emptyList(),
    public val details: JsonObject? = null,
    public val message: String? = null,
    public val protocolVersion: Int = BenchProtocol.VERSION,
) {
    init {
        BenchProtocol.requireVersion(protocolVersion)
        require(caseId.isNotBlank()) { "Result case id must not be blank" }
        require(metrics.map { it.name }.distinct().size == metrics.size) { "Metric names must be unique" }
        require(artifacts.map { it.path }.distinct().size == artifacts.size) { "Artifact paths must be unique" }
        require(status == BenchStatus.SUCCESS || !message.isNullOrBlank()) { "Skipped and failed results need a reason" }
    }
}
