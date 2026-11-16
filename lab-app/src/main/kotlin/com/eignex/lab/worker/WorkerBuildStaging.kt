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
import java.nio.file.FileVisitResult
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Reuse the walker's no-follow metadata, including under .git, without a second stat per entry.
 * Default failure callbacks propagate unreadable/missing paths; cancellation is checked between entries. */
internal fun verifyCheckoutTree(tree: Path, checkActive: () -> Unit) {
    Files.walkFileTree(tree, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
            checkActive()
            return FileVisitResult.CONTINUE
        }
        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            checkActive()
            require(!attributes.isSymbolicLink) { "source tree contains unsupported links" }
            return FileVisitResult.CONTINUE
        }
    })
}

/** The build-cache maintenance lock serializes discovery, pin creation and publication. */
internal class WorkerBuildStaging(private val root: Path) {
    internal class Lease(val directory: Path, private val pin: ProcessFilePin) : AutoCloseable {
        override fun close() = pin.close()
    }
    private fun <T> guarded(check: () -> Unit = {}, action: () -> T): T {
        Files.createDirectories(root)
        return ProcessFilePin.withLock(root.resolve("maintenance.lock"), check, action = action)
    }
    fun create(check: () -> Unit): Lease = guarded(check) {
        val directory = Files.createTempDirectory(root, ".build-")
        try { Lease(directory, checkNotNull(ProcessFilePin.tryLock(directory.resolve("owner.lock")))) }
        catch (failure: Exception) { deleteTree(directory); throw failure }
    }
    fun publish(lease: Lease, destination: Path, check: () -> Unit) = guarded(check) {
        Files.move(lease.directory, destination, ATOMIC_MOVE)
    }
    /** Move declared roots, rather than cloning their bytes. The remaining checkout is a reaper-owned
     * tombstone, so sealing the cache never walks or hashes unrelated source and compiler scratch. */
    fun retain(lease: Lease, paths: List<String>, check: () -> Unit): Path {
        val tree = lease.directory.resolve("tree")
        val retained = Files.createDirectory(lease.directory.resolve("retained"))
        val selected = mutableListOf<Path>()
        for (relative in paths.distinct().map(::safeRelative).sortedBy { it.nameCount }) {
            if (selected.none { relative.startsWith(it) }) selected.add(relative)
        }
        val canonical = tree.toRealPath()
        for (relative in selected) {
            check()
            val source = tree.resolve(relative)
            // Absent planner inputs are part of the selection identity, not required runtime files.
            if (!Files.exists(source, NOFOLLOW_LINKS)) continue
            require(source.toRealPath().startsWith(canonical) && !Files.isSymbolicLink(source)) { "build input escapes source" }
            val target = retained.resolve(relative)
            Files.createDirectories(target.parent)
            Files.move(source, target, ATOMIC_MOVE)
        }
        return guarded(check) {
            val trash = root.resolve(".evict-${UUID.randomUUID()}")
            Files.move(tree, trash, ATOMIC_MOVE)
            Files.move(retained, tree, ATOMIC_MOVE)
            trash
        }
    }
    fun close(lease: Lease) {
        var trash: Path? = null
        try {
            guarded {
                if (Files.exists(lease.directory, NOFOLLOW_LINKS)) {
                    trash = root.resolve(".build-trash-${UUID.randomUUID()}")
                    Files.move(lease.directory, trash, ATOMIC_MOVE)
                }
            }
        } finally { lease.close() }
        trash?.let(::deleteCacheGarbage)
    }
    /** Caller holds maintenance.lock. Return tombstones for deletion outside that lock. Older workers
     * have no pin: retain their staging longer than the maximum two-hour preparation allowance. */
    fun reapLocked(at: Long): List<Path> {
        val garbage = mutableListOf<Path>()
        Files.list(root).use { paths -> paths.forEach { path ->
            val name = path.fileName.toString()
            if (!name.startsWith(".build-trash-") && !name.matches(Regex("\\.build-[0-9]+"))) return@forEach
            if (!Files.isDirectory(path, NOFOLLOW_LINKS)) return@forEach
            if (name.startsWith(".build-trash-")) { garbage.add(path); return@forEach }
            val owner = path.resolve("owner.lock")
            val legacy = !Files.exists(owner, NOFOLLOW_LINKS)
            if (legacy && at - Files.getLastModifiedTime(path, NOFOLLOW_LINKS).toMillis() < 24L*60*60*1000) return@forEach
            ProcessFilePin.tryLock(owner)?.use {
                if (!legacy) {
                    // Age since first observed orphaned, not directory creation: a long-running
                    // compiler can outlive its worker briefly while the independent supervisor stops it.
                    val orphaned = path.resolve("orphaned.at")
                    if (!Files.exists(orphaned, NOFOLLOW_LINKS)) {
                        Files.createFile(orphaned)
                        Files.setLastModifiedTime(orphaned, java.nio.file.attribute.FileTime.fromMillis(at))
                        return@use
                    }
                    if (at - Files.getLastModifiedTime(orphaned, NOFOLLOW_LINKS).toMillis() < 5L*60*1000) return@use
                }
                val trash = root.resolve(".build-trash-${UUID.randomUUID()}")
                Files.move(path, trash, ATOMIC_MOVE); garbage.add(trash)
            }
        } }
        return garbage
    }
}
