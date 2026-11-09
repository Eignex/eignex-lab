package com.eignex.lab.reference

import java.io.Writer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A reference-solver process with its stdin fed on its own thread and a watchdog that force-kills a run still alive
 * after twice its time limit plus a grace period, so a solve that ignores its own limit can never hang a sweep.
 * [close] stops both helper threads once the caller has collected the output.
 */
internal class WatchedProcess private constructor(val process: Process) : AutoCloseable {
    private val timedOutFlag = AtomicBoolean()
    private var writer: Thread? = null
    private lateinit var watchdog: Thread

    /** Whether the watchdog killed the process. */
    val timedOut: Boolean get() = timedOutFlag.get()

    override fun close() {
        watchdog.interrupt()
        writer?.interrupt()
    }

    companion object {
        private const val GRACE_MS = 20_000L

        /**
         * Starts [builder], writing [input] to stdin concurrently (a solver can print while it reads, and a full stdout
         * pipe would block both) or closing stdin at once when there is none. On timeout [onTimeout] runs before the
         * process is destroyed, for cleanup that killing the process alone would miss.
         */
        fun start(
            builder: ProcessBuilder,
            timeoutMillis: Long,
            input: ((Writer) -> Unit)? = null,
            onTimeout: () -> Unit = {},
        ): WatchedProcess {
            val watched = WatchedProcess(builder.start())
            val process = watched.process
            if (input == null) {
                process.outputStream.close()
            } else {
                watched.writer = daemon { runCatching { process.outputStream.bufferedWriter().use(input) } }
            }
            watched.watchdog = daemon {
                runCatching {
                    if (!process.waitFor(timeoutMillis * 2 + GRACE_MS, TimeUnit.MILLISECONDS)) {
                        watched.timedOutFlag.set(true)
                        try { onTimeout() } finally { process.destroyForcibly() }
                    }
                }
            }
            return watched
        }

        private fun daemon(body: () -> Unit): Thread = Thread(body).apply {
            isDaemon = true
            start()
        }
    }
}
