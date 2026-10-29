package com.eignex.lab

import kotlinx.serialization.Serializable
import java.nio.file.Path
import com.eignex.lab.bench.BenchDescriptor
import com.eignex.lab.bench.BenchPlan

@Serializable
data class InputFile(val path: String, val sha256: String, val bytes: Long, val executable: Boolean = false) {
    init { safeRelative(path); require(sha256.matches(SHA256) && bytes >= 0) }
}

/** A trusted source build runs inside the session's existing CPU/memory reservation. */
@Serializable
data class WorkerBuild(val tasks: List<String> = emptyList(), val timeoutSec: Long = 1800, val gitBundle: String? = null,
                       val benchManifest: String? = null, val requiredTags: Set<String> = emptySet()) {
    init {
        require(tasks.all { it.matches(Regex(":?[A-Za-z0-9_][A-Za-z0-9_-]*(?::[A-Za-z0-9_][A-Za-z0-9_-]*)*")) && !it.startsWith("-") })
        require(timeoutSec in 1..7200)
        gitBundle?.let(::safeRelative)
        benchManifest?.let(::safeRelative)
        NodeTags.validate(NodeSelector(require = requiredTags))
    }
}

/** A coordinator-approved archive. Executables and cache identities are explicit, not inferred from ZIP metadata. */
@Serializable
data class WorkerBundle(
    val sha256: String, val files: List<InputFile>, val repository: String, val commit: String,
    val os: String, val arch: String, val toolchain: String, val buildOptions: List<String> = emptyList(),
    val build: WorkerBuild? = null,
) {
    init {
        require(sha256.matches(SHA256) && CommitSha.isValid(commit))
        Repositories.requireAllowed(repository)
        require(os in setOf("linux", "mac") && arch in setOf("amd64", "arm64") && toolchain.isNotBlank())
        require(files.isNotEmpty() && files.map { it.path }.distinct().size == files.size)
    }
}

@Serializable
data class WorkCase(val index: Int, val arm: Int, val problem: Problem, val seed: Long?, val repeat: Int, val timeoutSec: Long,
    val executionCase: com.eignex.lab.bench.BenchCase? = null) {
    init { require(index >= 0 && arm >= 0 && repeat >= 0 && timeoutSec in 1..604800) }
}

/** Portable domain input. Commands and filesystem paths are rendered only after staging on the worker. */
@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class WorkManifest(
    val session: String, val release: String, val experiment: ExperimentSpec, val arms: List<PlannedArm>,
    val cases: List<WorkCase>, val bundles: List<WorkerBundle>, val budget: ResourceBudget,
    val solveJavaOptions: String = "",
    @kotlinx.serialization.EncodeDefault val schemaVersion: Int = if (experiment.bench == null) 1 else 3,
    val preparationTimeoutSec: Long? = null, val inputCorpus: WorkerBundle? = null,
    val sharedInputs: List<SharedInput> = emptyList(),
    val benchReplay: BenchReplay? = null,
) {
    init {
        require(schemaVersion in (if (experiment.bench == null) 1..1 else 2..3) && session.matches(Regex("[a-z0-9-]{1,64}")) && release.isNotBlank())
        require(preparationTimeoutSec == null || preparationTimeoutSec in 1..7200)
        require(arms.isNotEmpty() && (cases.isNotEmpty() || preparationTimeoutSec != null) && cases.map { it.index }.distinct().size == cases.size)
        require(arms.all { (preparationTimeoutSec != null || it.arm.cores * 1000L <= budget.cpuMillis) && CommitSha.isValid(it.sha) })
        require(cases.all { it.arm in arms.indices })
        require(schemaVersion >= 3 || cases.all { it.executionCase == null } && benchReplay == null)
        benchReplay?.let { replay ->
            require(experiment.bench != null && preparationTimeoutSec != null)
            require(replay.plans.keys == arms.map { it.arm.label }.toSet())
            require(bundles.all { it.build == null })
            require(replay.corpus == null || replay.sharedInputs.isEmpty())
        }
        cases.forEach { case ->
            (case.executionCase ?: case.problem.benchCase)?.let { bench ->
                require(experiment.bench != null && bench.resources.cpus * 1000L <= budget.cpuMillis)
                require(bench.resources.memoryMiB <= budget.memoryMiB && bench.resources.timeoutSeconds == case.timeoutSec)
            }
            require(experiment.bench == null || case.problem.benchCase != null)
        }
        require(preparationTimeoutSec == null || cases.isEmpty())
        require(inputCorpus == null || inputCorpus.repository == experiment.repository)
        require(inputCorpus == null || sharedInputs.isEmpty())
        require(sharedInputs.map { it.file.path }.distinct().size == sharedInputs.size)
        require(sharedInputs.fold(0L) { total, input -> Math.addExact(total, input.file.bytes) } in 0..32L * 1024 * 1024 * 1024)
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

/** Immutable input bytes live in a regional store; jobs carry only paths and checksums. */
@Serializable
data class SharedInput(val file: InputFile, val key: String, val encoding: String = "identity",
                       val storedSha256: String = file.sha256, val storedBytes: Long = file.bytes) {
    init {
        safeRelative(key); require(!file.executable && encoding in setOf("identity", "zstd"))
        require(SHA256.matches(storedSha256) && storedBytes in 0..32L * 1024 * 1024 * 1024)
    }
}

/** Verified accepted preparations; workers never resolve another job or source ref themselves. */
@Serializable
data class BenchReplay(val plans: Map<String, BenchPlan>, val descriptor: BenchDescriptor,
                       val corpus: WorkerBundle? = null, val sharedInputs: List<SharedInput> = emptyList())
