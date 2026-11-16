package com.eignex.lab.store

import com.eignex.lab.domain.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** A captured inventory can be reused by the cache miss path without walking the same tree again. */
internal fun archiveInventory(tree: Path, output: Path, identity: WorkerBundle, paths: List<Path>,
                              directories: List<String>, checkActive: () -> Unit): WorkerBundle {
    require(paths.isNotEmpty())
    val files = mutableListOf<InputFile>(); var streamedBytes = 0L
    val archiveSha = DeterministicZip(java.io.BufferedOutputStream(Files.newOutputStream(output), 256 * 1024)).use { zip ->
        zip.directories(directories, checkActive)
        for (path in paths.sorted()) {
            val name = tree.relativize(path).toString().replace('\\', '/')
            val before = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(before.isRegularFile) { "archive contains unsupported links" }
            var bytes = 0L
            checkActive()
            val sha = zip.file(name) { sink ->
                Files.newInputStream(path).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { checkActive(); val count = input.read(buffer); if (count < 0) break; bytes += count; streamedBytes += count; require(bytes <= before.size() && streamedBytes <= 32L * 1024 * 1024 * 1024); sink(buffer, 0, count) }
                }
            }
            val after = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(bytes == before.size() && after.size() == bytes && before.lastModifiedTime() == after.lastModifiedTime() && before.fileKey() == after.fileKey()) { "archive input changed" }
            files += InputFile(name, sha, bytes, Files.isExecutable(path))
        }
        zip.finish()
    }
    return identity.copy(sha256 = archiveSha, files = files, build = null)
}

internal fun compressedArchivePath(path: String): Boolean = path.substringAfterLast('.').lowercase() in
    setOf("jar", "zip", "pack", "gz", "xz", "zst", "bz2", "png", "jpg", "jpeg", "webp")
