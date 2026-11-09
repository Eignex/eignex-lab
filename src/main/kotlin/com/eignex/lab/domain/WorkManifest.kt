package com.eignex.lab.domain

import com.eignex.lab.util.*

import kotlinx.serialization.Serializable
import java.nio.file.Path
import com.eignex.lab.bench.BenchDescriptor
import com.eignex.lab.bench.BenchPlan
import com.eignex.lab.execution.Repositories

@Serializable
data class InputFile(val path: String, val sha256: String, val bytes: Long, val executable: Boolean = false) {
    init { safeRelative(path); require(sha256.matches(SHA256) && bytes >= 0) }
}

/** A trusted source build runs inside the session's existing CPU/memory reservation. */
@Serializable
data class WorkerBuild(val tasks: List<String> = emptyList(), val timeoutSec: Long = 1800, val gitBundle: String? = null,
                       val benchManifest: String? = null, val requiredTags: Set<String> = emptySet(),
                       val preparation: WorkerPreparation? = null) {
    init {
        require(tasks.all { it.matches(Regex(":?[A-Za-z0-9_][A-Za-z0-9_-]*(?::[A-Za-z0-9_][A-Za-z0-9_-]*)*")) && !it.startsWith("-") })
        require(timeoutSec in 1..7200)
        gitBundle?.let(::safeRelative)
        benchManifest?.let(::safeRelative)
        NodeTags.validate(NodeSelector(require = requiredTags))
        preparation?.let { require(benchManifest != null); it.target.validateRequirements(requiredTags) }
    }
}

/** A coordinator-approved archive. Executables and cache identities are explicit, not inferred from ZIP metadata. */
@Serializable
data class WorkerBundle(
    val sha256: String, val files: List<InputFile>, val repository: String, val commit: String,
    val os: String, val arch: String, val toolchain: String, val buildOptions: List<String> = emptyList(),
    val build: WorkerBuild? = null,
    val tree: PreparedTree? = null,
) {
    init {
        require(sha256.matches(SHA256) && CommitSha.isValid(commit))
        Repositories.requireAllowed(repository)
        require(os in setOf("linux", "mac") && arch in setOf("amd64", "arm64") && toolchain.isNotBlank())
        require(files.isNotEmpty() && files.map { it.path }.distinct().size == files.size)
        require(build?.gitBundle != PackedGitSource.MARKER || PackedGitSource.usable(this)) { "invalid packed source receipt" }
        tree?.let { require(build == null && sha256 == preparedTreeDigest(files, it.directories)) { "invalid prepared tree identity" } }
    }
}

/** A job-owned immutable snapshot, retained with the job rather than a disposable worker cache. */
@Serializable
data class PreparedTree(val job: Long, val directories: List<String>) {
    init { require(job > 0 && directories == directories.distinct().sorted()); directories.forEach(::safeRelative) }
}

@Serializable
private data class PreparedTreeInventory(val format: String, val files: List<InputFile>, val directories: List<String>)

internal fun preparedTreeManifest(files: List<InputFile>, directories: List<String>): String =
    kotlinx.serialization.json.Json.encodeToString(PreparedTreeInventory.serializer(),
        PreparedTreeInventory("prepared-tree-v1", files.sortedBy { it.path }, directories.sorted()))

internal fun preparedTreeDigest(files: List<InputFile>, directories: List<String>): String =
    sha256Hex(preparedTreeManifest(files, directories))

internal fun preparationArtifact(bundle: WorkerBundle): String = "preparation/${bundle.commit}." + if (bundle.tree == null) "zip" else "tree.json"

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
    @kotlinx.serialization.EncodeDefault val schemaVersion: Int = when {
        experiment.measurement == Measurement.NATIVE_STARTUP -> 8
        !experiment.isBench -> 1
        bundles.any { it.build?.gitBundle == PackedGitSource.MARKER } -> 7
        bundles.any { it.build?.preparation != null } -> 6
        bundles.any { it.tree != null } -> 5
        experiment.work.bench?.adapterResources == true -> 4
        else -> 3
    },
    val preparationTimeoutSec: Long? = null, val inputCorpus: WorkerBundle? = null,
    val sharedInputs: List<SharedInput> = emptyList(),
    val benchReplay: BenchReplay? = null,
) {
    /** Frozen phase placement: ordinary recipes keep their original selector unchanged. */
    val placementTags: NodeSelector? get() = if (preparationTimeoutSec == null) experiment.nodeTags else
        bundles.firstOrNull()?.build?.preparation?.selector(experiment.nodeTags) ?: experiment.nodeTags

    init {
        require(schemaVersion in (if (!experiment.isBench) 1..1 else 2..8) && session.matches(Regex("[a-z0-9-]{1,64}")) && release.isNotBlank())
        require(experiment.measurement != Measurement.NATIVE_STARTUP || schemaVersion >= 8) { "native startup capture requires worker protocol 8" }
        require(bundles.none { it.build?.gitBundle == PackedGitSource.MARKER } || schemaVersion >= 7) { "packed source requires worker protocol 7" }
        require(bundles.none { it.build?.preparation != null } || schemaVersion >= 6) { "separate preparation platforms require worker protocol 6" }
        require(bundles.map { it.build?.preparation }.distinct().size <= 1) { "preparation contracts differ between source bundles" }
        require(bundles.none { it.tree != null } || schemaVersion >= 5) { "prepared trees require worker protocol 5" }
        require(schemaVersion >= 4 || experiment.work.bench?.adapterResources != true) { "adapter resources require worker protocol 4" }
        require(preparationTimeoutSec == null || preparationTimeoutSec in 1..7200)
        require(arms.isNotEmpty() && (cases.isNotEmpty() || preparationTimeoutSec != null) && cases.map { it.index }.distinct().size == cases.size)
        require(arms.all { (preparationTimeoutSec != null || it.arm.cores * 1000L <= budget.cpuMillis) && CommitSha.isValid(it.sha) })
        require(cases.all { it.arm in arms.indices })
        require(schemaVersion >= 3 || cases.all { it.executionCase == null } && benchReplay == null)
        benchReplay?.let { replay ->
            require(experiment.isBench && preparationTimeoutSec != null)
            require(replay.plans.keys == arms.map { it.arm.label }.toSet())
            require(bundles.all { it.build == null })
            require(replay.corpus == null || replay.sharedInputs.isEmpty())
        }
        cases.forEach { case ->
            (case.executionCase ?: case.problem.benchCase)?.let { bench ->
                require(experiment.isBench && bench.resources.cpus * 1000L <= budget.cpuMillis)
                require(bench.resources.memoryMiB <= budget.memoryMiB && bench.resources.timeoutSeconds == case.timeoutSec)
            }
            require(!experiment.isBench || case.problem.benchCase != null)
        }
        require(preparationTimeoutSec == null || cases.isEmpty())
        require(inputCorpus == null || inputCorpus.repository == experiment.repository)
        require(inputCorpus == null || sharedInputs.isEmpty())
        require(sharedInputs.map { it.file.path }.distinct().size == sharedInputs.size)
        require(sharedInputs.fold(0L) { total, input -> Math.addExact(total, input.file.bytes) } in 0..32L * 1024 * 1024 * 1024)
        cases.forEach { case -> (listOfNotNull(case.problem.model, case.problem.data) + case.problem.includeDirs).forEach(::safeRelative) }
        require(bundles.all { it.repository == experiment.repository })
        require(arms.all { arm -> bundles.any { it.commit == arm.sha } })
        require(experiment.work !is Work.Analysis && experiment.work !is Work.Script) { "analysis stays in the coordinator" }
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
