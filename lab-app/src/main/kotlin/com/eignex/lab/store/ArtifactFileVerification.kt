package com.eignex.lab.store

import com.eignex.lab.util.hashFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Semaphore

/** Disposable RAM metadata only. Unknown or changed bytes still pass the complete checksum before use. */
internal class ArtifactFileVerification(
    private val maxEntries: Int = 1024,
    maxFlights: Int = 32,
    private val hash: (Path) -> String = { hashFile(it) },
    private val observe: (Path) -> TreeVerification.FileMetadata? = {
        if (TreeVerification.reliableMetadata(it)) TreeVerification.fileMetadata(it) else null
    },
) {
    private data class Key(val path: Path, val sha: String)
    private val monitor = Any()
    private val proofs = LinkedHashMap<Key, TreeVerification.FileMetadata>(16, 0.75f, true)
    private val flights = mutableMapOf<Key, CompletableFuture<TreeVerification.FileMetadata?>>()
    private val slots = Semaphore(maxFlights, true)
    init { require(maxEntries in 1..1024 && maxFlights in 1..32) }

    internal val entryCount: Int get() = synchronized(monitor) { proofs.size }

    /** Metadata failures disable reuse; they never turn a failed checksum into a successful read. */
    fun capture(path: Path): TreeVerification.FileMetadata? = try { observe(path)?.takeIf { it.reusable } }
        catch (_: IOException) { null } catch (_: UnsupportedOperationException) { null } catch (_: SecurityException) { null }

    fun verify(path: Path, sha: String) {
        val key = Key(path.toAbsolutePath().normalize(), sha)
        while (true) {
            val current = capture(path)
            if (current == null) { checksum(path, sha); return }
            if (synchronized(monitor) { proofs[key] == current }) return
            var future = synchronized(monitor) { flights[key] }
            var leader = false
            if (future == null) {
                // Bound distinct in-flight keys too. Never wait, stat or hash while holding the map monitor.
                slots.acquire()
                synchronized(monitor) {
                    future = flights[key]
                    if (future == null) {
                        future = CompletableFuture<TreeVerification.FileMetadata?>()
                        flights[key] = future!!; leader = true
                    }
                }
                if (!leader) slots.release()
            }
            val flight = checkNotNull(future)
            if (!leader) {
                val verified = try { flight.get() } catch (failure: ExecutionException) { throw failure.cause ?: failure }
                if (verified != null && capture(path) == verified) return
                continue
            }
            try {
                val before = capture(path)
                if (before != null && synchronized(monitor) { proofs[key] == before }) {
                    flight.complete(before); return
                }
                synchronized(monitor) { proofs.remove(key) }
                checksum(path, sha)
                val after = capture(path)
                // A hash cannot establish which bytes a later read sees if its file changed during that hash.
                require(before == null || after == null || before == after) { "artifact changed during verification" }
                val verified = before?.takeIf { it == after }
                if (verified != null) remember(key, verified)
                flight.complete(verified)
                return
            } catch (failure: Throwable) {
                flight.completeExceptionally(failure); throw failure
            } finally {
                synchronized(monitor) { flights.remove(key) }
                slots.release()
            }
        }
    }

    private fun checksum(path: Path, sha: String) {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "artifact missing or corrupt" }
        require(hash(path) == sha) { "artifact missing or corrupt" }
    }

    /** Called only for an atomic rename of this writer's private, checksum-verified partial under its OS lock.
     * Rename may change ctime, so retain identity, size, full mtime and mode, then bind the new ctime explicitly.
     * This has the same trusted-copy boundary as TreeVerification: it is not proof for an arbitrary replacement. */
    fun renamed(path: Path, verifiedSource: TreeVerification.FileMetadata?): TreeVerification.FileMetadata? {
        if (verifiedSource == null) return null
        val target = capture(path) ?: return null
        return target.takeIf { it.copy(stamp = it.stamp.copy(changed = verifiedSource.stamp.changed)) == verifiedSource }
    }

    /** Publication barriers already succeeded. A metadata error or intervening change simply drops this hint. */
    fun published(path: Path, sha: String, verified: TreeVerification.FileMetadata?) {
        if (verified != null && capture(path) == verified) remember(Key(path.toAbsolutePath().normalize(), sha), verified)
    }

    private fun remember(key: Key, stamp: TreeVerification.FileMetadata) = synchronized(monitor) {
        proofs[key] = stamp
        while (proofs.size > maxEntries) proofs.remove(proofs.keys.first())
    }

    companion object {
        // Tiny journal blobs must not acquire several new metadata reads merely to avoid a tiny checksum.
        internal const val MIN_PUBLICATION_BYTES = 64L * 1024
    }
}
