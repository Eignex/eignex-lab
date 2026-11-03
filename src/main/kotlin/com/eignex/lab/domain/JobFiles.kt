package com.eignex.lab.domain

import com.eignex.lab.util.*

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** The names of the files a job's directory holds that more than one part of the lab looks for. */
object JobFileNames {
    /** Written when a job's files were trimmed ([JobTrim]), saying when and how much: its raw captures are gone. */
    const val TRIMMED = "trimmed"
    /** Internal immutable inputs are retained with the job, but are not individual result downloads. */
    const val PREPARED_TREES = "nomad/prepared-trees"
}

/** A listed job file; [bytes] is [UNKNOWN_BYTES] when size metadata cannot be read just now. */
@Serializable
data class FileEntry(val path: String, val bytes: Long, val sha256: String? = null) {
    companion object { const val UNKNOWN_BYTES = -1L }
}

/**
 * The regular files under job directory [root], relative to it. Symbolic links are neither listed nor followed: a
 * job's files are what it wrote there, and a link could lead anywhere on the machine.
 */
internal fun jobFiles(root: Path, entries: (Path, () -> Unit) -> List<MacDirectoryEntries.ListingEntry>? = MacDirectoryEntries::readListing): List<FileEntry> {
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    val files = mutableListOf<FileEntry>()
    val fallback = object : java.nio.file.SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            return if (root.relativize(dir).joinToString("/") == JobFileNames.PREPARED_TREES) java.nio.file.FileVisitResult.SKIP_SUBTREE
            else java.nio.file.FileVisitResult.CONTINUE
        }
        override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (attrs.isRegularFile) files += FileEntry(root.relativize(file).joinToString("/"), attrs.size())
            return java.nio.file.FileVisitResult.CONTINUE
        }
    }
    fun visit(dir: Path) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        if (root.relativize(dir).joinToString("/") == JobFileNames.PREPARED_TREES) return
        val bulk = entries(dir) { if (Thread.currentThread().isInterrupted) throw InterruptedException() }
        if (bulk == null) { Files.walkFileTree(dir, fallback); return }
        for (entry in bulk) {
            val path = dir.resolve(entry.name)
            if (entry.directory) visit(path)
            else if (entry.regular) files += FileEntry(root.relativize(path).joinToString("/"), entry.bytes)
        }
    }
    visit(root)
    return files.sortedBy { it.path }
}

/** Exact accepted logical replacements do not use their projection's size. All physical names/directories
 * still participate in the inventory; legacy-only directories retain the existing bulk-length path. */
internal fun jobFiles(root: Path, replaced: Set<String>,
                     names: (Path, () -> Unit) -> List<MacDirectoryEntries.NameEntry>? = MacDirectoryEntries::readNames,
                     entries: (Path, () -> Unit) -> List<MacDirectoryEntries.ListingEntry>? = MacDirectoryEntries::readListing,
                     attributes: (Path) -> java.nio.file.attribute.BasicFileAttributes = {
                         Files.readAttributes(it, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                     }): List<FileEntry> {
    val parents = mutableSetOf<String>()
    for (name in replaced) {
        parents += ""
        name.indices.filter { name[it] == '/' }.forEach { parents += name.substring(0, it) }
    }
    return jobFiles(root) { directory, checkActive ->
        val prefix = root.relativize(directory).joinToString("/")
        if (prefix !in parents) entries(directory, checkActive)
        else names(directory, checkActive)?.map { entry ->
            checkActive()
            val path = directory.resolve(entry.name)
            val relative = root.relativize(path).joinToString("/")
            if (!entry.regular || relative in replaced)
                MacDirectoryEntries.ListingEntry(entry.name, entry.directory, entry.regular, FileEntry.UNKNOWN_BYTES)
            else {
                val attrs = attributes(path) // Recheck type as well: a replacement symlink must not be followed.
                MacDirectoryEntries.ListingEntry(entry.name, attrs.isDirectory, attrs.isRegularFile,
                    if (attrs.isRegularFile) attrs.size() else FileEntry.UNKNOWN_BYTES)
            }
        }
    }
}

/**
 * The regular file [segments] name under job directory [root], or null when there is none. A segment that is empty,
 * `.` or `..`, or holds a slash, a backslash or a NUL fails as a bad request, whether it came plain or
 * percent-encoded. A file reached through a symbolic link is served only when the link resolves inside [root] too.
 */
internal fun jobFile(root: Path, segments: List<String>): Path? {
    require(segments.isNotEmpty() && segments.none { it in setOf("", ".", "..") || it.any { c -> c in "/\\\u0000" } }) {
        "bad file path"
    }
    if (segments.take(2).joinToString("/") == JobFileNames.PREPARED_TREES) return null
    val realRoot = runCatching { root.toRealPath() }.getOrNull() ?: return null
    val file = runCatching { realRoot.resolve(segments.joinToString("/")).toRealPath() }.getOrNull() ?: return null
    return file.takeIf { it.startsWith(realRoot) && !it.startsWith(realRoot.resolve(JobFileNames.PREPARED_TREES)) && Files.isRegularFile(it) }
}
