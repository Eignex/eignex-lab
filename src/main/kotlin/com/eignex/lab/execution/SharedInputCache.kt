package com.eignex.lab.execution

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Local derived inputs are disposable; regional objects and consumer-owned collections are never evicted here. */
internal class SharedInputCache(private val root: Path, private val maxBytes: Long = 8L * 1024 * 1024 * 1024,
                               private val maxAgeMs: Long = 7L * 24 * 60 * 60 * 1000,
                               private val startMaintenance: (Thread) -> Unit = Thread::start,
                               private val beforeInventory: () -> Unit = {}) {
    init { require(maxBytes >= 0 && maxAgeMs >= 0) }
    @Serializable private data class CachedFile(val key: String, val bytes: Long)
    @Serializable private data class Inventory(val key: String, val modified: String, val files: List<CachedFile>)

    fun pin(view: Path): AutoCloseable {
        val (path, pin) = locked {
            Files.createDirectories(view)
            Files.setLastModifiedTime(view, FileTime.from(java.time.Instant.now()))
            val pins = Files.createDirectories(root.resolve("pins"))
            val path = pins.resolve("${view.fileName}.${UUID.randomUUID()}")
            path to checkNotNull(ProcessFilePin.tryLock(path))
        }
        requestMaintenance()
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                locked {
                    try { Files.setLastModifiedTime(view, FileTime.from(java.time.Instant.now())) }
                    finally { pin.close(); Files.deleteIfExists(path) }
                }
                requestMaintenance()
            }
        }
    }

    /** One disposable background sweep per root. Acquiring or releasing an input never inventories a cache. */
    private fun requestMaintenance() {
        val key = root.toAbsolutePath().normalize()
        if (!sharedCacheSweeps.add(key)) return
        val worker = Thread({
            try { maintain() }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            catch (_: Exception) { System.err.println("shared input cache cleanup deferred") }
            finally { sharedCacheSweeps.remove(key) }
        }, "shared-input-cleanup").apply { isDaemon = true }
        try { startMaintenance(worker) }
        catch (_: Exception) { sharedCacheSweeps.remove(key); System.err.println("shared input cache cleanup deferred") }
    }

    /** Caller holds the short discovery lock. Same-process probes must not close another reader's POSIX lock. */
    private fun activePins(): Set<String> {
        val active = mutableSetOf<String>()
        val pins = root.resolve("pins")
        if (Files.isDirectory(pins, NOFOLLOW_LINKS)) Files.list(pins).use { paths -> paths.forEach { path ->
            val name = path.fileName.toString().substringBefore('.')
            if (SHA256.matches(name)) {
                val pin = ProcessFilePin.tryLock(path)
                if (pin == null) active += name else pin.use { Files.deleteIfExists(path) }
            }
        } }
        return active
    }

    /** Inventory and deletion run outside the pin lock; only discovery and atomic retirement hold it. */
    fun maintain(at: Long = now(), force: Boolean = false) {
        Files.createDirectories(root)
        val sweepPath = root.resolve("maintenance-sweep.lock")
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val sweep = ProcessFilePin.tryLock(sweepPath)
            if (sweep != null) {
                sweep.use { maintainSweep(at, force) }
                return
            }
            if (!force) return
            Thread.sleep(10)
        }
    }

    private fun maintainSweep(at: Long, force: Boolean) {
        val snapshot = locked {
            val stamp = root.resolve("maintenance.timestamp")
            if (!force && Files.exists(stamp) && at - Files.getLastModifiedTime(stamp).toMillis() < 60_000) return@locked null
            activePins()
            val entries = mutableListOf<Path>()
            for (directory in listOf("views", "blobs")) {
                val parent = root.resolve(directory)
                if (Files.isDirectory(parent, NOFOLLOW_LINKS)) Files.list(parent).use { paths ->
                    entries.addAll(paths.filter { SHA256.matches(it.fileName.toString()) }.toList())
                }
            }
            entries
        } ?: return
        data class Entry(val path: Path, val files: List<CachedFile>, val used: Long, val blob: Boolean)
        val entries = mutableListOf<Entry>()
        val references = mutableMapOf<String, Int>()
        val sizes = mutableMapOf<String, Long>()
        fun attributes(path: Path) = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        fun inventoryFile(path: Path) = root.resolve("inventories/${path.fileName}.json")
        fun files(path: Path): List<CachedFile> {
            val before = attributes(path)
            if (before.isRegularFile) return listOf(CachedFile(before.fileKey()?.toString() ?: path.toString(), before.size()))
            val receipt = inventoryFile(path)
            val cached = runCatching { Json.decodeFromString<Inventory>(Files.readString(receipt)) }.getOrNull()
            if (cached != null && before.fileKey()?.toString() == cached.key && before.lastModifiedTime().toString() == cached.modified &&
                cached.files.all { it.bytes >= 0 }) return cached.files
            beforeInventory()
            val files = Files.walk(path).use { paths -> paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.map { file ->
                val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                CachedFile(attributes.fileKey()?.toString() ?: file.toString(), attributes.size())
            }.toList() }
            // Only idle, unchanged views become reusable accounting hints. pin() touches the directory before
            // any writer enters and after it leaves, so later materialization invalidates this receipt.
            before.fileKey()?.toString()?.let { key ->
                Files.createDirectories(receipt.parent)
                val temporary = Files.createTempFile(receipt.parent, ".inventory-", ".partial")
                try {
                    Files.writeString(temporary, Json.encodeToString(Inventory(key, before.lastModifiedTime().toString(), files)))
                    locked {
                        val after = attributes(path)
                        if (before.fileKey() != after.fileKey() || before.lastModifiedTime() != after.lastModifiedTime() ||
                            path.fileName.toString() in activePins()) return@locked
                        Files.move(temporary, receipt, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally { Files.deleteIfExists(temporary) }
            }
            return files
        }
        for (path in snapshot) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val blob = path.parent.fileName.toString() == "blobs"
            if (blob && !Files.isRegularFile(path, NOFOLLOW_LINKS) || !blob && !Files.isDirectory(path, NOFOLLOW_LINKS)) continue
            val inventory = files(path)
            inventory.forEach { file -> references.merge(file.key, 1, Int::plus); sizes[file.key] = file.bytes }
            entries += Entry(path, inventory, Files.getLastModifiedTime(path).toMillis(), blob)
        }
        var size = sizes.values.sum()
        val removed = mutableSetOf<Path>()
        fun remove(entry: Entry) {
            val trash = locked {
                if (!Files.exists(entry.path, NOFOLLOW_LINKS) ||
                    Files.getLastModifiedTime(entry.path).toMillis() != entry.used ||
                    !entry.blob && entry.path.fileName.toString() in activePins()) return@locked null
                root.resolve(".evict-${UUID.randomUUID()}").also { Files.move(entry.path, it, java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
            } ?: return
            removed.add(entry.path)
            if (!entry.blob) Files.deleteIfExists(inventoryFile(entry.path))
            for (file in entry.files) {
                val count = references.getValue(file.key) - 1; references[file.key] = count
                if (count == 0) size -= file.bytes
            }
            deleteTree(trash)
        }
        fun unreferencedBlobs() {
            for (entry in entries.filter { it.blob }.sortedBy { it.used }) {
                if (entry.path in removed || size <= maxBytes && at - entry.used <= maxAgeMs ||
                    entry.files.any { references.getValue(it.key) > 1 }) continue
                FileChannel.open(entry.path.resolveSibling("${entry.path.fileName}.lock"), CREATE, WRITE).use { channel ->
                    val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    lock?.use { remove(entry) }
                }
            }
        }
        unreferencedBlobs()
        for (entry in entries.filter { !it.blob }.sortedBy { it.used }) {
            if (size <= maxBytes && at - entry.used <= maxAgeMs) continue
            remove(entry)
            unreferencedBlobs()
        }
        val blobs = root.resolve("blobs")
        if (Files.isDirectory(blobs, NOFOLLOW_LINKS)) Files.list(blobs).use { paths -> paths.forEach { path ->
            val name = path.fileName.toString()
            val sha = name.removePrefix(".").substringBefore('-')
            if (name.startsWith('.') && name.endsWith(".partial") && SHA256.matches(sha) &&
                at - Files.getLastModifiedTime(path).toMillis() > 24L * 60 * 60 * 1000) {
                FileChannel.open(blobs.resolve("$sha.lock"), CREATE, WRITE).use { channel ->
                    val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    lock?.use { Files.deleteIfExists(path) }
                }
            }
        } }
        Files.list(root).use { paths -> paths.filter { it.fileName.toString().startsWith(".evict-") }.forEach(::deleteTree) }
        locked {
            val stamp = root.resolve("maintenance.timestamp")
            Files.writeString(stamp, "")
            Files.setLastModifiedTime(stamp, FileTime.fromMillis(maxOf(at, now())))
        }
    }

    private fun <T> locked(block: () -> T): T = synchronized(sharedCacheMaintenance) {
        Files.createDirectories(root)
        FileChannel.open(root.resolve("maintenance.lock"), CREATE, WRITE).use { channel -> channel.lock().use { block() } }
    }
}

private val sharedCacheMaintenance = Any()
private val sharedCacheSweeps = ConcurrentHashMap.newKeySet<Path>()
