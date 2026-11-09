package com.eignex.lab.reference

import com.eignex.lab.checker.*
import com.eignex.lab.domain.*

import java.io.Writer
import java.util.concurrent.TimeUnit

/**
 * Runs a reference solver shipped as a native binary (cvc5, kissat, HiGHS): its own [ProcessBuilder], the instance on
 * stdin when an input writer is given, and a watchdog that force-kills a run that ignores its own time limit, so one
 * solve can never hang a sweep. Returns the solver's stdout and the wall-clock elapsed.
 */
internal object NativeReference {
    private const val EXIT_WAIT_MS = 5_000L

    /** Whether [binary] runs at all ([versionArg] exits 0). */
    fun available(binary: String, versionArg: String = "--version"): Boolean = runCatching {
        val p = ProcessBuilder(binary, versionArg)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        p.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS) && p.exitValue() == 0
    }.getOrElse { false }

    fun exec(cmd: List<String>, timeoutMillis: Long, input: ((Writer) -> Unit)? = null): Pair<String, Long> {
        val startNanos = System.nanoTime()
        WatchedProcess.start(ProcessBuilder(cmd).redirectErrorStream(true), timeoutMillis, input).use { watched ->
            val stdout = watched.process.inputStream.bufferedReader().readText()
            watched.process.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)
            return stdout to (System.nanoTime() - startNanos) / NANOS_PER_MS
        }
    }

    private const val NANOS_PER_MS = 1_000_000L
}
