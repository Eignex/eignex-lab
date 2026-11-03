package com.eignex.lab.util

import java.util.concurrent.TimeUnit

/**
 * A private copy cannot outlive the callback holding its source pin or destination ownership. Even cancellation
 * must join the actual owned child, rather than treating a timed wait or a cancelled Future as termination.
 * Like an interruptible blocking API, this throws interruption with the flag cleared so enclosing pin finalizers
 * can finish; the staging orchestrator restores its own interrupt only after those finalizers have returned.
 */
internal fun <T> withOwnedCopyProcess(process: Process, copy: () -> T): T {
    var failure: Throwable? = null
    try { return copy() }
    catch (error: Throwable) { failure = error; throw error }
    finally {
        var cleanupFailure: Throwable? = null
        var interrupted: InterruptedException? = null
        fun interruption(error: InterruptedException) {
            if (interrupted == null) interrupted = error
        }
        if (Thread.interrupted()) interruption(InterruptedException("private copy cleanup interrupted"))
        if (process.isAlive) {
            try { process.destroyForcibly() }
            catch (error: Throwable) { cleanupFailure = error }
            while (process.isAlive) try { process.waitFor(50, TimeUnit.MILLISECONDS) }
            catch (error: InterruptedException) { interruption(error) }
        }
        if (Thread.interrupted()) interruption(InterruptedException("private copy cleanup interrupted"))
        interrupted?.let { error ->
            if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
        }
        cleanupFailure?.let { error ->
            if (failure == null) throw error else failure.addSuppressed(error)
        }
    }
}
