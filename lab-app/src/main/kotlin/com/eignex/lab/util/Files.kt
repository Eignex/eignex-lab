package com.eignex.lab.util

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.isSymbolicLink

/**
 * Delete [root] and everything under it without following symbolic links, which are removed as links. With
 * [ignoreMissing], an entry something else removed after the walk listed it is no failure.
 */
internal fun deleteTree(root: Path, ignoreMissing: Boolean = false) {
    if (root.isSymbolicLink() || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
        Files.deleteIfExists(root)
        return
    }
    if (!ignoreMissing) {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        return
    }
    // The walk itself, not only the deletes, can meet an entry that is gone by the time it is read. Other failures
    // surface as Files.walk reports them: unchecked below the root.
    fun failed(path: Path, failure: IOException): FileVisitResult = when {
        failure is NoSuchFileException -> FileVisitResult.CONTINUE
        path == root -> throw failure
        else -> throw java.io.UncheckedIOException(failure)
    }
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.deleteIfExists(file); return FileVisitResult.CONTINUE
        }
        override fun visitFileFailed(file: Path, failure: IOException): FileVisitResult = failed(file, failure)
        override fun postVisitDirectory(dir: Path, failure: IOException?): FileVisitResult {
            if (failure != null) failed(dir, failure)
            Files.deleteIfExists(dir); return FileVisitResult.CONTINUE
        }
    })
}

/**
 * Write via a temp file beside this one and an atomic rename, so a reader never sees a half-written file. When
 * [durable], the bytes reach the disk before the rename, so a crash leaves the old file or the new one, never an empty
 * one; disposable files skip that flush. Each writer has its own temp file, so two writing at once cannot interleave.
 */
internal fun Path.writeAtomically(text: String, durable: Boolean = true) {
    val temp = resolveSibling(".$fileName.${UUID.randomUUID()}.tmp")
    try {
        FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val bytes = ByteBuffer.wrap(text.toByteArray())
            while (bytes.hasRemaining()) channel.write(bytes)
            if (durable) channel.force(true)
        }
        Files.move(temp, this, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temp)
    }
}

internal fun ByteArray.hex(): String = java.util.HexFormat.of().formatHex(this)

/** The SHA-256 of the file at [path], in hex; [checkActive] runs between reads, to give up on a long one. */
internal fun hashFile(path: Path, checkActive: () -> Unit = {}): String = Files.newInputStream(path).use { input ->
    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
    while (true) { checkActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }; digest.digest().hex()
}

/** A JVM-local monitor for [path], so threads of one process serialize before taking its file lock. */
internal fun fileLockFor(path: Path): Any = localFileLocks[Math.floorMod(path.toAbsolutePath().normalize().toString().hashCode(), localFileLocks.size)]
private val localFileLocks = Array(256) { Any() }
