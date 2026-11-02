package com.eignex.lab.execution

import java.io.IOException

/** Retry an idempotent request without repeating its caller's planning or preparation work. */
internal class InputRequestRetry(private val sleep: (Long) -> Unit = Thread::sleep) {
    fun <T> run(stage: String, checkActive: () -> Unit, request: () -> T): T {
        for (attempt in 1..3) {
            checkActive()
            try { return request().also { checkActive() } }
            catch (failure: IOException) {
                if (attempt == 3) throw failure
                checkActive()
                // Never log signed URLs, keys, credentials or provider response bodies.
                println("shared input retry: stage=$stage attempt=$attempt failure=${failure.javaClass.simpleName}")
                sleep(100L * attempt)
            }
        }
        error("unreachable")
    }
}
