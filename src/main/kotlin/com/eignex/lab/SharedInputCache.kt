package com.eignex.lab

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.UUID

/** Local derived inputs are disposable; regional objects and consumer-owned collections are never evicted here. */
internal class SharedInputCache(private val root: Path, private val maxBytes: Long = 8L * 1024 * 1024 * 1024,
                               private val maxAgeMs: Long = 7L * 24 * 60 * 60 * 1000) {
    init { require(maxBytes >= 0 && maxAgeMs >= 0) }

    fun pin(view: Path): AutoCloseable {
        val (path, channel, pin) = locked {
            Files.createDirectories(view)
            Files.setLastModifiedTime(view, FileTime.fromMillis(now()))
            val pins = Files.createDirectories(root.resolve("pins"))
            val path = pins.resolve("${view.fileName}.${UUID.randomUUID()}")
            val channel = FileChannel.open(path, CREATE_NEW, WRITE)
            try { Triple(path, channel, channel.lock()) }
            catch (failure: Exception) { channel.close(); Files.deleteIfExists(path); throw failure }
        }
        try { maintain() } catch (failure: Exception) {
            pin.release(); channel.close(); Files.deleteIfExists(path); throw failure
        }
        return AutoCloseable {
            locked {
                Files.setLastModifiedTime(view, FileTime.fromMillis(now()))
                pin.release(); channel.close(); Files.deleteIfExists(path)
            }
            maintain()
        }
    }

    /** Metadata only, at most once a minute per node. Active uses may temporarily exceed the soft quota. */
    fun maintain(at: Long = now(), force: Boolean = false) = locked {
        val stamp = root.resolve("maintenance.timestamp")
        if (!force && Files.exists(stamp) && at - Files.getLastModifiedTime(stamp).toMillis() < 60_000) return@locked
        Files.writeString(stamp, ""); Files.setLastModifiedTime(stamp, FileTime.fromMillis(at))
        val active = mutableSetOf<String>()
        val pins = root.resolve("pins")
        if (Files.isDirectory(pins, NOFOLLOW_LINKS)) Files.list(pins).use { paths -> paths.forEach { path ->
            val name = path.fileName.toString().substringBefore('.')
            if (SHA256.matches(name)) {
                FileChannel.open(path, WRITE).use { channel ->
                    val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (lock == null) active += name else lock.use { Files.deleteIfExists(path) }
                }
            }
        } }
        data class File(val key: Any, val bytes: Long)
        data class Entry(val path: Path, val files: List<File>, val used: Long, val blob: Boolean)
        val entries = mutableListOf<Entry>()
        val references = mutableMapOf<Any, Int>()
        val sizes = mutableMapOf<Any, Long>()
        fun files(path: Path): List<File> = Files.walk(path).use { paths -> paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.map { file ->
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            File(attributes.fileKey() ?: file, attributes.size())
        }.toList() }
        for (directory in listOf("views", "blobs")) {
            val parent = root.resolve(directory)
            if (!Files.isDirectory(parent, NOFOLLOW_LINKS)) continue
            Files.list(parent).use { paths -> paths.filter { SHA256.matches(it.fileName.toString()) }.forEach { path ->
                val blob = directory == "blobs"
                if (blob && !Files.isRegularFile(path, NOFOLLOW_LINKS) || !blob && !Files.isDirectory(path, NOFOLLOW_LINKS)) return@forEach
                val inventory = files(path)
                inventory.forEach { file -> references.merge(file.key, 1, Int::plus); sizes[file.key] = file.bytes }
                entries += Entry(path, inventory, Files.getLastModifiedTime(path).toMillis(), blob)
            } }
        }
        var size = sizes.values.sum()
        for (entry in entries.sortedWith(compareBy<Entry> { it.blob }.thenBy { it.used })) {
            if (size <= maxBytes && at - entry.used <= maxAgeMs) continue
            if (!entry.blob && entry.path.fileName.toString() in active) continue
            // Retain the blob anchor while any view still uses its inode.
            if (entry.blob && entry.files.any { references.getValue(it.key) > 1 }) continue
            fun remove() {
                Files.walk(entry.path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
                for (file in entry.files) {
                    val count = references.getValue(file.key) - 1; references[file.key] = count
                    if (count == 0) size -= file.bytes
                }
            }
            if (!entry.blob) remove() else {
                FileChannel.open(entry.path.resolveSibling("${entry.path.fileName}.lock"), CREATE, WRITE).use { channel ->
                    val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    lock?.use { remove() }
                }
            }
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
    }

    private fun <T> locked(block: () -> T): T = synchronized(sharedCacheMaintenance) {
        Files.createDirectories(root)
        FileChannel.open(root.resolve("maintenance.lock"), CREATE, WRITE).use { channel -> channel.lock().use { block() } }
    }
}

private val sharedCacheMaintenance = Any()
