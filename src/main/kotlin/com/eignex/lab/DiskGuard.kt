package com.eignex.lab

import java.nio.file.Files
import java.nio.file.Path

/**
 * Holds the queue while the data directory's disk has less than [minFreeBytes] free. A job started on a full disk
 * fails in setup, and every job queued behind it would fail the same way within seconds; held, they wait for space.
 * Below the same floor, by default, [JobTrim] trims just-ended jobs despite their grace, so the queue waits only on
 * downloads' holds, which lapse within hours.
 */
class DiskGuard(private val minFreeBytes: Long, private val freeBytes: () -> Long) {
    private var holding = false

    /** Whether a job may start now. Logs once when the queue is held and once when it resumes. */
    fun allowsWork(): Boolean {
        val free = freeBytes()
        val allowed = free >= minFreeBytes
        if (!allowed && !holding) println("holding the queue: ${free / BYTES_PER_GB} GB free, below LAB_MIN_FREE_GB")
        if (allowed && holding) println("disk space is back (${free / BYTES_PER_GB} GB free); resuming the queue")
        holding = !allowed
        return allowed
    }
}

/** Usable bytes on the file store holding [path]. */
fun freeBytes(path: Path): Long = Files.getFileStore(path).usableSpace

const val BYTES_PER_GB = 1_000_000_000L
