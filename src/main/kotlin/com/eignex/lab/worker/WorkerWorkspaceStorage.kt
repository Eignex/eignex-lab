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

/** Session-private mutable clones live beside their verified inputs, including on external cache volumes.
 * A process-owned lock protects live sessions. Reap crashed sessions off the staging path; normal exit removes its own. */
internal class WorkerWorkspaceStorage(private val root: Path) : AutoCloseable {
    private val directory: Path
    private val pin: ProcessFilePin
    val workspaces: WorkerWorkspaces
    init {
        Files.createDirectories(root)
        val created = guarded {
            val path = Files.createTempDirectory(root, "session-")
            try { path to checkNotNull(ProcessFilePin.tryLock(path.resolve("owner.lock"))) }
            catch (failure: Exception) { deleteTree(path); throw failure }
        }
        directory = created.first; pin = created.second
        workspaces = WorkerWorkspaces(directory.resolve("trees"))
        Thread({
            try { reap() } catch (_: java.io.IOException) { println("worker workspace cleanup deferred") }
        }, "worker-workspace-cleanup").apply { isDaemon = true; start() }
    }
    internal fun reap() {
        val garbage = guarded {
            val stale = mutableListOf<Path>()
            Files.list(root).use { entries -> entries.forEach { path ->
                if (!Files.isDirectory(path, NOFOLLOW_LINKS)) return@forEach
                if (path.fileName.toString().startsWith(".evict-")) { stale.add(path); return@forEach }
                if (!path.fileName.toString().startsWith("session-")) return@forEach
                ProcessFilePin.tryLock(path.resolve("owner.lock"))?.use {
                    val trash = root.resolve(".evict-${java.util.UUID.randomUUID()}")
                    Files.move(path, trash, ATOMIC_MOVE); stale.add(trash)
                }
            } }
            stale
        }
        for (path in garbage) try { deleteTree(path) } catch (_: java.nio.file.NoSuchFileException) { /* Another reaper finished. */ }
    }
    private fun <T> guarded(action: () -> T): T {
        val path = root.resolve("maintenance.lock")
        while (true) {
            ProcessFilePin.tryLock(path)?.use { return action() }
            Thread.sleep(10)
        }
    }
    override fun close() {
        try {
            // Remove the session from discovery before unlinking its owner lock. Otherwise a reaper could
            // recreate that lock file between unlink and final directory removal and race normal exit.
            val trash = guarded {
                root.resolve(".evict-${java.util.UUID.randomUUID()}").also { Files.move(directory, it, ATOMIC_MOVE) }
            }
            try { deleteTree(trash) } catch (_: java.nio.file.NoSuchFileException) { /* A reaper finished it. */ }
        } finally { pin.close() }
    }
}
