package com.eignex.lab

import java.io.IOException

internal class WorkerUnavailable(status: Int) : IOException("worker API HTTP $status")

/** Outages can last through a coordinator restart, but never extend a claim lease or whole-run allowance. */
internal class WorkerRetry(private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
                           private val sleep: (Long) -> Unit = Thread::sleep) {
    fun <T> run(remaining: () -> Long, operation: () -> T): T {
        val deadline = clock() + minOf(60_000L, remaining().coerceAtLeast(0))
        var attempts = 0
        while (true) {
            try { return operation() }
            catch (failure: Exception) {
                if (failure is ExecutionFenced || failure is ProfileRunTimeout || failure is InterruptedException ||
                    failure is java.util.concurrent.CancellationException) throw failure
                attempts++
                val transient = failure is IOException
                val left = minOf(deadline - clock(), remaining())
                if ((!transient && attempts >= 3) || left <= 0) throw failure
                sleep(minOf(250L * attempts, 2000, left))
            }
        }
    }
}
