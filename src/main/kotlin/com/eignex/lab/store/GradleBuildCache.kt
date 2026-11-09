package com.eignex.lab.store

import com.eignex.lab.util.*

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicLong

/** A bounded Gradle remote build cache shared by preparations across nodes, partitioned by repository and platform.
 *
 * Entries are opaque task outputs addressed by Gradle's own cache keys; Gradle validates and unpacks them. Partitions
 * keep one repository's or platform's entries from ever serving another. Reads refresh an entry's age; writes beyond
 * [maxBytes] evict the least recently used entries down to nine tenths of it. Only trusted-source preparations write. */
class GradleBuildCache(private val root: Path, private val maxBytes: Long, private val clock: () -> Long = ::now) {
    private val used = AtomicLong(-1)
    private val evicting = Any()

    init { require(maxBytes > 0) }

    fun partition(repository: String, os: String, arch: String, toolchain: String): String =
        sha256Hex("$repository\n$os\n$arch\n$toolchain").take(32)

    fun get(partition: String, key: String): Path? {
        val path = path(partition, key)
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) return null
        runCatching { Files.setLastModifiedTime(path, FileTime.fromMillis(clock())) }
        return path
    }

    /** Stores at most [limit] bytes from [input]; an oversized entry is refused and nothing is kept. */
    fun put(partition: String, key: String, input: InputStream, limit: Long = MAX_ENTRY_BYTES): Boolean {
        val path = path(partition, key)
        usage() // Count what exists before this entry lands, so the first write is not counted twice.
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".put-", ".tmp")
        try {
            var total = 0L
            Files.newOutputStream(temporary).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    total += count
                    if (total > limit) return false
                    output.write(buffer, 0, count)
                }
            }
            val replaced = if (Files.isRegularFile(path, NOFOLLOW_LINKS)) Files.size(path) else 0L
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
            usage().addAndGet(total - replaced)
            if (used.get() > maxBytes) evict()
            return true
        } finally { Files.deleteIfExists(temporary) }
    }

    internal fun usage(): AtomicLong {
        if (used.get() < 0) synchronized(evicting) { if (used.get() < 0) used.set(entries().sumOf { it.second }) }
        return used
    }

    private fun evict() = synchronized(evicting) {
        if (used.get() <= maxBytes) return
        val target = maxBytes - maxBytes / 10
        for ((path, bytes) in entries().sortedBy { runCatching { Files.getLastModifiedTime(it.first).toMillis() }.getOrDefault(0L) }) {
            if (used.get() <= target) break
            if (Files.deleteIfExists(path)) used.addAndGet(-bytes)
        }
    }

    private fun entries(): List<Pair<Path, Long>> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root, 2).use { paths ->
            paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && !it.fileName.toString().startsWith(".") }
                .map { it to runCatching { Files.size(it) }.getOrDefault(0L) }.toList()
        }
    }

    private fun path(partition: String, key: String): Path {
        require(PARTITION.matches(partition)) { "bad cache partition" }
        require(KEY.matches(key)) { "bad cache key" }
        return root.resolve(partition).resolve(key)
    }

    companion object {
        const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
        private val PARTITION = Regex("[0-9a-f]{32}")
        private val KEY = Regex("[0-9a-f]{32,128}")
    }
}
