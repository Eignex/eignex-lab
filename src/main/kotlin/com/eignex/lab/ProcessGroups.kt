package com.eignex.lab

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * [command] wrapped so that it leads a new session, and so its own process group. perl is the portable way to
 * get there: macOS ships no `setsid` command, and both macOS and every common Linux base image ship perl.
 */
internal fun inSession(vararg command: String): List<String> =
    listOf("perl", "-e", "use POSIX qw(setsid); setsid() or die \"setsid: \$!\"; exec @ARGV or die \"exec: \$!\"") +
        command

/** SIGKILL every process in the group that [leader] leads. */
internal fun killGroup(leader: Long) {
    signalGroups("KILL", listOf(leader))
}

/**
 * Send [signal] (a name such as TERM or KILL, or 0 to send none) to every process in the groups [leaders] lead: whether
 * any process was there to take it.
 */
internal fun signalGroups(signal: String, leaders: Collection<Long>): Boolean {
    val kill = ProcessBuilder(listOf("kill", "-$signal", "--") + leaders.map { "-$it" }).redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    if (!kill.waitFor(SIGNAL_WAIT_SEC, TimeUnit.SECONDS)) {
        kill.destroyForcibly()
        return true
    }
    return kill.exitValue() == 0
}

/**
 * Stop the process groups [leaders] lead: SIGTERM, up to [graceMs] for every process in them to exit, then SIGKILL to
 * any group with one left, after calling [beforeKill]. With no grace, straight to SIGKILL. Whether SIGKILL was needed.
 */
internal fun stopGroups(leaders: Collection<Long>, graceMs: Long, beforeKill: () -> Unit = {}): Boolean {
    var left = leaders.toList()
    var interrupted = false
    if (graceMs > 0) {
        signalGroups("TERM", left)
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMs)
        try {
            while (true) {
                left = left.filter { signalGroups("0", listOf(it)) }
                val remaining = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())
                if (left.isEmpty() || remaining <= 0) break
                Thread.sleep(remaining.coerceAtMost(GROUP_POLL_MS))
            }
        } catch (_: InterruptedException) {
            // Interrupted mid-grace: kill now, and leave the interrupt for the caller to see once that is done.
            interrupted = true
        }
    }
    try {
        if (left.isEmpty()) return false
        beforeKill()
        signalGroups("KILL", left)
        return true
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}

private const val SIGNAL_WAIT_SEC = 1L
private const val GROUP_POLL_MS = 100L

/** The exit [runProcess] reports for a command it killed at its time limit, as coreutils `timeout` does. */
internal const val TIMED_OUT_EXIT = 124

/**
 * Run [command] in [dir] as the leader of its own process group, with [env] added to its environment, and return its
 * exit and its output (stdout and stderr together; empty when [log] takes it instead). One still running after
 * [timeout] has its whole group killed, children included, and reports [TIMED_OUT_EXIT]. No shell is between.
 */
internal fun runProcess(
    command: List<String>,
    dir: File? = null,
    timeout: Duration,
    env: Map<String, String> = emptyMap(),
    log: File? = null,
): Pair<Int, String> {
    val builder = ProcessBuilder(inSession(*command.toTypedArray())).redirectErrorStream(true)
    dir?.let(builder::directory)
    builder.environment().putAll(env)
    if (log != null) builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
    val process = builder.start()
    // Read on another thread, so a command that fills the pipe cannot keep the timeout from firing.
    val output = if (log == null) CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
    else CompletableFuture.completedFuture("")
    try {
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            stopGroups(listOf(process.pid()), 0)
            process.waitFor(SIGNAL_WAIT_SEC, TimeUnit.SECONDS)
            return TIMED_OUT_EXIT to "timed out after ${timeout.toSeconds()} s: ${command.first()}"
        }
        return process.exitValue() to output.get(SIGNAL_WAIT_SEC, TimeUnit.SECONDS)
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}

/** Git with [args] in [dir], never asking for credentials, and killed after [timeout]: its exit and its output. */
internal fun runGit(dir: File, args: List<String>, timeout: Duration = GIT_TIMEOUT): Pair<Int, String> =
    runProcess(Background.prefix + listOf("git") + args, dir, timeout, mapOf("GIT_TERMINAL_PROMPT" to "0"))

internal val GIT_TIMEOUT: Duration = Duration.ofMinutes(10)

/**
 * Run [block] holding the lock on git mirror [mirror]: one fetch or clone at a time across this JVM's threads and
 * across the lab's processes (the runner and the Nomad coordinator share mirrors), by a `<mirror>.lock` file beside it.
 */
internal fun <T> withMirrorLock(mirror: Path, block: () -> T): T = synchronized(fileLockFor(mirror)) {
    val lock = mirror.resolveSibling("${mirror.fileName}.lock")
    Files.createDirectories(lock.parent)
    FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
        channel.lock().use { block() }
    }
}
