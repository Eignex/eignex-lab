package com.eignex.lab.util

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/** Same POSIX record lock used by the independent supervisor. Released by the kernel if the owner dies. */
class NativeExecutionLock private constructor(private val channel: FileChannel, private val lock: FileLock, private val path: Path) : AutoCloseable {
    override fun close() { try { lock.release() } finally { try { channel.close() } finally { held.remove(path) } } }
    companion object {
        private val held = java.util.concurrent.ConcurrentHashMap.newKeySet<Path>()
        fun tryAcquire(path: Path): NativeExecutionLock? {
            val normalized = path.toAbsolutePath().normalize()
            if (Files.exists(path.resolveSibling("${path.fileName}.owner"))) return null
            if (!held.add(normalized)) return null
            try {
            Files.createDirectories(path.parent)
            val channel = FileChannel.open(path, CREATE, WRITE)
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null } catch (e: Throwable) { channel.close(); throw e }
            if (lock == null) { channel.close(); held.remove(normalized); return null }
            if (Files.exists(path.resolveSibling("${path.fileName}.owner"))) { lock.release(); channel.close(); held.remove(normalized); return null }
            return NativeExecutionLock(channel, lock, normalized)
            } catch (e: Throwable) { held.remove(normalized); throw e }
        }
    }
}
