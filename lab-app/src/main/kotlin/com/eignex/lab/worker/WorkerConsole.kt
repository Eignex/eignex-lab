package com.eignex.lab.worker

import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE

/**
 * The worker process's console, also written to [file] for [WorkerSession] to append to its job's files: what the worker
 * prints (stage, request and workspace timings, retries) stays readable through the lab API after the allocation and its
 * node are gone. The console itself is unchanged. Past [maxBytes] the file stops growing; the console does not.
 */
internal class WorkerConsole(val file: Path, private val maxBytes: Long) : AutoCloseable {
    private val output = Files.newOutputStream(file, CREATE, APPEND)
    private var written = Files.size(file)
    private val previousOut = System.out
    private val previousErr = System.err

    init {
        System.setOut(PrintStream(Tee(previousOut), true))
        System.setErr(PrintStream(Tee(previousErr), true))
    }

    private inner class Tee(private val console: OutputStream) : OutputStream() {
        override fun write(b: Int) {
            console.write(b)
            copy(1) { it.write(b) }
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            console.write(b, off, len)
            copy(len) { it.write(b, off, len) }
        }
        override fun flush() {
            console.flush()
            copy(0) { it.flush() }
        }
    }

    /** A failing copy never fails the console write it copies. */
    private fun copy(bytes: Int, operation: (OutputStream) -> Unit) = synchronized(output) {
        if (written + bytes > maxBytes) return@synchronized
        runCatching { operation(output) }.onSuccess { written += bytes }
    }

    override fun close() {
        System.setOut(previousOut)
        System.setErr(previousErr)
        synchronized(output) { runCatching { output.close() } }
    }
}
