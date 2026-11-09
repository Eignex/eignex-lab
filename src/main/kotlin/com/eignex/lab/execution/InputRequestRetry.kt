package com.eignex.lab.execution

import com.eignex.lab.util.Backoff
import com.eignex.lab.util.retrying
import java.io.IOException

/** Retry an idempotent request without repeating its caller's planning or preparation work. */
internal class InputRequestRetry(private val sleep: (Long) -> Unit = Thread::sleep) {
    fun <T> run(stage: String, checkActive: () -> Unit, request: () -> T): T =
        retrying(BACKOFF, retryOn = { it is IOException }, onRetry = { attempt, _, failure ->
            checkActive()
            // Never log signed URLs, keys, credentials or provider response bodies.
            println("shared input retry: stage=$stage attempt=$attempt failure=${failure.javaClass.simpleName}")
        }, sleep = sleep) {
            checkActive()
            request().also { checkActive() }
        }

    private companion object {
        val BACKOFF = Backoff(attempts = 3, baseMs = 100, maxMs = 200)
    }
}
