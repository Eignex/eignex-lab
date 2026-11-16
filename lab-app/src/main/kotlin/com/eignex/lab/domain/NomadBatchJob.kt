package com.eignex.lab.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class NomadPlatform {
    @SerialName("mac") MAC,
    @SerialName("linux") LINUX,
}

/**
 * Experimental, operator-supplied session definition. This is a job renderer, not an experiment submission API or
 * the future trusted work manifest. It deliberately has no provider, registry credentials, or instance type.
 */
@Serializable
data class NomadBatchJob(
    val id: String,
    val platform: NomadPlatform,
    val nodeClass: String,
    val cpuCapacity: NomadCpuCapacity,
    val budget: ResourceBudget,
    val command: List<String>,
    val image: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val datacenter: String = "lab-prototype",
    val schemaVersion: Int = 1,
    val networkMode: String? = null,
    val statusHostVolume: String? = null,
    val excludedNodes: Set<String> = emptySet(),
    val nativeCapacity: ResourceBudget? = null,
    val cacheHostVolume: String? = null,
    val perHostParallel: Int? = null,
) {
    init {
        require(perHostParallel == null || perHostParallel > 0)
        require(schemaVersion == 1) { "unsupported Nomad session schema: $schemaVersion" }
        require(ID.matches(id)) { "invalid Nomad job ID" }
        require(excludedNodes.size <= 1024 && excludedNodes.all { ID.matches(it) }) { "invalid excluded node IDs" }
        require(nodeClass.isNotBlank() && datacenter.isNotBlank()) { "nodeClass and datacenter are required" }
        require(command.isNotEmpty() && command.first().isNotBlank()) { "command must contain an executable" }
        require(command.none { '\u0000' in it }) { "command contains NUL" }
        require(environment.keys.none { it.startsWith("NOMAD_") || it.startsWith("LAB_SESSION_") }) {
            "environment cannot override Nomad or session budget variables"
        }
        when (platform) {
            NomadPlatform.LINUX -> require(image != null && IMAGE.matches(image)) { "Linux image must be pinned with @sha256:<digest>" }
            NomadPlatform.MAC -> require(image == null) { "native Mac sessions do not use a container image" }
        }
        require(networkMode == null || (platform == NomadPlatform.LINUX && networkMode == "host"))
        require(statusHostVolume == null || (platform == NomadPlatform.LINUX && statusHostVolume == "lab-status"))
        require(cacheHostVolume == null || platform == NomadPlatform.LINUX && cacheHostVolume == "lab-cache")
        require(nativeCapacity == null || platform == NomadPlatform.MAC && budget.cpuMillis <= nativeCapacity.cpuMillis && budget.memoryMiB <= nativeCapacity.memoryMiB)
        budget.nomadCpuMHz(cpuCapacity)
    }

    companion object {
        val json = Json { prettyPrint = true }
        private val ID = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")
        private val IMAGE = Regex("[^\\s]+@sha256:[0-9a-f]{64}")
    }
}
