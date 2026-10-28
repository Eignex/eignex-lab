package com.eignex.lab

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.atomic.AtomicLong

/** A bounded, disposable index into existing job artifacts. It retains no additional artifact data:
 * job retention removes the original bytes, and a stale hint simply falls back to an upload. */
internal class ArtifactReuseIndex(private val artifacts: Path, private val maxEntries: Int = 4096,
                                  private val ttlMs: Long = 7L * 24 * 60 * 60 * 1000) {
    private val root = artifacts.resolve(".reuse")
    private val maintained = AtomicLong(0)
    fun find(sha: String): Path? {
        require(SHA256.matches(sha))
        val hint = root.resolve(sha)
        if (!Files.isRegularFile(hint, NOFOLLOW_LINKS)) return null
        if (now() - Files.getLastModifiedTime(hint).toMillis() > ttlMs) { Files.deleteIfExists(hint); return null }
        val job = runCatching { Files.readString(hint).toLong().takeIf { it > 0 } }.getOrNull() ?: return null
        return artifacts.resolve(job.toString()).resolve(sha).takeIf { Files.isRegularFile(it, NOFOLLOW_LINKS) }
    }
    fun forget(sha: String) { require(SHA256.matches(sha)); Files.deleteIfExists(root.resolve(sha)) }
    fun remember(job: Long, sha: String) {
        require(job > 0 && SHA256.matches(sha))
        Files.createDirectories(root)
        val temp = Files.createTempFile(root, ".hint-", ".tmp")
        try {
            Files.writeString(temp, job.toString())
            Files.move(temp, root.resolve(sha), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
        val at = now(); val last = maintained.get()
        if (at - last >= 60_000 && maintained.compareAndSet(last, at)) maintain(at)
    }
    internal fun maintain(at: Long = now()) {
        ProcessFilePin.tryLock(root.resolve("maintenance.lock"))?.use {
            val hints = Files.list(root).use { paths -> paths.filter { SHA256.matches(it.fileName.toString()) && Files.isRegularFile(it, NOFOLLOW_LINKS) }
                .map { it to Files.getLastModifiedTime(it).toMillis() }.toList().sortedByDescending { it.second } }
            for ((i, hint) in hints.withIndex()) if (i >= maxEntries || at - hint.second > ttlMs) Files.deleteIfExists(hint.first)
            Files.list(root).use { paths -> paths.filter { it.fileName.toString().startsWith(".hint-") && at - Files.getLastModifiedTime(it).toMillis() > ttlMs }
                .forEach { Files.deleteIfExists(it) } }
        }
    }
}
