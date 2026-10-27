package com.eignex.lab

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*

class WorkerBuildFailure(val detail: String) : IllegalStateException("trusted source build failed: $detail")

/** Builds are bounded helpers of their session, not another reservation or a whole-node exclusivity rule. */
class WorkerBuildCache(private val root: Path, private val supervisor: ProcessSupervisor,
                       private val maxBytes: Long = 32L * 1024 * 1024 * 1024) {
    @Serializable private data class Entry(val identity: String, val files: List<InputFile>)

    fun prepare(bundle: WorkerBundle, source: Path, budget: ResourceBudget, environment: Map<String, String>,
                remainingRunMs: () -> Long? = { null }, remainingLeaseMs: () -> Long): Path {
        fun checkRun() { if (remainingRunMs()?.let { it <= 0 } == true) throw ProfileRunTimeout() }
        checkRun()
        val build = requireNotNull(bundle.build)
        val identity = identity(bundle, budget)
        Files.createDirectories(root)
        val destination = root.resolve(identity)
        return locked(root.resolve("$identity.lock"), remainingRunMs, remainingLeaseMs) {
            maintain()
            require(remainingLeaseMs() > 0) { "build lease expired" }
            checkRun()
            if (valid(destination, identity)) {
                Files.setLastModifiedTime(destination.resolve("manifest.json"), java.nio.file.attribute.FileTime.fromMillis(now()))
                return@locked destination.resolve("tree")
            }
            if (Files.exists(destination, NOFOLLOW_LINKS)) delete(destination)
            val staging = Files.createTempDirectory(root, ".build-")
            try {
                val tree = staging.resolve("tree")
                val log = staging.resolve("build.out"); val errors = staging.resolve("build.err")
                val deadline = System.nanoTime() + build.timeoutSec * 1_000_000_000
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
                val dependencyKey = ExecutionStore.digest(listOf(bundle.repository, bundle.toolchain, bundle.os, bundle.arch, System.getProperty("java.version")).joinToString("\n"))
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
                if (build.tasks.isNotEmpty()) {
                    require(Files.isRegularFile(tree.resolve("gradlew"), NOFOLLOW_LINKS)) { "source archive has no Gradle wrapper" }
                    val init = staging.resolve("jvm.init.gradle")
                    Files.writeString(init, requireNotNull(javaClass.getResource("/worker-jvm.init.gradle")).readText())
                    compilationSlot(remainingRunMs, ::remaining) {
                    dependencyHome(dependencyKey, remainingRunMs, remainingLeaseMs) { dependencyHome ->
                        Files.createDirectories(dependencyHome)
                        run(listOf("bash", "./gradlew") + build.tasks + listOf("--no-daemon", "--max-workers=${(budget.cpuMillis / 1000).coerceAtLeast(1)}",
                            "--init-script", init.toString(), "-Dorg.gradle.jvmargs=-Xmx${heap}m", "-Pkotlin.compiler.execution.strategy=in-process", "-q"), tree,
                            env + ("GRADLE_USER_HOME" to dependencyHome.toString()))
                        Files.setLastModifiedTime(dependencyHome, java.nio.file.attribute.FileTime.fromMillis(now()))
                    }
                    }
                }
                // Keep commit objects for committed profile tools that inspect Git; transport was only a local bundle.
                tree.resolve(".gradle").takeIf { Files.exists(it, NOFOLLOW_LINKS) }?.let(::delete)
                val files = inventory(tree)
                checkRun()
                Files.writeString(staging.resolve("manifest.json"), Json.encodeToString(Entry(identity, files)))
                Files.move(staging, destination, ATOMIC_MOVE)
                destination.resolve("tree")
            } finally { if (Files.exists(staging, NOFOLLOW_LINKS)) delete(staging) }
        }
    }
    fun <T> withPrepared(bundle: WorkerBundle, source: Path, budget: ResourceBudget, environment: Map<String, String>,
                         remainingRunMs: () -> Long?, remainingLeaseMs: () -> Long, use: (Path) -> T): T {
        Files.createDirectories(root)
        return locked(root.resolve("${identity(bundle, budget)}.pin"), remainingRunMs, remainingLeaseMs) {
            use(prepare(bundle, source, budget, environment, remainingRunMs, remainingLeaseMs))
        }
    }

    /** Node-local slots bound cold compilers across allocations; warm cache hits bypass this gate. */
    internal fun <T> compilationSlot(run: () -> Long?, lease: () -> Long, slots: Int =
        System.getenv("LAB_WORKER_MAX_CONCURRENT_BUILDS")?.toIntOrNull() ?: 2, use: () -> T): T {
        require(slots in 1..64)
        while (true) {
            if (run()?.let { it <= 0 } == true) throw ProfileRunTimeout()
            check(lease() > 0) { "build lease expired while waiting for compiler" }
            for (slot in 0 until slots) {
                FileChannel.open(root.resolve("compile-$slot.lock"), CREATE, READ, WRITE).use { channel ->
                    val lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
                    if (lock != null) return lock.use { use() }
                }
            }
            Thread.sleep(50)
        }
    }

    private fun identity(bundle: WorkerBundle, budget: ResourceBudget): String = ExecutionStore.digest(listOf(
        "build-cache-v4", bundle.repository, bundle.commit, bundle.os, bundle.arch, bundle.toolchain,
        Json.encodeToString(bundle.buildOptions), Json.encodeToString(bundle.build?.tasks.orEmpty()),
        // Git export pack bytes may vary for the same approved commit. Extra inputs still bind their checksums.
        if (bundle.build?.gitBundle == null) bundle.sha256 else "approved-git-commit",
        Json.encodeToString(bundle.files.filter { it.path != bundle.build?.gitBundle }.sortedBy { it.path }),
        Json.encodeToString(budget), System.getProperty("java.vendor"), System.getProperty("java.version"),
        System.getProperty("os.name"), System.getProperty("os.arch"),
        ExecutionStore.digest(requireNotNull(javaClass.getResource("/worker-jvm.init.gradle")).readText()),
    ).joinToString("\n"))

    /** Gradle cannot negotiate cache locks across container namespaces. Reuse the first idle home, or give an
     * already-admitted concurrent build another home. Scheduling budgets bound concurrency; cache eviction bounds disk. */
    private fun <T> dependencyHome(key: String, run: () -> Long?, lease: () -> Long, use: (Path) -> T): T {
        var slot = 0
        while (true) {
            if (run()?.let { it <= 0 } == true) throw ProfileRunTimeout()
            require(lease() > 0) { "build lease expired while acquiring dependency cache" }
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("cache wait cancelled")
            val name = if (slot == 0) key else "$key-$slot"
            FileChannel.open(root.resolve("dependencies-$name.lock"), CREATE, READ, WRITE).use { channel ->
                val lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
                if (lock != null) return lock.use { use(root.resolve("dependencies/$name")) }
            }
            slot++
        }
    }

    private fun <T> locked(path: Path, run: () -> Long?, lease: () -> Long, use: () -> T): T {
        FileChannel.open(path, CREATE, READ, WRITE).use { channel ->
            while (true) {
                if (run()?.let { it <= 0 } == true) throw ProfileRunTimeout()
                require(lease() > 0) { "build lease expired while waiting for cache" }
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("cache wait cancelled")
                val lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
                if (lock != null) return lock.use { use() }
                Thread.sleep(50)
            }
        }
    }

    /** One maintenance pass per node per minute, using manifests instead of rescanning all build trees. */
    internal fun maintain(at: Long = now()) {
        FileChannel.open(root.resolve("maintenance.lock"), CREATE, WRITE).use { channel ->
            val lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
            if (lock == null) return
            lock.use {
                val stamp = root.resolve("maintenance.timestamp")
                if (Files.exists(stamp) && at - Files.getLastModifiedTime(stamp).toMillis() < 60_000) return
                Files.writeString(stamp, ""); Files.setLastModifiedTime(stamp, java.nio.file.attribute.FileTime.fromMillis(at))
                data class Candidate(val path: Path, val lock: Path, val bytes: Long, val used: Long)
                val candidates = mutableListOf<Candidate>()
                Files.list(root).use { paths -> paths.filter { it.fileName.toString().matches(SHA256) && Files.isDirectory(it, NOFOLLOW_LINKS) }.forEach { path ->
                    val manifest = path.resolve("manifest.json")
                    val entry = runCatching { Json.decodeFromString<Entry>(Files.readString(manifest)) }.getOrNull()
                    if (entry != null) candidates += Candidate(path, root.resolve("${path.fileName}.pin"), entry.files.sumOf { it.bytes }, Files.getLastModifiedTime(manifest).toMillis())
                } }
                val dependencies = root.resolve("dependencies")
                if (Files.isDirectory(dependencies)) Files.list(dependencies).use { paths -> paths.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.forEach { path ->
                    val bytes = runCatching { Files.walk(path).use { files -> files.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.mapToLong { runCatching { Files.size(it) }.getOrDefault(0) }.sum() } }.getOrDefault(0)
                    candidates += Candidate(path, root.resolve("dependencies-${path.fileName}.lock"), bytes, Files.getLastModifiedTime(path).toMillis())
                } }
                var size = candidates.sumOf { it.bytes }
                for (candidate in candidates.sortedBy { it.used }) {
                    if (size <= maxBytes) break
                    FileChannel.open(candidate.lock, CREATE, WRITE).use { pin ->
                        val free = try { pin.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
                        free?.use {
                            val trash = root.resolve(".evict-${java.util.UUID.randomUUID()}")
                            if (Files.exists(candidate.path, NOFOLLOW_LINKS)) {
                                Files.move(candidate.path, trash, ATOMIC_MOVE); delete(trash); size -= candidate.bytes
                            }
                        }
                    }
                }
            }
        }
    }

    private fun inventory(tree: Path): List<InputFile> {
        var total = 0L
        return Files.walk(tree).use { paths -> paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }.sorted().map { file ->
            require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "build produced a link or unsupported file" }
            val bytes = Files.size(file); total += bytes; require(total <= maxBytes) { "build exceeds cache disk budget" }
            InputFile(tree.relativize(file).toString().replace('\\', '/'), hashFile(file), bytes, Files.isExecutable(file))
        }.toList() }.also { require(it.isNotEmpty()) }
    }
    private fun valid(path: Path, identity: String): Boolean = runCatching {
        val entry = Json.decodeFromString<Entry>(Files.readString(path.resolve("manifest.json")))
        entry.identity == identity && inventory(path.resolve("tree")) == entry.files
    }.getOrDefault(false)
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
