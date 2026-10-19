package com.eignex.lab

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object CliMeasurements {
    const val CLI = "klause-cli/build/install/klause-cli-jvm/bin/klause-cli"
    val artifacts = listOf("cli.jfr", "resources.txt")

    /** Whether the lab itself runs on macOS, as the lab Mac does: its own profiling launcher is then the macOS one. */
    val localMacOs: Boolean get() = System.getProperty("os.name").orEmpty().startsWith("Mac")

    fun options(directory: String): String =
        "-XX:StartFlightRecording=filename=$directory/cli.jfr,settings=profile,dumponexit=true"

    /** What `resources.txt`'s `peakRssKiB` measures on macOS ([macOs]) or Linux, for a measurement's manifest. */
    fun memory(macOs: Boolean): String =
        if (macOs) "getrusage ru_maxrss of the CLI process (bytes on macOS, written as KiB), KiB"
        else "GNU time peak RSS of the CLI process, KiB"

    /**
     * The step that installs the profiling launcher in [worktree]'s CLI: with `KLAUSE_LAB_PROFILE_DIR` set, the CLI
     * runs under a measurement that writes `resources.txt` there, with the same keys in the same units on both
     * systems (`peakRssKiB`, `userSeconds`, `systemSeconds`, `wallSeconds`, `exit`). On Linux, an AWS node, GNU time
     * measures it through a guard that dies with its timer. On macOS ([macOs]), the lab Mac, which has neither GNU time
     * nor `prctl`, a Python wrapper measures it from `wait4`'s resource usage and passes SIGTERM, SIGINT and SIGHUP on,
     * so a case stopped at its limit still writes its JFR; the runner's kill of the case's process group reaches both.
     */
    // Only generated launch scripts change. The installed solver jars, and the cached builds, stay byte-identical.
    fun install(worktree: String, macOs: Boolean = false): String {
        val cli = "$worktree/$CLI"
        return """
            set -euo pipefail
            ${if (macOs) "" else "test -x /usr/bin/time"}
            command -v python3 >/dev/null
            test -x ${quote(cli)}
            if ! test -f ${quote("$cli.lab-original")}; then
                cp -p ${quote(cli)} ${quote("$cli.lab-original")}
            fi
            cat > ${quote("$cli.lab-measurement")} <<'KLAUSE_LAB_CLI_MEASUREMENT'
            #!/usr/bin/env bash
            set -euo pipefail
            delegate="${'$'}{0}.lab-original"
            if [[ -n "${'$'}{KLAUSE_LAB_PROFILE_DIR:-}" ]]; then
${if (macOs) MAC_MEASURE else LINUX_MEASURE}
            fi
            exec "${'$'}delegate" "${'$'}@"
            KLAUSE_LAB_CLI_MEASUREMENT
            chmod +x ${quote("$cli.lab-measurement")}
            mv ${quote("$cli.lab-measurement")} ${quote(cli)}
        """.trimIndent()
    }

    /**
     * Keep a profiled case's measurement beside its artifacts in [files]: [record], the case's solve record as it came
     * back, as `solve-record.json`, and the manifest (`measurement.json`) saying where and how it ran.
     */
    fun writeManifest(
        files: Path, index: Int, attempt: Int, measurementId: String, instance: String, command: String, exit: Int,
        javaOptions: String, record: String?, macOs: Boolean,
    ) {
        val recordHash = record?.takeIf { it.isNotEmpty() }?.let { snapshot ->
            files.resolve("solve-record.json").toFile().writeText(snapshot)
            MessageDigest.getInstance("SHA-256").digest(snapshot.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        files.resolve("measurement.json").toFile().writeText(Json { encodeDefaults = true }.encodeToString(
            CliMeasurementManifest.serializer(),
            CliMeasurementManifest(index, attempt, measurementId, instance, command, exit, javaOptions, recordHash, memory = memory(macOs)),
        ))
    }
}

// The measurements' shell, at the launcher's indentation in [CliMeasurements.install]'s template: its Python at the
// template's own margin, so it starts in column 0 once the template is trimmed.

/** Linux: GNU time over a guard that dies with its timer. */
private val LINUX_MEASURE = """
                export KLAUSE_LAB_TIMER_PID=${'$'}${'$'}
                guard='import ctypes, os, signal, sys
            expected = int(os.environ["KLAUSE_LAB_TIMER_PID"])
            libc = ctypes.CDLL(None, use_errno=True)
            if libc.prctl(1, signal.SIGKILL, 0, 0, 0) != 0:
                raise OSError(ctypes.get_errno(), "PR_SET_PDEATHSIG")
            if os.getppid() != expected:
                os.kill(os.getpid(), signal.SIGKILL)
            os.execv(sys.argv[1], sys.argv[1:])'
                exec /usr/bin/time --format='peakRssKiB=%M\nuserSeconds=%U\nsystemSeconds=%S\nwallSeconds=%e\nexit=%x' \
                    --output="${'$'}KLAUSE_LAB_PROFILE_DIR/resources.txt" python3 -c "${'$'}guard" "${'$'}delegate" "${'$'}@"""".removePrefix("\n")

/** macOS: a Python wrapper reading the CLI's resource usage from `wait4`; `ru_maxrss` is bytes there, KiB on Linux. */
private val MAC_MEASURE = """
                measure='import os, signal, subprocess, sys, time
            out = sys.argv[1]
            start = time.monotonic()
            child = subprocess.Popen(sys.argv[2:])
            for number in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
                signal.signal(number, lambda n, frame: child.send_signal(n))
            _, status, usage = os.wait4(child.pid, 0)
            wall = time.monotonic() - start
            code = os.waitstatus_to_exitcode(status)
            code = code if code >= 0 else 128 - code
            rss = usage.ru_maxrss // 1024 if sys.platform == "darwin" else usage.ru_maxrss
            with open(out, "w") as f:
                f.write("peakRssKiB=%d\nuserSeconds=%.2f\nsystemSeconds=%.2f\nwallSeconds=%.2f\nexit=%d\n" % (rss, usage.ru_utime, usage.ru_stime, wall, code))
            sys.exit(code)'
                exec python3 -c "${'$'}measure" "${'$'}KLAUSE_LAB_PROFILE_DIR/resources.txt" "${'$'}delegate" "${'$'}@"""".removePrefix("\n")

@Serializable
internal data class CliMeasurementManifest(
    val caseIndex: Int,
    val attempt: Int,
    val measurementId: String,
    val instance: String,
    val command: String,
    val commandExit: Int,
    val javaOptions: String,
    val recordSha256: String?,
    val schemaVersion: Int = 1,
    val scope: String = "whole-cli-jvm",
    val allocation: String = "JFR weighted allocation samples; statistical estimate",
    val memory: String = "GNU time peak RSS of the CLI process, KiB",
    val artifacts: List<String> = CliMeasurements.artifacts +
        if (recordSha256 == null) emptyList() else listOf("solve-record.json"),
)

internal class FileTransferFailure(val exit: Int?, message: String) : IllegalStateException(message)

internal fun retryFileTransfer(
    backoff: Backoff,
    cancelled: () -> Boolean,
    onFailure: (Int, FileTransferFailure) -> Unit = { _, _ -> },
    sleep: (Long) -> Unit = Thread::sleep,
    transfer: () -> Unit,
): Int {
    var attempt = 1
    while (true) {
        check(!cancelled()) { "file transfer cancelled" }
        try {
            transfer()
            return attempt
        } catch (error: FileTransferFailure) {
            try {
                onFailure(attempt, error)
            } catch (report: Throwable) {
                if (report !== error) error.addSuppressed(report)
                throw error
            }
            if (error.suppressed.isNotEmpty() || error.exit != null && error.exit != 255 ||
                attempt >= backoff.attempts || cancelled()
            ) throw error
            var remaining = backoff.delayAfter(attempt)
            while (remaining > 0L && !cancelled()) {
                val slice = minOf(remaining, 1_000L)
                sleep(slice)
                remaining -= slice
            }
            check(!cancelled()) { "file transfer cancelled" }
            attempt++
        }
    }
}

// Publish only complete, successful transfers. Failed SSH output must not masquerade as a usable recording.
internal fun receiveFile(process: Process, destination: Path, timeoutSec: Long, cancelled: () -> Boolean) {
    require(timeoutSec > 0)
    destination.parent.createDirectories()
    val temporary = destination.resolveSibling("${destination.fileName}.part")
    val failure = AtomicReference<Throwable>()
    val reader = thread(isDaemon = true) {
        try {
            Files.newOutputStream(temporary).use { output -> process.inputStream.use { it.copyTo(output) } }
        } catch (error: Throwable) {
            failure.set(error)
        }
    }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSec)
    var primary: Throwable? = null
    try {
        while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
            check(!cancelled()) { "file transfer cancelled" }
            if (System.nanoTime() >= deadline) throw FileTransferFailure(null, "file transfer timed out")
        }
        reader.join(5_000)
        check(!reader.isAlive) { "file transfer did not finish reading" }
        if (process.exitValue() != 0) throw FileTransferFailure(process.exitValue(), "file transfer exited ${process.exitValue()}")
        failure.get()?.let { throw IllegalStateException("file transfer failed", it) }
        check(!cancelled()) { "file transfer cancelled" }
        Files.move(temporary, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    } catch (error: Throwable) {
        primary = error
        throw error
    } finally {
        var cleanup: Throwable? = null
        for (action in listOf<() -> Unit>(
            { if (process.isAlive) process.destroyForcibly() },
            { process.inputStream.close() },
            { check(process.waitFor(5, TimeUnit.SECONDS)) { "file transfer process did not stop" } },
            { reader.join(5_000); check(!reader.isAlive) { "file transfer reader did not stop" } },
            { Files.deleteIfExists(temporary) },
        )) {
            try { action() } catch (error: Throwable) {
                val previous = primary ?: cleanup
                if (previous == null) cleanup = error else if (previous !== error) previous.addSuppressed(error)
            }
        }
        cleanup?.let { throw it }
    }
}
