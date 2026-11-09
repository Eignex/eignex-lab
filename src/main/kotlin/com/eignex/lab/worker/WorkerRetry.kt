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

import java.io.IOException

internal class WorkerUnavailable(status: Int) : IOException("worker API HTTP $status")

/** Outages can last through a coordinator restart, but never extend a claim lease or whole-run allowance. */
internal class WorkerRetry(private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
                           private val sleep: (Long) -> Unit = Thread::sleep) {
    fun <T> run(remaining: () -> Long, operation: () -> T): T {
        return run(remaining, 60_000L, operation)
    }

    /** Completed solve rows stay with their owner through backpressure; renewed leases never extend a run limit. */
    fun <T> runWhileOwned(remaining: () -> Long, operation: () -> T): T = run(remaining, null, operation)

    private fun <T> run(remaining: () -> Long, maxMs: Long?, operation: () -> T): T {
        val deadline = maxMs?.let { clock() + minOf(it, remaining().coerceAtLeast(0)) }
        var attempts = 0
        while (true) {
            try { return operation() }
            catch (failure: Exception) {
                if (failure is ExecutionFenced || failure is RunDeadlineExceeded || failure is InterruptedException ||
                    failure is RegionalResultRefused || failure is java.util.concurrent.CancellationException) throw failure
                attempts++
                val transient = failure is IOException
                val left = minOf(deadline?.let { it - clock() } ?: Long.MAX_VALUE, remaining())
                if ((!transient && attempts >= 3) || left <= 0) throw failure
                sleep(minOf(250L * attempts, 2000, left))
            }
        }
    }
}
