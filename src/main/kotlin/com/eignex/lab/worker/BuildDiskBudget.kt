package com.eignex.lab.worker

import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Disk accounting must not delay the lease that owns a compiler. One cancellable scan runs at a time;
 * the foreground still checks free space and observes failures, and publication checks the final output inventory. */
internal class BuildDiskBudget(private val root: Path, private val staging: Path, private val maxBytes: Long,
                              private val available: () -> Long = { Files.getFileStore(root).usableSpace },
                              private val inventory: (() -> Unit) -> Long = { check -> stagingBytes(staging, maxBytes, check) },
                              private val start: (Thread) -> Unit = Thread::start,
                              private val clock: () -> Long = System::nanoTime) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val running = AtomicBoolean()
    private val failure = AtomicReference<Exception?>()
    private val worker = AtomicReference<Thread?>()
    private var nextCheck: Long? = null

    @Synchronized fun check() {
        failure.get()?.let { throw it }
        check(!closed.get()) { "build disk monitor closed" }
        val at = clock()
        if (nextCheck?.let { at < it } == true) return
        check(available() >= 1024L * 1024 * 1024) { "build disk held" }
        nextCheck = at + 30_000_000_000
        if (!running.compareAndSet(false, true)) return
        val thread = Thread({
            try {
                val bytes = inventory {
                    if (closed.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
                }
                require(bytes in 0..maxBytes) { "build staging exceeds disk budget" }
            } catch (error: Exception) {
                if (!closed.get()) failure.compareAndSet(null, error)
            } finally { running.set(false) }
        }, "build-disk-accounting").apply { isDaemon = true }
        worker.set(thread)
        try { start(thread) }
        catch (error: Exception) { running.set(false); failure.compareAndSet(null, error); throw error }
        failure.get()?.let { throw it }
    }

    override fun close() {
        closed.set(true)
        worker.get()?.interrupt()
    }
}

/** Reuse the walker's attributes instead of statting every file twice more. Compilers may remove a
 * temporary file during the walk; an absent entry can be ignored, while other filesystem errors remain failures. */
private fun stagingBytes(root: Path, maxBytes: Long, checkActive: () -> Unit): Long {
    var bytes = 0L
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attributes: BasicFileAttributes): FileVisitResult {
            checkActive()
            return FileVisitResult.CONTINUE
        }
        override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
            checkActive()
            if (attributes.isRegularFile) bytes = Math.addExact(bytes, attributes.size())
            require(bytes <= maxBytes) { "build staging exceeds disk budget" }
            return FileVisitResult.CONTINUE
        }
        override fun visitFileFailed(file: Path, error: java.io.IOException): FileVisitResult {
            checkActive()
            if (error !is NoSuchFileException) throw error
            return FileVisitResult.CONTINUE
        }
    })
    return bytes
}
