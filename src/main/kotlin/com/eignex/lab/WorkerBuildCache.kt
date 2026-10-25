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
        val identity = ExecutionStore.digest(listOf("build-cache-v2", Json.encodeToString(bundle), Json.encodeToString(budget),
            System.getProperty("java.vendor"), System.getProperty("java.version"), System.getProperty("os.name"), System.getProperty("os.arch")).joinToString("\n"))
        Files.createDirectories(root)
        val destination = root.resolve(identity)
        synchronized(fileLockFor(root.resolve("$identity.lock"))) {
        FileChannel.open(root.resolve("$identity.lock"), CREATE, WRITE).use { lock -> lock.lock().use {
            require(remainingLeaseMs() > 0) { "build lease expired" }
            checkRun()
            if (valid(destination, identity)) return destination.resolve("tree")
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
                        val used = Files.walk(root).use { it.filter { file -> Files.isRegularFile(file, NOFOLLOW_LINKS) }.mapToLong(Files::size).sum() }
                        require(used <= maxBytes) { "build staging exceeds disk budget" }
                        nextDiskCheck = at + 1_000_000_000
                    }
                    return minOf(remainingLeaseMs(), (deadline - at) / 1_000_000)
                }
                val heap = (budget.memoryMiB / 3).coerceAtLeast(16)
                val env = environment + mapOf("GRADLE_USER_HOME" to staging.resolve("gradle-home").toString(),
                    "JAVA_TOOL_OPTIONS" to "-Xmx${heap}m -XX:ActiveProcessorCount=${(budget.cpuMillis / 1000).coerceAtLeast(1)}",
                    "LAB_SOURCE_COMMIT" to bundle.commit)
                fun run(argv: List<String>, directory: Path) {
                    val exit = supervisor.run(argv, directory, env, log, errors, build.timeoutSec,
                        graceMs = Experiments.STOP_GRACE_SEC * 1000, runRemainingMs = remainingRunMs(), remainingLeaseMs = ::remaining)
                    if (exit != 0) {
                        fun tail(path: Path): String = java.io.RandomAccessFile(path.toFile(), "r").use { input ->
                            input.seek((input.length() - 8192).coerceAtLeast(0)); String(ByteArray((input.length() - input.filePointer).toInt()).also { input.readFully(it) })
                        }
                        throw WorkerBuildFailure("exit $exit\n" + tail(errors) + "\n" + tail(log))
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
                    run(listOf("bash", "./gradlew") + build.tasks + listOf("--no-daemon", "--max-workers=${(budget.cpuMillis / 1000).coerceAtLeast(1)}",
                        "-Dorg.gradle.jvmargs=-Xmx${heap}m", "-Pkotlin.compiler.execution.strategy=in-process", "-q"), tree)
                }
                // Keep commit objects for committed profile tools that inspect Git; transport was only a local bundle.
                tree.resolve(".gradle").takeIf { Files.exists(it, NOFOLLOW_LINKS) }?.let(::delete)
                staging.resolve("gradle-home").takeIf { Files.exists(it, NOFOLLOW_LINKS) }?.let(::delete)
                val files = inventory(tree)
                checkRun()
                Files.writeString(staging.resolve("manifest.json"), Json.encodeToString(Entry(identity, files)))
                Files.move(staging, destination, ATOMIC_MOVE)
                return destination.resolve("tree")
            } finally { if (Files.exists(staging, NOFOLLOW_LINKS)) delete(staging) }
        } }
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
