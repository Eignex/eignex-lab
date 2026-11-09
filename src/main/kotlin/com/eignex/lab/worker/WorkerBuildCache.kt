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
import java.nio.file.StandardCopyOption.*

class WorkerBuildFailure(val detail: String) : IllegalStateException("trusted source build failed: $detail")

/** Builds are bounded helpers of their session, not another reservation or a whole-node exclusivity rule. */
class WorkerBuildCache(private val root: Path, private val supervisor: ProcessSupervisor,
                       private val maxBytes: Long = 32L * 1024 * 1024 * 1024,
                       private val dependencyBytes: (Path) -> Long = ::dependencyTreeBytes,
                       private val startMaintenance: (Thread) -> Unit = Thread::start,
                       private val observeBuild: (Path, Path, PreparationBuildDiagnostic) -> Unit = { _, _, _ -> },
                       /** The shared Gradle remote build cache's loopback address ([GradleCacheProxy]), when there is one. */
                       private val remoteCache: () -> String? = { null }) {
    private val dependencyScope = ThreadLocal<DependencyPins?>()
    private val maintenance = WorkerBuildCacheMaintenance(root, maxBytes, dependencyBytes, startMaintenance)
    @Serializable internal data class Entry(val identity: String, val files: List<InputFile>, val workspaceManifest: String? = null)

    fun prepare(bundle: WorkerBundle, source: Path, budget: ResourceBudget, environment: Map<String, String>,
                remainingRunMs: () -> Long? = { null }, trace: PreparationTrace = PreparationTrace(), remainingLeaseMs: () -> Long): Path =
        prepareFrom(bundle, { consume -> consume(source) }, budget, environment, remainingRunMs, trace, remainingLeaseMs)

    private fun prepareFrom(bundle: WorkerBundle, source: ((Path) -> Unit) -> Unit, budget: ResourceBudget, environment: Map<String, String>,
                            remainingRunMs: () -> Long?, trace: PreparationTrace, remainingLeaseMs: () -> Long, pinWaitingMs: Long = 0,
                            declaredWorkspace: Boolean = false): Path {
        fun checkRun() { if (remainingRunMs()?.let { it <= 0 } == true) throw RunDeadlineExceeded() }
        checkRun()
        val build = requireNotNull(bundle.build)
        val identity = identity(bundle)
        Files.createDirectories(root)
        val destination = root.resolve(identity)
        var waitingMs = 0L
        return buildCacheLocked(root.resolve("$identity.lock"), remainingRunMs, remainingLeaseMs, { waitingMs = it }) {
            require(remainingLeaseMs() > 0) { "build lease expired" }
            checkRun()
            cachedTree(destination, identity, build, trace, ::checkRun, waitingMs + pinWaitingMs, declaredWorkspace)
                ?: buildCold(bundle, build, identity, destination, source, budget, environment, remainingRunMs, remainingLeaseMs,
                    trace, ::checkRun, declaredWorkspace)
        }
    }

    /** Under the build lock: a verified published tree is a hit and refreshes its eviction age; null is a miss. */
    private fun cachedTree(destination: Path, identity: String, build: WorkerBuild, trace: PreparationTrace, checkRun: () -> Unit,
                           waitingMs: Long, declaredWorkspace: Boolean): Path? {
        val verification = TreeVerification()
        val present = Files.isDirectory(destination, NOFOLLOW_LINKS)
        val verificationAt = System.nanoTime()
        val hit = trace.step("build-cache") { valid(destination, identity, checkRun, verification,
            build.benchManifest.takeIf { declaredWorkspace }) }
        trace.cache("build", if (hit) "hit" else "miss", if (hit) (if (verification.stats.hashedFiles > 0) "receipt-rebuilt" else "verified") else if (present) "verification-failed" else "absent",
            verificationMs = (System.nanoTime() - verificationAt) / 1_000_000, waitingMs = waitingMs, checkedFiles = verification.stats.checkedFiles,
            hashedFiles = verification.stats.hashedFiles, hashedBytes = verification.stats.hashedBytes)
        if (hit) {
            maintenance.requestMaintenance()
            Files.setLastModifiedTime(destination.resolve("manifest.json"), java.nio.file.attribute.FileTime.fromMillis(now()))
            return destination.resolve("tree")
        }
        return null
    }

    /** Under the build lock: check out, compile and seal a fresh tree in pinned staging, then publish it. */
    private fun buildCold(bundle: WorkerBundle, build: WorkerBuild, identity: String, destination: Path, source: ((Path) -> Unit) -> Unit,
                          budget: ResourceBudget, environment: Map<String, String>, remainingRunMs: () -> Long?, remainingLeaseMs: () -> Long,
                          trace: PreparationTrace, checkRun: () -> Unit, declaredWorkspace: Boolean): Path {
        if (Files.exists(destination, NOFOLLOW_LINKS)) deleteTree(destination)
        val storage = WorkerBuildStaging(root)
        val lease = trace.step("cache-cleanup") { storage.create(checkRun) }
        // Create and pin staging before our background sweep can take its discovery lock.
        val staging = lease.directory
        val disk = BuildDiskBudget(root, staging, maxBytes)
        var dependency: DependencyHome? = null
        return try {
            maintenance.requestMaintenance()
            val cold = ColdBuild(bundle, build, staging, disk, budget, environment, remainingRunMs, remainingLeaseMs, trace, checkRun)
            source { source -> trace.step("checkout") { cold.checkout(source) } }
            val tasks = build.benchManifest?.let { BenchRuns.entry(cold.tree, BenchSpec(it)).buildTasks } ?: build.tasks
            if (tasks.isNotEmpty()) cold.compile(tasks) { dependency = it }
            // Compilation has finished. The sealed output inventory below enforces its final byte
            // limit; stop the temporary staging scan before it competes with checksum capture.
            disk.close()
            // Keep commit objects for committed profile tools that inspect Git; transport was only a local bundle.
            cold.tree.resolve(".gradle").takeIf { Files.exists(it, NOFOLLOW_LINKS) }?.let { deleteTree(it) }
            seal(cold.tree, staging, storage, lease, identity, destination, build, trace, checkRun, declaredWorkspace)
            destination.resolve("tree")
        } finally {
            disk.close()
            try { storage.close(lease) } finally { dependency?.let { home ->
                val scope = dependencyScope.get()
                if (scope == null) home.close() else if (home !in scope.homes) scope.homes.add(home)
            } }
            if (maintenance.discardedBuilds.isNotEmpty()) maintenance.requestMaintenance()
        }
    }

    /** Captures the staged tree's verified inventory, retaining only a declared workspace when asked, and publishes it. */
    private fun seal(tree: Path, staging: Path, storage: WorkerBuildStaging, lease: WorkerBuildStaging.Lease, identity: String,
                     destination: Path, build: WorkerBuild, trace: PreparationTrace, checkRun: () -> Unit, declaredWorkspace: Boolean) {
        val (files, workspaceManifest) = trace.step("build-verification") {
            val manifest = build.benchManifest?.takeIf { declaredWorkspace }?.takeIf { manifest ->
                val spec = BenchSpec(manifest)
                val workspace = BenchRuns.workspaceInputs(tree, spec) ?: return@takeIf false
                // Keep planner identity inputs as well as everything copied to solve workspaces.
                // Profiles and benches without declarations retain their complete checkout.
                maintenance.discardedBuilds.add(storage.retain(lease, workspace + BenchSelections.planInputs(tree, spec).orEmpty(), checkRun))
                true
            }
            val verifier = TreeVerification()
            val files = verifier.capture(tree, staging.resolve("verified.json"), checkRun)
            println("worker build verification: files=${files.size} hashedFiles=${verifier.stats.hashedFiles} hashedBytes=${verifier.stats.hashedBytes} declaredWorkspace=${manifest != null}")
            files to manifest
        }
        require(files.sumOf { it.bytes } <= maxBytes) { "build exceeds cache disk budget" }
        checkRun()
        Files.writeString(staging.resolve("manifest.json"), Json.encodeToString(Entry(identity, files, workspaceManifest)))
        storage.publish(lease, destination, checkRun)
    }

    /** One cold build's processes: checkout and compilation share its logs, environment and build deadline. */
    private inner class ColdBuild(private val bundle: WorkerBundle, private val build: WorkerBuild, private val staging: Path,
                                  private val disk: BuildDiskBudget, private val budget: ResourceBudget, environment: Map<String, String>,
                                  private val remainingRunMs: () -> Long?, private val remainingLeaseMs: () -> Long,
                                  private val trace: PreparationTrace, private val checkRun: () -> Unit) {
        val tree: Path = staging.resolve("tree")
        private val log = staging.resolve("build.out"); private val errors = staging.resolve("build.err")
        private var deadline = System.nanoTime() + build.timeoutSec * 1_000_000_000
        private val heap = buildHeapMiB(budget.memoryMiB)
        private val dependencyKey = ExecutionStore.digest(listOf(bundle.repository, buildToolchain(bundle.toolchain), bundle.os, bundle.arch, System.getProperty("java.version")).joinToString("\n"))
        private val env = environment + mapOf("JAVA_TOOL_OPTIONS" to "-Xmx${heap}m -XX:ActiveProcessorCount=${(budget.cpuMillis / 1000).coerceAtLeast(1)}",
            "LAB_SOURCE_COMMIT" to bundle.commit)
        private var distributionSeed: GradleDistributionSeed.Receipt? = null

        private fun remaining(): Long {
            disk.check()
            val at = System.nanoTime()
            return minOf(remainingLeaseMs(), (deadline - at) / 1_000_000)
        }

        private fun run(argv: List<String>, directory: Path, runEnvironment: Map<String, String> = env, kind: String = "checkout") {
            val child = PreparationBuildDiagnostic(kind = kind, stage = trace.snapshot().stage, sourceCommit = bundle.commit)
            observeBuild(log, errors, child)
            var termination: ProcessTermination? = null
            val exit = try {
                supervisor.run(argv, directory, runEnvironment, log, errors, build.timeoutSec,
                    graceMs = Experiments.STOP_GRACE_SEC * 1000, runRemainingMs = remainingRunMs(),
                    observeTermination = { termination = it }, remainingLeaseMs = ::remaining)
            } finally {
                if (kind == "compilation") distributionSeed?.let { receipt ->
                    Files.writeString(log, "\nGradle distribution seed: sha256=" + receipt.sha256 +
                        " bytes=" + receipt.bytes + " downloaded=" + receipt.downloaded + "\n",
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
                }
                observeBuild(log, errors, child.copy(exitCode = termination?.exitCode,
                signal = termination?.signal, runTimedOut = termination?.runTimedOut)) }
            if (exit != 0) {
                fun tail(path: Path): String = java.io.RandomAccessFile(path.toFile(), "r").use { input ->
                    input.seek((input.length() - 8192).coerceAtLeast(0)); String(ByteArray((input.length() - input.filePointer).toInt()).also { input.readFully(it) })
                }
                checkPreparationExit(exit) { "exit $exit\n" + tail(errors) + "\n" + tail(log) }
            }
        }

        fun checkout(source: Path) {
            if (build.gitBundle == PackedGitSource.MARKER) {
                require(PackedGitSource.usable(bundle)) { "invalid packed source receipt" }
                copy(source, tree, checkRun)
                run(listOf("git", "-c", "core.hooksPath=/dev/null", "checkout", "--detach", "--force", bundle.commit), tree,
                    env.filterKeys { !it.startsWith("GIT_") } + mapOf("GIT_CONFIG_NOSYSTEM" to "1", "GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_NO_REPLACE_OBJECTS" to "1"))
                verifyCheckoutTree(tree, checkRun)
            } else if (build.gitBundle != null) {
                Files.createDirectory(tree)
                run(listOf("git", "-c", "core.hooksPath=/dev/null", "init"), tree)
                // Portable checkouts retain symlink blobs as plain files and never follow host paths.
                run(listOf("git", "config", "--local", "core.symlinks", "false"), tree)
                run(listOf("git", "-c", "core.hooksPath=/dev/null", "fetch", "--no-tags", source.resolve(safeRelative(build.gitBundle)).toString(), "refs/lab-source/${bundle.commit}"), tree)
                run(listOf("git", "-c", "core.hooksPath=/dev/null", "checkout", "--detach", bundle.commit), tree)
                verifyCheckoutTree(tree, checkRun)
                for (file in bundle.files.filter { it.path != build.gitBundle }) {
                    require(safeRelative(file.path).first().toString() == "lab-inputs") { "additional input cannot overwrite committed code" }
                    val target = tree.resolve(safeRelative(file.path)); Files.createDirectories(target.parent)
                    Files.copy(source.resolve(file.path), target, COPY_ATTRIBUTES, REPLACE_EXISTING)
                }
            } else copy(source, tree, checkRun)
        }

        /** [onPinned] receives the dependency home as soon as it is acquired, so the caller releases it on failure too. */
        fun compile(tasks: List<String>, onPinned: (DependencyHome) -> Unit) {
            require(Files.isRegularFile(tree.resolve("gradlew"), NOFOLLOW_LINKS)) { "source archive has no Gradle wrapper" }
            val init = staging.resolve("jvm.init.gradle")
            Files.writeString(init, requireNotNull(this@WorkerBuildCache.javaClass.getResource("/worker-jvm.init.gradle")).readText())
            trace.step("compiler-wait") {
                compilationSlot(remainingRunMs, remainingLeaseMs) {
                    deadline = System.nanoTime() + build.timeoutSec * 1_000_000_000
                    val pinned = acquireDependencyHome(dependencyKey, remainingRunMs, remainingLeaseMs)
                    onPinned(pinned)
                    pinned.directory.let { dependencyHome ->
                        Files.createDirectories(dependencyHome)
                        trace.step("compilation") {
                            distributionSeed = try {
                                GradleDistributionSeed().stage(tree, dependencyHome) { checkRun(); remaining() }
                            } catch (failure: IllegalArgumentException) {
                                throw WorkerBuildFailure("Gradle distribution seed: " + failure.message)
                            }
                            // Task outputs are reused from earlier preparations on any node; the init script binds the cache.
                            val cache = remoteCache()
                            run(listOf("bash", "./gradlew") + tasks + listOf("--no-daemon", "--max-workers=${(budget.cpuMillis / 1000).coerceAtLeast(1)}",
                                "--init-script", init.toString(), "-Dorg.gradle.jvmargs=-Xmx${heap}m", "-Pkotlin.compiler.execution.strategy=in-process", "--console=plain") +
                                listOfNotNull(cache?.let { "--build-cache" }), tree,
                                env + ("GRADLE_USER_HOME" to dependencyHome.toString()) + listOfNotNull(cache?.let { "LAB_GRADLE_CACHE_URL" to it }), kind = "compilation")
                        }
                        Files.setLastModifiedTime(dependencyHome, java.nio.file.attribute.FileTime.fromMillis(now()))
                    }
                }
            }
        }
    }

    internal fun maintain(at: Long = now(), wait: Boolean = true): Unit = maintenance.maintain(at, wait)

    internal fun awaitMaintenanceIdle(timeoutMs: Long = 10_000): Boolean = maintenance.awaitMaintenanceIdle(timeoutMs)

    fun <T> withPrepared(bundle: WorkerBundle, source: Path, budget: ResourceBudget, environment: Map<String, String>,
                         remainingRunMs: () -> Long?, remainingLeaseMs: () -> Long, trace: PreparationTrace = PreparationTrace(), use: (Path) -> T): T =
        withPreparedSource(bundle, budget, environment, remainingRunMs, remainingLeaseMs, trace, { consume -> consume(source) }, use = use)

    /** Warm verified builds need no source archive at all. Acquire source inputs only during cold checkout,
     * retaining their caller-owned pin throughout consumption instead of returning an unpinned cache path.
     * [declaredWorkspace] is only for preparation: [use] may read declared planner inputs and copy the declared
     * workspace. Every other consumer retains complete-tree verification. */
    internal fun <T> withPreparedSource(bundle: WorkerBundle, budget: ResourceBudget, environment: Map<String, String>,
                                       remainingRunMs: () -> Long?, remainingLeaseMs: () -> Long, trace: PreparationTrace = PreparationTrace(),
                                       source: ((Path) -> Unit) -> Unit, declaredWorkspace: Boolean = false, use: (Path) -> T): T {
        Files.createDirectories(root)
        return withDependenciesPinned { trace.step("build-cache") {
            var waitingMs = 0L
            buildCacheLocked(root.resolve("${identity(bundle)}.pin"), remainingRunMs, remainingLeaseMs, { waitingMs = it }) {
                var acquired = false
                val tree = prepareFrom(bundle, { consume -> acquired = true; source(consume) }, budget, environment, remainingRunMs, trace, remainingLeaseMs, waitingMs, declaredWorkspace)
                if (!acquired) trace.cache("input", "bypass", "build-hit")
                use(tree)
            }
        } }
    }

    /** Preparation keeps its dirty dependency homes pinned through planning, input publication and
     * handoff too. Standalone consumers own a shorter scope; nested preparation scopes retain pins
     * until the outermost consumer has finished, including on failure. */
    internal fun <T> withDependenciesPinned(use: () -> T): T {
        if (dependencyScope.get() != null) return use()
        val scope = DependencyPins()
        dependencyScope.set(scope)
        try { return scope.use { use() } } finally { dependencyScope.remove() }
    }

    private inner class DependencyPins : AutoCloseable {
        val homes = mutableListOf<DependencyHome>()
        override fun close() {
            var failure: Throwable? = null
            for (home in homes.asReversed()) try { home.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            failure?.let { throw it }
        }
    }

    /** A prepared distribution can be reconstructed from an identical verified build on this node. The
     * approved per-file manifest, not commit identity alone, proves equivalence; a pin prevents eviction. */
    internal fun materialize(commit: String, files: List<InputFile>, destination: Path, receipt: Path, checkActive: () -> Unit): Boolean {
        if (!Files.isDirectory(root)) return false
        val wanted = files.toSet()
        val candidates = Files.list(root).use { paths -> paths.filter {
            SHA256.matches(it.fileName.toString()) && Files.isDirectory(it, NOFOLLOW_LINKS)
        }.toList() }
        for (candidate in candidates) {
            checkActive()
            val head = candidate.resolve("tree/.git/HEAD")
            if (!Files.isRegularFile(head, NOFOLLOW_LINKS) || runCatching { Files.readString(head).trim() }.getOrNull() != commit) continue
            val key = candidate.fileName.toString()
            ProcessFilePin.tryLock(root.resolve("$key.pin"))?.use candidatePin@ {
                val entry = runCatching { Json.decodeFromString<Entry>(Files.readString(candidate.resolve("manifest.json"))) }.getOrNull() ?: return@candidatePin
                val runtime = entry.files.filter { WorkerTrees.runtimePath(Path.of(it.path)) }.toSet()
                val runtimeOnly = entry.files.toSet() != wanted
                if (runtimeOnly && runtime != wanted || !runtimeOnly && entry.files.size != files.size) return@candidatePin
                val sourceReceipt = candidate.resolve(if (runtimeOnly) "runtime-verified.json" else "verified.json")
                if (runtimeOnly && !Files.exists(sourceReceipt) && Files.isRegularFile(candidate.resolve("verified.json"))) {
                    Files.copy(candidate.resolve("verified.json"), sourceReceipt)
                }
                if (!TreeVerification().valid(candidate.resolve("tree"), files, sourceReceipt, checkActive,
                        exactExecutables = true, allowUndeclared = runtimeOnly)) return@candidatePin
                TreeVerification().copy(candidate.resolve("tree"), sourceReceipt, destination, receipt,
                    checkActive, { !runtimeOnly || WorkerTrees.runtimePath(it) }) {
                    if (runtimeOnly) WorkerTrees.copyRuntime(candidate.resolve("tree"), destination, checkActive)
                    else WorkerTrees.copy(candidate.resolve("tree"), destination, checkActive)
                }
                Files.setLastModifiedTime(candidate.resolve("manifest.json"), java.nio.file.attribute.FileTime.fromMillis(now()))
                return true
            }
        }
        return false
    }

    /** Node-local slots bound cold compilers across allocations; warm cache hits bypass this gate. */
    internal fun <T> compilationSlot(run: () -> Long?, lease: () -> Long, slots: Int =
        System.getenv("LAB_WORKER_MAX_CONCURRENT_BUILDS")?.toIntOrNull() ?: 2, use: () -> T): T {
        return CompilerAdmission(root).use(slots, {
            if (run()?.let { it <= 0 } == true) throw RunDeadlineExceeded()
            if (lease() <= 0) throw LeaseLost("build lease expired while waiting for compiler")
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("compiler wait cancelled")
        }, use)
    }

    private fun identity(bundle: WorkerBundle): String = ExecutionStore.digest((listOf(
        // Existing recipes keep their warm trees; only manifest-driven builds need the SDK identity extension.
        if (bundle.build?.benchManifest == null) "build-cache-v4" else "build-cache-v5",
        bundle.repository, bundle.commit, bundle.os, bundle.arch, buildToolchain(bundle.toolchain),
    ) + listOfNotNull(bundle.build?.benchManifest) + listOf(
        Json.encodeToString(bundle.buildOptions), Json.encodeToString(bundle.build?.tasks.orEmpty()),
        // Git export pack bytes may vary for the same approved commit. Extra inputs still bind their checksums.
        if (bundle.build?.gitBundle == null) bundle.sha256 else "approved-git-commit",
        Json.encodeToString(bundle.files.filter { it.path != bundle.build?.gitBundle }.sortedBy { it.path }),
        // Budgets only bound build heap and parallelism, so jobs at one commit share a tree across budgets.
        System.getProperty("java.vendor"), System.getProperty("java.version"),
        System.getProperty("os.name"), System.getProperty("os.arch"),
        ExecutionStore.digest(requireNotNull(javaClass.getResource("/worker-jvm.init.gradle")).readText()),
    )).joinToString("\n"))

    /** Gradle cannot negotiate cache locks across container namespaces. Reuse the first idle home, or give an
     * already-admitted concurrent build another home. Scheduling budgets bound concurrency; cache eviction bounds disk. */
    internal fun <T> dependencyHome(key: String, run: () -> Long?, lease: () -> Long, use: (Path) -> T): T =
        withDependenciesPinned {
            val home = acquireDependencyHome(key, run, lease)
            try { use(home.directory) } finally {
                val scope = requireNotNull(dependencyScope.get())
                if (home !in scope.homes) scope.homes.add(home)
            }
        }

    /** Keep a dirty dependency home pinned through sealing. Releasing it immediately after Gradle
     * caused background accounting to walk its entire cache while the same USB disk was sealing
     * thousands of compiler outputs. It is still accounted after publication or a failed build. */
    private inner class DependencyHome(val key: String, val directory: Path, private val owner: ProcessFilePin) : AutoCloseable {
        override fun close() {
            try {
                if (Files.isDirectory(directory, NOFOLLOW_LINKS))
                    Files.setLastModifiedTime(directory, java.nio.file.attribute.FileTime.fromMillis(now()))
            } finally { owner.close(); maintenance.requestMaintenance() }
        }
    }

    private fun acquireDependencyHome(key: String, run: () -> Long?, lease: () -> Long): DependencyHome {
        dependencyScope.get()?.homes?.firstOrNull { it.key == key }?.let { return it }
        var slot = 0
        while (true) {
            if (run()?.let { it <= 0 } == true) throw RunDeadlineExceeded()
            if (lease() <= 0) throw LeaseLost("build lease expired while acquiring dependency cache")
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("cache wait cancelled")
            val name = if (slot == 0) key else "$key-$slot"
            val owner = ProcessFilePin.tryLock(root.resolve("dependencies-$name.lock"))
            if (owner != null) {
                try {
                    Files.deleteIfExists(root.resolve("dependency-sizes/$name.json"))
                    return DependencyHome(key, root.resolve("dependencies/$name"), owner)
                } catch (failure: Throwable) { owner.close(); throw failure }
            }
            slot++
        }
    }

    private fun valid(path: Path, identity: String, checkActive: () -> Unit, verification: TreeVerification, workspaceManifest: String? = null): Boolean {
        val entry = runCatching { Json.decodeFromString<Entry>(Files.readString(path.resolve("manifest.json"))) }.getOrNull() ?: return false
        if (entry.files.isEmpty() || entry.files.fold(0L) { total, file ->
                if (file.bytes > maxBytes - total) return false
                total + file.bytes
            } > maxBytes) return false
        if (entry.identity != identity) return false
        // A retained bench tree is complete only for its declared preparation scope. A full-checkout
        // consumer must rebuild; never silently serve a profile or replay a pruned checkout.
        if (entry.workspaceManifest != null && entry.workspaceManifest != workspaceManifest) return false
        val tree = path.resolve("tree")
        fun verify(files: List<InputFile>, name: String, roots: List<Path>): Boolean {
            val receipt = path.resolve(name)
            if (!Files.exists(receipt) && Files.isRegularFile(path.resolve("verified.json"))) Files.copy(path.resolve("verified.json"), receipt)
            return verification.valid(tree, files, receipt, checkActive, exactExecutables = true,
                include = { relative -> relative.toString().isEmpty() || roots.any { relative.startsWith(it) || it.startsWith(relative) } })
        }
        if (workspaceManifest != null) {
            val manifest = entry.files.singleOrNull { it.path == workspaceManifest } ?: return false
            // The manifest chooses the scope, so verify its approved bytes before reading any declarations.
            if (!verify(listOf(manifest), "manifest-verified.json", listOf(safeRelative(workspaceManifest)))) return false
            val spec = BenchSpec(workspaceManifest)
            val workspace = BenchRuns.workspaceInputs(tree, spec)
            if (workspace != null) {
                val roots = (workspace + BenchSelections.planInputs(tree, spec).orEmpty()).distinct().map(::safeRelative)
                val selected = entry.files.filter { file -> roots.any { Path.of(file.path).startsWith(it) } }
                return verify(selected, "workspace-verified.json", roots)
            }
        }
        return verification.valid(tree, entry.files, path.resolve("verified.json"), checkActive, exactExecutables = true)
    }
    private fun copy(source: Path, destination: Path, checkActive: () -> Unit) {
        Files.walk(source).use { paths -> paths.forEach { path ->
            checkActive()
            val target = destination.resolve(source.relativize(path))
            if (Files.isDirectory(path, NOFOLLOW_LINKS)) Files.createDirectories(target) else {
                require(Files.isRegularFile(path, NOFOLLOW_LINKS)); Files.copy(path, target, COPY_ATTRIBUTES)
            }
        } }
    }
}

/** Historical source bundles included the worker release, which is unrelated to compiler outputs.
 * Preserve explicit custom toolchains; the running JDK and build recipe are independently keyed above. */
internal fun buildToolchain(toolchain: String): String =
    if (Regex("jdk25:[a-f0-9]{40}").matches(toolchain)) "jdk25" else toolchain

/** A build cache file lock, waited for while the run and lease remain; maintenance waits without either bound. */
internal fun <T> buildCacheLocked(path: Path, run: () -> Long?, lease: () -> Long, waited: (Long) -> Unit = {}, use: () -> T): T {
    return ProcessFilePin.withLock(path, {
        if (run()?.let { it <= 0 } == true) throw RunDeadlineExceeded()
        if (lease() <= 0) throw LeaseLost("build lease expired while waiting for cache")
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("cache wait cancelled")
    }, pollMs = 50, onWaited = waited, action = use)
}

/**
 * The heap each build JVM may take within a preparation's [memoryMiB]. Gradle compiles Kotlin in its own JVM, which
 * needs most of the budget: a third (1.3 GiB of 4 GiB) left klause's compiler out of memory. Three fifths leave room
 * for that JVM's metaspace, code cache and threads and for the small wrapper JVM inside the container limit.
 */
internal fun buildHeapMiB(memoryMiB: Int): Int = (memoryMiB * 3 / 5).coerceAtLeast(16)
