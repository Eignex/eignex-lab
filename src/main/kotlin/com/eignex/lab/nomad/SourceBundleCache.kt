package com.eignex.lab.nomad

import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Disposable metadata into job-owned source blobs. Trust must be checked before lookup, just as before export.
 * Only the approved Git bundle is reusable; current build settings and platform are supplied by the caller. */
internal class SourceBundleCache(private val root: Path, private val artifacts: ArtifactStore,
                                 private val maxEntries: Int = 256, private val ttlMs: Long = 7L * 24 * 60 * 60 * 1000) {
    @Serializable private data class Entry(val bundle: WorkerBundle, val bytes: Long, val checksum: String)
    private fun checksum(bundle: WorkerBundle, bytes: Long) = ExecutionStore.digest(Json.encodeToString(bundle) + "\n$bytes")
    private fun key(repository: String, commit: String): String {
        Repositories.requireAllowed(repository); require(CommitSha.isValid(commit))
        return ExecutionStore.digest("approved-git-bundle-v1\n$repository\n$commit")
    }
    private fun usable(bundle: WorkerBundle, repository: String, commit: String) =
        bundle.repository == repository && bundle.commit == commit && bundle.tree == null &&
            bundle.build?.gitBundle == "source.git.bundle" && bundle.files.singleOrNull()?.path == "source.git.bundle" && bundle.buildOptions.isEmpty()

    fun reuse(job: Long, repository: String, commit: String, os: String, arch: String, toolchain: String, build: WorkerBuild,
              authorize: () -> Unit,
              recover: () -> Sequence<Pair<Long, WorkerBundle>> = { emptySequence() }): WorkerBundle? {
        authorize()
        val hint = root.resolve(key(repository, commit))
        val cached = runCatching {
            require(Files.isRegularFile(hint, NOFOLLOW_LINKS) && Files.size(hint) <= 65_536)
            require(now() - Files.getLastModifiedTime(hint).toMillis() <= ttlMs)
            Json.decodeFromString<Entry>(Files.readString(hint)).also {
                require(it.bytes > 0 && usable(it.bundle, repository, commit) && it.checksum == checksum(it.bundle, it.bytes))
            }
        }.getOrNull()
        fun receive(bundle: WorkerBundle, bytes: Long?): WorkerBundle? {
            val received = artifacts.reuse(job, bundle.sha256) ?: return null
            if (bytes != null && received != bytes) return null
            return bundle.copy(os = os, arch = arch, toolchain = toolchain, build = build.copy(gitBundle = "source.git.bundle"))
        }
        if (cached != null) receive(cached.bundle, cached.bytes)?.let { return it }
        runCatching { Files.deleteIfExists(hint) }
        // A restart/upgrade need not export again when another active preparation already retained this commit.
        for ((owner, bundle) in recover()) {
            if (!usable(bundle, repository, commit)) continue
            artifacts.remember(owner, bundle.sha256)
            receive(bundle, null)?.let { result -> remember(job, result, artifacts.size(job, result.sha256)); return result }
        }
        return null
    }

    fun remember(job: Long, bundle: WorkerBundle, bytes: Long) {
        require(bytes > 0 && usable(bundle, bundle.repository, bundle.commit))
        artifacts.remember(job, bundle.sha256)
        // Hints are not authoritative. A crash or a full metadata directory simply makes the next export cold.
        runCatching {
            Files.createDirectories(root)
            val temporary = Files.createTempFile(root, ".source-", ".tmp")
            try {
                Files.writeString(temporary, Json.encodeToString(Entry(bundle, bytes, checksum(bundle, bytes))))
                Files.move(temporary, root.resolve(key(bundle.repository, bundle.commit)), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
            ProcessFilePin.tryLock(root.resolve("maintenance.lock"))?.use {
                val hints = Files.list(root).use { paths -> paths.filter { it.fileName.toString().matches(SHA256) && Files.isRegularFile(it, NOFOLLOW_LINKS) }
                    .map { it to Files.getLastModifiedTime(it).toMillis() }.toList().sortedByDescending { it.second } }
                for ((index, hint) in hints.withIndex()) if (index >= maxEntries || now() - hint.second > ttlMs) Files.deleteIfExists(hint.first)
                Files.list(root).use { paths -> paths.filter { it.fileName.toString().startsWith(".source-") &&
                    now() - Files.getLastModifiedTime(it).toMillis() > ttlMs }.forEach { Files.deleteIfExists(it) } }
            }
        }.onFailure { println("source bundle hint unavailable: ${it.javaClass.simpleName}") }
    }
}
