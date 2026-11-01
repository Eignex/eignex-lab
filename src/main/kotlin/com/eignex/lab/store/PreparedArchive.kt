package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A captured inventory can be reused by the cache miss path without walking the same tree again. */
internal fun archiveInventory(tree: Path, output: Path, identity: WorkerBundle, paths: List<Path>,
                              directories: List<String>, checkActive: () -> Unit): WorkerBundle {
    require(paths.isNotEmpty())
    val files = mutableListOf<InputFile>(); var streamedBytes = 0L
    val archiveDigest = java.security.MessageDigest.getInstance("SHA-256")
    ZipOutputStream(java.security.DigestOutputStream(java.io.BufferedOutputStream(Files.newOutputStream(output), 256 * 1024), archiveDigest)).use { zip ->
        // Compress text quickly; each binary entry can select streaming framing below.
        zip.setLevel(java.util.zip.Deflater.BEST_SPEED)
        // Empty directories carry runtime meaning too, including a detached checkout's .git/refs.
        for (directory in directories.sorted()) {
            checkActive(); zip.putNextEntry(ZipEntry(directory).apply { time = 0 }); zip.closeEntry()
        }
        for (path in paths.sorted()) {
            val name = tree.relativize(path).toString().replace('\\', '/')
            val before = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(before.isRegularFile) { "archive contains unsupported links" }
            val digest = java.security.MessageDigest.getInstance("SHA-256"); var bytes = 0L
            // JAR/ZIP and Git pack contents are already compressed. Deflating those bytes again consumes
            // CPU; retain DEFLATED framing so the checksum and size stream in one pass.
            zip.setLevel(if (compressedArchivePath(name)) java.util.zip.Deflater.NO_COMPRESSION else java.util.zip.Deflater.BEST_SPEED)
            checkActive(); zip.putNextEntry(ZipEntry(name).apply { time = 0 })
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { checkActive(); val count = input.read(buffer); if (count < 0) break; bytes += count; streamedBytes += count; require(bytes <= before.size() && streamedBytes <= 32L * 1024 * 1024 * 1024); zip.write(buffer, 0, count); digest.update(buffer, 0, count) }
            }; zip.closeEntry()
            val after = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(bytes == before.size() && after.size() == bytes && before.lastModifiedTime() == after.lastModifiedTime() && before.fileKey() == after.fileKey()) { "archive input changed" }
            files += InputFile(name, digest.digest().hex(), bytes, Files.isExecutable(path))
        }
    }
    return identity.copy(sha256 = archiveDigest.digest().hex(), files = files, build = null)
}

internal fun compressedArchivePath(path: String): Boolean = path.substringAfterLast('.').lowercase() in
    setOf("jar", "zip", "pack", "gz", "xz", "zst", "bz2", "png", "jpg", "jpeg", "webp")
