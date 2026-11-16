package com.eignex.lab.store

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** Two bounded drain lanes: yield between quanta, but never sleep between eligible packs. */
internal class JournalDrainLoop(
    private val drainOne: () -> Boolean,
    private val hasWork: () -> Boolean,
    private val closed: () -> Boolean,
    private val schedule: (Runnable, Long) -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun start() { repeat(2) { enqueue(0) } }

    private fun enqueue(delayMs: Long) {
        if (closed()) return
        try { schedule(Runnable(::run), delayMs) }
        catch (failure: RejectedExecutionException) { if (!closed()) throw failure }
    }

    private fun run() {
        val started = nanoTime()
        var attempted = 0
        while (!closed() && attempted < 32) {
            val progressed = try { drainOne() }
                catch (failure: Exception) { onFailure(failure); true }
            if (!progressed) break
            attempted++
            // A single external publication can exceed the quantum; never interrupt its durability barrier.
            if (nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(25)) break
        }
        enqueue(if (hasWork()) 0 else 250)
    }
}
