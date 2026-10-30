package com.eignex.lab.util

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** POSIX record locks belong to the process: closing ANY descriptor for a file releases its locks.
 * Reserve its canonical path before opening, so a same-process probe never opens/closes a second descriptor.
 * Keep that reservation until the owning descriptor is closed, including between unlock and close. */
internal class ProcessFilePin private constructor(private val path: Path, private val channel: FileChannel,
                                                 private val lock: FileLock) : AutoCloseable {
    private val closed = AtomicBoolean()
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { lock.release() } finally { try { channel.close() } finally { held.remove(path) } }
    }
    companion object {
        private val held = ConcurrentHashMap.newKeySet<Path>()
        fun tryLock(path: Path): ProcessFilePin? {
            val key = path.parent.toRealPath().resolve(path.fileName)
            if (!held.add(key)) return null
            var channel: FileChannel? = null
            try {
                channel = FileChannel.open(key, CREATE, WRITE, NOFOLLOW_LINKS)
                val lock = channel.tryLock()
                if (lock != null) return ProcessFilePin(key, channel, lock)
                channel.close(); held.remove(key)
                return null
            } catch (failure: Exception) {
                try { channel?.close() } finally { held.remove(key) }
                throw failure
            }
        }
    }
}
