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
        val deadline = clock() + minOf(60_000L, remaining().coerceAtLeast(0))
        var attempts = 0
        while (true) {
            try { return operation() }
            catch (failure: Exception) {
                if (failure is ExecutionFenced || failure is RunDeadlineExceeded || failure is InterruptedException ||
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
