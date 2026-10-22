package com.eignex.lab

import java.nio.file.Files
import java.nio.file.Path

/**
 * Holds the queue while [reason] gives one: in the runner ([Volumes.hold]) a volume the lab writes to (the data
 * directory's, the job files', the corpus's) has less than `LAB_MIN_FREE_GB` free, or the job files' or the corpus's
 * volume is not mounted. A job started on a full disk fails in setup, and every job queued behind it would fail the
 * same way within seconds; held, they wait for space, or for the volume. Below the same floor, by default, [JobTrim]
 * trims just-ended jobs despite their grace, so the queue waits only on downloads' holds, which lapse within hours.
 */
class DiskGuard(private val reason: () -> String?) {
    /** Why the queue is held, as last checked; null while it is not. */
    @Volatile var held: String? = null
        private set

    /** Whether a job may start now. Logs once when the queue is held and once when it resumes. */
    fun allowsWork(): Boolean {
        val now = runCatching { reason() }.getOrElse { "the lab's volumes could not be read: ${it.message}" }
        if (now != null && held == null) println("holding the queue: $now")
        if (now == null && held != null) println("the queue's hold is over ($held); resuming the queue")
        held = now
        return now == null
    }
}

/** Usable bytes on the file store holding [path]. */
fun freeBytes(path: Path): Long = Files.getFileStore(path).usableSpace

const val BYTES_PER_GB = 1_000_000_000L
