package com.eignex.lab

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Serializable
data class PreparationResult(
    val selections: List<List<Problem>>, val bundles: List<WorkerBundle>, val corpus: WorkerBundle? = null,
    val features: List<ProblemFeatures> = emptyList(), val artifacts: Map<String, String> = emptyMap(),
    val sharedInputs: List<SharedInput> = emptyList(),
    val benchPlan: com.eignex.lab.bench.BenchPlan? = null,
    val benchDescriptor: com.eignex.lab.bench.BenchDescriptor? = null,
)

/** Relative roots in the domain plan have the same meaning on every worker. */
internal fun portableProblem(problem: Problem, source: Path, corpus: Path): Problem {
    fun path(value: String): String {
        val file = Path.of(value).let { if (it.isAbsolute) it else source.resolve(it) }.toAbsolutePath().normalize()
        return when {
            file.startsWith(corpus.toAbsolutePath().normalize()) -> "lab-corpus/" + corpus.toAbsolutePath().normalize().relativize(file)
            file.startsWith(source.toAbsolutePath().normalize()) -> "lab-source/" + source.toAbsolutePath().normalize().relativize(file)
            else -> error("selection returned a path outside its source and corpus")
        }.replace('\\', '/').also { safeRelative(it) }
    }
    return problem.copy(model = problem.model?.let(::path), data = problem.data?.let(::path), includeDirs = problem.includeDirs.map(::path))
}

internal fun workerEnvironment(mac: Boolean, environment: Map<String, String> = System.getenv()): Map<String, String> {
    val cache = environment["XDG_CACHE_HOME"] ?: if (mac) null else environment["LAB_WORKER_CACHE_ROOT"]?.let { "$it/consumer" }
    return environment.filterKeys {
        it in setOf("PATH", "HOME", "TMPDIR", "JAVA_HOME", "LANG", "LC_ALL", "KLAUSE_BENCH_CORPUS_MIRROR", "KLAUSE_BENCH_CORPUS_MIRROR_WRITE") || mac && it == "DOCKER_HOST"
    } + (cache?.let { mapOf("XDG_CACHE_HOME" to it) } ?: emptyMap()) +
        (if (environment["LAB_SHARED_INPUT_ROOT"] != null) mapOf("KLAUSE_BENCH_CORPUS_KEEP_PACKED" to "true") else emptyMap()) +
        mapOf("OPENBLAS_NUM_THREADS" to "1", "VECLIB_MAXIMUM_THREADS" to "1", "MKL_NUM_THREADS" to "1", "OMP_NUM_THREADS" to "1")
}

