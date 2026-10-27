package com.eignex.lab

/** EX_TEMPFAIL is a consumer's explicit dependency/infrastructure failure, before code execution. */
internal class WorkerInfrastructureFailure(detail: String) : IllegalStateException(detail)

internal fun checkPreparationExit(exit: Int, detail: () -> String) {
    when (exit) {
        0 -> Unit
        75 -> throw WorkerInfrastructureFailure(detail())
        else -> throw WorkerBuildFailure(detail())
    }
}
