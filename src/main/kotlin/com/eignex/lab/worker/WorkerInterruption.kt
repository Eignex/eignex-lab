package com.eignex.lab.worker

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/** Stop new claims on the host's read-only warning. Existing work has at most 45 seconds, leaving
 * 15 seconds of Nomad's 60-second drain for child cleanup and evidence. Removing the flag cannot
 * extend the first observed monotonic deadline. */
internal class WorkerInterruption(private val file: Path? = System.getenv("LAB_WORKER_DRAIN_FILE")?.let(Path::of),
                                  private val clock: () -> Long = System::nanoTime) {
    private val deadline = AtomicLong(Long.MAX_VALUE)
    fun requested(): Boolean {
        if (deadline.get() != Long.MAX_VALUE) return true
        if (file != null && Files.exists(file)) deadline.compareAndSet(Long.MAX_VALUE, clock() + 45_000_000_000)
        return deadline.get() != Long.MAX_VALUE
    }
    fun remainingMs(): Long = if (requested()) ((deadline.get() - clock()) / 1_000_000).coerceAtLeast(0) else Long.MAX_VALUE
}
