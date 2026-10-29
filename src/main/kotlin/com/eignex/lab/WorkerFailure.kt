package com.eignex.lab

internal const val WORKER_SOFTWARE_EXIT = 70
internal const val WORKER_TEMPORARY_EXIT = 75

/**
 * The coordinator may bound software crashes, so never disguise transport/revocation as a software exit: a fenced
 * worker exits cleanly; a lost lease, an interrupt or an infrastructure failure is temporary; anything else is the
 * code's own failure.
 */
internal fun workerProcessExit(failure: Throwable): Int = when (failure) {
    is ExecutionFenced -> 0
    is LeaseLost -> WORKER_TEMPORARY_EXIT
    is java.io.IOException, is WorkerInfrastructureFailure, is InterruptedException,
    is java.util.concurrent.CancellationException -> WORKER_TEMPORARY_EXIT
    else -> WORKER_SOFTWARE_EXIT
}

/**
 * The worker's lease on its attempt, or on a build it waits for, ran out before the work did: the coordinator has
 * moved on, so this is never the code's fault and must not count as a crash. A cancellation, so waits that already
 * stop on one stop on this too.
 */
internal class LeaseLost(detail: String) : java.util.concurrent.CancellationException(detail), NotRetryable

/** EX_TEMPFAIL is a consumer's explicit dependency/infrastructure failure, before code execution. */
internal class WorkerInfrastructureFailure(detail: String) : IllegalStateException(detail)

internal fun checkPreparationExit(exit: Int, detail: () -> String) {
    when (exit) {
        0 -> Unit
        WORKER_TEMPORARY_EXIT -> throw WorkerInfrastructureFailure(detail())
        else -> throw WorkerBuildFailure(detail())
    }
}

/** A run's whole-run deadline ran out. A job it fails records [ERROR] as its error. */
internal class RunDeadlineExceeded : IllegalStateException(ERROR), NotRetryable {
    companion object {
        /** The error a job failed by its run deadline records; the lab reads it back, so it never changes. */
        const val ERROR = "Whole-run deadline exhausted"
    }
}
