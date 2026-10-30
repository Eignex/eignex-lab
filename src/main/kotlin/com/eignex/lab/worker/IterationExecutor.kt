package com.eignex.lab.worker

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.nomad.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

import com.eignex.lab.bench.BenchInputRoot
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

/** Runs only coordinator-approved domain work. No Store, provider client, source resolution or capacity credentials. */
class IterationExecutor(
    private val supervisor: ProcessSupervisor, private val cache: VerifiedCache,
    private val bundleInput: (WorkerBundle) -> java.io.InputStream,
    private val launcher: String, private val os: String, private val arch: String,
    private val buildCache: WorkerBuildCache? = null,
    private val inputCache: WorkerInputCache? = null,
    private val privateWorkspaces: WorkerWorkspaces? = null,
) {
    fun run(manifest: WorkManifest, indices: List<Int>, directory: Path, host: String, generation: Int,
            prepared: () -> Unit = {}, starting: (WorkCase) -> Unit = {}, remainingRunMs: () -> Long? = { null },
            remainingLeaseMs: () -> Long): IterationResult {
        val runLimit = remainingRunMs() ?: Experiments.runTimeoutSec(manifest.experiment)?.times(1000)
        val runDeadline = runLimit?.let { System.nanoTime() + it.coerceAtLeast(0) * 1_000_000 }
        fun runRemaining() = runDeadline?.let { minOf((it - System.nanoTime()) / 1_000_000, remainingRunMs() ?: Long.MAX_VALUE) }
        fun checkRun() {
            if (runRemaining()?.let { it <= 0 } == true) throw RunDeadlineExceeded()
            if (remainingLeaseMs() <= 0) throw ExecutionFenced("lease expired during staging")
        }
        checkRun()
        require(indices.isNotEmpty() && indices.distinct().size == indices.size)
        val selected = indices.map { index -> manifest.cases.single { it.index == index } }
        require(selected.map { Triple(it.problem, it.seed, it.repeat) }.distinct().size == 1) { "one whole paired iteration per execution" }
        val expected = manifest.cases.filter { Triple(it.problem, it.seed, it.repeat) == Triple(selected.first().problem, selected.first().seed, selected.first().repeat) }
        require(expected.map { it.index }.toSet() == indices.toSet()) { "partial paired iteration" }
        Files.createDirectories(directory)
        val stagingStart = System.nanoTime()
        val inputs = if (manifest.sharedInputs.isNotEmpty()) {
            requireNotNull(SharedInputs.environment()) { "shared input transport is not configured" }
                .acquire(manifest.sharedInputs, ::checkRun, required = iterationInputPaths(selected))
        } else null
        try {
        val corpus = inputs?.directory ?: directory.resolve("corpus")
        if (manifest.inputCorpus != null) {
            withInput(manifest.inputCorpus, ::checkRun) { input -> copyTree(input, corpus, ::checkRun) }
        } else Files.createDirectories(corpus)
        val inputsReady = System.nanoTime()
        val fresh = mutableSetOf<String>()
        val worktrees = selected.map { manifest.arms[it.arm].sha }.distinct().associateWith { sha ->
            val bundle = manifest.bundles.single { it.commit == sha && it.os == os && it.arch == arch }
            val key = ExecutionStore.digest(kotlinx.serialization.json.Json.encodeToString(WorkerBundle.serializer(), bundle))
            privateWorkspaces?.reuse(key, ::checkRun)?.let { return@associateWith it }
            fresh += sha
            fun copy(cached: Path): Path {
                fun stage(destination: Path) { copyTree(cached, destination, ::checkRun) }
                // Never modify a reusable cache entry; keep it pinned until this attempt's copy is complete.
                return privateWorkspaces?.copy(key, cached, ::checkRun, false)
                    ?: directory.resolve("worktrees").resolve(sha).also(::stage)
            }
            withInput(bundle, ::checkRun, runtimeOnly = false) { source ->
                if (bundle.build == null) copy(source) else requireNotNull(buildCache) { "source build cache is not configured" }
                    .withPrepared(bundle, source, manifest.budget, environment(), ::runRemaining, remainingLeaseMs, use = ::copy)
            }
        }
        val workspacesReady = System.nanoTime()
        val setupReady = System.nanoTime()
        if (privateWorkspaces != null) for (sha in fresh) {
            val bundle = manifest.bundles.single { it.commit == sha && it.os == os && it.arch == arch }
            privateWorkspaces.seal(ExecutionStore.digest(kotlinx.serialization.json.Json.encodeToString(WorkerBundle.serializer(), bundle)), worktrees.getValue(sha), ::checkRun)
        }
        val sealed = System.nanoTime()
        prepared()
        println("worker staging timing: inputsMs=${(inputsReady-stagingStart)/1_000_000} workspaceMs=${(workspacesReady-inputsReady)/1_000_000} setupMs=${(setupReady-workspacesReady)/1_000_000} sealMs=${(sealed-setupReady)/1_000_000} notifyMs=${(System.nanoTime()-sealed)/1_000_000} fresh=${fresh.size}")
        val timedOut = java.util.concurrent.atomic.AtomicBoolean()
        val cpu = Semaphore(manifest.budget.cpuMillis)
        val threads = Executors.newFixedThreadPool(minOf(manifest.budget.parallel, selected.size))
        try {
            val results = threads.invokeAll(selected.map { case -> Callable {
                val arm = manifest.arms[case.arm].arm
                val benchCase = case.executionCase ?: case.problem.benchCase
                val reference = arm.values["backend"] == REFERENCE_BACKEND
                val permits = Math.multiplyExact(benchCase?.resources?.cpus ?: arm.cores, 1000)
                require(permits <= manifest.budget.cpuMillis) { "bench case exceeds the session CPU budget" }
                cpu.acquire(permits)
                try {
                    checkRun()
                    check(remainingLeaseMs() > 0) { "lease expired before starting case" }
                    starting(case)
                    val worktree = worktrees.getValue(manifest.arms[case.arm].sha)
                    val problem = case.problem.copy(model = case.problem.model?.let { portablePath(it, worktree, corpus).toString() },
                        data = case.problem.data?.let { portablePath(it, worktree, corpus).toString() },
                        includeDirs = case.problem.includeDirs.map { portablePath(it, worktree, corpus).toString() })
                    benchCase?.let { BenchRuns.validateInputs(it, worktree, corpus) }
                    val command = if (reference) Experiments.referenceCommand(launcher, problem, arm, case.seed, case.index)
                        else when (val work = manifest.experiment.work) {
                            is Work.Bench -> {
                                BenchRuns.writeCase(directory, case.index, requireNotNull(benchCase))
                                BenchRuns.command(worktree, work.spec, case.index, corpus, manifest.experiment.repository)
                            }
                            is Work.Profile -> Experiments.profileCommand(worktree.toString(), work.main, case.index, corpus.toString(), arm.debug, manifest.experiment.repository)
                            is Work.Analysis, is Work.Script, Work.Recipe -> error("execution requires a repository bench manifest or explicit profile")
                        }
                    val env = environment() + mapOf("JOB_DIR" to directory.toString())
                    val start = now()
                    val processRunMs = runRemaining()
                    val graceMs = minOf(Experiments.STOP_GRACE_SEC * 1000, processRunMs?.let { (it / 10).coerceAtLeast(0) } ?: Long.MAX_VALUE)
                    var exit = try { supervisor.run(listOf("bash", "-c", command), worktree, env,
                        directory.resolve("${case.index}.out"), directory.resolve("${case.index}.err"), case.timeoutSec,
                        graceMs = graceMs, runRemainingMs = processRunMs, remainingLeaseMs = remainingLeaseMs)
                    } catch (_: RunDeadlineExceeded) {
                        timedOut.set(true)
                        Termination.record(directory.resolve("${case.index}.termination"), Termination.deadline(graceMs))
                        124
                    }
                    val finish = now()
                    val record = if (manifest.experiment.isBench && !reference) {
                        val resultFile = directory.resolve("cases/${case.index}/out/result.json")
                        val processed = BenchRuns.executionRecord(
                            resultFile.takeIf(Files::isRegularFile)?.let(Files::readString), requireNotNull(benchCase), exit)
                        exit = processed.exit
                        processed.json
                    } else directory.resolve("cases/${case.index}").toFile().listFiles { f -> f.extension == "json" }?.singleOrNull()?.readText()
                    Files.writeString(directory.resolve("${case.index}.exit"), "$exit\n")
                    ExecutionCaseResult(case.index, exit, start, finish, record, recordedCommand(directory, case.index, command))
                } finally { cpu.release(permits) }
            } }).map { future ->
                try { future.get() } catch (error: java.util.concurrent.ExecutionException) {
                    if (error.cause is RunDeadlineExceeded) throw error.cause!!
                    throw error
                }
            }
            return IterationResult(results, runTimedOut = timedOut.get())
        } finally { threads.shutdownNow() }
        } finally { inputs?.close() }
    }

    /** Large literal arguments stay intact in the invocation and artifact; bounded result metadata uses the planned command. */
    internal fun recordedCommand(directory: Path, index: Int, command: String): String? {
        if (command.length <= 65536) return command
        val file = directory.resolve("cases/$index/command.sh")
        Files.createDirectories(file.parent)
        Files.writeString(file, command + "\n")
        return null
    }

    private fun environment(): Map<String, String> = workerEnvironment(os == "mac")
    private fun <T> withInput(bundle: WorkerBundle, check: () -> Unit, runtimeOnly: Boolean = false, use: (Path) -> T): T =
        if (inputCache != null) {
            if (runtimeOnly) inputCache.useRuntime(bundle, check, { bundleInput(bundle) }, use)
            else inputCache.use(bundle, check, { bundleInput(bundle) }, use)
        } else use(if (runtimeOnly) cache.stageRuntime(bundle, check) { bundleInput(bundle) } else cache.stage(bundle, check) { bundleInput(bundle) })
    private fun copyTree(source: Path, destination: Path, checkActive: () -> Unit) {
        WorkerTrees.copy(source, destination, checkActive)
    }
}

/** Resolve and verify this iteration's model/data/include trees, rather than the experiment's entire corpus. */
internal fun iterationInputPaths(cases: List<WorkCase>): List<String> = cases.flatMap { case ->
    listOfNotNull(case.problem.model, case.problem.data) + case.problem.includeDirs +
        (case.executionCase ?: case.problem.benchCase)?.inputs.orEmpty().filter { it.root == BenchInputRoot.CORPUS }.map { "lab-corpus/${it.path}" }
}.filter { it == "lab-corpus" || it.startsWith("lab-corpus/") }
    .map { it.removePrefix("lab-corpus").removePrefix("/") }.distinct()

/** Resolve only known portable roots; old manifests keep their worktree-relative interpretation. */
internal fun portablePath(value: String, source: Path, corpus: Path): Path {
    val relative = safeRelative(value)
    return when (relative.first().toString()) {
        "lab-source" -> if (relative.nameCount == 1) source else source.resolve(relative.subpath(1, relative.nameCount))
        "lab-corpus" -> if (relative.nameCount == 1) corpus else corpus.resolve(relative.subpath(1, relative.nameCount))
        else -> source.resolve(relative)
    }
}
