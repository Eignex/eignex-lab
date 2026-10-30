package com.eignex.lab.domain

import com.eignex.lab.util.CommitSha
import kotlinx.serialization.Serializable

/** Provisional failure evidence, never an accepted build or performance result. No commands or credentials. */
@Serializable
data class PreparationDiagnostics(val elapsedMs: Long, val stage: String, val budget: ResourceBudget,
    val enforcement: String, val cpuMax: String?, val memoryMax: String?, val counters: Map<String, String>,
    val leaseRemainingMs: Long, val runRemainingMs: Long?, val compilerSlots: Int,
    val build: PreparationBuildDiagnostic? = null, val uploads: List<PreparationUploadDiagnostic> = emptyList(), val sequence: Long = 0) {
    init {
        require(elapsedMs in 0..86_400_000 && stage in PreparationTrace.labels)
        require(enforcement in setOf("advisory-native", "cgroup-v2-hard"))
        require(listOfNotNull(cpuMax, memoryMax).all { it.length <= 128 })
        require(counters.keys.all { it in setOf("cpu.stat", "memory.events", "memory.current") })
        require(counters.values.all { it.length <= 2048 })
        require(leaseRemainingMs >= 0 && (runRemainingMs == null || runRemainingMs >= 0))
        require(compilerSlots in 1..64 && uploads.size <= 16 && sequence in 0..1_000_000)
    }
}

@Serializable
data class PreparationBuildDiagnostic(val exitCode: Int? = null, val stdout: String = "", val stderr: String = "",
    val signal: Int? = null, val runTimedOut: Boolean? = null,
    val kind: String? = null, val stage: String? = null, val sourceCommit: String? = null) {
    init {
        require(stdout.length <= 4096 && stderr.length <= 4096 && (signal == null || signal >= 0))
        require(kind in setOf(null, "checkout", "compilation"))
        require(stage == null || stage in PreparationTrace.labels)
        require(sourceCommit == null || CommitSha.isValid(sourceCommit))
    }
}

@Serializable
data class PreparationUploadDiagnostic(val sha256: String, val method: String, val status: Int?,
    val offset: Long?, val acceptedOffset: Long?, val bytes: Long, val elapsedMs: Long, val failure: String? = null) {
    init {
        require(sha256.matches(Regex("[0-9a-f]{64}")) && method in setOf("GET", "PUT"))
        require(status == null || status in 100..599)
        require(listOfNotNull(offset, acceptedOffset, bytes).all { it in 0..(64L * 1024 * 1024 * 1024) })
        require(elapsedMs >= 0 && failure in setOf(null, "io", "timeout", "interrupted"))
    }
}
