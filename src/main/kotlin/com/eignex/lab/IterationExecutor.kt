package com.eignex.lab

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
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
) {
    fun run(manifest: WorkManifest, indices: List<Int>, directory: Path, host: String, generation: Int,
            prepared: () -> Unit = {}, starting: (WorkCase) -> Unit = {}, remainingRunMs: () -> Long? = { null },
            remainingLeaseMs: () -> Long): IterationResult {
        val runLimit = remainingRunMs() ?: manifest.experiment.profile?.let(Experiments::profileRunTimeoutSec)?.times(1000)
        val runDeadline = runLimit?.let { System.nanoTime() + it.coerceAtLeast(0) * 1_000_000 }
        fun runRemaining() = runDeadline?.let { minOf((it - System.nanoTime()) / 1_000_000, remainingRunMs() ?: Long.MAX_VALUE) }
        fun checkRun() { if (runRemaining()?.let { it <= 0 } == true) throw ProfileRunTimeout() }
        checkRun()
        require(indices.isNotEmpty() && indices.distinct().size == indices.size)
        val selected = indices.map { index -> manifest.cases.single { it.index == index } }
        require(selected.map { Triple(it.problem, it.seed, it.repeat) }.distinct().size == 1) { "one whole paired iteration per execution" }
        val expected = manifest.cases.filter { Triple(it.problem, it.seed, it.repeat) == Triple(selected.first().problem, selected.first().seed, selected.first().repeat) }
        require(expected.map { it.index }.toSet() == indices.toSet()) { "partial paired iteration" }
        Files.createDirectories(directory)
        val inputs = if (manifest.sharedInputs.isNotEmpty()) {
            requireNotNull(SharedInputs.environment()) { "shared input transport is not configured" }.acquire(manifest.sharedInputs, ::checkRun)
        } else null
        try {
        val corpus = inputs?.directory ?: directory.resolve("corpus")
        if (manifest.inputCorpus != null) {
            val input = cache.stage(manifest.inputCorpus, ::checkRun) { bundleInput(manifest.inputCorpus) }
            copyTree(input, corpus, ::checkRun)
        } else Files.createDirectories(corpus)
        val worktrees = selected.map { manifest.arms[it.arm].sha }.distinct().associateWith { sha ->
            val bundle = manifest.bundles.single { it.commit == sha && it.os == os && it.arch == arch }
            val source = cache.stage(bundle, ::checkRun) { bundleInput(bundle) }
            val destination = directory.resolve("worktrees").resolve(sha)
            fun copy(cached: Path): Path {
                // Never modify a reusable cache entry; keep it pinned until this attempt's copy is complete.
                copyTree(cached, destination, ::checkRun)
                return destination
            }
            if (bundle.build == null) copy(source) else requireNotNull(buildCache) { "source build cache is not configured" }
                .withPrepared(bundle, source, manifest.budget, environment(), ::runRemaining, remainingLeaseMs, ::copy)
        }
        if (manifest.experiment.measuresCli) for (worktree in worktrees.values) {
            val exit = supervisor.run(listOf("bash", "-c", CliMeasurements.install(worktree.toString(), os == "mac")), worktree,
                environment(), directory.resolve("measurement-setup.out"), directory.resolve("measurement-setup.err"), 60,
                runRemainingMs = runRemaining(), remainingLeaseMs = remainingLeaseMs)
            require(exit == 0) { "measurement launcher setup failed" }
        }
        // Provenance binds absolute distribution/runtime paths and launcher metadata. Capture only after
        // relocation and measurement installation, on the host and in the tree that actually runs the cases.
        if (!manifest.experiment.isProfile) for ((sha, worktree) in worktrees) {
            if (manifest.arms.none { it.sha == sha && it.arm.values["backend"] != REFERENCE_BACKEND }) continue
            val exit = supervisor.run(listOf("bash", "-c", Experiments.captureProvenance(worktree.toString())), worktree,
                environment(), directory.resolve("provenance-$sha.out"), directory.resolve("provenance-$sha.err"), 60,
                runRemainingMs = runRemaining(), remainingLeaseMs = remainingLeaseMs)
            if (exit != 0) throw WorkerBuildFailure("execution provenance capture failed (exit $exit)")
        }
        prepared()
        val timedOut = java.util.concurrent.atomic.AtomicBoolean()
        val cpu = Semaphore(manifest.budget.cpuMillis)
        val threads = Executors.newFixedThreadPool(minOf(manifest.budget.parallel, selected.size))
        try {
            val results = threads.invokeAll(selected.map { case -> Callable {
                val arm = manifest.arms[case.arm].arm
                val permits = Math.multiplyExact(arm.cores, 1000)
                cpu.acquire(permits)
                try {
                    checkRun()
                    check(remainingLeaseMs() > 0) { "lease expired before starting case" }
                    starting(case)
                    val worktree = worktrees.getValue(manifest.arms[case.arm].sha)
                    val problem = case.problem.copy(model = case.problem.model?.let { portablePath(it, worktree, corpus).toString() },
                        data = case.problem.data?.let { portablePath(it, worktree, corpus).toString() },
                        includeDirs = case.problem.includeDirs.map { portablePath(it, worktree, corpus).toString() })
                    val command = manifest.experiment.profile?.let { Experiments.profileCommand(worktree.toString(), it, case.index, corpus.toString(), arm.debug, manifest.experiment.repository) }
                        ?: if (arm.values["backend"] == REFERENCE_BACKEND) Experiments.referenceCommand(launcher, problem, arm, case.seed, case.index)
                        else Experiments.command(worktree.toString(), problem, arm, case.seed, case.index, corpus.toString())
                    val id = UUID.randomUUID().toString()
                    val profile = if (manifest.experiment.measuresCli) Files.createDirectories(directory.resolve("cases/${case.index}/profile-$id")) else null
                    val runtime = "${manifest.solveJavaOptions} -XX:ActiveProcessorCount=${arm.cores}".trim()
                    val javaOptions = listOf(runtime, profile?.takeIf { manifest.experiment.recordsCliJfr }?.let { CliMeasurements.options(it.toString(), manifest.experiment.compilationRecording) }.orEmpty()).joinToString(" ").trim()
                    val env = environment() + mapOf("JOB_DIR" to directory.toString(), "KLAUSE_DEBUG" to "true", "KLAUSE_CLI_OPTS" to javaOptions, "KLAUSE_CLI_PREFLIGHT_OPTS" to runtime) +
                        (profile?.let { mapOf("EIGNEX_LAB_PROFILE_DIR" to it.toString()) } ?: emptyMap())
                    val start = now()
                    val processRunMs = runRemaining()
                    val graceMs = minOf(Experiments.STOP_GRACE_SEC * 1000, processRunMs?.let { (it / 10).coerceAtLeast(0) } ?: Long.MAX_VALUE)
                    val exit = try { supervisor.run(listOf("bash", "-c", command), worktree, env,
                        directory.resolve("${case.index}.out"), directory.resolve("${case.index}.err"), case.timeoutSec,
                        graceMs = graceMs, runRemainingMs = processRunMs, remainingLeaseMs = remainingLeaseMs)
                    } catch (_: ProfileRunTimeout) {
                        timedOut.set(true)
                        Termination.record(directory.resolve("${case.index}.termination"), Termination.deadline(graceMs))
                        124
                    }
                    val finish = now()
                    Files.writeString(directory.resolve("${case.index}.exit"), "$exit\n")
                    val record = directory.resolve("cases/${case.index}").toFile().listFiles { f -> f.extension == "json" }?.singleOrNull()?.readText()
                    if (profile != null) CliMeasurements.writeManifest(profile, case.index, generation, id, host, command, exit,
                        javaOptions, record, os == "mac", manifest.experiment.compilationRecording, manifest.experiment.recordsCliJfr)
                    ExecutionCaseResult(case.index, exit, start, finish, record, command)
                } finally { cpu.release(permits) }
            } }).map { future ->
                try { future.get() } catch (error: java.util.concurrent.ExecutionException) {
                    if (error.cause is ProfileRunTimeout) throw error.cause!!
                    throw error
                }
            }
            return IterationResult(results, runTimedOut = timedOut.get())
        } finally { threads.shutdownNow() }
        } finally { inputs?.close() }
    }

    private fun environment(): Map<String, String> = workerEnvironment(os == "mac")
    private fun copyTree(source: Path, destination: Path, checkActive: () -> Unit) {
        require(!Files.exists(destination)) { "attempt worktree already exists" }
        Files.walk(source).use { paths -> paths.forEach { path ->
            checkActive()
            val target = destination.resolve(source.relativize(path))
            if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target, COPY_ATTRIBUTES)
        } }
    }
}

/** Resolve only known portable roots; old manifests keep their worktree-relative interpretation. */
internal fun portablePath(value: String, source: Path, corpus: Path): Path {
    val relative = safeRelative(value)
    return when (relative.first().toString()) {
        "lab-source" -> if (relative.nameCount == 1) source else source.resolve(relative.subpath(1, relative.nameCount))
        "lab-corpus" -> if (relative.nameCount == 1) corpus else corpus.resolve(relative.subpath(1, relative.nameCount))
        else -> source.resolve(relative)
    }
}
