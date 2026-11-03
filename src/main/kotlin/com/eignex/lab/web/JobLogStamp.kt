package com.eignex.lab.web

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/** Coordinator logs can be published after the job's terminal database transition. Metadata only, never contents. */
internal data class JobLogStamp(val name: String, val regular: Boolean = false, val bytes: Long? = null,
                                val modified: FileTime? = null, val identity: String? = null)

/** A fixed four-path background probe; it never enumerates a directory or touches accepted artifact blobs. */
internal fun jobLogStamp(root: Path, attributes: (Path) -> BasicFileAttributes = {
    Files.readAttributes(it, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
}): List<JobLogStamp> = listOf("job.log", "setup.log", "setup.err", "git-output.log").map { name ->
    if (Thread.currentThread().isInterrupted) throw InterruptedException()
    try {
        val file = attributes(root.resolve(name))
        JobLogStamp(name, file.isRegularFile, file.size(), file.lastModifiedTime(), file.fileKey()?.toString())
    } catch (_: NoSuchFileException) { JobLogStamp(name) }
    catch (_: IOException) { throw ApiError.Unavailable("job log metadata unavailable") }
}
