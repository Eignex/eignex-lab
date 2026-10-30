package com.eignex.lab.util

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.isSymbolicLink

/** Delete [root] and everything under it without following symbolic links, which are removed as links. */
internal fun deleteTree(root: Path) {
    if (root.isSymbolicLink() || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
        Files.deleteIfExists(root)
        return
    }
    Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
}

/**
 * Write via a temp file beside this one and an atomic rename, so a reader never sees a half-written file. The bytes
 * reach the disk before the rename, so a crash leaves the old file or the new one, never an empty one; each writer
 * has its own temp file, so two writing at once cannot interleave.
 */
internal fun Path.writeAtomically(text: String) {
    val temp = resolveSibling(".$fileName.${UUID.randomUUID()}.tmp")
    try {
        FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val bytes = ByteBuffer.wrap(text.toByteArray())
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
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
