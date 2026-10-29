package com.eignex.lab

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.FileTime
import java.util.UUID

/** Reuse immutable ZIPs by verified file inventory and directory layout. Active publishers hold a process pin;
 * eviction bounds compressed bytes, and neither cache hits nor creation expose a shared mutable workspace. */
internal class WorkerArchiveCache(private val root: Path, private val maxBytes: Long = 2L * 1024 * 1024 * 1024,
                                  cleanupExecutor: java.util.concurrent.Executor? = null,
                                  private val create: ((Path, Path, WorkerBundle, Boolean, () -> Unit) -> WorkerBundle)? = null) {
    init { require(maxBytes >= 0) }
    private val cleanup = WorkerCacheCleanup(root, cleanupExecutor)
    @Serializable private data class Entry(val bundle: WorkerBundle, val bytes: Long)

    fun <T> use(tree: Path, identity: WorkerBundle, runtimeOnly: Boolean, checkActive: () -> Unit,
                trace: PreparationTrace = PreparationTrace(), action: (Path, WorkerBundle) -> T): T {
        Files.createDirectories(root)
        // A private workspace copy already has a receipt proving the bytes copied from the build cache.
        val original = tree.resolveSibling("${tree.fileName}.verified.json")
        val receipt = tree.resolveSibling("${tree.fileName}.archive-verified.json")
        if (!Files.exists(receipt) && Files.isRegularFile(original)) Files.copy(original, receipt)
        val inventoryStarted = System.nanoTime()
        val inventoryVerifier = TreeVerification()
        val directories = mutableListOf<String>()
        val files = trace.step("archive") { inventoryVerifier.capture(tree, receipt, checkActive, directories) { !runtimeOnly || WorkerTrees.runtimePath(it) } }
        val inventoryMs = (System.nanoTime() - inventoryStarted) / 1_000_000
        val key = ExecutionStore.digest("archive-v2\n" + Json.encodeToString(files.sortedBy { it.path }) + "\n" + Json.encodeToString(directories.sorted()))
        val destination = root.resolve(key)
        val pin = guarded(checkActive) {
            val pins = Files.createDirectories(root.resolve("pins")); val path = pins.resolve("$key.${UUID.randomUUID()}")
            path to checkNotNull(ProcessFilePin.tryLock(path))
        }
        try {
            val waitStarted = System.nanoTime()
            val entry = trace.step("archive") { locked(root.resolve("$key.lock"), checkActive) {
                val waitingMs = (System.nanoTime() - waitStarted) / 1_000_000
                val verificationStarted = System.nanoTime()
                val cached = runCatching { Json.decodeFromString<Entry>(Files.readString(destination.resolve("manifest.json"))) }.getOrNull()
                val zipVerifier = TreeVerification()
                val valid = cached != null && cached.bundle.files.size == files.size && cached.bundle.files.toSet() == files.toSet() && cached.bytes in 0..maxBytes &&
                    trace.step("archive") { zipVerifier.valid(destination.resolve("data"), listOf(InputFile("archive.zip", cached.bundle.sha256, cached.bytes)),
                        destination.resolve("verified.json"), checkActive, exactExecutables = true) }
                val verificationMs = inventoryMs + (System.nanoTime() - verificationStarted) / 1_000_000
                val result = if (valid) cached else trace.step("archive") {
                    if (Files.exists(destination, NOFOLLOW_LINKS)) deleteTree(destination)
                    val storage = WorkerBuildStaging(root)
                    val lease = storage.create(checkActive); val staging = lease.directory
                    try {
                        val data = Files.createDirectory(staging.resolve("data")); val output = data.resolve("archive.zip")
                        val writeStarted = System.nanoTime()
                        val bundle = create?.invoke(tree, output, identity, runtimeOnly, checkActive)
                            ?: archiveInventory(tree, output, identity, files.map { tree.resolve(it.path) }, directories.map { "$it/" }, checkActive)
                        require(bundle.files.toSet() == files.toSet()) { "archive source changed" }
                        val bytes = Files.size(output)
                        println("worker archive timing: inventoryMs=$inventoryMs writeMs=${(System.nanoTime() - writeStarted) / 1_000_000} files=${files.size} inputBytes=${files.sumOf { it.bytes }} zipBytes=$bytes")
                        val verifier = TreeVerification()
                        verifier.record(data, listOf(verifier.written(output, InputFile("archive.zip", bundle.sha256, bytes))), staging.resolve("verified.json"), checkActive)
                        val result = Entry(bundle, bytes)
                        Files.writeString(staging.resolve("manifest.json"), Json.encodeToString(result))
                        storage.publish(lease, destination, checkActive)
                        result
                    } finally { storage.close(lease) }
                }
                trace.cache("archive", if (valid) "hit" else "miss", if (valid) "verified-local" else if (cached == null) "absent" else "verification-failed",
                    verificationMs = verificationMs, waitingMs = waitingMs, bytes = result.bytes, files = files.size,
                    checkedFiles = inventoryVerifier.stats.checkedFiles + zipVerifier.stats.checkedFiles + if (valid) 0 else files.size,
                    hashedFiles = inventoryVerifier.stats.hashedFiles + zipVerifier.stats.hashedFiles + if (valid) 0 else files.size,
                    hashedBytes = inventoryVerifier.stats.hashedBytes + zipVerifier.stats.hashedBytes + if (valid) 0 else files.sumOf { it.bytes })
                result
            } }
            Files.setLastModifiedTime(destination.resolve("manifest.json"), FileTime.fromMillis(now()))
            maintain(checkActive)
            return action(destination.resolve("data/archive.zip"), identity.copy(sha256 = entry.bundle.sha256, files = entry.bundle.files, build = null))
        } finally {
            guarded({}) { pin.second.close(); Files.deleteIfExists(pin.first) }
        }
    }

    internal fun maintain(checkActive: () -> Unit = {}, at: Long = now()) {
        val garbage = guarded(checkActive) {
            val active = mutableSetOf<String>()
            val pins = root.resolve("pins")
            if (Files.isDirectory(pins)) Files.list(pins).use { paths -> paths.forEach { pin ->
                val owner = ProcessFilePin.tryLock(pin)
                if (owner == null) active += pin.fileName.toString().substringBefore('.')
                else owner.use { Files.deleteIfExists(pin) }
            } }
            data class Candidate(val path: Path, val bytes: Long, val used: Long)
            val entries = Files.list(root).use { paths -> paths.filter { SHA256.matches(it.fileName.toString()) && Files.isDirectory(it, NOFOLLOW_LINKS) }
                .map { path ->
                    val manifest = path.resolve("manifest.json")
                    val size = runCatching { Files.size(path.resolve("data/archive.zip")) }.getOrDefault(0)
                    val used = runCatching { Files.getLastModifiedTime(manifest).toMillis() }.getOrDefault(0)
                    Candidate(path, size, used)
                }.toList().sortedBy { it.used } }
            var bytes = entries.sumOf { it.bytes }
            val removed = WorkerBuildStaging(root).reapLocked(at).toMutableList()
            Files.list(root).use { paths -> removed.addAll(paths.filter { it.fileName.toString().startsWith(".evict-") }.toList()) }
            for (entry in entries) {
                checkActive()
                if (entry.path.fileName.toString() in active || bytes <= maxBytes && at - entry.used <= 7L * 24 * 60 * 60 * 1000) continue
                val trash = root.resolve(".evict-${UUID.randomUUID()}")
                Files.move(entry.path, trash, ATOMIC_MOVE); removed.add(trash); bytes -= entry.bytes
            }
            Files.list(root).use { paths -> paths.filter { it.fileName.toString().endsWith(".lock") }.forEach { lock ->
                val key = lock.fileName.toString().removeSuffix(".lock")
                if (SHA256.matches(key) && key !in active && !Files.exists(root.resolve(key), NOFOLLOW_LINKS)) Files.deleteIfExists(lock)
            } }
            removed
        }
        if (garbage.isNotEmpty()) cleanup.request()
    }

    internal fun awaitCleanupIdle(timeoutMs: Long = 10_000) = cleanup.awaitIdle(timeoutMs)

    private fun <T> guarded(check: () -> Unit, action: () -> T): T {
        Files.createDirectories(root)
        return locked(root.resolve("maintenance.lock"), check, action)
    }
    private fun <T> locked(path: Path, check: () -> Unit, action: () -> T): T {
        while (true) {
            check()
            ProcessFilePin.tryLock(path)?.use { return action() }
            Thread.sleep(10)
        }
    }
}
