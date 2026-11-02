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

    private fun stamp(file: Path): Stamp? {
        val attributes = try { Files.readAttributes(file, "unix:size,lastModifiedTime,ctime,fileKey,isRegularFile", NOFOLLOW_LINKS) }
            catch (_: UnsupportedOperationException) { return null }
        require(attributes["isRegularFile"] == true) { "bench input must be a regular file" }
        return Stamp(attributes["size"] as Long, attributes["lastModifiedTime"].toString(), attributes["ctime"]?.toString() ?: return null,
            attributes["fileKey"]?.toString() ?: return null)
    }
}
