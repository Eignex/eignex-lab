package com.eignex.lab

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
    @Serializable private data class Blob(val sha: String, val bytes: Long)
    @Serializable private data class Manifest(val job: Long, val blobs: List<Blob>)
    private data class Batch(val path: Path, val manifest: Manifest, val charge: Long,
                             var readers: Int = 0, var busy: Boolean = false, var flushed: Boolean = false, var retryAt: Long = 0,
                             var durable: Boolean = true)
    data class Status(val batches: Int, val bytes: Long, val limit: Long, val lastError: String?)
    private val monitor = Any()
    private val batches = linkedMapOf<Path, Batch>()
    private val index = mutableMapOf<Pair<Long, String>, Batch>()
    private var used = 8192L // Directory and owner-lock allowance, plus rounded blocks charged for each pack.
    @Volatile private var closed = false
    @Volatile private var lastError: String? = null
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
                        val batch = Batch(path, manifest, charge(Files.size(path), manifest.blobs.size))
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
                try { if (drainOne()) lastError = null }
                catch (failure: Exception) {
                    val detail = failure.javaClass.simpleName
                    if (lastError != detail) System.err.println("result journal drain: $detail; pending data retained")
                    lastError = detail
                }
            }, 0, 250, TimeUnit.MILLISECONDS)
        }
    }

    fun status(): Status = synchronized(monitor) { Status(batches.size, used, maxBytes, lastError) }
    private fun charge(bytes: Long, blobs: Int): Long = ((bytes + 4095) / 4096 + 1) * 4096 + blobs * 512L
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
            val reservation = charge(pending.values.sumOf { it.size.toLong() } + pending.size * 512L + 4096, pending.size)
            check(reservation <= maxBytes - used) { "result journal full; pending data retained" }
            val temporary = Files.createTempFile(root, ".pending-", ".tmp")
            val destination = root.resolve("$job-${UUID.randomUUID()}.zip")
            var published = false
            try {
                val manifest = Manifest(job, pending.map { Blob(it.key, it.value.size.toLong()) })
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
    override fun offset(job: Long, sha: String): Long {
        check(job, sha)
        return synchronized(monitor) { index[job to sha]?.manifest?.blobs?.single { it.sha == sha }?.bytes }
            ?: external.offset(job, sha)
    }
    override fun put(job: Long, sha: String, bytes: Long, offset: Long, input: InputStream): Long {
        check(job, sha)
        val pending = synchronized(monitor) { index.containsKey(job to sha) }
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
            val blobs = batch.manifest.blobs.associate { it.sha to read(batch, it.sha) }
            external.putAll(batch.manifest.job, blobs)
            synchronized(monitor) {
                batch.flushed = true
                for (blob in batch.manifest.blobs) index.remove(batch.manifest.job to blob.sha, batch)
            }
            return true
        } catch (failure: Exception) {
            synchronized(monitor) { batch.retryAt = now() + 5000 }
            throw failure
        } finally { synchronized(monitor) { batch.busy = false; batch.readers--; cleanup(batch) } }
    }
    private fun cleanup(batch: Batch) {
        if (!batch.flushed || batch.readers != 0 || batch.path !in batches) return
        Files.deleteIfExists(batch.path)
        syncDirectory(root)
        batches.remove(batch.path); used -= batch.charge
    }
    private fun read(batch: Batch, sha: String): ByteArray = ZipFile(batch.path.toFile()).use { zip ->
        val blob = batch.manifest.blobs.single { it.sha == sha }
        val entry = requireNotNull(zip.getEntry(sha)); require(entry.size == blob.bytes)
        val bytes = zip.getInputStream(entry).use { it.readNBytes(INLINE_BYTES + 1) }
        check(bytes.size.toLong() == blob.bytes && MessageDigest.getInstance("SHA-256").digest(bytes).hex() == sha) { "result journal checksum mismatch" }
        bytes
    }
    private fun manifest(path: Path): Manifest = ZipFile(path.toFile()).use { zip ->
        val entry = requireNotNull(zip.getEntry("manifest.json")); require(entry.size in 1..65536)
        val body = zip.getInputStream(entry).use { it.readNBytes(65537) }; require(body.size <= 65536)
        Json.decodeFromString<Manifest>(String(body)).also { manifest ->
            require(manifest.job > 0 && manifest.blobs.isNotEmpty() && manifest.blobs.size <= INLINE_FILES)
            require(manifest.blobs.map { it.sha }.distinct().size == manifest.blobs.size)
            require(manifest.blobs.all { SHA256.matches(it.sha) && it.bytes in 0..INLINE_BYTES.toLong() })
            require(manifest.blobs.sumOf { it.bytes } <= INLINE_BYTES)
            require(zip.entries().toList().map { it.name }.sorted() == (manifest.blobs.map { it.sha } + "manifest.json").sorted())
        }
    }
    override fun close() {
        closed = true; workers.shutdownNow()
        // Retain ownership if an uninterruptible external write is still active; pending data stays on disk.
        if (workers.awaitTermination(5, TimeUnit.SECONDS) && synchronized(monitor) { batches.values.none { it.busy || it.readers != 0 } }) {
            ownership.release(); channel.close()
        }
    }
}
