package com.eignex.lab

import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
data class InputFile(val path: String, val sha256: String, val bytes: Long, val executable: Boolean = false) {
    init { safeRelative(path); require(sha256.matches(SHA256) && bytes >= 0) }
}

/** A coordinator-approved archive. Executables and cache identities are explicit, not inferred from ZIP metadata. */
@Serializable
data class WorkerBundle(
    val sha256: String, val files: List<InputFile>, val repository: String, val commit: String,
    val os: String, val arch: String, val toolchain: String, val buildOptions: List<String> = emptyList(),
) {
    init {
        require(sha256.matches(SHA256) && commit.matches(Regex("[a-f0-9]{40}")))
        Repositories.requireAllowed(repository)
        require(os in setOf("linux", "mac") && arch in setOf("amd64", "arm64") && toolchain.isNotBlank())
        require(files.isNotEmpty() && files.map { it.path }.distinct().size == files.size)
    }
}

@Serializable
data class WorkCase(val index: Int, val arm: Int, val problem: Problem, val seed: Long?, val repeat: Int, val timeoutSec: Long) {
    init { require(index >= 0 && arm >= 0 && repeat >= 0 && timeoutSec in 1..604800) }
}

/** Portable domain input. Commands and filesystem paths are rendered only after staging on the worker. */
@Serializable
data class WorkManifest(
    val session: String, val release: String, val experiment: ExperimentSpec, val arms: List<PlannedArm>,
    val cases: List<WorkCase>, val bundles: List<WorkerBundle>, val budget: ResourceBudget,
    val solveJavaOptions: String = "", val schemaVersion: Int = 1,
) {
    init {
        require(schemaVersion == 1 && session.matches(Regex("[a-z0-9-]{1,64}")) && release.isNotBlank())
        require(arms.isNotEmpty() && cases.isNotEmpty() && cases.map { it.index }.distinct().size == cases.size)
        require(arms.all { it.arm.cores * 1000L <= budget.cpuMillis && it.sha.matches(Regex("[a-f0-9]{40}")) })
        require(cases.all { it.arm in arms.indices })
        cases.forEach { case -> (listOfNotNull(case.problem.model, case.problem.data) + case.problem.includeDirs).forEach(::safeRelative) }
        require(bundles.all { it.repository == experiment.repository })
        require(arms.all { arm -> bundles.any { it.commit == arm.sha } })
        require(experiment.analyze == null && !experiment.isScript) { "analysis stays in the coordinator" }
    }
}

internal val SHA256 = Regex("[a-f0-9]{64}")
internal fun safeRelative(value: String): Path {
    val path = Path.of(value)
    require(value.isNotEmpty() && !value.contains('\\') && !value.contains('\u0000') && !path.isAbsolute && path.normalize() == path && path.none { it.toString() in setOf("..", ".") }) { "unsafe relative path" }
    return path
}
