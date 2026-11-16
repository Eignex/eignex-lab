package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A local receipt avoids rereading unchanged bytes, including across worker processes. Every hit still
 * walks the tree. Unix ctime and inode identity detect replacement and writes with a restored mtime.
 * Filesystems without those attributes always take the checksum path. Receipts are disposable cache state. */
internal class TreeVerification(private val readAheadFiles: Int = 512, private val bulkMetadata: Boolean = true,
                                private val memoryReceipt: MemoryReceipt? = null, private val hash: (Path, () -> Unit) -> String = ::hashFile) {
    @Serializable internal data class Stamp(val bytes: Long, val modified: String, val changed: String?,
                                          val key: String?, val executable: Boolean) {
        val reusable get() = changed != null && key != null
    }
    @Serializable internal data class Written(val file: InputFile, val stamp: Stamp)
    @Serializable internal data class Receipt(val version: Int, val rootKey: String?, val files: List<Written>)
    /** Session-private proof hint, owned by one guarded workspace slot. Every use still verifies the mutable tree.
     * Never used for a copy source: shared source proofs remain persistent and root-bound. */
    internal class MemoryReceipt { internal var value: Receipt? = null }
    internal enum class Rejection(val code: Int) {
        NONE(0), MISSING_TREE(1), DUPLICATE_EXPECTED(2), UNDECLARED_FILE(3), SIZE(4), EXECUTABLE(5),
        CHECKSUM(6), CHANGED_DURING_HASH(7), MISSING_FILE(8), DIRECTORIES(9), IO(10),
    }
    internal data class Stats(var checkedFiles: Int = 0, var hashedFiles: Int = 0, var hashedBytes: Long = 0,
                              var verificationNanos: Long = 0, var readAheadFiles: Int = 0, var bulkFiles: Int = 0,
                              var removedFiles: Int = 0, var rejection: Rejection = Rejection.NONE)
    // RAM-only single-file proof; do not change the existing serialized tree receipt's Stamp format.
    internal data class FileMetadata(val stamp: Stamp, val mode: Int) {
        val reusable get() = stamp.reusable
    }
    val stats = Stats()
    private fun checksum(path: Path, checkActive: () -> Unit, bytes: Long): String {
        stats.hashedFiles++; stats.hashedBytes = Math.addExact(stats.hashedBytes, bytes)
        return hash(path, checkActive)
    }
    private class Invalid(val reason: Rejection) : RuntimeException()

    private fun reliable(tree: Path) = reliableMetadata(tree)

    private fun persistentPrevious(tree: Path, receipt: Path): Receipt? = runCatching {
        require(Files.isRegularFile(receipt, NOFOLLOW_LINKS))
        Json.decodeFromString<Receipt>(Files.readString(receipt))
    }.getOrNull()?.takeIf { it.version == 1 && it.rootKey != null &&
        it.rootKey == Files.readAttributes(tree, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()?.toString() }

    private fun previous(tree: Path, receipt: Path): Receipt? = if (memoryReceipt == null) persistentPrevious(tree, receipt)
        else memoryReceipt.value?.takeIf { it.version == 1 && it.rootKey != null && it.rootKey == rootKey(tree) }

    /** An individually unchanged file may reuse its verified-copy checksum. Missing, stale or unsupported proof
     * returns a hint miss, leaving the caller's ordinary byte-hashing path intact. */
    fun checksums(tree: Path, receipt: Path): (Path, MacDirectoryEntries.Entry?) -> String? {
        val root = tree.toAbsolutePath().normalize()
        val proof = if (reliable(root)) persistentPrevious(root, receipt)?.files?.associateBy { it.file.path }.orEmpty() else emptyMap()
        return { file, observed ->
            val path = file.toAbsolutePath().normalize()
            val entry = if (path.startsWith(root)) proof[root.relativize(path).toString().replace('\\', '/')] else null
            entry?.takeIf { it.stamp.reusable && (observed?.let { value ->
                require(value.regular && value.name == path.fileName.toString())
                Stamp(value.bytes, value.modified, value.changed, value.key, value.executable)
            } ?: stamp(path)) == it.stamp }?.file?.sha256
        }
    }

    /** Proof of bytes just verified while writing. Retain that exact inode/ctime through publication. */
    fun written(path: Path, file: InputFile): Written = Written(file, stamp(path))

    fun record(tree: Path, files: List<Written>, receipt: Path, checkActive: () -> Unit = {}) {
        require(files.map { it.file.path }.distinct().size == files.size)
        for (entry in files) {
            checkActive()
            require(entry.stamp.bytes == entry.file.bytes && entry.stamp.executable == entry.file.executable &&
                stamp(tree.resolve(entry.file.path)) == entry.stamp) { "verified output changed before publication" }
        }
        writeReceipt(receipt, Receipt(1, Files.readAttributes(tree, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()?.toString(), files.sortedBy { it.file.path }))
    }

    /** A successful trusted filesystem copy preserves bytes. Bind it to a verified source before/after copying,
     * then record the new private inodes. Return the complete verified inventory only after its receipt is published;
     * callers with no intervening setup can seal that private copy without another traversal. No hard links, and
     * setup changes still require fresh checksums. */
    fun copy(source: Path, sourceReceipt: Path, destination: Path, receipt: Path,
             checkActive: () -> Unit = {}, include: (Path) -> Boolean = { true }, copy: () -> Unit): List<InputFile>? {
        val started = System.nanoTime(); var copyingNanos = 0L
        try {
            return copyTree(source, sourceReceipt, destination, receipt, checkActive, include) {
                val copyingAt = System.nanoTime()
                try { copy() } finally { copyingNanos = System.nanoTime() - copyingAt }
            }
        } finally { stats.verificationNanos += (System.nanoTime() - started - copyingNanos).coerceAtLeast(0) }
    }

    private fun copyTree(source: Path, sourceReceipt: Path, destination: Path, receipt: Path,
                         checkActive: () -> Unit, include: (Path) -> Boolean, copy: () -> Unit): List<InputFile>? {
        val original = if (reliable(source)) persistentPrevious(source, sourceReceipt) else null
        val proof = original?.let { it.copy(files = it.files.filter { file -> include(Path.of(file.file.path)) }) }
        val expectedProof = proof?.files?.associateBy { it.file.path }.orEmpty()
        val proofPaths = if (proof != null && proof.files.size >= 64) buildSet {
            add("")
            for (file in proof.files) {
                val path = Path.of(file.file.path)
                for (count in 1..path.nameCount) add(path.subpath(0, count).toString().replace('\\', '/'))
            }
        } else emptySet()
        fun unchanged() {
            if (bulkMetadata && proofPaths.isNotEmpty() && MacDirectoryEntries.supported(source)) {
                var count = 0
                // A receipt may intentionally cover only runtime or selected inputs. Traverse their ancestors,
                // checking exactly those files, just like the individual-stat path; never require unrelated scratch.
                walk(source, checkActive, include = { it.toString().replace('\\', '/') in proofPaths }) { path, observed ->
                    val entry = expectedProof[source.relativize(path).toString().replace('\\', '/')]
                    require(entry != null && observed.stamp() == entry.stamp) { "verified copy source changed" }
                    count++
                }
                require(count == expectedProof.size) { "verified copy source changed" }
                return
            }
            for (entry in proof?.files.orEmpty()) {
                checkActive()
                val path = source.resolve(entry.file.path)
                require(stamp(path) == entry.stamp) {
                    "verified copy source changed"
                }
            }
        }
        val started = System.nanoTime(); unchanged(); val checked = System.nanoTime()
        copy(); val copiedAt = System.nanoTime(); unchanged(); val confirmed = System.nanoTime()
        if (proof == null || !reliable(destination)) return null // Portable checksum fallback when metadata cannot prove identity.
        val expected = proof.files.associateBy { it.file.path }
        val copied = mutableListOf<Written>()
        walk(destination, checkActive) { file, observed ->
            val name = destination.relativize(file).toString().replace('\\', '/')
            val entry = requireNotNull(expected[name]) { "copy contains an unverified file" }.file
            val actual = observed.stamp()
            require(actual.bytes == entry.bytes && actual.executable == entry.executable) { "copy metadata differs" }
            copied += Written(entry, actual)
        }
        require(copied.map { it.file.path }.toSet() == expected.keys) { "private copy is incomplete" }
        val observed = System.nanoTime()
        // The new private inodes were observed after copying; later use checks these exact stamps.
        // Restatting the entire destination here cannot strengthen that later check.
        writeReceipt(receipt, Receipt(1, rootKey(destination), copied.sortedBy { it.file.path }))
        println("worker copy timing: sourceMs=${(checked-started)/1_000_000} copyMs=${(copiedAt-checked)/1_000_000} confirmMs=${(confirmed-copiedAt)/1_000_000} targetMs=${(observed-confirmed)/1_000_000} receiptMs=${(System.nanoTime()-observed)/1_000_000} files=${copied.size}")
        return copied.map { it.file }
    }

    fun valid(tree: Path, expected: List<InputFile>, receipt: Path, checkActive: () -> Unit = {},
              exactExecutables: Boolean = false, removeUndeclared: Boolean = false, allowUndeclared: Boolean = false,
              expectedDirectories: List<String>? = null, include: (Path) -> Boolean = { true }): Boolean {
        val started = System.nanoTime(); stats.rejection = Rejection.NONE
        try { return validTree(tree, expected, receipt, checkActive, exactExecutables, removeUndeclared, allowUndeclared, expectedDirectories, include) }
        finally { stats.verificationNanos += System.nanoTime() - started }
    }

    private fun validTree(tree: Path, expected: List<InputFile>, receipt: Path, checkActive: () -> Unit,
                          exactExecutables: Boolean, removeUndeclared: Boolean, allowUndeclared: Boolean, expectedDirectories: List<String>?, include: (Path) -> Boolean): Boolean {
        checkActive()
        fun reject(reason: Rejection): Boolean { stats.rejection = reason; return false }
        if (!Files.isDirectory(tree, NOFOLLOW_LINKS)) return reject(Rejection.MISSING_TREE)
        // Coarse timestamp filesystems (for example HFS+/FAT) cannot distinguish a quick write/mtime restore.
        val reliableMetadata = reliable(tree)
        val rootKey = Files.readAttributes(tree, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()?.toString()
        val previous = previous(tree, receipt)
        val old = previous?.files?.associateBy { it.file.path }.orEmpty()
        val wanted = expected.associateBy { it.path }
        if (wanted.size != expected.size) return reject(Rejection.DUPLICATE_EXPECTED)
        val verified = mutableListOf<Written>()
        val directories = expectedDirectories?.let { mutableListOf<String>() }
        try { readAhead(tree, expected.map { it.path }, checkActive) {
            walk(tree, checkActive, include = include, directories = directories) { file, observed ->
                val name = tree.relativize(file).toString().replace('\\', '/')
                val entry = wanted[name]
                if (entry == null) {
                    if (!allowUndeclared) {
                        if (!removeUndeclared) throw Invalid(Rejection.UNDECLARED_FILE)
                        Files.delete(file); stats.removedFiles++
                    }
                } else {
                    val cached = old[name]
                    val before = observed.stamp()
                    if (before.bytes != entry.bytes) throw Invalid(Rejection.SIZE)
                    if ((entry.executable || exactExecutables) && before.executable != entry.executable) throw Invalid(Rejection.EXECUTABLE)
                    if (!reliableMetadata || !before.reusable || cached?.file != entry || cached.stamp != before) {
                        if (checksum(file, checkActive, before.bytes) != entry.sha256) throw Invalid(Rejection.CHECKSUM)
                        if (stamp(file) != before) throw Invalid(Rejection.CHANGED_DURING_HASH)
                    }
                    verified += Written(entry, before)
                }
            }
        } } catch (failure: Invalid) { return reject(failure.reason) } catch (_: IOException) { return reject(Rejection.IO) }
        if (verified.size != expected.size) return reject(Rejection.MISSING_FILE)
        if (expectedDirectories != null && directories?.sorted() != expectedDirectories.sorted()) return reject(Rejection.DIRECTORIES)
        val result = Receipt(1, rootKey, verified.sortedBy { it.file.path })
        if (result != previous) {
            checkActive()
            writeReceipt(receipt, result)
        }
        return true
    }

    /** Seal a newly private tree and its metadata receipt with one checksum pass, rather than hashing twice. */
    fun capture(tree: Path, receipt: Path, checkActive: () -> Unit = {}, directories: MutableList<String>? = null, include: (Path) -> Boolean = { true }): List<InputFile> {
        checkActive(); require(Files.isDirectory(tree, NOFOLLOW_LINKS))
        val files = mutableListOf<Written>(); var bytes = 0L
        val previous = if (reliable(tree)) previous(tree, receipt) else null
        val old = previous?.files?.associateBy { it.file.path }.orEmpty()
        val rootKey = Files.readAttributes(tree, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()?.toString()
        walk(tree, checkActive, include, directories) { file, observed ->
            val name = tree.relativize(file).toString().replace('\\', '/')
            val cached = old[name]
            val before = observed.stamp()
            bytes = Math.addExact(bytes, before.bytes); require(bytes <= 32L * 1024 * 1024 * 1024)
            val reusable = before.reusable && cached?.stamp == before
            val sha = if (reusable) requireNotNull(cached).file.sha256 else checksum(file, checkActive, before.bytes)
            if (!reusable) require(stamp(file) == before) { "workspace changed during sealing" }
            files += Written(InputFile(name, sha, before.bytes, before.executable), before)
        }
        checkActive(); writeReceipt(receipt, Receipt(1, rootKey, files.sortedBy { it.file.path }))
        return files.map { it.file }
    }

    /** Cold metadata, not hashing, dominates a warm hit on a loaded node: a Mac spent 77 s (7 ms per file) on the first
     * no-follow stats of an 11k-file cached build whose cached walk takes 30 µs per file, and hashed nothing. One
     * sequential walk waits for each read in turn. Concurrent stats of the expected paths overlap that latency.
     * Their results are discarded: the walk below still decides validity from its own observations alone. */
    private fun <T> readAhead(tree: Path, paths: List<String>, checkActive: () -> Unit, use: () -> T): T {
        if (paths.size < readAheadFiles || bulkMetadata && MacDirectoryEntries.supported(tree)) return use()
        checkActive()
        val next = AtomicInteger(); val stop = AtomicBoolean(); val read = AtomicInteger()
        val threads = List(READ_AHEAD_THREADS) { Thread.ofPlatform().daemon().name("tree-read-ahead").start {
            while (!stop.get()) {
                val index = next.getAndIncrement(); if (index >= paths.size) break
                try { Files.readAttributes(tree.resolve(safeRelative(paths[index])), BasicFileAttributes::class.java, NOFOLLOW_LINKS); read.incrementAndGet() }
                catch (_: IOException) { } catch (_: IllegalArgumentException) { } catch (_: UnsupportedOperationException) { }
            }
        } }
        try { return use() } finally {
            stop.set(true)
            var interrupted = false
            for (thread in threads) while (thread.isAlive) try { thread.join() } catch (_: InterruptedException) { interrupted = true }
            if (interrupted) Thread.currentThread().interrupt()
            stats.readAheadFiles += read.get()
        }
    }

    private fun writeReceipt(receipt: Path, result: Receipt) {
        if (memoryReceipt != null) { memoryReceipt.value = result; return }
        receipt.writeAtomically(Json.encodeToString(result), durable = false)
    }

    private fun rootKey(tree: Path) = Files.readAttributes(tree, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()?.toString()

    /** One no-follow stat per entry instead of walkFileTree's basic stat followed by another Unix stat.
     * Keep access(2) for effective-user/ACL executable semantics, even when ctime/inode are unchanged. */
    private class Observed(val file: Path, val directory: Boolean, val regular: Boolean,
                           val bytes: Long, val modified: String, val changed: String?, val key: String?,
                           private val executable: Boolean? = null, val mode: Int? = null) {
        fun stamp(): Stamp {
            if (!regular) throw IOException("verification requires a regular file")
            return Stamp(bytes, modified, changed, key, executable ?: Files.isExecutable(file))
        }
    }

    private fun stamp(file: Path): Stamp {
        stats.checkedFiles++
        return observe(file).stamp()
    }

    private fun walk(tree: Path, checkActive: () -> Unit, include: (Path) -> Boolean = { true }, directories: MutableList<String>? = null, visit: (Path, Observed) -> Unit) {
        fun directory(path: Path) {
            checkActive()
            if (path != tree) directories?.add(tree.relativize(path).toString().replace('\\', '/'))
            val bulk = if (bulkMetadata) MacDirectoryEntries.read(path, checkActive) else null
            if (bulk != null) {
                for (entry in bulk) {
                    checkActive(); val file = path.resolve(entry.name)
                    if (!include(tree.relativize(file))) continue
                    if (entry.directory) directory(file) else {
                        stats.checkedFiles++; stats.bulkFiles++
                        visit(file, Observed(file, false, entry.regular, entry.bytes, entry.modified, entry.changed, entry.key, entry.executable))
                    }
                }
                return
            }
            Files.newDirectoryStream(path).use { entries ->
                for (file in entries) {
                    checkActive()
                    if (!include(tree.relativize(file))) continue
                    val observed = observe(file)
                    if (observed.directory) directory(file) else { stats.checkedFiles++; visit(file, observed) }
                }
            }
        }
        if (include(tree.relativize(tree))) directory(tree)
    }

    companion object {
        internal fun reliableMetadata(path: Path) = Files.getFileStore(path).type().lowercase() in
            setOf("apfs", "ext4", "xfs", "btrfs", "overlay", "tmpfs", "zfs")

        internal fun fileMetadata(file: Path): FileMetadata? = observe(file, includeMode = true).let { observed ->
            observed.mode?.let { FileMetadata(observed.stamp(), it) }
        }

        private fun observe(file: Path, includeMode: Boolean = false): Observed {
            val attributes = "unix:size,lastModifiedTime,ctime,fileKey,isRegularFile,isDirectory"
            val unix = try { Files.readAttributes(file, if (includeMode) "$attributes,mode" else attributes, NOFOLLOW_LINKS) }
                catch (_: UnsupportedOperationException) { null }
            if (unix != null) return Observed(file, unix["isDirectory"] == true, unix["isRegularFile"] == true,
                unix["size"] as Long, unix["lastModifiedTime"].toString(), unix["ctime"]?.toString(), unix["fileKey"]?.toString(), mode = unix["mode"] as? Int)
            val basic = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            return Observed(file, basic.isDirectory, basic.isRegularFile, basic.size(), basic.lastModifiedTime().toString(), null, basic.fileKey()?.toString())
        }
    }
}

/** Enough outstanding reads to overlap SSD latency under contention, few enough to stay a bounded side load. */
private const val READ_AHEAD_THREADS = 16
