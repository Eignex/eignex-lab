package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*

interface ArtifactStore {
    fun put(job: Long, sha: String, bytes: Long, offset: Long, input: InputStream): Long
    fun offset(job: Long, sha: String): Long
    fun file(job: Long, sha: String): Path
    fun readFile(job: Long, sha: String): Path = file(job, sha)
    fun open(job: Long, sha: String, verify: Boolean = true): InputStream = Files.newInputStream(if (verify) file(job, sha) else readFile(job, sha))
    fun size(job: Long, sha: String): Long = Files.size(readFile(job, sha))
    /** Best-effort listing metadata, never a payload verification or admission authority. */
    fun sizes(job: Long, hashes: Set<String>): Map<String, Long> = hashes.mapNotNull { sha ->
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        try { sha to size(job, sha) }
        catch (failure: InterruptedException) { throw failure }
        catch (_: Exception) { null }
    }.toMap()
    fun verify(job: Long, sha: String) { open(job, sha).use { } }
    /** Optional reuse of immutable, checksum-verified preparation archives across jobs. */
    fun reuse(job: Long, sha: String): Long? = null
    fun remember(job: Long, sha: String) { }
    /** Whether [sha] is held only by the regional build store, which readers here would have to fetch it from. */
    fun regional(job: Long, sha: String): Boolean = false
    /**
     * The size of result blob [sha] in the regional result store ([com.eignex.lab.execution.ResultBlobs]), null when it is
     * not there. Throws [RegionalResultRefused] when there is no such store, and an IOException when it cannot be reached.
     */
    fun regionalResult(sha: String): Long? = throw RegionalResultRefused("no regional result store")
    /** Return only after all writes in [publish] are durable; providers may share one waited barrier. */
    fun publishBatch(job: Long, publish: () -> Unit) { publish() }
    /** Complete bounded sources; the default retains ordinary confirmed-offset resumable semantics.
     * A provider may replace only unpublished partial state, under its normal per-SHA writer lock.
     * Existing complete content still needs verification and the enclosing publication barrier.
     * Stores wrapping writes must apply the same write checks to this method. */
    fun putComplete(job: Long, blobs: List<ResultBlob>, source: (ResultBlob) -> InputStream) {
        validateResultBlobs(blobs)
        require(blobs.sumOf { it.bytes } <= INLINE_BYTES)
        for (blob in blobs) {
            val confirmed = offset(job, blob.sha)
            require(confirmed in 0..blob.bytes) { "external artifact size changed" }
            val written = if (confirmed == blob.bytes) put(job, blob.sha, blob.bytes, confirmed, InputStream.nullInputStream())
            else source(blob).use { input ->
                input.skipNBytes(confirmed)
                put(job, blob.sha, blob.bytes, confirmed, input)
            }
            check(written == blob.bytes) { "incomplete artifact source" }
        }
    }
    fun putAll(job: Long, blobs: Map<String, ByteArray>) {
        for ((sha, bytes) in blobs) put(job, sha, bytes.size.toLong(), 0, bytes.inputStream())
    }
}

