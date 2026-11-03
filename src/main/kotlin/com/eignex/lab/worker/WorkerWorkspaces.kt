package com.eignex.lab.worker

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.nomad.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID
import java.util.concurrent.Executor

/** Mutable trees belong to one serial worker session, never to the node-shared cache. */
class WorkerWorkspaces(private val root: Path, cleanupExecutor: Executor? = null,
                       private val detach: (Path, Path) -> Unit = { from, to -> Files.move(from, to, ATOMIC_MOVE) }) {
    private data class Ready(val tree: Path, val files: List<InputFile>, val receipt: Path)
    private val ready = mutableMapOf<String, Ready>()
    private val cleanup = WorkerCacheCleanup(root, cleanupExecutor)

    internal fun reuse(key: String, checkActive: () -> Unit): Path? {
        val entry = ready[key] ?: run {
            println("worker workspace reuse: outcome=0 reason=0 elapsedMs=0 checkedFiles=0 hashedFiles=0 hashedBytes=0 removedFiles=0")
            return null
        }
        val verification = TreeVerification(); val started = System.nanoTime()
        // Prune consumer-generated caches and verify sealed inputs in the same walk, without following links.
        val valid = verification.valid(entry.tree, entry.files, entry.receipt, checkActive,
            exactExecutables = true, removeUndeclared = true)
        val stats = verification.stats
        println("worker workspace reuse: outcome=${if (valid) 1 else 2} reason=${stats.rejection.code} elapsedMs=${(System.nanoTime()-started)/1_000_000} checkedFiles=${stats.checkedFiles} hashedFiles=${stats.hashedFiles} hashedBytes=${stats.hashedBytes} removedFiles=${stats.removedFiles}")
        if (valid) return entry.tree
        ready.remove(key)
        return null
    }

    internal fun create(key: String, checkActive: () -> Unit, copy: (Path) -> Unit): Path {
        require(SHA256.matches(key)); checkActive(); Files.createDirectories(root)
        val tree = root.resolve(key)
        ready.remove(key)
        val started = System.nanoTime()
        Files.deleteIfExists(root.resolve("$key.verified.json"))
        val receiptDeleted = System.nanoTime()
        var detached = false
        if (Files.exists(tree, NOFOLLOW_LINKS)) {
            // Retain at most one extra old tree in this pinned session. A failed or slow reaper cannot accumulate
            // generations; further replacements keep the ordinary synchronous deletion path until it catches up.
            val pending = Files.list(root).use { entries -> entries.anyMatch { it.fileName.toString().startsWith(".evict-") } }
            if (!pending) {
                checkActive()
                val garbage = root.resolve(".evict-${UUID.randomUUID()}")
                try { detach(tree, garbage); detached = true }
                catch (_: java.io.IOException) { /* Unsupported atomic moves retain ordinary deletion. */ }
            }
            if (detached) cleanup.request() else deleteTree(tree)
        }
        val deleted = System.nanoTime()
        checkActive()
        copy(tree)
        println("worker workspace create: detached=${if (detached) 1 else 0} deleteMs=${(deleted-receiptDeleted)/1_000_000} receiptMs=${(receiptDeleted-started)/1_000_000} copyMs=${(System.nanoTime()-deleted)/1_000_000}")
        return tree
    }

    internal fun awaitCleanupIdle(timeoutMs: Long = 10_000): Boolean = cleanup.awaitIdle(timeoutMs)

    internal fun copy(key: String, source: Path, checkActive: () -> Unit, runtimeOnly: Boolean, paths: List<String>? = null): Path =
        copyWithProof(key, source, checkActive, runtimeOnly, paths).first

    /** Execution has no setup between the trusted private copy and its first use. Keep its complete copy proof;
     * preparation callers still use [copy] and seal after their mutable planning/setup work. */
    internal fun copySealed(key: String, source: Path, checkActive: () -> Unit): Path {
        val (tree, files) = copyWithProof(key, source, checkActive, false, null)
        checkActive()
        val sealing = System.nanoTime()
        if (files == null) seal(key, tree, checkActive)
        else ready[key] = Ready(tree, files, root.resolve("$key.verified.json"))
        println("worker workspace seal: copyProof=${if (files == null) 0 else 1} elapsedMs=${(System.nanoTime()-sealing)/1_000_000}")
        return tree
    }

    private fun copyWithProof(key: String, source: Path, checkActive: () -> Unit, runtimeOnly: Boolean,
                              paths: List<String>?): Pair<Path, List<InputFile>?> {
        var files: List<InputFile>? = null
        val tree = create(key, checkActive) { destination ->
            val sourceReceipt = if (Files.isRegularFile(source.parent.resolve("manifest.json"))) source.parent.resolve("verified.json")
                else source.resolveSibling("${source.fileName}.verified.json")
            val selected = paths?.map(::safeRelative)
            files = TreeVerification().copy(source, sourceReceipt, destination, root.resolve("$key.verified.json"), checkActive,
                { path -> (selected == null || selected.any { path.startsWith(it) }) && (!runtimeOnly || WorkerTrees.runtimePath(path)) }) {
                if (paths != null) WorkerTrees.copySelected(source, destination, paths, checkActive)
                else if (runtimeOnly) WorkerTrees.copyRuntime(source, destination, checkActive) else WorkerTrees.copy(source, destination, checkActive)
            }
        }
        return tree to files
    }

    /** Seal after trusted measurement installation/provenance capture, which bind this stable private path. */
    internal fun seal(key: String, tree: Path, checkActive: () -> Unit) {
        check(tree == root.resolve(key))
        val receipt = root.resolve("$key.verified.json")
        val files = TreeVerification().capture(tree, receipt, checkActive)
        ready[key] = Ready(tree, files, receipt)
    }
}
