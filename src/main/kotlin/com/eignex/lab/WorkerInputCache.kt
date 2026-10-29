package com.eignex.lab

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.FileTime
import java.util.UUID

/** Node-local verified distributions. Process-owned pins protect readers; eviction accounts declared expanded bytes. */
class WorkerInputCache(private val root: Path, private val maxBytes: Long = 8L * 1024 * 1024 * 1024,
    private val builds: WorkerBuildCache? = null, cleanupExecutor: java.util.concurrent.Executor? = null) {
    init { require(maxBytes >= 0) }
    private val cleanup = WorkerCacheCleanup(root, cleanupExecutor)
    private val entries = root.resolve("entries")
    private val sizes = root.resolve("sizes")
    private val pins = root.resolve("pins")
    private val verified = VerifiedCache(entries, materialize = { bundle, files, tree, receipt, check ->
        bundle.build == null && builds?.materialize(bundle.commit, files, tree, receipt, check) == true
    })

    fun <T> use(bundle: WorkerBundle, checkActive: () -> Unit, download: () -> InputStream, action: (Path) -> T): T =
        use(bundle, checkActive, false, download, action, PreparationTrace())

    internal fun <T> use(bundle: WorkerBundle, checkActive: () -> Unit, download: () -> InputStream, trace: PreparationTrace, action: (Path) -> T): T =
        use(bundle, checkActive, false, download, action, trace)

    /** Publish a private verified tree directly: filesystem clones avoid ZIP rereads/decompression and rewrites. */
    internal fun seed(bundle: WorkerBundle, source: Path, sourceReceipt: Path, checkActive: () -> Unit,
                      trace: PreparationTrace = PreparationTrace()) {
        use(bundle, checkActive, false, { error("seed cannot download") }, {}, trace, source to sourceReceipt)
    }

    internal fun <T> useRuntime(bundle: WorkerBundle, checkActive: () -> Unit, download: () -> InputStream, action: (Path) -> T): T =
        // Existing verified full distributions remain useful. A miss avoids unpacking compiler intermediates even
        // for bundles published by older preparations; custom consumers still acquire their separate complete tree.
        use(bundle, checkActive, !Files.isDirectory(entries.resolve(inputCacheKey(bundle)), NOFOLLOW_LINKS), download, action, PreparationTrace())

    private fun <T> use(bundle: WorkerBundle, checkActive: () -> Unit, runtimeOnly: Boolean, download: () -> InputStream, action: (Path) -> T, trace: PreparationTrace, seed: Pair<Path, Path>? = null): T {
        val key = inputCacheKey(bundle, runtimeOnly)
        val pin = guarded(checkActive) {
            Files.createDirectories(pins)
            val path = pins.resolve("$key.${UUID.randomUUID()}")
            try { path to checkNotNull(ProcessFilePin.tryLock(path)) }
            catch (failure: Exception) { Files.deleteIfExists(path); throw failure }
        }
        try {
            val missing = !Files.isDirectory(entries.resolve(key), NOFOLLOW_LINKS)
            val directory = if (seed != null) verified.seed(bundle, seed.first, seed.second, checkActive, trace)
                else if (runtimeOnly) verified.stageRuntime(bundle, checkActive, download, trace) else verified.stage(bundle, checkActive, download, trace)
            Files.createDirectories(sizes)
            val size = sizes.resolve(key)
            if (!Files.exists(size)) {
                val temporary = Files.createTempFile(sizes, ".size-", ".tmp")
                try {
                    Files.writeString(temporary, bundle.files.filter { !runtimeOnly || WorkerTrees.runtimePath(Path.of(it.path)) }.sumOf { it.bytes }.toString())
                    Files.move(temporary, size, ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } finally { Files.deleteIfExists(temporary) }
            }
            Files.setLastModifiedTime(size, FileTime.fromMillis(now()))
            // Account cold entries synchronously; deletion of unrelated trees must not delay publication.
            if (missing) maintain(checkActive)
            return action(directory)
        } finally {
            // Keep pin removal serialized with maintenance's directory snapshot.
            guarded({}) { pin.second.close(); Files.deleteIfExists(pin.first) }
        }
    }

    internal fun maintain(checkActive: () -> Unit = {}) {
        val garbage = guarded(checkActive) {
            val active = mutableSetOf<String>()
            if (Files.isDirectory(pins)) Files.list(pins).use { paths -> paths.forEach { pin ->
                val lock = ProcessFilePin.tryLock(pin)
                if (lock == null) active += pin.fileName.toString().substringBefore('.')
                else lock.use { Files.deleteIfExists(pin) }
            } }
            data class Entry(val key: String, val bytes: Long, val used: Long)
            val inventory = if (!Files.isDirectory(sizes)) emptyList() else Files.list(sizes).use { paths ->
                paths.filter { SHA256.matches(it.fileName.toString()) }.map {
                    Entry(it.fileName.toString(), Files.readString(it).toLong(), Files.getLastModifiedTime(it).toMillis())
                }.toList().sortedBy { it.used }
            }
            var bytes = inventory.sumOf { it.bytes }
            val removed = Files.list(root).use { paths -> paths.filter { it.fileName.toString().startsWith(".evict-") }.toList().toMutableList() }
            for (entry in inventory) {
                checkActive()
                if (entry.key in active || bytes <= maxBytes && now() - entry.used <= 7L * 24 * 60 * 60 * 1000) continue
                val directory = entries.resolve(entry.key)
                if (Files.exists(directory, NOFOLLOW_LINKS)) {
                    val trash = root.resolve(".evict-${UUID.randomUUID()}")
                    Files.move(directory, trash, ATOMIC_MOVE); removed.add(trash)
                }
                for (suffix in listOf(".verified.json", ".lock")) Files.deleteIfExists(entries.resolve(entry.key + suffix))
                Files.deleteIfExists(sizes.resolve(entry.key)); bytes -= entry.bytes
            }
            removed
        }
        if (garbage.isNotEmpty()) cleanup.request()
    }

    internal fun awaitCleanupIdle(timeoutMs: Long = 10_000) = cleanup.awaitIdle(timeoutMs)

    private fun <T> guarded(checkActive: () -> Unit, action: () -> T): T {
        Files.createDirectories(root)
        val path = root.resolve("maintenance.lock")
        while (true) {
            checkActive()
            ProcessFilePin.tryLock(path)?.use { return action() }
            Thread.sleep(10)
        }
    }
}
