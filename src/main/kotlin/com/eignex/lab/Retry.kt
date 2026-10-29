package com.eignex.lab

import java.util.concurrent.CancellationException

/**
 * How a step that can fail for a passing reason (the network, a busy remote, a dependency download) is retried: up
 * to [attempts] tries in all, waiting [baseMs] after the first failure and twice as long after each next one, at most
 * [maxMs]. The last failure is rethrown, so a step that keeps failing still fails its job.
 */
data class Backoff(val attempts: Int, val baseMs: Long, val maxMs: Long) {
    init {
        require(attempts >= 1 && baseMs >= 0 && maxMs >= baseMs) { "invalid backoff: $this" }
    }

    /** The wait after failed attempt [attempt], counting from 1. */
    fun delayAfter(attempt: Int): Long =
        (baseMs shl (attempt - 1).coerceAtMost(MAX_SHIFT)).coerceIn(baseMs, maxMs)

    private companion object {
        const val MAX_SHIFT = 30
    }
}

/** A failure that no retry can change, such as a refused commit or a run past its deadline: [retrying] rethrows it. */
interface NotRetryable

/**
 * Run [step], retrying it by [backoff]. [onRetry] hears of each failure that will be retried and the wait before the
 * next try; [sleep] waits, and may throw to give up early (a cancelled job, a runner shutting down).
 */
fun <T> retrying(
    backoff: Backoff,
    onRetry: (attempt: Int, waitMs: Long, failure: Throwable) -> Unit = { _, _, _ -> },
    sleep: (Long) -> Unit = Thread::sleep,
    step: () -> T,
): T {
    var attempt = 1
    while (true) {
        try {
            return step()
        } catch (e: Exception) {
            if (attempt >= backoff.attempts || e is NotRetryable || e is CancellationException) throw e
            val wait = backoff.delayAfter(attempt)
            onRetry(attempt, wait, e)
            sleep(wait)
            attempt++
        }
    }
}
