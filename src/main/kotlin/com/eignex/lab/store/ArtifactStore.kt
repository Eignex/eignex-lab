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
    fun verify(job: Long, sha: String) { open(job, sha).use { } }
    /** Optional reuse of immutable, checksum-verified preparation archives across jobs. */
    fun reuse(job: Long, sha: String): Long? = null
    fun remember(job: Long, sha: String) { }
    /** Whether [sha] is held only by the regional build store, which readers here would have to fetch it from. */
    fun regional(job: Long, sha: String): Boolean = false
    fun putAll(job: Long, blobs: Map<String, ByteArray>) {
        for ((sha, bytes) in blobs) put(job, sha, bytes.size.toLong(), 0, bytes.inputStream())
    }
}

/** Job-scoped CAS. Interrupted uploads resume at a confirmed offset; corrupt content is never published. */
class LocalArtifactStore(private val root: Path, private val maxBytes: Long = 32L * 1024 * 1024 * 1024,
                         private val writable: () -> Boolean = { true }) : ArtifactStore {
    private val reuseIndex = ArtifactReuseIndex(root)
    private fun directory(job: Long): Path { require(job > 0); return root.resolve(job.toString()) }
    private fun path(job: Long, sha: String): Path { require(SHA256.matches(sha)); return directory(job).resolve(sha) }
    override fun readFile(job: Long, sha: String): Path = path(job, sha).also { require(Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS)) }
    override fun file(job: Long, sha: String): Path = readFile(job, sha).also {
        require(hashFile(it) == sha) { "artifact missing or corrupt" }
    }
    override fun offset(job: Long, sha: String): Long = path(job, sha).let { full ->
        if (Files.isRegularFile(full)) Files.size(full) else full.resolveSibling("$sha.partial").let { if (Files.exists(it)) Files.size(it) else 0 }
    }
    override fun reuse(job: Long, sha: String): Long? {
        val full = path(job, sha)
        if (Files.isRegularFile(full)) return Files.size(full)
        check(writable()) { "artifact storage temporarily unavailable" }
        Files.createDirectories(full.parent)
        synchronized(fileLockFor(full)) {
            FileChannel.open(full.resolveSibling("$sha.lock"), CREATE, WRITE).use { channel -> channel.lock().use {
                if (Files.isRegularFile(full)) return Files.size(full)
                val source = try { reuseIndex.find(sha) } catch (_: java.io.IOException) { null } ?: return null
                try {
                    // Index entries are hints, never proof. Check the immutable bytes before sharing their inode.
                    if (Files.size(source) > maxBytes) return null
                    if (hashFile(source) != sha) { reuseIndex.forget(sha); return null }
                    Files.createLink(full, source)
                } catch (_: java.io.IOException) { return null } // Deleted source or unsupported hard links: ordinary upload.
                Files.deleteIfExists(full.resolveSibling("$sha.partial"))
                FileChannel.open(full.parent, READ).use { it.force(true) }
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
        for ((sha, bytes) in blobs) write(job, sha, bytes.size.toLong(), 0, bytes.inputStream())
    }
    private fun write(job: Long, sha: String, bytes: Long, offset: Long, input: InputStream): Long {
        require(bytes in 0..maxBytes && offset in 0..bytes) { "artifact size or offset limit exceeded" }
        val full = path(job, sha); Files.createDirectories(full.parent)
        synchronized(fileLockFor(full)) {
        FileChannel.open(full.resolveSibling("$sha.lock"), CREATE, WRITE).use { channel -> channel.lock().use {
            if (Files.exists(full)) { require(Files.size(file(job, sha)) == bytes); return bytes }
            val partial = full.resolveSibling("$sha.partial")
            val current = if (Files.exists(partial)) Files.size(partial) else 0
            require(offset == current) { "upload offset changed" }
            // A complete upload already passes every byte through this process. Verify that stream before
            // publication instead of reading the newly written external file a second time. Resumed uploads
            // still hash the complete on-disk prefix and suffix, including state from an earlier process.
            val streamed = if (current == 0L) java.security.MessageDigest.getInstance("SHA-256") else null
            try {
                Files.newOutputStream(partial, CREATE, APPEND).use { output ->
                    val buffer = ByteArray(64 * 1024); var total = current
                    while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= bytes) { "artifact exceeds declared size" }; output.write(buffer, 0, n); streamed?.update(buffer, 0, n) }
                }
            } catch (e: IllegalArgumentException) { Files.deleteIfExists(partial); throw e }
            if (Files.size(partial) == bytes) {
                if ((streamed?.digest()?.hex() ?: hashFile(partial)) != sha) { Files.delete(partial); error("artifact checksum mismatch") }
                FileChannel.open(partial, WRITE).use { it.force(true) }
                Files.move(partial, full, ATOMIC_MOVE)
                FileChannel.open(full.parent, READ).use { it.force(true) }
            }
            return offset(job, sha)
        } }
        }
    }
}
