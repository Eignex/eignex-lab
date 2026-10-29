package com.eignex.lab

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
                       private val startMaintenance: (Thread) -> Unit = Thread::start) {
    private val maintenanceRunning = java.util.concurrent.atomic.AtomicBoolean()
    @Serializable private data class DependencyUsage(val key: String, val modified: String, val bytes: Long)
    @Serializable private data class Entry(val identity: String, val files: List<InputFile>)

    fun prepare(bundle: WorkerBundle, source: Path, budget: ResourceBudget, environment: Map<String, String>,
                remainingRunMs: () -> Long? = { null }, trace: PreparationTrace = PreparationTrace(), remainingLeaseMs: () -> Long): Path =
        prepareFrom(bundle, { consume -> consume(source) }, budget, environment, remainingRunMs, trace, remainingLeaseMs)

    private fun prepareFrom(bundle: WorkerBundle, source: ((Path) -> Unit) -> Unit, budget: ResourceBudget, environment: Map<String, String>,
                            remainingRunMs: () -> Long?, trace: PreparationTrace, remainingLeaseMs: () -> Long, pinWaitingMs: Long = 0): Path {
        fun checkRun() { if (remainingRunMs()?.let { it <= 0 } == true) throw RunDeadlineExceeded() }
        checkRun()
        val build = requireNotNull(bundle.build)
        val identity = identity(bundle, budget)
        Files.createDirectories(root)
        val destination = root.resolve(identity)
        var waitingMs = 0L
        return locked(root.resolve("$identity.lock"), remainingRunMs, remainingLeaseMs, { waitingMs = it }) {
            require(remainingLeaseMs() > 0) { "build lease expired" }
            checkRun()
            val verification = TreeVerification()
            val present = Files.isDirectory(destination, NOFOLLOW_LINKS)
            val verificationAt = System.nanoTime()
            val hit = trace.step("build-cache") { valid(destination, identity, ::checkRun, verification) }
            trace.cache("build", if (hit) "hit" else "miss", if (hit) (if (verification.stats.hashedFiles > 0) "receipt-rebuilt" else "verified") else if (present) "verification-failed" else "absent",
                verificationMs = (System.nanoTime() - verificationAt) / 1_000_000, waitingMs = waitingMs + pinWaitingMs, checkedFiles = verification.stats.checkedFiles,
                hashedFiles = verification.stats.hashedFiles, hashedBytes = verification.stats.hashedBytes)
            if (hit) {
                requestMaintenance()
                Files.setLastModifiedTime(destination.resolve("manifest.json"), java.nio.file.attribute.FileTime.fromMillis(now()))
                return@locked destination.resolve("tree")
            }
            if (Files.exists(destination, NOFOLLOW_LINKS)) delete(destination)
            val storage = WorkerBuildStaging(root)
            val lease = trace.step("cache-cleanup") { storage.create(::checkRun) }
            // Create and pin staging before our background sweep can take its discovery lock.
            val staging = lease.directory
            try {
                requestMaintenance()
                val tree = staging.resolve("tree")
                val log = staging.resolve("build.out"); val errors = staging.resolve("build.err")
                var deadline = System.nanoTime() + build.timeoutSec * 1_000_000_000
                var nextDiskCheck = 0L
                fun remaining(): Long {
                    val at = System.nanoTime()
                    if (at >= nextDiskCheck) {
                        check(Files.getFileStore(root).usableSpace >= 1024L * 1024 * 1024) { "build disk held" }
                        val used = Files.walk(staging).use { it.filter { file -> Files.isRegularFile(file, NOFOLLOW_LINKS) }.mapToLong(Files::size).sum() }
                        require(used <= maxBytes) { "build staging exceeds disk budget" }
                        nextDiskCheck = at + 30_000_000_000
                    }
                    return minOf(remainingLeaseMs(), (deadline - at) / 1_000_000)
                }
                val heap = (budget.memoryMiB / 3).coerceAtLeast(16)
                val dependencyKey = ExecutionStore.digest(listOf(bundle.repository, buildToolchain(bundle.toolchain), bundle.os, bundle.arch, System.getProperty("java.version")).joinToString("\n"))
                val env = environment + mapOf("JAVA_TOOL_OPTIONS" to "-Xmx${heap}m -XX:ActiveProcessorCount=${(budget.cpuMillis / 1000).coerceAtLeast(1)}",
                    "LAB_SOURCE_COMMIT" to bundle.commit)
                fun run(argv: List<String>, directory: Path, runEnvironment: Map<String, String> = env) {
                    val exit = supervisor.run(argv, directory, runEnvironment, log, errors, build.timeoutSec,
                        graceMs = Experiments.STOP_GRACE_SEC * 1000, runRemainingMs = remainingRunMs(), remainingLeaseMs = ::remaining)
                    if (exit != 0) {
                        fun tail(path: Path): String = java.io.RandomAccessFile(path.toFile(), "r").use { input ->
                            input.seek((input.length() - 8192).coerceAtLeast(0)); String(ByteArray((input.length() - input.filePointer).toInt()).also { input.readFully(it) })
                        }
                        checkPreparationExit(exit) { "exit $exit\n" + tail(errors) + "\n" + tail(log) }
                    }
                }
                source { source -> trace.step("checkout") {
                    if (build.gitBundle != null) {
                        Files.createDirectory(tree)
                        run(listOf("git", "-c", "core.hooksPath=/dev/null", "init"), tree)
                        // Portable checkouts retain symlink blobs as plain files and never follow host paths.
                        run(listOf("git", "config", "--local", "core.symlinks", "false"), tree)
                        run(listOf("git", "-c", "core.hooksPath=/dev/null", "fetch", "--no-tags", source.resolve(safeRelative(build.gitBundle)).toString(), "refs/lab-source/${bundle.commit}"), tree)
                        run(listOf("git", "-c", "core.hooksPath=/dev/null", "checkout", "--detach", bundle.commit), tree)
                        Files.walk(tree).use { paths -> require(paths.noneMatch(Files::isSymbolicLink)) { "source tree contains unsupported links" } }
                        for (file in bundle.files.filter { it.path != build.gitBundle }) {
                            require(safeRelative(file.path).first().toString() == "lab-inputs") { "additional input cannot overwrite committed code" }
                            val target = tree.resolve(safeRelative(file.path)); Files.createDirectories(target.parent)
                            Files.copy(source.resolve(file.path), target, COPY_ATTRIBUTES, REPLACE_EXISTING)
                        }
                    } else copy(source, tree, ::checkRun)
                } }
                val tasks = build.benchManifest?.let { BenchRuns.entry(tree, BenchSpec(it)).buildTasks } ?: build.tasks
                if (tasks.isNotEmpty()) {
                    require(Files.isRegularFile(tree.resolve("gradlew"), NOFOLLOW_LINKS)) { "source archive has no Gradle wrapper" }
                    val init = staging.resolve("jvm.init.gradle")
                    Files.writeString(init, requireNotNull(javaClass.getResource("/worker-jvm.init.gradle")).readText())
                    trace.step("compiler-wait") {
                        compilationSlot(remainingRunMs, remainingLeaseMs) {
                            deadline = System.nanoTime() + build.timeoutSec * 1_000_000_000
                            dependencyHome(dependencyKey, remainingRunMs, remainingLeaseMs) { dependencyHome ->
                                Files.createDirectories(dependencyHome)
                                trace.step("compilation") {
                                    run(listOf("bash", "./gradlew") + tasks + listOf("--no-daemon", "--max-workers=${(budget.cpuMillis / 1000).coerceAtLeast(1)}",
                                        "--init-script", init.toString(), "-Dorg.gradle.jvmargs=-Xmx${heap}m", "-Pkotlin.compiler.execution.strategy=in-process", "-q"), tree,
                                        env + ("GRADLE_USER_HOME" to dependencyHome.toString()))
                                }
                                Files.setLastModifiedTime(dependencyHome, java.nio.file.attribute.FileTime.fromMillis(now()))
                            }
                        }
                    }
                }
                // Keep commit objects for committed profile tools that inspect Git; transport was only a local bundle.
                tree.resolve(".gradle").takeIf { Files.exists(it, NOFOLLOW_LINKS) }?.let(::delete)
                val files = trace.step("build-verification") { TreeVerification().capture(tree, staging.resolve("verified.json"), ::checkRun) }
                require(files.sumOf { it.bytes } <= maxBytes) { "build exceeds cache disk budget" }
                checkRun()
                Files.writeString(staging.resolve("manifest.json"), Json.encodeToString(Entry(identity, files)))
                storage.publish(lease, destination, ::checkRun)
                destination.resolve("tree")
            } finally { storage.close(lease) }
        }
    }
    fun <T> withPrepared(bundle: WorkerBundle, source: Path, budget: ResourceBudget, environment: Map<String, String>,
                         remainingRunMs: () -> Long?, remainingLeaseMs: () -> Long, trace: PreparationTrace = PreparationTrace(), use: (Path) -> T): T =
        withPreparedSource(bundle, budget, environment, remainingRunMs, remainingLeaseMs, trace, { consume -> consume(source) }, use)

    /** Warm verified builds need no source archive at all. Acquire source inputs only during cold checkout,
     * retaining their caller-owned pin throughout consumption instead of returning an unpinned cache path. */
    internal fun <T> withPreparedSource(bundle: WorkerBundle, budget: ResourceBudget, environment: Map<String, String>,
                                       remainingRunMs: () -> Long?, remainingLeaseMs: () -> Long, trace: PreparationTrace = PreparationTrace(),
                                       source: ((Path) -> Unit) -> Unit, use: (Path) -> T): T {
        Files.createDirectories(root)
        return trace.step("build-cache") {
            var waitingMs = 0L
            locked(root.resolve("${identity(bundle, budget)}.pin"), remainingRunMs, remainingLeaseMs, { waitingMs = it }) {
                var acquired = false
                val tree = prepareFrom(bundle, { consume -> acquired = true; source(consume) }, budget, environment, remainingRunMs, trace, remainingLeaseMs, waitingMs)
                if (!acquired) trace.cache("input", "bypass", "build-hit")
                use(tree)
            }
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
        require(slots in 1..64)
        while (true) {
            if (run()?.let { it <= 0 } == true) throw RunDeadlineExceeded()
            if (lease() <= 0) throw LeaseLost("build lease expired while waiting for compiler")
            for (slot in 0 until slots) {
                ProcessFilePin.tryLock(root.resolve("compile-$slot.lock"))?.use { return use() }
            }
            Thread.sleep(50)
        }
    }

    private fun identity(bundle: WorkerBundle, budget: ResourceBudget): String = ExecutionStore.digest((listOf(
        // Existing recipes keep their warm trees; only manifest-driven builds need the SDK identity extension.
        if (bundle.build?.benchManifest == null) "build-cache-v4" else "build-cache-v5",
        bundle.repository, bundle.commit, bundle.os, bundle.arch, buildToolchain(bundle.toolchain),
    ) + listOfNotNull(bundle.build?.benchManifest) + listOf(
        Json.encodeToString(bundle.buildOptions), Json.encodeToString(bundle.build?.tasks.orEmpty()),
        // Git export pack bytes may vary for the same approved commit. Extra inputs still bind their checksums.
        if (bundle.build?.gitBundle == null) bundle.sha256 else "approved-git-commit",
        Json.encodeToString(bundle.files.filter { it.path != bundle.build?.gitBundle }.sortedBy { it.path }),
        Json.encodeToString(budget), System.getProperty("java.vendor"), System.getProperty("java.version"),
        System.getProperty("os.name"), System.getProperty("os.arch"),
        ExecutionStore.digest(requireNotNull(javaClass.getResource("/worker-jvm.init.gradle")).readText()),
    )).joinToString("\n"))

    /** Gradle cannot negotiate cache locks across container namespaces. Reuse the first idle home, or give an
     * already-admitted concurrent build another home. Scheduling budgets bound concurrency; cache eviction bounds disk. */
    internal fun <T> dependencyHome(key: String, run: () -> Long?, lease: () -> Long, use: (Path) -> T): T {
        var slot = 0
        while (true) {
            if (run()?.let { it <= 0 } == true) throw RunDeadlineExceeded()
            if (lease() <= 0) throw LeaseLost("build lease expired while acquiring dependency cache")
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("cache wait cancelled")
            val name = if (slot == 0) key else "$key-$slot"
            val owner = ProcessFilePin.tryLock(root.resolve("dependencies-$name.lock"))
            if (owner != null) {
                try { owner.use {
                    val home = root.resolve("dependencies/$name")
                    // Invalidate before any writer enters, including failed/crashed builds.
                    Files.deleteIfExists(root.resolve("dependency-sizes/$name.json"))
                    try { return use(home) }
                    finally { if (Files.isDirectory(home, NOFOLLOW_LINKS)) Files.setLastModifiedTime(home, java.nio.file.attribute.FileTime.fromMillis(now())) }
                } } finally { requestMaintenance() }
            }
            slot++
        }
    }

    private fun <T> locked(path: Path, run: () -> Long?, lease: () -> Long, waited: (Long) -> Unit = {}, use: () -> T): T {
        var waitingNanos = 0L
        while (true) {
            if (run()?.let { it <= 0 } == true) throw RunDeadlineExceeded()
            if (lease() <= 0) throw LeaseLost("build lease expired while waiting for cache")
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("cache wait cancelled")
            ProcessFilePin.tryLock(path)?.use { waited(waitingNanos / 1_000_000); return use() }
            val waitingAt = System.nanoTime()
            Thread.sleep(50)
            waitingNanos += System.nanoTime() - waitingAt
        }
    }

    /** Foreground preparation never performs an unbounded legacy inventory scan. */
    private fun requestMaintenance() {
        if (!maintenanceRunning.compareAndSet(false, true)) return
        val thread = Thread({
            try { maintain(wait = false) }
            catch (_: java.io.IOException) { println("worker build cache cleanup deferred") }
            finally { maintenanceRunning.set(false) }
        }, "worker-build-cleanup").apply { isDaemon = true }
        try { startMaintenance(thread) }
        catch (failure: Throwable) { maintenanceRunning.set(false); throw failure }
    }

    /** The sweep pin serializes accounting; the staging lock covers only snapshots and atomic renames.
     * Published manifests account immutable builds. Dependency homes are scanned once per dirty generation,
     * under their own pin, outside the staging lock. Active/unknown homes count conservatively. */
    internal fun maintain(at: Long = now(), wait: Boolean = true) {
        // The request flag is reserved before Thread.start(), so a synchronous caller also drains
        // requests that have not been scheduled yet, before it concludes maintenance is finished.
        if (wait) check(awaitMaintenanceIdle(Long.MAX_VALUE))
        Files.createDirectories(root)
        val sweep = root.resolve("maintenance-sweep.lock")
        if (wait) locked(sweep, { null }, { Long.MAX_VALUE }) { maintainSweep(at) }
        else ProcessFilePin.tryLock(sweep)?.use { maintainSweep(at) }
    }

    /** After foreground users finish, wait until this instance has no pending filesystem work. */
    internal fun awaitMaintenanceIdle(timeoutMs: Long = 10_000): Boolean {
        require(timeoutMs >= 0)
        val started = System.nanoTime()
        val timeoutNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (maintenanceRunning.get()) {
            checkMaintenanceActive()
            if (System.nanoTime() - started >= timeoutNanos) return false
            Thread.sleep(10)
        }
        return true
    }

    private fun <T> maintenanceLock(use: () -> T): T = locked(root.resolve("maintenance.lock"), { null }, { Long.MAX_VALUE }, use = use)

    private fun maintainSweep(at: Long) {
        data class Snapshot(val builds: List<Path>, val dependencies: List<Path>, val garbage: List<Path>)
        val snapshot = maintenanceLock {
            val stamp = root.resolve("maintenance.timestamp")
            if (Files.exists(stamp) && at - Files.getLastModifiedTime(stamp).toMillis() < 60_000) return@maintenanceLock null
            Files.writeString(stamp, ""); Files.setLastModifiedTime(stamp, java.nio.file.attribute.FileTime.fromMillis(at))
            val garbage = WorkerBuildStaging(root).reapLocked(at).toMutableList()
            val builds = Files.list(root).use { paths -> paths.filter { SHA256.matches(it.fileName.toString()) }.toList() }
            val dependencies = root.resolve("dependencies").let { directory ->
                if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) emptyList() else Files.list(directory).use { it.toList() }
            }
            Files.list(root).use { paths -> garbage.addAll(paths.filter { it.fileName.toString().startsWith(".evict-") }.toList()) }
            Snapshot(builds, dependencies, garbage)
        } ?: return
        data class Candidate(val path: Path, val lock: Path, val bytes: Long, val used: Long, val key: String?, val usage: DependencyUsage? = null, val accounted: Boolean = true)
        val candidates = mutableListOf<Candidate>()
        val unknown = maxBytes.coerceAtLeast(1)
        fun attributes(path: Path) = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        for (path in snapshot.builds) {
            checkMaintenanceActive()
            if (!Files.isDirectory(path, NOFOLLOW_LINKS)) continue
            val manifest = path.resolve("manifest.json")
            val entry = runCatching { Json.decodeFromString<Entry>(Files.readString(manifest)) }.getOrNull()
            val bytes = runCatching {
                require(entry != null && entry.identity == path.fileName.toString())
                entry.files.fold(0L) { total, file -> require(file.bytes >= 0); Math.addExact(total, file.bytes) }
            }.getOrDefault(unknown)
            val used = runCatching { Files.getLastModifiedTime(manifest).toMillis() }.getOrDefault(0)
            candidates += Candidate(path, root.resolve("${path.fileName}.pin"), bytes, used, attributes(path).fileKey()?.toString())
        }
        var accountingIncomplete = false
        for (path in snapshot.dependencies) {
            checkMaintenanceActive()
            if (!Files.isDirectory(path, NOFOLLOW_LINKS)) continue
            val lock = root.resolve("dependencies-${path.fileName}.lock")
            var usage: DependencyUsage? = null
            var measuredBytes: Long? = null
            val owner = ProcessFilePin.tryLock(lock)
            if (owner == null) {
                // Older workers do not invalidate usage records themselves. Observing their live pin
                // makes the old size dirty too, so their released growth must be measured again.
                Files.deleteIfExists(usageFile(path)); accountingIncomplete = true
            }
            owner?.use {
                usage = knownDependencyUsage(path) ?: run {
                    val before = attributes(path)
                    val bytes = dependencyBytes(path)
                    measuredBytes = bytes
                    checkMaintenanceActive()
                    val after = attributes(path)
                    require(bytes >= 0 && before.fileKey() == after.fileKey() && before.lastModifiedTime() == after.lastModifiedTime()) { "dependency cache changed during accounting" }
                    after.fileKey()?.toString()?.let { key ->
                        DependencyUsage(key, after.lastModifiedTime().toString(), bytes).also { recordDependencyUsage(path, it) }
                    }
                }
            }
            val attrs = attributes(path)
            candidates += Candidate(path, lock, usage?.bytes ?: measuredBytes ?: 0, attrs.lastModifiedTime().toMillis(),
                attrs.fileKey()?.toString(), usage, accounted = usage != null || measuredBytes != null)
        }
        fun saturated(a: Long, b: Long) = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
        val accounted = candidates.filter { it.accounted }
        var bytes = accounted.fold(0L) { total, candidate -> saturated(total, candidate.bytes) }
        val garbage = snapshot.garbage.toMutableList()
        // Unknown active homes cannot justify eviction of warm data within the known budget.
        // Still reclaim proven excess among accounted entries; reconcile unknown growth after release.
        if (accountingIncomplete) maintenanceLock { Files.deleteIfExists(root.resolve("maintenance.timestamp")) }
        for (candidate in accounted.sortedBy { it.used }) {
            checkMaintenanceActive()
            if (bytes <= maxBytes) break
            ProcessFilePin.tryLock(candidate.lock)?.use {
                maintenanceLock {
                    if (!Files.isDirectory(candidate.path, NOFOLLOW_LINKS)) return@maintenanceLock
                    if (attributes(candidate.path).fileKey()?.toString() != candidate.key) return@maintenanceLock
                    if (candidate.usage != null && knownDependencyUsage(candidate.path) != candidate.usage) return@maintenanceLock
                    val trash = root.resolve(".evict-${java.util.UUID.randomUUID()}")
                    Files.move(candidate.path, trash, ATOMIC_MOVE)
                    if (candidate.path.parent.fileName.toString() == "dependencies") Files.deleteIfExists(usageFile(candidate.path))
                    garbage.add(trash); bytes = (bytes - candidate.bytes).coerceAtLeast(0)
                }
            }
        }
        for (path in garbage.distinct()) {
            checkMaintenanceActive()
            try { deleteTree(path) } catch (_: java.nio.file.NoSuchFileException) { }
        }
        if (garbage.isNotEmpty()) println("worker build cache cleanup: trees=${garbage.size}")
    }

    private fun usageFile(path: Path) = root.resolve("dependency-sizes/${path.fileName}.json")
    private fun knownDependencyUsage(path: Path): DependencyUsage? = runCatching {
        val usage = Json.decodeFromString<DependencyUsage>(Files.readString(usageFile(path)))
        val attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        usage.takeIf { it.bytes >= 0 && attrs.isDirectory && it.key == attrs.fileKey()?.toString() && it.modified == attrs.lastModifiedTime().toString() }
    }.getOrNull()
    private fun recordDependencyUsage(path: Path, usage: DependencyUsage) {
        val target = usageFile(path); Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".size-", ".tmp")
        try { Files.writeString(temporary, Json.encodeToString(usage)); Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING) }
        finally { Files.deleteIfExists(temporary) }
    }

    private fun valid(path: Path, identity: String, checkActive: () -> Unit, verification: TreeVerification): Boolean {
        val entry = runCatching { Json.decodeFromString<Entry>(Files.readString(path.resolve("manifest.json"))) }.getOrNull() ?: return false
        if (entry.files.isEmpty() || entry.files.fold(0L) { total, file ->
                if (file.bytes > maxBytes - total) return false
                total + file.bytes
            } > maxBytes) return false
        return entry.identity == identity && verification.valid(path.resolve("tree"), entry.files,
            path.resolve("verified.json"), checkActive, exactExecutables = true)
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
    private fun delete(path: Path) {
        Files.walk(path).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}

/** Historical source bundles included the worker release, which is unrelated to compiler outputs.
 * Preserve explicit custom toolchains; the running JDK and build recipe are independently keyed above. */
internal fun buildToolchain(toolchain: String): String =
    if (Regex("jdk25:[a-f0-9]{40}").matches(toolchain)) "jdk25" else toolchain

private fun checkMaintenanceActive() {
    if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("cache cleanup cancelled")
}

private fun dependencyTreeBytes(path: Path): Long {
    var bytes = 0L
    Files.walkFileTree(path, object : java.nio.file.SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
            checkMaintenanceActive(); return java.nio.file.FileVisitResult.CONTINUE
        }
        override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
            checkMaintenanceActive()
            if (attrs.isRegularFile) bytes = Math.addExact(bytes, attrs.size())
            return java.nio.file.FileVisitResult.CONTINUE
        }
    })
    return bytes
}
