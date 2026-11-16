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
    if (exit == 0) return
    val text = detail()
    if (exit == WORKER_TEMPORARY_EXIT || upstreamUnavailable(text)) throw WorkerInfrastructureFailure(text)
    throw WorkerBuildFailure(text)
}

/**
 * A download host's own outage (gateway errors, overload, rate limits, dropped connections) says nothing about the
 * code: the same build passes once the host recovers, so it must replace the attempt instead of failing the job.
 * Client errors such as 404 or 401 stay build failures, since retrying cannot change them.
 */
private val UPSTREAM_UNAVAILABLE = listOf(
    // Gradle dependency resolution: "Could not GET '...'. Received status code 504 from server: Gateway Time-out"
    Regex("""Received status code (5\d\d|408|429) from server"""),
    // Gradle wrapper: "java.io.IOException: Server returned HTTP response code: 504 for URL: https://..."
    Regex("""Server returned HTTP response code: (5\d\d|408|429) for URL"""),
    Regex("""Could not (GET|HEAD) '[^']+'\.\s*(Connect to [^ ]+ failed|Read timed out|Connection reset|The target server failed to respond)"""),
    Regex("""java\.net\.(SocketTimeoutException|ConnectException|UnknownHostException)\b[^\n]*\n\s*at org\.gradle\.wrapper\."""),
)

internal fun upstreamUnavailable(output: String): Boolean = UPSTREAM_UNAVAILABLE.any { it.containsMatchIn(output) }

