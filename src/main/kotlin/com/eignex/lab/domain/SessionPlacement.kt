package com.eignex.lab.domain

import com.eignex.lab.util.*

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import com.eignex.lab.nomad.NomadPlatform

/**
 * How a worker's CPU is held to its budget, by where it runs ([NomadBatchJob.platform]): a cloud container under a hard
 * CPU quota, or the native worker on the local machine, which the lab holds to its budget itself by reservation.
 */
@Serializable
enum class CpuEnforcement(val stored: String) {
    @SerialName("hard-quota") HARD_QUOTA("hard-quota"),
    @SerialName("advisory-native") ADVISORY_NATIVE("advisory-native");

    companion object {
        fun of(platform: NomadPlatform): CpuEnforcement = if (platform == NomadPlatform.LINUX) HARD_QUOTA else ADVISORY_NATIVE
        fun of(stored: String?): CpuEnforcement? = entries.firstOrNull { it.stored == stored }
    }
}

/**
 * Where a session's worker was placed, as its Nomad job was submitted: kept with the session ([ExecutionStore]), so
 * no part of the lab reads it back out of the submitted JSON by its constraint strings. [of] is the one place that
 * does, when a session is created or a job about to be submitted is adjusted.
 */
@Serializable
data class SessionPlacement(
    val enforcement: CpuEnforcement? = null,
    /** The Nomad node class its worker runs on. */
    val nodeClass: String? = null,
    /** The CPU the node class's nodes have, as the job's constraints pinned it. */
    val cpuCapacity: NomadCpuCapacity? = null,
    /** Nodes it may not be placed on: those whose parallel slots its other sessions fill. */
    val excludedNodes: Set<String> = emptySet(),
    /** The most cases a cloud node runs at once across sessions, when the queue limits it. */
    val perHostParallel: Int? = null,
    /** The native reservation's CPU (mCPU) and memory (MiB) limits it was submitted with, when given. */
    val nativeCpuLimit: Long? = null,
    val nativeMemoryLimit: Long? = null,
) {
    /** Whether it runs as the native worker on the local machine. */
    val native: Boolean get() = enforcement == CpuEnforcement.ADVISORY_NATIVE

    fun json(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun parse(json: String): SessionPlacement = Json.decodeFromString(serializer(), json)

        /** The placement the Nomad job [job] was, or is about to be, submitted with. */
        fun of(job: JsonObject): SessionPlacement {
            val meta = job["Meta"]?.jsonObject
            val constraints = job["Constraints"]?.jsonArray.orEmpty().map { it.jsonObject }
            fun equal(target: String) = constraints.singleOrNull {
                it["LTarget"]?.jsonPrimitive?.content == target && (it["Operand"]?.jsonPrimitive?.content ?: "=") == "="
            }?.get("RTarget")?.jsonPrimitive?.content
            val cpu = equal("\${attr.cpu.usablecompute}")?.toIntOrNull()
            val cores = equal("\${attr.cpu.numcores}")?.toIntOrNull()
            // A job states its enforcement; one without it is placed by its kernel, as the native worker is on darwin.
            val enforcement = CpuEnforcement.of(meta?.get("lab_cpu_enforcement")?.jsonPrimitive?.content)
                ?: when (equal("\${attr.kernel.name}")) {
                    "darwin" -> CpuEnforcement.ADVISORY_NATIVE
                    "linux" -> CpuEnforcement.HARD_QUOTA
                    else -> null
                }
            return SessionPlacement(
                enforcement = enforcement,
                nodeClass = equal("\${node.class}"),
                cpuCapacity = if (cpu != null && cores != null) NomadCpuCapacity(cpu, cores) else null,
                excludedNodes = constraints.filter {
                    it["LTarget"]?.jsonPrimitive?.content == "\${node.unique.id}" && it["Operand"]?.jsonPrimitive?.content == "!="
                }.mapNotNull { it["RTarget"]?.jsonPrimitive?.content }.toSet(),
                perHostParallel = meta?.get("lab_per_host_parallel")?.jsonPrimitive?.intOrNull,
                nativeCpuLimit = meta?.get("lab_native_cpu_limit")?.jsonPrimitive?.longOrNull,
                nativeMemoryLimit = meta?.get("lab_native_memory_limit")?.jsonPrimitive?.longOrNull,
            )
        }

        /** [of] the submitted JSON text [payload]. */
        fun of(payload: String): SessionPlacement = of(Json.parseToJsonElement(payload).jsonObject)
    }
}
