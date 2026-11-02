package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** One preparation can reference the same compiler or include tree in hundreds of cases. Reuse only
 * unchanged regular-file bytes on filesystems with reliable inode/ctime metadata; never cache a directory. */
internal class BenchInputDigests(private val checkActive: () -> Unit = {},
                                 private val hash: (Path, () -> Unit) -> String = ::hashFile,
                                 private val reliableRoot: (Path) -> Boolean = { Files.getFileStore(it).type().lowercase() in
                                     setOf("apfs", "ext4", "xfs", "btrfs", "overlay", "tmpfs", "zfs") }) {
    private data class Stamp(val size: Long, val modified: String, val changed: String, val key: String)
    private data class Entry(val stamp: Stamp, val sha: String)
    private val roots = mutableMapOf<Path, Boolean>()
    private val files = mutableMapOf<Path, Entry>()
    private val receipts = mutableMapOf<Path, (Path, MacDirectoryEntries.Entry?) -> String?>()
    var hashedFiles = 0; private set
    var reusedFiles = 0; private set
    var hashedBytes = 0L; private set
    var bulkFiles = 0; private set

    fun receipt(root: Path, path: Path) {
        checkActive()
        receipts[root.toAbsolutePath().normalize()] = TreeVerification().checksums(root, path)
    }

    fun checksum(root: Path, file: Path, observed: MacDirectoryEntries.Entry? = null): String {
        checkActive()
        observed?.let { require(it.regular && it.name == file.fileName.toString()); bulkFiles++ }
        val reliable = roots.getOrPut(root) { reliableRoot(root) }
        val before = if (reliable) observed?.let { Stamp(it.bytes, it.modified, it.changed, it.key) } ?: stamp(file) else null
        files[file]?.takeIf { before != null && it.stamp == before }?.let { reusedFiles++; return it.sha }
        if (before != null) receipts[root.toAbsolutePath().normalize()]?.invoke(file, observed)?.let { sha ->
            if (observed == null) require(stamp(file) == before) { "bench input changed during verification" }
            files[file] = Entry(before, sha); reusedFiles++; return sha
        }
        val sha = hash(file, checkActive)
        hashedFiles++; hashedBytes = Math.addExact(hashedBytes, before?.size ?: Files.size(file))
        if (before != null) {
            require(stamp(file) == before) { "bench input changed during verification" }
            files[file] = Entry(before, sha)
        }
        return sha
    }

    /** Traverse anew on every use, including empty directories. Batched metadata only replaces individual
     * stats; a second observation rejects mutations while checksums from the first batch were consumed. */
    fun inventory(base: Path, root: Path, bulkMetadata: Boolean = true, check: () -> Unit = checkActive): List<String> {
        check()
        require(root.toRealPath().startsWith(base.toRealPath())) { "bench input escapes its root" }
        val native = if (bulkMetadata && Files.isDirectory(root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(root) &&
            MacDirectoryEntries.supported(root)) nativeInventory(root, check) else null
        val entries = native ?: Files.walk(root).use { it.sorted().toList() }.map { file ->
            require(!Files.isSymbolicLink(file)) { "bench input contains a link" }
            Triple(file, Files.isDirectory(file, NOFOLLOW_LINKS), null)
        }
        val lines = entries.map { (file, directory, observed) ->
            check()
            "${root.relativize(file)}:" + if (directory) "directory" else checksum(base, file, observed)
        }
        if (native != null) require(nativeInventory(root, check) == native) { "bench inputs changed during verification" }
        return lines
    }

    private fun nativeInventory(root: Path, check: () -> Unit): List<Triple<Path, Boolean, MacDirectoryEntries.Entry?>>? {
        val entries = mutableListOf(Triple(root, true, null as MacDirectoryEntries.Entry?))
        fun directory(path: Path): Boolean {
            check()
            val batch = MacDirectoryEntries.read(path, check) ?: return false
            for (entry in batch) {
                check(); require(entry.directory || entry.regular) { "bench input contains a link or unsupported file" }
                val file = path.resolve(entry.name)
                entries += Triple(file, entry.directory, entry)
                if (entry.directory && !directory(file)) return false
            }
            return true
        }
        return if (directory(root)) entries.sortedBy { it.first } else null
    }

    private fun stamp(file: Path): Stamp? {
        val attributes = try { Files.readAttributes(file, "unix:size,lastModifiedTime,ctime,fileKey,isRegularFile", NOFOLLOW_LINKS) }
            catch (_: UnsupportedOperationException) { return null }
        require(attributes["isRegularFile"] == true) { "bench input must be a regular file" }
        return Stamp(attributes["size"] as Long, attributes["lastModifiedTime"].toString(), attributes["ctime"]?.toString() ?: return null,
            attributes["fileKey"]?.toString() ?: return null)
    }
}
