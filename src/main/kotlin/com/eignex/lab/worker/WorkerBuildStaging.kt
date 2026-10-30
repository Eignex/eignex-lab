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

/** The build-cache maintenance lock serializes discovery, pin creation and publication. */
internal class WorkerBuildStaging(private val root: Path) {
    internal class Lease(val directory: Path, private val pin: ProcessFilePin) : AutoCloseable {
        override fun close() = pin.close()
    }
    private fun <T> guarded(check: () -> Unit = {}, action: () -> T): T {
        Files.createDirectories(root)
        while (true) {
            check()
            ProcessFilePin.tryLock(root.resolve("maintenance.lock"))?.use { return action() }
            Thread.sleep(10)
        }
    }
    fun create(check: () -> Unit): Lease = guarded(check) {
        val directory = Files.createTempDirectory(root, ".build-")
        try { Lease(directory, checkNotNull(ProcessFilePin.tryLock(directory.resolve("owner.lock")))) }
        catch (failure: Exception) { deleteTree(directory); throw failure }
    }
    fun publish(lease: Lease, destination: Path, check: () -> Unit) = guarded(check) {
        Files.move(lease.directory, destination, ATOMIC_MOVE)
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
