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

/** Which workers a session runs on, each with what only it has. */
sealed interface WorkerPool {
    /** The local machine's native worker, held to its budget by the lab's reservation, at most these limits. */
    data class Native(val cpuLimit: Long?, val memoryLimit: Long?) : WorkerPool {
        /** The reservation's (mCPU, MiB) limits, when it was submitted with both. */
        val limits: Pair<Long, Long>? get() = cpuLimit?.let { cpu -> memoryLimit?.let { cpu to it } }
    }

    /** Cloud containers under a hard CPU quota, spread over nodes: at most [perHostParallel] cases on one, never on [excludedNodes]. */
    data class Cloud(val perHostParallel: Int?, val excludedNodes: Set<String>) : WorkerPool
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
    private val excludedNodes: Set<String> = emptySet(),
    /** The most cases a cloud node runs at once across sessions, when the queue limits it. */
    private val perHostParallel: Int? = null,
    /** The native reservation's CPU (mCPU) and memory (MiB) limits it was submitted with, when given. */
    private val nativeCpuLimit: Long? = null,
    private val nativeMemoryLimit: Long? = null,
) {
    init {
        require(enforcement == CpuEnforcement.ADVISORY_NATIVE || nativeCpuLimit == null && nativeMemoryLimit == null) {
            "only the native worker has a native reservation"
        }
    }

    /** The workers it runs on, with what only they have; unknown for a session placed before enforcement was kept. */
    val pool: WorkerPool? get() = when (enforcement) {
        CpuEnforcement.ADVISORY_NATIVE -> WorkerPool.Native(nativeCpuLimit, nativeMemoryLimit)
        CpuEnforcement.HARD_QUOTA -> WorkerPool.Cloud(perHostParallel, excludedNodes)
        null -> null
    }

    /** Whether it runs as the native worker on the local machine. */
    val native: Boolean get() = pool is WorkerPool.Native

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
            val native = enforcement == CpuEnforcement.ADVISORY_NATIVE
            // Each pool keeps only its own fields: the queue's per-host limit and node exclusions spread cloud sessions,
            // and only the native worker has a reservation.
            return SessionPlacement(
                enforcement = enforcement,
                nodeClass = equal("\${node.class}"),
                cpuCapacity = if (cpu != null && cores != null) NomadCpuCapacity(cpu, cores) else null,
                excludedNodes = if (native) emptySet() else constraints.filter {
                    it["LTarget"]?.jsonPrimitive?.content == "\${node.unique.id}" && it["Operand"]?.jsonPrimitive?.content == "!="
                }.mapNotNull { it["RTarget"]?.jsonPrimitive?.content }.toSet(),
                perHostParallel = if (native) null else meta?.get("lab_per_host_parallel")?.jsonPrimitive?.intOrNull,
                nativeCpuLimit = if (native) meta?.get("lab_native_cpu_limit")?.jsonPrimitive?.longOrNull else null,
                nativeMemoryLimit = if (native) meta?.get("lab_native_memory_limit")?.jsonPrimitive?.longOrNull else null,
            )
        }

        /** [of] the submitted JSON text [payload]. */
        fun of(payload: String): SessionPlacement = of(Json.parseToJsonElement(payload).jsonObject)
    }
}
