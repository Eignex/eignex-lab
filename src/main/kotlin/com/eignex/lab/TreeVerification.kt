package com.eignex.lab

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes

/** A local receipt avoids rereading unchanged bytes, including across worker processes. Every hit still
 * walks the tree. Unix ctime and inode identity detect replacement and writes with a restored mtime.
 * Filesystems without those attributes always take the checksum path. Receipts are disposable cache state. */
internal class TreeVerification(private val hash: (Path, () -> Unit) -> String = ::hashFile) {
    @Serializable private data class Stamp(val bytes: Long, val modified: String, val changed: String?,
                                          val key: String?, val executable: Boolean) {
        val reusable get() = changed != null && key != null
    }
    @Serializable private data class Verified(val file: InputFile, val stamp: Stamp)
    @Serializable private data class Receipt(val version: Int, val rootKey: String?, val files: List<Verified>)
    private class Invalid : RuntimeException()

    fun valid(tree: Path, expected: List<InputFile>, receipt: Path, checkActive: () -> Unit = {},
              exactExecutables: Boolean = false): Boolean {
        checkActive()
        if (!Files.isDirectory(tree, NOFOLLOW_LINKS)) return false
        // Coarse timestamp filesystems (for example HFS+/FAT) cannot distinguish a quick write/mtime restore.
        val reliableMetadata = Files.getFileStore(tree).type().lowercase() in setOf("apfs", "ext4", "xfs", "btrfs", "overlay", "tmpfs", "zfs")
        val rootKey = Files.readAttributes(tree, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()?.toString()
        val previous = runCatching {
            require(Files.isRegularFile(receipt, NOFOLLOW_LINKS))
            Json.decodeFromString<Receipt>(Files.readString(receipt))
        }.getOrNull()?.takeIf { it.version == 1 && rootKey != null && it.rootKey == rootKey }
        val old = previous?.files?.associateBy { it.file.path }.orEmpty()
        val wanted = expected.associateBy { it.path }
        if (wanted.size != expected.size) return false
        val verified = mutableListOf<Verified>()
        try {
            Files.walkFileTree(tree, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checkActive()
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checkActive()
                    if (!attrs.isRegularFile) throw Invalid()
                    val name = tree.relativize(file).toString().replace('\\', '/')
                    val entry = wanted[name] ?: throw Invalid()
                    val before = stamp(file, attrs)
                    if (before.bytes != entry.bytes || (entry.executable || exactExecutables) && before.executable != entry.executable) throw Invalid()
                    val cached = old[name]
                    if (!reliableMetadata || !before.reusable || cached?.file != entry || cached.stamp != before) {
                        if (hash(file, checkActive) != entry.sha256 || stamp(file) != before) throw Invalid()
                    }
                    verified += Verified(entry, before)
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: Invalid) { return false } catch (_: IOException) { return false }
        if (verified.size != expected.size) return false
        val result = Receipt(1, rootKey, verified.sortedBy { it.file.path })
        if (result != previous) {
            checkActive()
            val temporary = Files.createTempFile(receipt.parent, ".verification-", ".tmp")
            try {
                Files.writeString(temporary, Json.encodeToString(result))
                Files.move(temporary, receipt, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }
        return true
    }

    private fun stamp(file: Path, attrs: BasicFileAttributes = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)): Stamp =
        Stamp(attrs.size(), attrs.lastModifiedTime().toString(),
            runCatching { Files.getAttribute(file, "unix:ctime", NOFOLLOW_LINKS).toString() }.getOrNull(),
            attrs.fileKey()?.toString(), Files.isExecutable(file))
}
