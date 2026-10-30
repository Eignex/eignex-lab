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
    private data class Stamp(val size: Long, val modified: Any?, val changed: Any, val key: Any)
    private data class Entry(val stamp: Stamp, val sha: String)
    private val roots = mutableMapOf<Path, Boolean>()
    private val files = mutableMapOf<Path, Entry>()
    var hashedFiles = 0; private set
    var reusedFiles = 0; private set
    var hashedBytes = 0L; private set

    fun checksum(root: Path, file: Path): String {
        checkActive()
        val reliable = roots.getOrPut(root) { reliableRoot(root) }
        val before = if (reliable) stamp(file) else null
        files[file]?.takeIf { before != null && it.stamp == before }?.let { reusedFiles++; return it.sha }
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
        return Stamp(attributes["size"] as Long, attributes["lastModifiedTime"], attributes["ctime"] ?: return null,
            attributes["fileKey"] ?: return null)
    }
}
