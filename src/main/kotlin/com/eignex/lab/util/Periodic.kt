package com.eignex.lab.util

import kotlin.concurrent.thread

/**
 * A daemon thread named [name] that runs [work], then [wait]s, for as long as it is not interrupted. A failure of one
 * round is logged ([describeFailure]) and the next round runs; an interrupt, in the work or the wait, ends the loop.
 */
internal fun periodic(name: String, wait: () -> Unit, work: () -> Unit): Thread = thread(isDaemon = true, name = name) {
    val label = name.replace('-', ' ')
    while (!Thread.currentThread().isInterrupted) {
        try {
            work()
        } catch (_: InterruptedException) {
            return@thread
        } catch (failure: Exception) {
            System.err.println("$label: ${describeFailure(failure)}")
        }
        try { wait() } catch (_: InterruptedException) { return@thread }
    }
}

/** [periodic], a round every [everyMs]. */
internal fun periodic(name: String, everyMs: Long, work: () -> Unit): Thread = periodic(name, { Thread.sleep(everyMs) }, work)

/** A background failure in one line: its type, message and the lab frame it came from, never a secret-bearing trace. */
internal fun describeFailure(failure: Throwable): String {
    val frame = failure.stackTrace.firstOrNull { it.className.startsWith("com.eignex.") }
    return "${failure.javaClass.simpleName}: ${failure.message?.take(256)}" + (frame?.let { " at ${it.fileName}:${it.lineNumber}" } ?: "")
}
