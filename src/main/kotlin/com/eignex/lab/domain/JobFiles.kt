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
}

@Serializable
data class FileEntry(val path: String, val bytes: Long)

/**
 * The regular files under job directory [root], relative to it. Symbolic links are neither listed nor followed: a
 * job's files are what it wrote there, and a link could lead anywhere on the machine.
 */
internal fun jobFiles(root: Path): List<FileEntry> {
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    return Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .map { FileEntry(root.relativize(it).joinToString("/"), Files.size(it)) }
            .toList()
    }.sortedBy { it.path }
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
    val realRoot = runCatching { root.toRealPath() }.getOrNull() ?: return null
    val file = runCatching { realRoot.resolve(segments.joinToString("/")).toRealPath() }.getOrNull() ?: return null
    return file.takeIf { it.startsWith(realRoot) && Files.isRegularFile(it) }
}
