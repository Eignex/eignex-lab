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
    val armPlans: Map<String, com.eignex.lab.bench.BenchPlan> = emptyMap(),
    /** Planner outputs offered for reuse; the coordinator moves them to its selection cache on acceptance. */
    val selectionCache: List<BenchSelectionEntry> = emptyList(),
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
        it in setOf("PATH", "HOME", "TMPDIR", "JAVA_HOME", "LANG", "LC_ALL", "LAB_CORPUS_MIRROR", "LAB_CORPUS_MIRROR_WRITE") || mac && it == "DOCKER_HOST"
    } + (cache?.let { mapOf("XDG_CACHE_HOME" to it) } ?: emptyMap()) +
        (if (environment["LAB_SHARED_INPUT_ROOT"] != null) mapOf("LAB_CORPUS_KEEP_PACKED" to "true") else emptyMap())
}

/**
 * Set for a bench's `describe` and `plan` when the lab accepts declared input hashes in place of local bytes. A bench
 * that understands it may plan from an index and leave inputs unmaterialized; one that does not simply ignores it.
 */
internal const val DECLARED_INPUTS = "LAB_DECLARED_INPUTS"

/** Preparation is a worker operation under the same supervisor, cgroup and ownership lease as solves. */
internal class PreparationExecutor(private val supervisor: ProcessSupervisor, private val cache: VerifiedCache,
                          private val builds: WorkerBuildCache, private val download: (WorkerBundle) -> java.io.InputStream,
                          private val os: String, private val arch: String,
                          private val shared: SharedInputs? = SharedInputs.environment(), private val inputSigner: String? = null,
                          private val inputCache: WorkerInputCache? = null, private val privateWorkspaces: WorkerWorkspaces? = null,
                          private val trace: PreparationTrace = PreparationTrace(), private val archives: WorkerArchiveCache? = null,
                          private val nativeTrees: NativePreparedTrees? = null, private val nativeJob: Long? = null) {
    /**
     * [cachedSelections] looks accepted planner outputs up by selection key ([BenchSelections]). A verified hit skips that
     * arm's `describe`, `plan` and input pinning; a failed lookup is a miss, so the coordinator never blocks preparation.
     */
    fun run(manifest: WorkManifest, directory: Path, remainingRunMs: () -> Long?, remainingLeaseMs: () -> Long,
            selectProblems: (List<List<Problem>>) -> List<List<Problem>> = { it },
            cachedSelections: (List<String>) -> List<BenchSelectionEntry> = { emptyList() }, upload: (Path) -> String): PreparationResult {
        Files.createDirectories(directory)
        val timeout = requireNotNull(manifest.preparationTimeoutSec)
        val expires = System.nanoTime() + timeout * 1_000_000_000
        fun remaining(): Long = minOf(remainingLeaseMs(), (expires - System.nanoTime()) / 1_000_000)
        fun checkActive() {
            if (remainingLeaseMs() <= 0) throw LeaseLost("preparation lease expired")
            check(remaining() > 0) { "preparation allowance expired" }
            if (remainingRunMs()?.let { it <= 0 } == true) throw RunDeadlineExceeded()
        }
        val replay = manifest.benchReplay
        val sharing = if (replay == null) shared else null
        val corpus = Files.createDirectories(sharing?.root ?: directory.resolve("corpus"))
        fun restoreCorpus(source: Path) {
            Files.walk(source).use { paths -> paths.forEach { file ->
                checkActive(); val target = corpus.resolve(source.relativize(file))
                if (Files.isDirectory(file)) Files.createDirectories(target) else Files.copy(file, target)
            } }
        }
        replay?.corpus?.let { bundle -> restoreCorpus(cache.stage(bundle, ::checkActive) { download(bundle) }) }
        if (!replay?.sharedInputs.isNullOrEmpty()) {
            requireNotNull(shared) { "replay shared inputs require configured transport" }.acquire(replay.sharedInputs, ::checkActive).use { restoreCorpus(it.directory) }
        }
        val env = workerEnvironment(os == "mac") + (inputSigner?.let { mapOf("LAB_INPUT_SIGNER" to it) } ?: emptyMap())
        // Shared inputs are published by content hash and verified on every fetch, so the bench may plan from a corpus
        // index: it declares each input's sha256 and leaves the bytes unmaterialized (see SharedInputs.publish).
        val planEnv = if (sharing != null) env + (DECLARED_INPUTS to "true") else env
        val log = directory.resolve("setup.log"); val errors = directory.resolve("setup.err")
        fun run(tree: Path, command: String, output: Path = log, environment: Map<String, String> = env) {
            checkActive()
            val exit = trace.step(if (output.fileName.toString().startsWith("features-")) "classification" else "selection") { supervisor.run(listOf("bash", "-c", command), tree, environment, output, errors, timeout,
                graceMs = Experiments.STOP_GRACE_SEC * 1000, runRemainingMs = remainingRunMs(), remainingLeaseMs = ::remaining) }
            checkPreparationExit(exit) { "preparation command failed (exit $exit): " + Files.readString(errors).takeLast(8192) }
        }
        val prepared = manifest.arms.map { it.sha }.distinct().associateWith { sha ->
            val bundle = manifest.bundles.single { it.commit == sha && it.os == os && it.arch == arch }
            fun copy(built: Path): Path = trace.step("workspace") {
                privateWorkspaces?.copy(ExecutionStore.digest(sha), built, ::checkActive, false)?.let { return@step it }
                val tree = directory.resolve("worktrees/$sha")
                WorkerTrees.copy(built, tree, ::checkActive)
                tree
            }
            fun acquireSource(consume: (Path) -> Unit) = trace.step("inputs") {
                if (inputCache != null) inputCache.use(bundle, ::checkActive, { download(bundle) }, trace, consume)
                else consume(cache.stage(bundle, ::checkActive, { download(bundle) }, trace))
            }
            if (bundle.tree != null && nativeTrees != null) nativeTrees.use(bundle, ::checkActive) { tree, _ -> copy(tree) }
            else if (bundle.build != null) builds.withPreparedSource(bundle, manifest.budget, env, remainingRunMs, ::remaining, trace, ::acquireSource, ::copy)
            else trace.step("inputs") {
                inputCache?.use(bundle, ::checkActive, { download(bundle) }, trace, ::copy)
                    ?: copy(cache.stage(bundle, ::checkActive, { download(bundle) }, trace))
            }
        }
        val spec = manifest.experiment
        val bench = spec.work.bench
        val armPlans = linkedMapOf<String, com.eignex.lab.bench.BenchPlan>()
        val inputDigests = BenchInputDigests(::checkActive)
        var descriptor: com.eignex.lab.bench.BenchDescriptor? = replay?.descriptor
        var materialize: () -> Unit = {}
        val reusedSelections = linkedMapOf<String, BenchSelectionEntry>()
        val plannedSelections = linkedMapOf<String, BenchSelectionEntry>()
        var planned = false
        if (replay != null) {
            armPlans.putAll(replay.plans)
            for (arm in manifest.arms) for (case in armPlans.getValue(arm.arm.label).cases) {
                val current = BenchRuns.pinned(case, prepared.getValue(arm.sha), corpus, digests = inputDigests)
                require(current.attributes["labInputDigest"] == case.attributes["labInputDigest"]) { "replay input bytes changed" }
            }
        } else if (bench != null) {
            val repositoryArms = manifest.arms.filter { it.arm.values["backend"] != REFERENCE_BACKEND }.ifEmpty { listOf(manifest.arms.first()) }
            val keys = trace.step("selection") { repositoryArms.associate { arm -> arm.arm.label to try {
                BenchSelections.key(manifest.release, spec.repository, bench, BenchRuns.request(bench, arm.arm), arm.sha,
                    prepared.getValue(arm.sha), sharing?.let { "shared:${it.identity}" } ?: "local", ::checkActive)
            } catch (_: IllegalArgumentException) { null } catch (_: java.io.IOException) { null } } }
            val found = keys.values.filterNotNull().distinct().takeIf { it.isNotEmpty() }?.let { wanted -> trace.step("selection") {
                try { cachedSelections(wanted).filter { it.key in wanted }.associateBy { it.key } }
                catch (failure: Exception) {
                    if (failure is ExecutionFenced || failure is LeaseLost || failure is RunDeadlineExceeded) throw failure
                    println("worker selection cache unavailable: ${failure.javaClass.simpleName}"); emptyMap()
                }
            } }.orEmpty()
            // Corpus bytes of a reused plan come from its shared inputs, checked on fetch; source bytes are checked here.
            fun reusable(entry: BenchSelectionEntry, tree: Path): Boolean = try {
                BenchRuns.resources(entry.plan)
                val corpusPaths = BenchSelections.corpusPaths(entry.plan)
                (corpusPaths.isEmpty() || sharing != null && BenchSelections.covering(entry.sharedInputs, corpusPaths) != null) &&
                    BenchSelections.sourceDigest(entry.plan, tree, inputDigests) == entry.sourceDigest
            } catch (_: IllegalArgumentException) { false } catch (_: java.io.IOException) { false }
            fun plan(index: Int, arm: PlannedArm, key: String?) {
                val tree = prepared.getValue(arm.sha)
                planned = true
                val request = directory.resolve("bench-request-$index.json")
                Files.writeString(request, com.eignex.lab.bench.BenchProtocol.json.encodeToString(
                    com.eignex.lab.bench.BenchRequest.serializer(), BenchRuns.request(bench, arm.arm)))
                val prefix = "export LAB_WORKTREE=${quote(tree.toString())} LAB_CORPUS=${quote(corpus.toString())} LAB_REPOSITORY=${quote(spec.repository)} && "
                val descriptionFile = directory.resolve("bench-descriptor-$index.jsonl")
                run(tree, prefix + BenchRuns.invocation(tree, bench, "describe"), descriptionFile, planEnv)
                val current = com.eignex.lab.bench.BenchProtocol.json.decodeFromString<com.eignex.lab.bench.BenchDescriptor>(Files.readString(descriptionFile))
                descriptor?.let { require(it.id == current.id) { "bench arms name different suites" } }
                descriptor = current
                val planFile = directory.resolve("bench-plan-$index.jsonl")
                run(tree, prefix + BenchRuns.invocation(tree, bench, "plan", request.toString()), planFile, planEnv)
                // Arms share one corpus, so whichever arm planned can materialize it.
                materialize = {
                    run(tree, prefix + BenchRuns.invocation(tree, bench, "plan", request.toString()), directory.resolve("bench-materialized-plan.jsonl"))
                }
                val plan = com.eignex.lab.bench.BenchProtocol.json.decodeFromString<com.eignex.lab.bench.BenchPlan>(Files.readString(planFile))
                BenchRuns.resources(plan)
                val runtime = BenchRuns.runtime(tree, bench)
                val pinned = plan.copy(cases = plan.cases.map { BenchRuns.pinned(it, tree, corpus, runtime, inputDigests, trustDeclared = sharing != null) })
                armPlans[arm.arm.label] = pinned
                if (key != null) plannedSelections[arm.arm.label] = BenchSelectionEntry(key, arm.arm.label, current, pinned,
                    BenchSelections.sourceDigest(pinned, tree, inputDigests))
            }
            for ((index, arm) in repositoryArms.withIndex()) {
                val key = keys[arm.arm.label]
                val hit = key?.let(found::get)?.takeIf { trace.step("selection") { reusable(it, prepared.getValue(arm.sha)) } }
                if (hit != null) {
                    descriptor?.let { require(it.id == hit.descriptor.id) { "bench arms name different suites" } }
                    descriptor = hit.descriptor
                    armPlans[arm.arm.label] = hit.plan
                    reusedSelections[arm.arm.label] = hit
                    trace.cache("selection", "hit", "accepted-selection")
                } else {
                    if (key != null) trace.cache("selection", "miss", if (key in found) "verification-failed" else "absent")
                    plan(index, arm, key)
                }
            }
            fun align() {
                val first = armPlans.values.firstOrNull() ?: error("a bench experiment needs at least one repository arm")
                val canonical = bench.frozenCases.ifEmpty { first.cases }
                for ((label, plan) in armPlans.toMap()) {
                    if (bench.frozenCases.isEmpty()) require(plan.cases.map(BenchRuns::key).toSet() == canonical.map(BenchRuns::key).toSet()) { "bench arms selected different workloads" }
                    armPlans[label] = BenchRuns.align(canonical, plan)
                }
            }
            try { align() } catch (failure: IllegalArgumentException) {
                // A planning arm disagrees with a reused plan: plan those arms too, so the cache never fails a job alone.
                if (reusedSelections.isEmpty() || !planned) throw failure
                for ((index, arm) in repositoryArms.withIndex()) if (reusedSelections.remove(arm.arm.label) != null) {
                    trace.cache("selection", "miss", "verification-failed")
                    plan(index, arm, keys[arm.arm.label])
                }
                align()
            }
            val selected = armPlans.values.first()
            for (arm in manifest.arms.filter { it.arm.values["backend"] == REFERENCE_BACKEND })
                armPlans[arm.arm.label] = BenchRuns.referencePlan(selected, arm.arm)
        }
        var benchPlan = armPlans.values.firstOrNull()
        val raw = if (benchPlan != null) listOf(benchPlan.cases.map(BenchRuns::problem))
            else listOf(listOf(Experiments.profileProblem(spec)))
        val selections = trace.step("selection") { selectProblems(raw) }
        if (benchPlan != null) {
            val keys = selections.single().map { BenchRuns.key(requireNotNull(it.benchCase)) }.toSet()
            require(keys.isNotEmpty()) { "reference filter kept no workloads" }
            for ((label, plan) in armPlans.toMap()) armPlans[label] = plan.copy(cases = plan.cases.filter { BenchRuns.key(it) in keys })
            benchPlan = benchPlan.copy(cases = benchPlan.cases.filter { BenchRuns.key(it) in keys })
        } else require(selections == raw)

        println("worker selection verification: hashedFiles=${inputDigests.hashedFiles} reusedFiles=${inputDigests.reusedFiles} hashedBytes=${inputDigests.hashedBytes} reusedPlans=${reusedSelections.size}")
        val features = emptyList<ProblemFeatures>()
        val artifacts = mutableMapOf<String, String>()
        fun transfer(file: Path): String = trace.step("upload") { upload(file) }
        val inputPaths = selections.flatten().flatMap { listOfNotNull(it.model, it.data) + it.includeDirs }
            .filter { it.startsWith("lab-corpus/") }.map { it.removePrefix("lab-corpus/") }.plus(
                armPlans.values.flatMap { it.cases }.flatMap { it.inputs }.filter { it.root == com.eignex.lab.bench.BenchInputRoot.CORPUS }.map { it.path }).distinct()
        val declared = armPlans.values.flatMap { it.cases }.flatMap { it.inputs }
            .filter { it.root == com.eignex.lab.bench.BenchInputRoot.CORPUS && it.sha256 != null }.associate { it.path to it.sha256!! }
        // When no arm planned, the corpus was never fetched here: its accepted shared inputs stand in for publication.
        // Aligned arms resolved identical input digests, so any arm that did plan left every selected input resolvable.
        val sharedInputs = trace.step("shared-inputs") { when {
            sharing == null -> emptyList()
            reusedSelections.isNotEmpty() && !planned -> requireNotNull(BenchSelections.covering(
                reusedSelections.values.flatMap { it.sharedInputs }, inputPaths)) { "accepted selection omitted a selected input" }
            else -> try { sharing.publish(inputPaths, ::checkActive, declared) }
                catch (missing: SharedInputs.MissingInputs) {
                    // First use of these bytes: plan once more without declared inputs so the bench materializes them here.
                    println("worker shared inputs: ${missing.paths.size} declared input(s) unstored, materializing")
                    materialize(); sharing.publish(inputPaths, ::checkActive, declared)
                }
        } }
        val selectionCache = plannedSelections.values.mapNotNull { entry ->
            val corpusPaths = BenchSelections.corpusPaths(entry.plan)
            if (corpusPaths.isEmpty()) entry
            else BenchSelections.covering(sharedInputs, corpusPaths)?.let { entry.copy(sharedInputs = it) }
        }
        val bundles = prepared.map { (sha, tree) ->
            val original = manifest.bundles.single { it.commit == sha && it.os == os && it.arch == arch }
            fun publish(zip: Path, built: WorkerBundle): WorkerBundle {
                // Seed the solve input cache without a coordinator round trip.
                trace.step("cache-publication") { if (inputCache != null) {
                    inputCache.seed(built, tree, tree.resolveSibling("${tree.fileName}.archive-verified.json"), ::checkActive, trace)
                } }
                artifacts["preparation/$sha.zip"] = transfer(zip)
                return built
            }
            if (replay != null) {
                // TrustedQueueSources already copied this accepted input blob into the new job namespace.
                // Preserve its exact archive identity across changes to compression or archive inventory code.
                artifacts[preparationArtifact(original)] = original.sha256
                original
            } else if (nativeTrees != null && nativeJob != null && manifest.experiment.isBench && manifest.experiment.host == HostPlacement.LOCAL) {
                trace.step("local-handoff") {
                    val proof = tree.resolveSibling("${tree.fileName}.verified.json")
                    val (metadata, snapshot) = nativeTrees.publish(nativeJob, tree, proof, original.copy(build = null), ::checkActive)
                    artifacts[preparationArtifact(snapshot)] = transfer(metadata)
                    trace.cache("handoff", "hit", "job-owned-snapshot", bytes = snapshot.files.sumOf { it.bytes }, files = snapshot.files.size)
                    snapshot
                }
            } else if (archives != null) trace.step("archive") { archives.use(tree, original.copy(build = null), false, ::checkActive, trace, ::publish) }
            else {
                val zip = directory.resolve("$sha.zip")
                publish(zip, trace.step("archive") { archive(tree, zip, original.copy(build = null), runtimeOnly = false, checkActive = ::checkActive) })
            }
        }
        val corpusBundle = if (sharing == null && Files.walk(corpus).use { files -> files.anyMatch { Files.isRegularFile(it, NOFOLLOW_LINKS) } }) {
            trace.step("archive") { archive(corpus, directory.resolve("corpus.zip"), manifest.bundles.first().copy(os = os, arch = arch, build = null, tree = null, toolchain = "corpus-v1"), checkActive = ::checkActive) }
                .also { artifacts["preparation/corpus.zip"] = transfer(directory.resolve("corpus.zip")) }
        } else null
        for (name in Files.list(directory).use { paths -> paths.map { it.fileName.toString() }.filter { it in setOf("setup.log", "setup.err") || it.endsWith(".jsonl") }.toList() }) {
            val path = directory.resolve(name)
            if (Files.isRegularFile(path)) artifacts["preparation/$name"] = transfer(path)
        }
        require(bundles.all { artifacts[preparationArtifact(it)] == it.sha256 } && (corpusBundle == null || artifacts["preparation/corpus.zip"] == corpusBundle.sha256))
        val cacheFile = directory.resolve("preparation-cache.json")
        Files.writeString(cacheFile, Json.encodeToString(trace.snapshot().caches))
        artifacts["preparation/cache.json"] = transfer(cacheFile)
        val timingFile = directory.resolve("preparation-timings.json")
        Files.writeString(timingFile, Json.encodeToString(trace.snapshot().timings))
        artifacts["preparation/timings.json"] = transfer(timingFile)
        trace.ready()
        val timings = trace.snapshot().timings
        println("worker preparation timing: " + timings.entries.joinToString(" ") { "${it.key}Ms=${it.value}" })
        return PreparationResult(selections, bundles, corpusBundle, features, artifacts, sharedInputs, benchPlan, descriptor, armPlans, selectionCache)
    }
}

/** Archive inventory and stream are bounded independently; links and special files never leave a worker. */
internal fun archive(tree: Path, output: Path, identity: WorkerBundle, runtimeOnly: Boolean = false, checkActive: () -> Unit): WorkerBundle {
    var total = 0L
    val directories = mutableListOf<String>()
    val paths = mutableListOf<Path>()
    Files.walkFileTree(tree, object : java.nio.file.SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
            checkActive()
            val relative = tree.relativize(dir)
            if (runtimeOnly && !WorkerTrees.runtimePath(relative)) return java.nio.file.FileVisitResult.SKIP_SUBTREE
            if (dir != tree) directories += relative.toString().replace('\\', '/') + "/"
            return java.nio.file.FileVisitResult.CONTINUE
        }
        override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
            checkActive()
            if (runtimeOnly && !WorkerTrees.runtimePath(tree.relativize(file))) return java.nio.file.FileVisitResult.CONTINUE
            require(attrs.isRegularFile)
            require(attrs.size() <= 32L * 1024 * 1024 * 1024 - total); total += attrs.size()
            paths.add(file)
            return java.nio.file.FileVisitResult.CONTINUE
        }
    })
    return archiveInventory(tree, output, identity, paths, directories, checkActive)
}
