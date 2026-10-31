package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Small results acknowledge an internal durable copy; external publication proceeds independently. */
class ResultJournal(private val root: Path, private val external: ArtifactStore,
                    private val maxBytes: Long = 128L * 1024 * 1024,
                    private val writable: () -> Boolean = { true }, start: Boolean = true,
                    private val syncDirectory: (Path) -> Unit = { FileChannel.open(it, READ).use { channel -> channel.force(true) } }) : ArtifactStore, AutoCloseable {
    @Serializable private data class Manifest(val job: Long, val blobs: List<ResultBlob>)
    private data class Batch(val path: Path, val manifest: Manifest, val charge: Long,
                             var readers: Int = 0, var busy: Boolean = false, var flushed: Boolean = false, var retryAt: Long = 0,
                             var durable: Boolean = true, val createdAt: Long = now(), var error: String? = null)
    @Serializable data class Status(val batches: Int, val bytes: Long, val limit: Long, val lastError: String?,
                                   val oldestAgeMs: Long = 0, val drainErrors: Long = 0, val lastDrainAt: Long? = null)
    private val monitor = Any()
    private val batches = linkedMapOf<Path, Batch>()
    private val index = mutableMapOf<Pair<Long, String>, Batch>()
    private var used = 8192L // Directory and owner-lock allowance, plus rounded blocks charged for each pack.
    private var receiving = 0
    @Volatile private var closed = false
    @Volatile private var lastError: String? = null
    @Volatile private var loggedError: String? = null
    private var drainErrors = 0L
    private var lastDrainAt: Long? = null
    private val channel: FileChannel
    private val ownership: java.nio.channels.FileLock
    private val workers = Executors.newScheduledThreadPool(2) { action -> Thread(action, "result-journal-drain").apply { isDaemon = true } }

    init {
        require(maxBytes >= 8192)
        require(Files.isDirectory(root.parent)) { "result journal parent must exist" }
        Files.createDirectories(root)
        syncDirectory(root.parent)
        channel = FileChannel.open(root.resolve("owner.lock"), CREATE, WRITE)
        ownership = try { checkNotNull(channel.tryLock()) { "result journal already owned" } }
            catch (failure: Exception) { channel.close(); throw failure }
        try {
            Files.list(root).use { paths -> paths.sorted().forEach { path ->
                val name = path.fileName.toString()
                when {
                    name == "owner.lock" -> Unit
                    name.startsWith(".pending-") -> Files.delete(path) // Never acknowledged before atomic publication.
                    name.matches(Regex("[1-9][0-9]*-[a-f0-9-]+\\.zip")) -> {
                        require(Files.isRegularFile(path, NOFOLLOW_LINKS))
                        val manifest = manifest(path)
                        require(name.startsWith("${manifest.job}-"))
                        val batch = Batch(path, manifest, charge(Files.size(path), manifest.blobs.size), createdAt = Files.getLastModifiedTime(path).toMillis())
                        for (blob in manifest.blobs) read(batch, blob.sha)
                        add(batch)
                    }
                    else -> error("unexpected result journal entry")
                }
            } }
            check(used <= maxBytes) { "recovered result journal exceeds its configured cap" }
            syncDirectory(root)
        } catch (failure: Exception) { ownership.release(); channel.close(); workers.shutdownNow(); throw failure }
        if (start) repeat(2) {
            workers.scheduleWithFixedDelay({
                try { drainOne() }
                catch (failure: Exception) {
                    val detail = failure.javaClass.simpleName
                    if (loggedError != detail) System.err.println("result journal drain: $detail; pending data retained")
                    loggedError = detail; lastError = detail
                }
            }, 0, 250, TimeUnit.MILLISECONDS)
        }
    }

    fun status(): Status = synchronized(monitor) {
        Status(batches.size, used, maxBytes, lastError,
            batches.values.minOfOrNull { it.createdAt }?.let { (now() - it).coerceAtLeast(0) } ?: 0, drainErrors, lastDrainAt)
    }
    private fun charge(bytes: Long, blobs: Int): Long = ((bytes + 4095) / 4096 + 1) * 4096 + blobs * 512L
    private fun reservation(bytes: Long, blobs: Int): Long = charge(bytes + bytes / 16384 * 8 + blobs * 512L + 4096, blobs)
    private fun add(batch: Batch) {
        batches[batch.path] = batch; used += batch.charge
        for (blob in batch.manifest.blobs) index.putIfAbsent(batch.manifest.job to blob.sha, batch)
    }
    private fun check(job: Long, sha: String) { require(job > 0 && SHA256.matches(sha)) }

    override fun putAll(job: Long, blobs: Map<String, ByteArray>) {
        require(job > 0 && blobs.size <= INLINE_FILES && blobs.values.sumOf { it.size.toLong() } <= INLINE_BYTES)
        for ((sha, bytes) in blobs) {
            check(job, sha); require(MessageDigest.getInstance("SHA-256").digest(bytes).hex() == sha) { "artifact checksum mismatch" }
        }
        synchronized(monitor) {
            check(!closed); check(writable()) { "result journal storage unavailable" }
            val unsynced = blobs.keys.mapNotNull { index[job to it] }.filter { !it.durable }.distinct()
            if (unsynced.isNotEmpty()) {
                syncDirectory(root)
                unsynced.forEach { it.durable = true }
            }
            val pending = blobs.filterKeys { (job to it) !in index }
            if (pending.isEmpty()) return
            // Reserve the worst-case stored ZIP/manifest size before creating even a temporary file.
            val reservation = reservation(pending.values.sumOf { it.size.toLong() }, pending.size)
            check(reservation <= maxBytes - used) { "result journal full; pending data retained" }
            val temporary = Files.createTempFile(root, ".pending-", ".tmp")
            val destination = root.resolve("$job-${UUID.randomUUID()}.zip")
            var published = false
            try {
                val manifest = Manifest(job, pending.map { ResultBlob(it.key, it.value.size.toLong()) })
                ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
                    zip.setLevel(0)
                    zip.putNextEntry(ZipEntry("manifest.json").apply { time = 0 })
                    zip.write(Json.encodeToString(manifest).toByteArray()); zip.closeEntry()
                    for ((sha, bytes) in pending) {
                        zip.putNextEntry(ZipEntry(sha).apply { time = 0 }); zip.write(bytes); zip.closeEntry()
                    }
                }
                val actual = charge(Files.size(temporary), pending.size); check(actual <= reservation)
                FileChannel.open(temporary, WRITE).use { it.force(true) }
                Files.move(temporary, destination, ATOMIC_MOVE); published = true
                // Until the containing directory is synced, this batch cannot acknowledge acceptance.
                syncDirectory(root)
                add(Batch(destination, manifest, actual))
            } catch (failure: Exception) {
                // If publication reached rename, retain and account the durable candidate for retry/recovery.
                if (published && destination !in batches) {
                    val recovered = manifest(destination)
                    add(Batch(destination, recovered, charge(Files.size(destination), recovered.blobs.size), durable = false))
                }
                throw failure
            } finally { Files.deleteIfExists(temporary) }
        }
    }

    /** Reserve before receiving bytes; external I/O and slow senders never own the small-commit monitor. */
    fun putPacked(job: Long, blobs: List<ResultBlob>, input: InputStream) {
        require(job > 0); validateResultBlobs(blobs)
        val reservation = reservation(blobs.sumOf { it.bytes }, blobs.size)
        var duplicate = false
        synchronized(monitor) {
            check(!closed); check(writable()) { "result journal storage unavailable" }
            duplicate = blobs.all { index[job to it.sha] != null }
            if (duplicate) {
                if (blobs.any { index.getValue(job to it.sha).durable.not() }) {
                    syncDirectory(root); batches.values.forEach { it.durable = true }
                }
            } else {
                check(reservation <= maxBytes - used) { "result journal full; pending data retained" }
                used += reservation; receiving++
            }
        }
        if (duplicate) {
            for (blob in blobs) copyBlob(blob, input, java.io.OutputStream.nullOutputStream())
            require(input.read() == -1) { "trailing result data" }; return
        }
        var temporary: Path? = null
        var accounted = false
        try {
            temporary = Files.createTempFile(root, ".pending-", ".tmp")
            val manifest = Manifest(job, blobs)
            ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
                zip.setLevel(0)
                zip.putNextEntry(ZipEntry("manifest.json").apply { time = 0 })
                zip.write(Json.encodeToString(manifest).toByteArray()); zip.closeEntry()
                for (blob in blobs) {
                    zip.putNextEntry(ZipEntry(blob.sha).apply { time = 0 })
                    copyBlob(blob, input, zip); zip.closeEntry()
                }
                require(input.read() == -1) { "trailing result data" }
            }
            val actual = charge(Files.size(temporary), blobs.size); check(actual <= reservation)
            FileChannel.open(temporary, WRITE).use { it.force(true) }
            synchronized(monitor) {
                check(!closed)
                val destination = root.resolve("$job-${UUID.randomUUID()}.zip")
                Files.move(temporary, destination, ATOMIC_MOVE)
                // Account even a failed publication barrier so retry/restart cannot lose the candidate.
                val batch = Batch(destination, manifest, actual, durable = false)
                used -= reservation; add(batch); accounted = true
                syncDirectory(root); batches.values.forEach { it.durable = true }
            }
        } finally {
            // On cleanup failure keep the reservation charged until startup removes the unacknowledged temp.
            try {
                if (temporary == null || Files.deleteIfExists(temporary) || !Files.exists(temporary)) {
                    if (!accounted) synchronized(monitor) { used -= reservation }
                }
            } finally { synchronized(monitor) { receiving-- } }
        }
    }

    private fun copyBlob(blob: ResultBlob, input: InputStream, output: java.io.OutputStream) {
        val digest = MessageDigest.getInstance("SHA-256"); var remaining = blob.bytes
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(count > 0) { "truncated result blob" }
            output.write(buffer, 0, count); digest.update(buffer, 0, count); remaining -= count
        }
        require(digest.digest().hex() == blob.sha) { "artifact checksum mismatch" }
    }

    override fun open(job: Long, sha: String, verify: Boolean): InputStream {
        check(job, sha)
        val batch = synchronized(monitor) { index[job to sha]?.also { it.readers++ } }
            ?: return external.open(job, sha, verify)
        try { return ByteArrayInputStream(read(batch, sha)) }
        finally { synchronized(monitor) { batch.readers--; cleanup(batch) } }
    }
    override fun size(job: Long, sha: String): Long {
        check(job, sha)
        return synchronized(monitor) { index[job to sha]?.manifest?.blobs?.single { it.sha == sha }?.bytes }
            ?: external.size(job, sha)
    }
    override fun verify(job: Long, sha: String) {
        check(job, sha)
        if (synchronized(monitor) { index.containsKey(job to sha) }) open(job, sha).use { } else external.verify(job, sha)
    }
    override fun regional(job: Long, sha: String): Boolean {
        check(job, sha)
        return !synchronized(monitor) { index.containsKey(job to sha) } && external.regional(job, sha)
    }
    // Only large preparation archives opt in; the bounded result journal remains unchanged.
    override fun reuse(job: Long, sha: String): Long? { check(job, sha); return external.reuse(job, sha) }
    override fun remember(job: Long, sha: String) { check(job, sha); external.remember(job, sha) }
    override fun offset(job: Long, sha: String): Long {
        check(job, sha)
        return synchronized(monitor) { index[job to sha]?.manifest?.blobs?.single { it.sha == sha }?.bytes }
            ?: external.offset(job, sha)
    }
    override fun put(job: Long, sha: String, bytes: Long, offset: Long, input: InputStream): Long {
        check(job, sha)
        val pending = synchronized(monitor) { index.containsKey(job to sha) }
        // Preparation metadata uses the same durable bounded journal as small solve results.
        // Partial/chunked transfers retain the resumable external-store path.
        if (!pending && offset == 0L && bytes in 0..65_536 && external.offset(job, sha) == 0L) {
            val payload = input.readNBytes(bytes.toInt() + 1)
            require(payload.size.toLong() <= bytes) { "artifact exceeds declared size" }
            if (payload.size.toLong() == bytes) {
                putAll(job, mapOf(sha to payload)); return bytes
            }
            return external.put(job, sha, bytes, offset, ByteArrayInputStream(payload))
        }
        if (!pending) return external.put(job, sha, bytes, offset, input)
        require(offset in 0..bytes && size(job, sha) == bytes); verify(job, sha); return bytes
    }
    // Compatibility for callers requiring stable filesystem paths: stream consumers never wait for this drain.
    override fun file(job: Long, sha: String): Path {
        check(job, sha)
        while (true) {
            val batch = synchronized(monitor) {
                check(!closed) { "result journal closed" }
                index[job to sha]?.also {
                    if (!it.durable) { syncDirectory(root); it.durable = true }
                }
            } ?: return external.file(job, sha)
            if (!drain(batch)) Thread.sleep(10)
        }
    }
    override fun readFile(job: Long, sha: String): Path = file(job, sha)

    internal fun drainOne(): Boolean {
        val batch = synchronized(monitor) {
            batches.values.filter { it.flushed }.toList().forEach(::cleanup)
            batches.values.firstOrNull { it.durable && !it.busy && !it.flushed && it.retryAt <= now() }
        } ?: return false
        return drain(batch)
    }
    private fun drain(batch: Batch): Boolean {
        synchronized(monitor) {
            if (closed || !batch.durable || batch.busy || batch.flushed || batch.path !in batches) return false
            batch.busy = true; batch.readers++
        }
        try {
            val offsets = batch.manifest.blobs.associate { it.sha to external.offset(batch.manifest.job, it.sha) }
            if (batch.manifest.blobs.sumOf { it.bytes } <= INLINE_BYTES && offsets.values.all { it == 0L }) {
                external.putAll(batch.manifest.job, batch.manifest.blobs.associate { it.sha to read(batch, it.sha) })
            } else ZipFile(batch.path.toFile()).use { zip ->
                for (blob in batch.manifest.blobs) zip.getInputStream(requireNotNull(zip.getEntry(blob.sha))).use { input ->
                    val offset = offsets.getValue(blob.sha)
                    require(offset in 0..blob.bytes) { "external artifact size changed" }
                    input.skipNBytes(offset)
                    external.put(batch.manifest.job, blob.sha, blob.bytes, offset, input)
                }
            }
            synchronized(monitor) {
                batch.flushed = true; batch.error = null; lastDrainAt = now()
                if (batches.values.none { it.error != null }) { lastError = null; loggedError = null }
                for (blob in batch.manifest.blobs) index.remove(batch.manifest.job to blob.sha, batch)
            }
            return true
        } catch (failure: Exception) {
            synchronized(monitor) {
                batch.retryAt = now() + 5000; batch.error = failure.javaClass.simpleName
                lastError = batch.error; drainErrors++
            }
            throw failure
        } finally { synchronized(monitor) { batch.busy = false; batch.readers--; cleanup(batch) } }
    }
    private fun cleanup(batch: Batch) {
        if (!batch.flushed || batch.readers != 0 || batch.path !in batches) return
        try {
            Files.deleteIfExists(batch.path)
            syncDirectory(root)
        } catch (failure: Exception) {
            batch.error = failure.javaClass.simpleName; lastError = batch.error; drainErrors++
            throw failure
        }
        batches.remove(batch.path); used -= batch.charge
        if (batches.values.none { it.error != null }) { lastError = null; loggedError = null }
    }
    private fun read(batch: Batch, sha: String): ByteArray = ZipFile(batch.path.toFile()).use { zip ->
        val blob = batch.manifest.blobs.single { it.sha == sha }
        val entry = requireNotNull(zip.getEntry(sha)); require(entry.size == blob.bytes)
        val bytes = zip.getInputStream(entry).use { it.readNBytes(PACKED_BYTES + 1) }
        check(bytes.size.toLong() == blob.bytes && MessageDigest.getInstance("SHA-256").digest(bytes).hex() == sha) { "result journal checksum mismatch" }
        bytes
    }
    private fun manifest(path: Path): Manifest = ZipFile(path.toFile()).use { zip ->
        val entry = requireNotNull(zip.getEntry("manifest.json")); require(entry.size in 1..65536)
        val body = zip.getInputStream(entry).use { it.readNBytes(65537) }; require(body.size <= 65536)
        Json.decodeFromString<Manifest>(String(body)).also { manifest ->
            require(manifest.job > 0 && manifest.blobs.isNotEmpty() && manifest.blobs.size <= INLINE_FILES)
            require(manifest.blobs.map { it.sha }.distinct().size == manifest.blobs.size)
            validateResultBlobs(manifest.blobs)
            require(zip.entries().toList().map { it.name }.sorted() == (manifest.blobs.map { it.sha } + "manifest.json").sorted())
        }
    }
    override fun close() {
        closed = true; workers.shutdownNow()
        // Retain ownership if an uninterruptible external write is still active; pending data stays on disk.
        if (workers.awaitTermination(5, TimeUnit.SECONDS) && synchronized(monitor) { receiving == 0 && batches.values.none { it.busy || it.readers != 0 } }) {
            ownership.release(); channel.close()
        }
    }
}
