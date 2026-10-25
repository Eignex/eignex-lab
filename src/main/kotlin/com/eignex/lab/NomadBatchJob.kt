package com.eignex.lab

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

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
) {
    init {
        require(schemaVersion == 1) { "unsupported Nomad session schema: $schemaVersion" }
        require(ID.matches(id)) { "invalid Nomad job ID" }
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
        budget.nomadCpuMHz(cpuCapacity)
    }

    /** Nomad's native Job JSON, accepted by `nomad job run -json`; the registration API wraps this in `Job`. */
    fun render(): JsonObject = buildJsonObject {
        put("ID", id)
        put("Name", id)
        put("Type", "batch")
        putJsonArray("Datacenters") { add(JsonPrimitive(datacenter)) }
        putJsonObject("Meta") {
            put("lab_session_schema", schemaVersion.toString())
            put("lab_cpu_millis", budget.cpuMillis.toString())
            put("lab_memory_mib", budget.memoryMiB.toString())
            put("lab_cpu_enforcement", if (platform == NomadPlatform.LINUX) "hard-quota" else "advisory-native")
        }
        put("Constraints", buildJsonArray {
            add(constraint("${'$'}{node.class}", nodeClass))
            add(constraint("${'$'}{attr.kernel.name}", if (platform == NomadPlatform.LINUX) "linux" else "darwin"))
            // A job converted for one pool must not land on a node with a different quota denominator.
            add(constraint("${'$'}{attr.cpu.usablecompute}", cpuCapacity.usableMHz.toString()))
            add(constraint("${'$'}{attr.cpu.numcores}", cpuCapacity.logicalCpus.toString()))
        })
        putJsonArray("TaskGroups") {
            add(buildJsonObject {
                put("Name", "session")
                put("Count", 1)
                putJsonObject("RestartPolicy") {
                    put("Attempts", 0)
                    put("Mode", "fail")
                    put("Interval", 86_400_000_000_000L)
                    put("Delay", 15_000_000_000L)
                }
                putJsonObject("ReschedulePolicy") {
                    put("Attempts", 3)
                    put("Interval", 3_600_000_000_000L)
                    put("Delay", 15_000_000_000L)
                    put("DelayFunction", "exponential")
                    put("MaxDelay", 120_000_000_000L)
                    put("Unlimited", false)
                }
                putJsonArray("Tasks") { add(task()) }
            })
        }
    }

    private fun task() = buildJsonObject {
        put("Name", "worker")
        put("Driver", if (platform == NomadPlatform.LINUX) "docker" else "raw_exec")
        put("KillTimeout", 30_000_000_000L)
        putJsonObject("Config") {
            put("command", command.first())
            putJsonArray("args") { command.drop(1).forEach { add(JsonPrimitive(it)) } }
            if (platform == NomadPlatform.LINUX) {
                put("image", image)
                put("cpu_hard_limit", true)
                put("cpu_cfs_period", 100_000)
                put("cgroupns", "private")
                put("memory_hard_limit", budget.memoryMiB)
                put("init", true)
            }
        }
        putJsonObject("Resources") {
            put("CPU", budget.nomadCpuMHz(cpuCapacity))
            put("MemoryMB", budget.memoryMiB)
        }
        putJsonObject("Env") {
            environment.forEach { (key, value) -> put(key, value) }
            put("LAB_SESSION_CPU_MILLIS", budget.cpuMillis.toString())
            put("LAB_SESSION_MEMORY_MIB", budget.memoryMiB.toString())
            put("LAB_SESSION_PARALLEL", budget.parallel.toString())
        }
    }

    companion object {
        val json = Json { prettyPrint = true }
        private val ID = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")
        private val IMAGE = Regex("[^\\s]+@sha256:[0-9a-f]{64}")

        private fun constraint(target: String, value: String) = buildJsonObject {
            put("LTarget", target)
            put("Operand", "=")
            put("RTarget", value)
        }
    }
}
