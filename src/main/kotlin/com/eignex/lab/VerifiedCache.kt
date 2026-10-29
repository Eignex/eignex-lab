package com.eignex.lab

import java.io.InputStream
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
class VerifiedCache(private val root: Path, private val maxBytes: Long = 32L * 1024 * 1024 * 1024,
    private val materialize: ((WorkerBundle, List<InputFile>, Path, Path, () -> Unit) -> Boolean)? = null) {
    fun stage(bundle: WorkerBundle, checkActive: () -> Unit = {}, download: () -> InputStream): Path = stage(bundle, checkActive, false, download)
    internal fun stage(bundle: WorkerBundle, checkActive: () -> Unit, download: () -> InputStream, trace: PreparationTrace): Path = stage(bundle, checkActive, false, download, trace)
    internal fun seed(bundle: WorkerBundle, source: Path, sourceReceipt: Path, checkActive: () -> Unit, trace: PreparationTrace) =
        stage(bundle, checkActive, false, { error("seed cannot download") }, trace) { tree, receipt ->
            val verifier = TreeVerification()
            // Missing/corrupt receipts take the checksum path before using filesystem-copy proof.
            require(verifier.valid(source, bundle.files, sourceReceipt, checkActive, exactExecutables = true)) { "seed source differs from approved files" }
            verifier.copy(source, sourceReceipt, tree, receipt, checkActive, copy = { WorkerTrees.copy(source, tree, checkActive) })
            true
        }

    internal fun stageRuntime(bundle: WorkerBundle, checkActive: () -> Unit = {}, download: () -> InputStream): Path = stage(bundle, checkActive, true, download)
    internal fun stageRuntime(bundle: WorkerBundle, checkActive: () -> Unit, download: () -> InputStream, trace: PreparationTrace): Path = stage(bundle, checkActive, true, download, trace)

    private fun stage(bundle: WorkerBundle, checkActive: () -> Unit, runtimeOnly: Boolean, download: () -> InputStream,
                      trace: PreparationTrace = PreparationTrace(), seed: ((Path, Path) -> Boolean)? = null): Path {
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
        val waitingAt = System.nanoTime()
        // ProcessFilePin keeps a same-process waiter from opening and then closing another descriptor
        // for a held POSIX lock (which would release the owner's cross-process lock).
        while (true) {
            checkActive()
            val owner = ProcessFilePin.tryLock(root.resolve("$key.lock"))
            if (owner == null) { Thread.sleep(10); continue }
            owner.use {
            val waitingMs = (System.nanoTime() - waitingAt) / 1_000_000
            val present = Files.isDirectory(destination, NOFOLLOW_LINKS)
            val verificationAt = System.nanoTime()
            checkActive()
            val validation = TreeVerification()
            val valid = validation.valid(destination, included, receipt, checkActive)
            trace.cache("input", if (valid) "hit" else "miss", if (valid) (if (validation.stats.hashedFiles > 0) "receipt-rebuilt" else "verified") else if (present) "verification-failed" else "absent",
                verificationMs = (System.nanoTime() - verificationAt) / 1_000_000, waitingMs = waitingMs,
                bytes = included.sumOf { it.bytes }, files = included.size,
                checkedFiles = validation.stats.checkedFiles, hashedFiles = validation.stats.hashedFiles, hashedBytes = validation.stats.hashedBytes)
            if (valid) return destination
            Files.deleteIfExists(receipt)
            if (Files.exists(destination, NOFOLLOW_LINKS)) remove(destination)
            val staging = Files.createTempDirectory(root, "$key-")
            val tree = staging.resolve("tree")
            try {
                if (seed?.invoke(tree, receipt) == true || runtimeOnly && materialize?.invoke(bundle, included, tree, receipt, checkActive) == true) {
                    require(TreeVerification().valid(tree, included, receipt, checkActive, exactExecutables = true)) { "local distribution does not match approved input files" }
                    checkActive(); Files.move(tree, destination, ATOMIC_MOVE)
                    trace.cache("input", "miss", "verified-local", verificationMs = (System.nanoTime() - verificationAt) / 1_000_000,
                        waitingMs = waitingMs, bytes = included.sumOf { it.bytes }, files = included.size,
                checkedFiles = validation.stats.checkedFiles, hashedFiles = validation.stats.hashedFiles, hashedBytes = validation.stats.hashedBytes)
                    println("worker input cache: materialized verified local files=${included.size}")
                    return destination
                }
                Files.createDirectory(tree)
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
        }
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
