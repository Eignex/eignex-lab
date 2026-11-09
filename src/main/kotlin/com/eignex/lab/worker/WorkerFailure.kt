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

/** EX_TEMPFAIL is a consumer's explicit dependency/infrastructure failure, before code execution. */
internal class WorkerInfrastructureFailure(detail: String) : IllegalStateException(detail)

internal fun checkPreparationExit(exit: Int, detail: () -> String) {
    when (exit) {
        0 -> Unit
        WORKER_TEMPORARY_EXIT -> throw WorkerInfrastructureFailure(detail())
        else -> throw WorkerBuildFailure(detail())
    }
}

