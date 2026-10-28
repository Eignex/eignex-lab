package com.eignex.lab

import java.nio.file.Path

/**
 * Why a case's processes were ended, kept in `<n>.termination` beside its `<n>.exit`, one line per event: when, in epoch
 * milliseconds, then the reason, which starts with its kind (`shutdown:`, `cancel:`, `timeout:` and so on). The lab
 * writes a line before it kills a case's process tree, so the record holds even when the runner dies right after. A
 * case that exits by SIGKILL or SIGTERM with no line from the lab gets an `external:` line instead, saying only what is
 * known: the lab did not send it. The file only grows, so a case that was killed by a shutdown and rerun keeps both
 * stories. [JobTrim] keeps it with the exits.
 */
internal object Termination {
    /** The runner was stopped, by a restart, an update or a reboot. The case is left RUNNING and reruns. */
    const val SHUTDOWN = "shutdown: the runner stopped, so the lab killed the case; it reruns when the runner is back"

    /** The case's process group was still running when its job was taken up again, after a runner that died. */
    const val ORPHAN = "orphan: a runner that stopped left the case running; the lab killed it before rerunning the case"

    const val CANCEL = "cancel: the job was cancelled, so the lab killed the case"

    /** Another case of the same job could not be run, which fails the job, so its siblings are killed with it. */
    const val SIBLING = "sibling: another case of the job could not be run, which failed the job, so the lab killed this one"

    /**
     * A profile job's whole-run limit ran out while its command ran: the lab stops the case gracefully, SIGTERM to its
     * process group and SIGKILL to what is left [graceMs] later, so its shutdown hooks (JFR's dump on exit) can run.
     */
    fun deadline(graceMs: Long) = "deadline: the job's whole-run limit ran out, so the lab sent the case SIGTERM, " +
        "and SIGKILL to what is left after ${span(graceMs)}"

    /** A case killed outright at its [timeoutSec] limit, as an AWS case is when its SSH session outlives `timeout`. */
    fun timeout(timeoutSec: Long) = "timeout: still running after its $timeoutSec s limit, so the lab killed the case"

    /**
     * A case still running at its [timeoutSec] limit, stopped gracefully: SIGTERM to its process group first, so it can
     * flush what it has (a JVM runs its shutdown hooks), and SIGKILL to whatever is left [graceMs] later ([graceExpired]).
     */
    fun timeoutGraceful(timeoutSec: Long, graceMs: Long) =
        "timeout: still running after its $timeoutSec s limit, so the lab sent the case SIGTERM, and SIGKILL to what is left after ${span(graceMs)}"

    /** A case of [kind] (`timeout`, `deadline`) sent SIGTERM was still running when its [graceMs] grace ran out. */
    fun graceExpired(kind: String, graceMs: Long) = "$kind: still running ${span(graceMs)} after SIGTERM, so the lab sent SIGKILL"

    private fun span(ms: Long) = if (ms % MS_PER_SEC == 0L) "${ms / MS_PER_SEC} s" else "$ms ms"

    fun hostLost(reason: String) = "host-lost: $reason; the case goes back to the queue for another host"

    fun file(dir: Path, index: Int): Path = dir.resolve("$index.termination")

    /** Append [reason] to [file]. A failure to write is ignored, as the kill it explains must happen anyway. */
    fun record(file: Path, reason: String, at: Long = now()) {
        runCatching { file.toFile().appendText("$at ${reason.replace('\n', ' ')}\n") }
    }

    /** The reasons recorded for case [index] under [dir], oldest first, each with when; empty when there are none. */
    fun read(dir: Path, index: Int): List<String> =
        file(dir, index).toFile().takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }.orEmpty()

    /** The signal an exit of 128 plus its number says ended a process, for the two a kill sends; null for other exits. */
    fun signal(exit: Int): String? = when (exit) {
        SIGKILL_EXIT -> "SIGKILL"
        SIGTERM_EXIT -> "SIGTERM"
        else -> null
    }

    /**
     * The reason for an [exit] by signal the lab did not send, with [evidence] (kernel log lines, say) when any was
     * found; null for an exit that is no such signal. It names no cause the evidence does not.
     */
    fun external(exit: Int, evidence: List<String> = emptyList()): String? = signal(exit)?.let { signal ->
        "external: exit $exit is a $signal the lab did not send" +
            if (evidence.isEmpty()) "; nothing the lab saw says what sent it" else "; ${evidence.joinToString(" | ")}"
    }

    /**
     * Why an AWS case ended by signal: [exit] as `timeout -k` reported it after [elapsedMs] under a [timeoutSec] limit,
     * with the instance's kernel log lines about killed processes in [kernel]. `timeout` sends SIGTERM at the limit and
     * SIGKILL a grace period later, so a SIGKILL or SIGTERM once the limit passed is the timeout; one before it is
     * external. Null for an exit that is no such signal.
     */
    fun remote(exit: Int, elapsedMs: Long, timeoutSec: Long, kernel: List<String>): String? = when {
        signal(exit) == null -> null
        elapsedMs >= timeoutSec * MS_PER_SEC ->
            "timeout: still running after its $timeoutSec s limit, so `timeout` on the instance sent ${signal(exit)}"
        else -> external(exit, kernel.map { "kernel: $it" }.ifEmpty { listOf("the instance's kernel log names no killed process") })
    }

    const val SIGKILL_EXIT = 137
    const val SIGTERM_EXIT = 143
    private const val MS_PER_SEC = 1000L
}
