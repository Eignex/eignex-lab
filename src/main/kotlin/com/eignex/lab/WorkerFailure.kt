package com.eignex.lab

internal const val WORKER_SOFTWARE_EXIT = 70
internal const val WORKER_TEMPORARY_EXIT = 75

/** The coordinator may bound software crashes, so never disguise transport/revocation as a software exit. */
internal fun workerProcessExit(failure: Throwable): Int = when (failure) {
    is ExecutionFenced -> 0
    is java.io.IOException, is WorkerInfrastructureFailure, is InterruptedException,
    is java.util.concurrent.CancellationException -> WORKER_TEMPORARY_EXIT
    else -> WORKER_SOFTWARE_EXIT
}

/** EX_TEMPFAIL is a consumer's explicit dependency/infrastructure failure, before code execution. */
internal class WorkerInfrastructureFailure(detail: String) : IllegalStateException(detail)

internal fun checkPreparationExit(exit: Int, detail: () -> String) {
    when (exit) {
        0 -> Unit
        WORKER_TEMPORARY_EXIT -> throw WorkerInfrastructureFailure(detail())
        else -> throw WorkerBuildFailure(detail())
    }
}

/** A run's whole-run deadline ran out. Its message is what a failed job records ([ExecutionStore]), so it is kept. */
internal class RunDeadlineExceeded : IllegalStateException("Whole-run deadline exhausted"), NotRetryable
