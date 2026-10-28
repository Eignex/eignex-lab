package com.eignex.lab

import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.security.DigestInputStream
import java.io.FilterInputStream
import java.util.zip.ZipInputStream

/** Bounded, locked, checksum-verified extraction with atomic publication. No archive-supplied links or modes. */
class VerifiedCache(private val root: Path, private val maxBytes: Long = 32L * 1024 * 1024 * 1024) {
    fun stage(bundle: WorkerBundle, checkActive: () -> Unit = {}, download: () -> InputStream): Path = stage(bundle, checkActive, false, download)
    internal fun stageRuntime(bundle: WorkerBundle, checkActive: () -> Unit = {}, download: () -> InputStream): Path = stage(bundle, checkActive, true, download)

    private fun stage(bundle: WorkerBundle, checkActive: () -> Unit, runtimeOnly: Boolean, download: () -> InputStream): Path {
        checkActive()
        val included = bundle.files.filter { !runtimeOnly || WorkerTrees.runtimePath(Path.of(it.path)) }
        val declaredBytes = bundle.files.fold(0L) { total, file ->
            require(file.bytes <= maxBytes - total) { "expanded archive exceeds cache limit" }
            total + file.bytes
        }
        require(declaredBytes <= maxBytes)
        Files.createDirectories(root)
        val key = inputCacheKey(bundle, runtimeOnly)
        val destination = root.resolve(key)
        val receipt = root.resolve("$key.verified.json")
        synchronized(fileLockFor(root.resolve("$key.lock"))) {
        FileChannel.open(root.resolve("$key.lock"), CREATE, WRITE).use { channel -> channel.lock().use {
            checkActive()
            if (TreeVerification().valid(destination, included, receipt, checkActive)) return destination
            Files.deleteIfExists(receipt)
            if (Files.exists(destination, NOFOLLOW_LINKS)) remove(destination)
            val staging = Files.createTempDirectory(root, "$key-")
            val tree = Files.createDirectory(staging.resolve("tree"))
            try {
                val expected = bundle.files.associateBy { it.path }; require(expected.size == bundle.files.size)
                val seen = mutableSetOf<String>()
                val verifier = TreeVerification(); val written = mutableListOf<TreeVerification.Written>()
                val digest = MessageDigest.getInstance("SHA-256")
                download().use { input ->
                val bounded = object : FilterInputStream(input) {
                    var bytes = 0L
                    override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 255 }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        checkActive(); val count = `in`.read(buffer, offset, length)
                        if (count > 0) { bytes += count; require(bytes <= maxBytes) { "archive exceeds cache limit" } }
                        return count
                    }
                }
                val raw = DigestInputStream(bounded, digest)
                ZipInputStream(raw).use { zip ->
                    while (true) {
                        checkActive()
                        val entry = zip.nextEntry ?: break
                        val relative = safeRelative(entry.name.removeSuffix("/"))
                        val target = tree.resolve(relative)
                        if (entry.isDirectory) {
                            if (!runtimeOnly || WorkerTrees.runtimePath(relative)) Files.createDirectories(target)
                            continue
                        }
                        val file = requireNotNull(expected[entry.name]) { "undeclared archive entry" }
                        require(seen.add(entry.name)) { "duplicate archive entry" }
                        val keep = !runtimeOnly || WorkerTrees.runtimePath(relative)
                        if (keep) Files.createDirectories(target.parent)
                        val fileDigest = MessageDigest.getInstance("SHA-256")
                        (if (keep) Files.newOutputStream(target, CREATE_NEW) else java.io.OutputStream.nullOutputStream()).use { output ->
                            var size = 0L; val buffer = ByteArray(64 * 1024)
                            while (true) { checkActive(); val n = zip.read(buffer); if (n < 0) break; size += n; require(size <= file.bytes) { "expanded file exceeds declared size" }; output.write(buffer, 0, n); fileDigest.update(buffer, 0, n) }
                            require(size == file.bytes)
                        }
                        require(fileDigest.digest().hex() == file.sha256) { "file checksum mismatch" }
                        if (keep) {
                            if (file.executable) require(target.toFile().setExecutable(true, true))
                            written += verifier.written(target, file)
                        }
                    }
                    // ZipInputStream stops at the central directory. Drain the entire raw archive, including its
                    // trailer, so its advertised digest remains a whole-object integrity check.
                    val buffer = ByteArray(64 * 1024)
                    while (raw.read(buffer) >= 0) checkActive()
                } }
                require(digest.digest().hex() == bundle.sha256) { "archive checksum mismatch" }
                require(seen == expected.keys) { "archive is incomplete" }
                verifier.record(tree, written, receipt, checkActive)
                checkActive()
                Files.move(tree, destination, ATOMIC_MOVE)
                return destination
            } finally { remove(staging) }
        } }
        }
    }
    private fun remove(path: Path) { Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
}

internal fun ByteArray.hex(): String = java.util.HexFormat.of().formatHex(this)
internal fun hashFile(path: Path, checkActive: () -> Unit = {}): String = Files.newInputStream(path).use { input ->
    val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
    while (true) { checkActive(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }; digest.digest().hex()
}

private val localFileLocks = Array(256) { Any() }
internal fun fileLockFor(path: Path): Any = localFileLocks[Math.floorMod(path.toAbsolutePath().normalize().toString().hashCode(), localFileLocks.size)]

/** Keep full/source and reduced runtime layouts separate, while retaining existing keys when no files are omitted. */
internal fun inputCacheKey(bundle: WorkerBundle, runtimeOnly: Boolean = false): String = ExecutionStore.digest(
    (if (runtimeOnly && bundle.files.any { !WorkerTrees.runtimePath(Path.of(it.path)) }) "runtime-inputs-v1\n" else "") +
        kotlinx.serialization.json.Json.encodeToString(WorkerBundle.serializer(), bundle))