/** Preparation is a worker operation under the same supervisor, cgroup and ownership lease as solves. */
internal class PreparationExecutor(private val supervisor: ProcessSupervisor, private val cache: VerifiedCache,
                          private val builds: WorkerBuildCache, private val download: (WorkerBundle) -> java.io.InputStream,
                          private val os: String, private val arch: String,
                          private val shared: SharedInputs? = SharedInputs.environment(), private val inputSigner: String? = null,
                          private val inputCache: WorkerInputCache? = null, private val privateWorkspaces: WorkerWorkspaces? = null,
                          private val trace: PreparationTrace = PreparationTrace()) {
    fun run(manifest: WorkManifest, directory: Path, remainingRunMs: () -> Long?, remainingLeaseMs: () -> Long,
            selectProblems: (List<List<Problem>>) -> List<List<Problem>> = { it }, upload: (Path) -> String): PreparationResult {
        Files.createDirectories(directory)
        val timeout = requireNotNull(manifest.preparationTimeoutSec)
        val expires = System.nanoTime() + timeout * 1_000_000_000
        fun remaining(): Long = minOf(remainingLeaseMs(), (expires - System.nanoTime()) / 1_000_000)
        fun checkActive() {
            check(remaining() > 0) { "preparation allowance expired" }
            if (remainingRunMs()?.let { it <= 0 } == true) throw ProfileRunTimeout()
        }
        val corpus = Files.createDirectories(shared?.root ?: directory.resolve("corpus"))
        val env = workerEnvironment(os == "mac") + (inputSigner?.let { mapOf("KLAUSE_BENCH_CORPUS_SIGNER" to it) } ?: emptyMap())
        val log = directory.resolve("setup.log"); val errors = directory.resolve("setup.err")
        fun run(tree: Path, command: String, output: Path = log) {
            checkActive()
            val exit = trace.step(if (output.fileName.toString().startsWith("features-")) "classification" else "selection") { supervisor.run(listOf("bash", "-c", command), tree, env, output, errors, timeout,
                graceMs = Experiments.STOP_GRACE_SEC * 1000, runRemainingMs = remainingRunMs(), remainingLeaseMs = ::remaining) }
            checkPreparationExit(exit) { "preparation command failed (exit $exit): " + Files.readString(errors).takeLast(8192) }
        }
        val prepared = manifest.arms.map { it.sha }.distinct().associateWith { sha ->
            val bundle = manifest.bundles.single { it.commit == sha && it.os == os && it.arch == arch }
            fun copy(built: Path): Path = trace.step("workspace") {
                val runtimeOnly = WorkerTrees.runtimeOnly(manifest.experiment)
                privateWorkspaces?.copy(ExecutionStore.digest(sha), built, ::checkActive, runtimeOnly)?.let { return@step it }
                val tree = directory.resolve("worktrees/$sha")
                if (runtimeOnly) WorkerTrees.copyRuntime(built, tree, ::checkActive) else WorkerTrees.copy(built, tree, ::checkActive)
                tree
            }
            fun prepare(input: Path): Path = if (bundle.build == null) copy(input)
                else builds.withPrepared(bundle, input, manifest.budget, env, remainingRunMs, ::remaining, trace, ::copy)
            trace.step("inputs") { inputCache?.use(bundle, ::checkActive, { download(bundle) }, ::prepare)
                ?: prepare(cache.stage(bundle, ::checkActive) { download(bundle) }) }
        }
        val primary = prepared.getValue(manifest.arms.first().sha)
        val json = Json { ignoreUnknownKeys = true }
        fun select(selection: Map<String, String>, name: String): List<String> {
            val output = directory.resolve("$name.jsonl")
            val options = "-Dklause.bench.corpusCache=$corpus -Dklause.workspace.root=$primary" +
                if (shared == null) " -Dklause.bench.corpus.shared=true" else ""
            run(primary, "cd ${quote("$primary/klause-bench")} && JAVA_OPTS=${quote(options)} KLAUSE_BENCH_CORPUS_MAX_GB=off " +
                "./build/install/klause-bench/bin/klause-bench select ${Experiments.selectArgs(selection)}", output)
            return Files.readAllLines(output).filter { it.startsWith("{") }
        }
        val spec = manifest.experiment
        val benchPlan = spec.bench?.let { bench ->
            val request = directory.resolve("bench-request.json")
            Files.writeString(request, com.eignex.lab.bench.BenchProtocol.json.encodeToString(
                com.eignex.lab.bench.BenchRequest.serializer(), bench.request))
            val prefix = "export LAB_WORKTREE=${quote(primary.toString())} LAB_CORPUS=${quote(corpus.toString())} && "
            run(primary, prefix + BenchRuns.invocation(primary, bench, "describe"), directory.resolve("bench-descriptor.jsonl"))
            run(primary, prefix + BenchRuns.invocation(primary, bench, "plan", request.toString()), directory.resolve("bench-plan.jsonl"))
            com.eignex.lab.bench.BenchProtocol.json.decodeFromString<com.eignex.lab.bench.BenchPlan>(
                Files.readString(directory.resolve("bench-plan.jsonl"))).also { plan ->
                BenchRuns.resources(plan)
                plan.cases.forEach { BenchRuns.validateInputs(it, primary, corpus) }
            }
        }
        val benchDescriptor = benchPlan?.let { com.eignex.lab.bench.BenchProtocol.json.decodeFromString<com.eignex.lab.bench.BenchDescriptor>(
            Files.readString(directory.resolve("bench-descriptor.jsonl"))) }
        fun problems(selection: Map<String, String>, name: String): List<Problem> = try {
            select(selection, name).map { json.decodeFromString<Problem>(it) }
        } catch (failure: IllegalArgumentException) { throw WorkerBuildFailure("invalid bench selection: ${failure.javaClass.simpleName}") }
        val raw = when {
            benchPlan != null -> listOf(benchPlan.cases.map(BenchRuns::problem))
            spec.isProfile -> listOf(listOf(Experiments.profileProblem(spec)))
            spec.problemList.isNotEmpty() -> listOf(spec.problemList)
            else -> spec.problems.mapIndexed { index, selection ->
                val mode = Experiments.referenceFilter(spec, selection)
                val asked = if (Experiments.refills(mode, selection)) Experiments.uncapped(selection) else selection
                problems(asked - "files", "selection-$index")
            }
        }.map { group -> group.map { if (benchPlan != null) it else it.copy(model = null, data = null, includeDirs = emptyList()) } }
        val selected = trace.step("selection") { selectProblems(raw) }
        require(selected.size == raw.size)
        val selections = if (spec.isProfile || benchPlan != null) selected else {
            val resolved = selected.flatten().distinctBy { it.suite to it.problem }.groupBy { it.suite }.flatMap { (suite, group) ->
                require(group.all { it.problem.none { char -> char in "*?[]{}" } }) { "preparation needs exact problem names" }
                val asked = mapOf("suite" to suite, "name" to group.joinToString(",") { it.problem }, "per-family" to "1000000", "files" to "true")
                problems(asked, "files-${group.first().suite.hashCode().toUInt()}").map { (it.suite to it.problem) to it }
            }.toMap()
            selected.map { group -> group.map { problem ->
                val files = requireNotNull(resolved[problem.suite to problem.problem]) { "bench did not resolve selected problem files" }
                portableProblem(problem.copy(format = problem.format.ifEmpty { files.format }, model = files.model, data = files.data, includeDirs = files.includeDirs), primary, corpus)
            } }
        }
        val features = mutableListOf<ProblemFeatures>()
        if (!spec.isProfile && benchPlan == null) for ((index, group) in selected.flatten().distinctBy { it.suite to it.problem }.groupBy { it.suite }.values.withIndex()) {
            checkActive()
            try {
                val asked = mapOf("suite" to group.first().suite, "name" to group.joinToString(",") { it.problem }, "per-family" to "1000000", "features" to "true")
                features += select(asked, "features-$index").map { json.decodeFromString<ProblemFeatures>(it) }.filter { it.themes.isNotEmpty() }
            } catch (failure: IllegalArgumentException) { Files.writeString(log, "classification failed: ${failure.javaClass.simpleName}\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND) }
              catch (failure: WorkerBuildFailure) { Files.writeString(log, "classification failed: ${failure.detail.take(1024)}\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND) }
            checkActive()
        }
        val artifacts = mutableMapOf<String, String>()
        fun transfer(file: Path): String = trace.step("upload") { upload(file) }
        val bundles = prepared.map { (sha, tree) ->
            val original = manifest.bundles.single { it.commit == sha && it.os == os && it.arch == arch }
            trace.step("archive") { archive(tree, directory.resolve("$sha.zip"), original.copy(build = null), runtimeOnly = WorkerTrees.runtimeOnly(spec), checkActive = ::checkActive) }.also { built ->
                // The first solve on this node should reuse the distribution just built here, without a round trip.
                trace.step("cache-publication") { if (inputCache != null) {
                    val input = { Files.newInputStream(directory.resolve("$sha.zip")) }
                    if (WorkerTrees.runtimeOnly(spec)) inputCache.useRuntime(built, ::checkActive, input) { }
                    else inputCache.use(built, ::checkActive, input) { }
                } }
                artifacts["preparation/$sha.zip"] = transfer(directory.resolve("$sha.zip"))
            }
        }
        val inputPaths = selections.flatten().flatMap { listOfNotNull(it.model, it.data) + it.includeDirs }
            .filter { it.startsWith("lab-corpus/") }.map { it.removePrefix("lab-corpus/") }.plus(
                benchPlan?.cases.orEmpty().flatMap { it.inputs }.filter { it.root == com.eignex.lab.bench.BenchInputRoot.CORPUS }.map { it.path }).distinct()
        val sharedInputs = trace.step("shared-inputs") { if (shared != null) shared.publish(inputPaths, ::checkActive) else emptyList() }
        val corpusBundle = if (shared == null && Files.walk(corpus).use { files -> files.anyMatch { Files.isRegularFile(it, NOFOLLOW_LINKS) } }) {
            trace.step("archive") { archive(corpus, directory.resolve("corpus.zip"), manifest.bundles.first().copy(os = os, arch = arch, build = null, toolchain = "corpus-v1"), checkActive = ::checkActive) }
                .also { artifacts["preparation/corpus.zip"] = transfer(directory.resolve("corpus.zip")) }
        } else null
        for (name in Files.list(directory).use { paths -> paths.map { it.fileName.toString() }.filter { it in setOf("setup.log", "setup.err") || it.endsWith(".jsonl") }.toList() }) {
            val path = directory.resolve(name)
            if (Files.isRegularFile(path)) artifacts["preparation/$name"] = transfer(path)
        }
        require(bundles.all { artifacts["preparation/${it.commit}.zip"] == it.sha256 } && (corpusBundle == null || artifacts["preparation/corpus.zip"] == corpusBundle.sha256))
        val timingFile = directory.resolve("preparation-timings.json")
        Files.writeString(timingFile, Json.encodeToString(trace.snapshot().timings))
        artifacts["preparation/timings.json"] = transfer(timingFile)
        trace.ready()
        val timings = trace.snapshot().timings
        println("worker preparation timing: " + timings.entries.joinToString(" ") { "${it.key}Ms=${it.value}" })
        return PreparationResult(selections, bundles, corpusBundle, features, artifacts, sharedInputs, benchPlan, benchDescriptor)
    }
}

/** Archive inventory and stream are bounded independently; links and special files never leave a worker. */
internal fun archive(tree: Path, output: Path, identity: WorkerBundle, runtimeOnly: Boolean = false, checkActive: () -> Unit): WorkerBundle {
    var total = 0L
    val directories = mutableListOf<String>()
    val paths = Files.walk(tree).use { paths -> paths.sorted().filter { path ->
        checkActive()
        if (runtimeOnly && !WorkerTrees.runtimePath(tree.relativize(path))) return@filter false
        if (Files.isDirectory(path, NOFOLLOW_LINKS)) {
            if (path != tree) directories += tree.relativize(path).toString().replace('\\', '/') + "/"
            false
        } else true
    }.map { path ->
        require(Files.isRegularFile(path, NOFOLLOW_LINKS))
        val bytes = Files.size(path); require(bytes <= 32L * 1024 * 1024 * 1024 - total); total += bytes
        path
    }.toList() }
    require(paths.isNotEmpty())
    val files = mutableListOf<InputFile>(); var streamedBytes = 0L
    val archiveDigest = java.security.MessageDigest.getInstance("SHA-256")
    ZipOutputStream(java.security.DigestOutputStream(java.io.BufferedOutputStream(Files.newOutputStream(output), 256 * 1024), archiveDigest)).use { zip ->
        // Runtime distributions contain already-compressed jars; avoid recompressing them at the default level.
        zip.setLevel(java.util.zip.Deflater.BEST_SPEED)
        // Empty directories carry runtime meaning too, including a detached checkout's .git/refs.
        for (directory in directories) {
            checkActive(); zip.putNextEntry(ZipEntry(directory).apply { time = 0 }); zip.closeEntry()
        }
        for (path in paths) {
            val name = tree.relativize(path).toString().replace('\\', '/')
            val before = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            val digest = java.security.MessageDigest.getInstance("SHA-256"); var bytes = 0L
            checkActive(); zip.putNextEntry(ZipEntry(name).apply { time = 0 })
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { checkActive(); val count = input.read(buffer); if (count < 0) break; bytes += count; streamedBytes += count; require(bytes <= before.size() && streamedBytes <= 32L * 1024 * 1024 * 1024); zip.write(buffer, 0, count); digest.update(buffer, 0, count) }
            }; zip.closeEntry()
            val after = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(bytes == before.size() && after.size() == bytes && before.lastModifiedTime() == after.lastModifiedTime() && before.fileKey() == after.fileKey()) { "archive input changed" }
            files += InputFile(name, digest.digest().hex(), bytes, Files.isExecutable(path))
        }
    }
    return identity.copy(sha256 = archiveDigest.digest().hex(), files = files, build = null)
}