/** Job-scoped CAS. Interrupted uploads resume at a confirmed offset; corrupt content is never published. */
class LocalArtifactStore(private val root: Path, private val maxBytes: Long = 32L * 1024 * 1024 * 1024,
                         private val writable: () -> Boolean = { true }) : ArtifactStore {
    private val reuseIndex = ArtifactReuseIndex(root)
    private val publishingJob = ThreadLocal<Long>()
    private val publishingProofs = ThreadLocal<LinkedHashMap<Pair<Path, String>, TreeVerification.FileMetadata>>()
    private var verification = ArtifactFileVerification()
    internal constructor(root: Path, verification: ArtifactFileVerification) : this(root) { this.verification = verification }
    private fun directory(job: Long): Path { require(job > 0); return root.resolve(job.toString()) }
    private fun path(job: Long, sha: String): Path { require(SHA256.matches(sha)); return directory(job).resolve(sha) }
    override fun readFile(job: Long, sha: String): Path = path(job, sha).also { require(Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS)) }
    override fun file(job: Long, sha: String): Path = readFile(job, sha).also {
        verification.verify(it, sha)
    }
    override fun sizes(job: Long, hashes: Set<String>): Map<String, Long> = sizes(job, hashes, MacDirectoryEntries::read)

    internal fun sizes(job: Long, hashes: Set<String>, entries: (Path, () -> Unit) -> List<MacDirectoryEntries.Entry>?): Map<String, Long> {
        if (hashes.isEmpty()) return emptyMap()
        val bulk = entries(directory(job)) { if (Thread.currentThread().isInterrupted) throw InterruptedException() }
            ?: return hashes.mapNotNull { sha ->
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                runCatching { sha to Files.size(readFile(job, sha)) }.getOrNull()
            }.toMap()
        return bulk.asSequence().filter { it.regular && it.name in hashes }.associate { it.name to it.bytes }
    }
    override fun offset(job: Long, sha: String): Long = path(job, sha).let { full ->
        if (Files.isRegularFile(full)) Files.size(full) else full.resolveSibling("$sha.partial").let { if (Files.exists(it)) Files.size(it) else 0 }
    }
    override fun publishBatch(job: Long, publish: () -> Unit) {
        if (!MacVolumeSync.supported) { publish(); return }
        publishBatch(job, { MacVolumeSync.flush(it) {} }, publish)
    }

    /** Injection keeps failure/recovery tests independent of the host filesystem's barrier implementation. */
    internal fun publishBatch(job: Long, flush: (Path) -> Unit, publish: () -> Unit) {
        val target = directory(job)
        check(writable()) { "artifact storage temporarily unavailable" }
        val prior = publishingJob.get()
        val priorProofs = publishingProofs.get()
        val proofs = LinkedHashMap<Pair<Path, String>, TreeVerification.FileMetadata>()
        publishingJob.set(job)
        publishingProofs.set(proofs)
        try {
            publish()
            // Both data and renamed directory entries must reach the volume before journal reclamation.
            if (Files.isDirectory(target)) flush(target)
            proofs.forEach { (key, stamp) -> verification.published(key.first, key.second, stamp) }
        } finally {
            if (prior == null) publishingJob.remove() else publishingJob.set(prior)
            if (priorProofs == null) publishingProofs.remove() else publishingProofs.set(priorProofs)
        }
    }

    private fun flushPublished(full: Path) {
        FileChannel.open(full, READ).use { it.force(true) }
        FileChannel.open(full.parent, READ).use { it.force(true) }
    }
    override fun reuse(job: Long, sha: String): Long? {
        val full = path(job, sha)
        if (Files.isRegularFile(full)) { flushPublished(full); return Files.size(full) }
        check(writable()) { "artifact storage temporarily unavailable" }
        Files.createDirectories(full.parent)
        synchronized(fileLockFor(full)) {
            FileChannel.open(full.resolveSibling("$sha.lock"), CREATE, WRITE).use { channel -> channel.lock().use {
                if (Files.isRegularFile(full)) { flushPublished(full); return Files.size(full) }
                val source = try { reuseIndex.find(sha) } catch (_: java.io.IOException) { null } ?: return null
                try {
                    // Index entries are hints, never proof. Check the immutable bytes before sharing their inode.
                    if (Files.size(source) > maxBytes) return null
                    if (hashFile(source) != sha) { reuseIndex.forget(sha); return null }
                    Files.createLink(full, source)
                } catch (_: java.io.IOException) { return null } // Deleted source or unsupported hard links: ordinary upload.
                Files.deleteIfExists(full.resolveSibling("$sha.partial"))
                // Another publisher may still be within its volume batch; a shared inode must be durable now.
                flushPublished(full)
                remember(job, sha)
                return Files.size(full)
            } }
        }
    }
    override fun remember(job: Long, sha: String) {
        if (Files.isRegularFile(path(job, sha))) {
            try { reuseIndex.remember(job, sha) } catch (_: java.io.IOException) { /* A disposable hint must not reject accepted bytes. */ }
        }
    }
    override fun put(job: Long, sha: String, bytes: Long, offset: Long, input: InputStream): Long {
        check(writable()) { "artifact storage temporarily unavailable" }
        return write(job, sha, bytes, offset, input)
    }
    override fun putAll(job: Long, blobs: Map<String, ByteArray>) {
        // One fresh mount/space check covers this bounded small-output operation, as with a single upload chunk.
        require(blobs.size <= INLINE_FILES && blobs.values.sumOf { it.size.toLong() } <= INLINE_BYTES)
        check(writable()) { "artifact storage temporarily unavailable" }
        if (blobs.isEmpty()) return
        for ((sha, bytes) in blobs) {
            require(SHA256.matches(sha))
            require(bytes.size.toLong() in 0..maxBytes) { "artifact size or offset limit exceeded" }
        }
        // A batch shares one job directory. Do not repeatedly walk its ancestors, or recreate it if a
        // removal/unmount occurs after this check: a failed batch retains its journal-owned inputs.
        Files.createDirectories(directory(job))
        for ((sha, bytes) in blobs) write(job, sha, bytes.size.toLong(), 0, bytes.inputStream(), createParent = false)
    }
    override fun putComplete(job: Long, blobs: List<ResultBlob>, source: (ResultBlob) -> InputStream) {
        validateResultBlobs(blobs)
        require(blobs.sumOf { it.bytes } <= INLINE_BYTES)
        for (blob in blobs) require(blob.bytes <= maxBytes) { "artifact size or offset limit exceeded" }
        check(writable()) { "artifact storage temporarily unavailable" }
        // This is the same bounded parent/volume guard as putAll. Never recreate a removed batch parent.
        Files.createDirectories(directory(job))
        for (blob in blobs) {
            val written = write(job, blob.sha, blob.bytes, 0, InputStream.nullInputStream(), createParent = false) { source(blob) }
            check(written == blob.bytes) { "incomplete artifact source" }
        }
    }
    private fun write(job: Long, sha: String, bytes: Long, offset: Long, input: InputStream,
                      createParent: Boolean = true, completeSource: (() -> InputStream)? = null): Long {
        require(bytes in 0..maxBytes && offset in 0..bytes) { "artifact size or offset limit exceeded" }
        val full = path(job, sha)
        if (createParent) Files.createDirectories(full.parent)
        synchronized(fileLockFor(full)) {
        FileChannel.open(full.resolveSibling("$sha.lock"), CREATE, WRITE).use { channel -> channel.lock().use {
            val force = publishingJob.get() != job
            if (Files.exists(full)) {
                require(Files.size(file(job, sha)) == bytes)
                // A concurrent batch can expose complete bytes before its final barrier.
                if (force) flushPublished(full)
                return bytes
            }
            val partial = full.resolveSibling("$sha.partial")
            val current = if (completeSource != null) 0 else if (Files.exists(partial)) Files.size(partial) else 0
            require(offset == current) { "upload offset changed" }
            // Open only after the locked complete-file check. A failed source leaves prior partial state intact.
            val contents = completeSource?.invoke() ?: input
            try {
            // A complete upload already passes every byte through this process. Verify that stream before
            // publication instead of reading the newly written external file a second time. Resumed uploads
            // still hash the complete on-disk prefix and suffix, including state from an earlier process.
            val streamed = if (current == 0L) java.security.MessageDigest.getInstance("SHA-256") else null
            try {
                val output = if (completeSource == null) Files.newOutputStream(partial, CREATE, APPEND)
                    else Files.newOutputStream(partial, CREATE, TRUNCATE_EXISTING, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                output.use { output ->
                    val buffer = ByteArray(64 * 1024); var total = current
                    while (true) { val n = contents.read(buffer); if (n < 0) break; total += n; require(total <= bytes) { "artifact exceeds declared size" }; output.write(buffer, 0, n); streamed?.update(buffer, 0, n) }
                }
            } catch (e: IllegalArgumentException) { Files.deleteIfExists(partial); throw e }
            if (Files.size(partial) == bytes) {
                // This private partial has one trusted writer under the existing per-SHA OS lock. A fresh
                // checksum covers bytes passed through that writer; a resumed checksum also rereads its prefix.
                // Metadata is a hint only: it cannot replace either checksum or the publication barriers.
                val before = if (bytes >= ArtifactFileVerification.MIN_PUBLICATION_BYTES) verification.capture(partial) else null
                if ((streamed?.digest()?.hex() ?: hashFile(partial)) != sha) { Files.delete(partial); error("artifact checksum mismatch") }
                val verified = before?.takeIf { verification.capture(partial) == it }
                if (force) FileChannel.open(partial, WRITE).use { it.force(true) }
                Files.move(partial, full, ATOMIC_MOVE)
                if (force) FileChannel.open(full.parent, READ).use { it.force(true) }
                val published = verification.renamed(full, verified)
                if (force) verification.published(full, sha, published)
                else if (published != null) publishingProofs.get()?.let { proofs ->
                    proofs[full to sha] = published
                    // Hints remain bounded even for a publisher containing many tiny blobs.
                    while (proofs.size > 1024) proofs.remove(proofs.keys.first())
                }
                // The verified complete write is already published; do not stat it again to rediscover its size.
                return bytes
            }
            return offset(job, sha)
            } finally { if (completeSource != null) contents.close() }
        } }
        }
    }
}
