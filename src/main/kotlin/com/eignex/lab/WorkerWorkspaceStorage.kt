package com.eignex.lab

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*

/** Session-private mutable clones live beside their verified inputs, including on external cache volumes.
 * A process-owned lock protects live sessions. Reap crashed sessions off the staging path; normal exit removes its own. */
internal class WorkerWorkspaceStorage(private val root: Path) : AutoCloseable {
    private val directory: Path
    private val owner: FileChannel
    private val pin: java.nio.channels.FileLock
    val workspaces: WorkerWorkspaces
    init {
        Files.createDirectories(root)
        val created = guarded {
            val path = Files.createTempDirectory(root, "session-")
            val channel = FileChannel.open(path.resolve("owner.lock"), CREATE_NEW, WRITE)
            try { Triple(path, channel, channel.lock()) }
            catch (failure: Exception) { channel.close(); deleteTree(path); throw failure }
        }
        directory = created.first; owner = created.second; pin = created.third
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
                FileChannel.open(path.resolve("owner.lock"), CREATE, WRITE).use { channel ->
                    val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    lock?.use {
                        val trash = root.resolve(".evict-${java.util.UUID.randomUUID()}")
                        Files.move(path, trash, ATOMIC_MOVE); stale.add(trash)
                    }
                }
            } }
            stale
        }
        for (path in garbage) try { deleteTree(path) } catch (_: java.nio.file.NoSuchFileException) { /* Another reaper finished. */ }
    }
    private fun <T> guarded(action: () -> T): T {
        val path = root.resolve("maintenance.lock")
        synchronized(fileLockFor(path)) {
            FileChannel.open(path, CREATE, WRITE).use { channel -> channel.lock().use { return action() } }
        }
    }
    override fun close() {
        try { deleteTree(directory) } finally { pin.release(); owner.close() }
    }
}
