package com.eignex.lab.util

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Coalesced wakeups shorten accepted-result handling without adding a second task queue or a busy loop. */
internal class CoordinatorSignal {
    private val ready = Semaphore(0)
    @Synchronized fun wake() { if (ready.availablePermits() == 0) ready.release() }
    fun await(timeoutMs: Long = 10_000): Boolean = ready.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)
}
