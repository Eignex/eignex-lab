package com.eignex.lab.util

/**
 * The worker's lease on its attempt, or on a build it waits for, ran out before the work did: the coordinator has
 * moved on, so this is never the code's fault and must not count as a crash. A cancellation, so waits that already
 * stop on one stop on this too.
 */
internal class LeaseLost(detail: String) : java.util.concurrent.CancellationException(detail), NotRetryable

/** A run's whole-run deadline ran out. A job it fails records [ERROR] as its error. */
internal class RunDeadlineExceeded : IllegalStateException(ERROR), NotRetryable {
    companion object {
        /** The error a job failed by its run deadline records; the lab reads it back, so it never changes. */
        const val ERROR = "Whole-run deadline exhausted"
    }
}
