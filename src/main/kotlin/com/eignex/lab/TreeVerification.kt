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
    @Serializable internal data class Stamp(val bytes: Long, val modified: String, val changed: String?,
                                          val key: String?, val executable: Boolean) {
        val reusable get() = changed != null && key != null
    }
    @Serializable internal data class Written(val file: InputFile, val stamp: Stamp)
    @Serializable private data class Receipt(val version: Int, val rootKey: String?, val files: List<Written>)
    private class Invalid : RuntimeException()

    private fun reliable(tree: Path) = Files.getFileStore(tree).type().lowercase() in
        setOf("apfs", "ext4", "xfs", "btrfs", "overlay", "tmpfs", "zfs")

    private fun previous(tree: Path, receipt: Path): Receipt? = runCatching {
        require(Files.isRegularFile(receipt, NOFOLLOW_LINKS))
        Json.decodeFromString<Receipt>(Files.readString(receipt))
    }.getOrNull()?.takeIf { it.version == 1 && it.rootKey != null &&
        it.rootKey == Files.readAttributes(tree, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()?.toString() }

    /** Proof of bytes just verified while writing. Retain that exact inode/ctime through publication. */
    fun written(path: Path, file: InputFile): Written = Written(file, stamp(path))

    fun record(tree: Path, files: List<Written>, receipt: Path, checkActive: () -> Unit = {}) {
        require(files.map { it.file.path }.distinct().size == files.size)
        for (entry in files) {
            checkActive()
            require(entry.stamp.bytes == entry.file.bytes && entry.stamp.executable == entry.file.executable &&
                Files.isRegularFile(tree.resolve(entry.file.path), NOFOLLOW_LINKS) && stamp(tree.resolve(entry.file.path)) == entry.stamp) { "verified output changed before publication" }
        }
        writeReceipt(receipt, Receipt(1, Files.readAttributes(tree, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()?.toString(), files.sortedBy { it.file.path }))
    }

    /** A successful trusted filesystem copy preserves bytes. Bind it to a verified source before/after copying,
     * then record the new private inodes. No hard links, and setup changes still require fresh checksums. */
    fun copy(source: Path, sourceReceipt: Path, destination: Path, receipt: Path,
             checkActive: () -> Unit = {}, include: (Path) -> Boolean = { true }, copy: () -> Unit) {
        val proof = if (reliable(source)) previous(source, sourceReceipt)?.let { it.copy(files = it.files.filter { file -> include(Path.of(file.file.path)) }) } else null
        fun unchanged() {
            for (entry in proof?.files.orEmpty()) {
                checkActive()
                val path = source.resolve(entry.file.path)
                require(Files.isRegularFile(path, NOFOLLOW_LINKS) && stamp(path) == entry.stamp) {
                    "verified copy source changed"
                }
            }
        }
        val started = System.nanoTime(); unchanged(); val checked = System.nanoTime()
        copy(); val copiedAt = System.nanoTime(); unchanged(); val confirmed = System.nanoTime()
        if (proof == null || !reliable(destination)) return // Portable checksum fallback when metadata cannot prove identity.
        val expected = proof.files.associateBy { it.file.path }
        val copied = mutableListOf<Written>()
        Files.walkFileTree(destination, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkActive(); return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkActive(); require(attrs.isRegularFile) { "copy contains unsupported links" }
                val name = destination.relativize(file).toString().replace('\\', '/')
                val entry = requireNotNull(expected[name]) { "copy contains an unverified file" }.file
                require(attrs.size() == entry.bytes && Files.isExecutable(file) == entry.executable) { "copy metadata differs" }
                copied += written(file, entry)
                return FileVisitResult.CONTINUE
            }
        })
        require(copied.map { it.file.path }.toSet() == expected.keys) { "private copy is incomplete" }
        val observed = System.nanoTime(); record(destination, copied, receipt, checkActive)
        println("worker copy timing: sourceMs=${(checked-started)/1_000_000} copyMs=${(copiedAt-checked)/1_000_000} confirmMs=${(confirmed-copiedAt)/1_000_000} targetMs=${(observed-confirmed)/1_000_000} receiptMs=${(System.nanoTime()-observed)/1_000_000} files=${copied.size}")
    }

    fun valid(tree: Path, expected: List<InputFile>, receipt: Path, checkActive: () -> Unit = {},
              exactExecutables: Boolean = false, removeUndeclared: Boolean = false): Boolean {
        checkActive()
        if (!Files.isDirectory(tree, NOFOLLOW_LINKS)) return false
        // Coarse timestamp filesystems (for example HFS+/FAT) cannot distinguish a quick write/mtime restore.
        val reliableMetadata = reliable(tree)
        val rootKey = Files.readAttributes(tree, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()?.toString()
        val previous = previous(tree, receipt)
        val old = previous?.files?.associateBy { it.file.path }.orEmpty()
        val wanted = expected.associateBy { it.path }
        if (wanted.size != expected.size) return false
        val verified = mutableListOf<Written>()
        try {
            Files.walkFileTree(tree, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checkActive()
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checkActive()
                    val name = tree.relativize(file).toString().replace('\\', '/')
                    val entry = wanted[name] ?: run {
                        if (!removeUndeclared) throw Invalid()
                        Files.delete(file); return FileVisitResult.CONTINUE
                    }
                    if (!attrs.isRegularFile) throw Invalid()
                    val before = stamp(file, attrs)
                    if (before.bytes != entry.bytes || (entry.executable || exactExecutables) && before.executable != entry.executable) throw Invalid()
                    val cached = old[name]
                    if (!reliableMetadata || !before.reusable || cached?.file != entry || cached.stamp != before) {
                        if (hash(file, checkActive) != entry.sha256 || stamp(file) != before) throw Invalid()
                    }
                    verified += Written(entry, before)
                    return FileVisitResult.CONTINUE
                }
            })
        } catch (_: Invalid) { return false } catch (_: IOException) { return false }
        if (verified.size != expected.size) return false
        val result = Receipt(1, rootKey, verified.sortedBy { it.file.path })
        if (result != previous) {
            checkActive()
            writeReceipt(receipt, result)
        }
        return true
    }

    /** Seal a newly private tree and its metadata receipt with one checksum pass, rather than hashing twice. */
    fun capture(tree: Path, receipt: Path, checkActive: () -> Unit = {}): List<InputFile> {
        checkActive(); require(Files.isDirectory(tree, NOFOLLOW_LINKS))
        val files = mutableListOf<Written>(); var bytes = 0L
        val old = if (reliable(tree)) previous(tree, receipt)?.files?.associateBy { it.file.path }.orEmpty() else emptyMap()
        val rootKey = Files.readAttributes(tree, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()?.toString()
        Files.walkFileTree(tree, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkActive(); return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkActive(); require(attrs.isRegularFile) { "workspace contains unsupported links" }
                val before = stamp(file, attrs)
                bytes = Math.addExact(bytes, before.bytes); require(bytes <= 32L * 1024 * 1024 * 1024)
                val name = tree.relativize(file).toString().replace('\\', '/')
                val cached = old[name]
                val sha = if (before.reusable && cached?.stamp == before) cached.file.sha256 else hash(file, checkActive)
                require(stamp(file) == before) { "workspace changed during sealing" }
                files += Written(InputFile(name, sha, before.bytes, before.executable), before)
                return FileVisitResult.CONTINUE
            }
        })
        checkActive(); writeReceipt(receipt, Receipt(1, rootKey, files.sortedBy { it.file.path }))
        return files.map { it.file }
    }

    private fun writeReceipt(receipt: Path, result: Receipt) {
        val temporary = Files.createTempFile(receipt.parent, ".verification-", ".tmp")
        try {
            Files.writeString(temporary, Json.encodeToString(result))
            Files.move(temporary, receipt, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun stamp(file: Path, attrs: BasicFileAttributes = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)): Stamp =
        Stamp(attrs.size(), attrs.lastModifiedTime().toString(),
            runCatching { Files.getAttribute(file, "unix:ctime", NOFOLLOW_LINKS).toString() }.getOrNull(),
            attrs.fileKey()?.toString(), Files.isExecutable(file))
}
