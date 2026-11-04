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

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*

/** Provisional output is isolated by attempt. It can never overwrite accepted case artifacts. */
class WorkerLogs(private val store: Store, private val directory: (Long) -> Path) {
    fun append(owner: AttemptOwner, index: Int, stream: String, offset: Long, bytes: ByteArray): Long {
        require(stream in setOf("out", "err") && offset in 0..MAX_BYTES && bytes.size <= CHUNK_BYTES)
        store.execution.authorize(owner)
        val session = requireNotNull(store.execution.session(owner.session))
        val location = store.execution.liveLog(session.jobId, index) ?: throw ExecutionFenced("case log is not active")
        require(location.session == owner.session && location.generation == owner.generation && location.iteration == owner.iteration)
        return appendAt(path(directory(session.jobId), location, index, stream), offset, bytes, MAX_BYTES)
    }

    /**
     * Appends to [session]'s own worker log, `nomad/workers/<session>.log` among its job's files: the worker process's
     * console as it printed it (stage timings, request timings, retries), kept after the worker and its node are gone.
     * Unstructured diagnostics only; nothing reads it back.
     */
    fun appendWorker(session: ExecutionSession, offset: Long, bytes: ByteArray): Long {
        require(offset in 0..MAX_WORKER_BYTES && bytes.size <= CHUNK_BYTES)
        return appendAt(workerPath(directory(session.jobId), session.id), offset, bytes, MAX_WORKER_BYTES)
    }

    private fun appendAt(file: Path, offset: Long, bytes: ByteArray, maxBytes: Long): Long {
        Files.createDirectories(file.parent)
        synchronized(fileLockFor(file)) {
        FileChannel.open(file.resolveSibling("${file.fileName}.lock"), CREATE, WRITE).use { lock -> lock.lock().use {
            java.io.RandomAccessFile(file.toFile(), "rw").use { output ->
                val length = output.length()
                require(offset <= length) { "log offset gap" }
                if (offset < length) {
                    val count = minOf(bytes.size.toLong(), maxBytes - offset).toInt()
                    require(offset + count <= length) { "log chunk overlaps a different append" }
                    output.seek(offset); val prior = ByteArray(count); output.readFully(prior)
                    require(prior.contentEquals(bytes.copyOf(count))) { "log retry differs" }
                    return length
                }
                val accepted = minOf(bytes.size.toLong(), maxBytes - length).toInt()
                output.seek(length); output.write(bytes, 0, accepted)
                return length + accepted
            }
        } }
        }
    }
    companion object {
        const val CHUNK_BYTES = 64 * 1024
        const val MAX_BYTES = 8L * 1024 * 1024
        /** One worker session's console log; past it, later lines are dropped. */
        const val MAX_WORKER_BYTES = 16L * 1024 * 1024
        fun workerPath(root: Path, session: String): Path {
            require(session.matches(Regex("[A-Za-z0-9._-]{1,128}")))
            return root.resolve("nomad/workers/$session.log")
        }
        fun path(root: Path, location: LiveLog, index: Int, stream: String): Path = root.resolve(
            "nomad/live/${location.session}/${location.iteration.replace(':', '-')}-${location.generation}/$index.$stream")
        fun resolve(store: Store, root: Path, job: Long, segments: List<String>): Path? {
            if (segments.size != 1) return null
            val match = Regex("([0-9]+)\\.(out|err)").matchEntire(segments.single()) ?: return null
            val index = match.groupValues[1].toIntOrNull() ?: return null
            val current = store.execution.liveLog(job, index) ?: return null
            val relative = root.relativize(path(root, current, index, match.groupValues[2]))
            return jobFile(root, relative.map { it.toString() })
        }
    }
}
