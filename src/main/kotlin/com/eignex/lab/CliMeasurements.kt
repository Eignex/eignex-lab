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
    const val JFR = "cli.jfr"
    const val RESOURCES = "resources.txt"
    const val MANIFEST = "measurement.json"
    /** HotSpot's compilation log, written with `profileCompilation: "jfr+log"`. */
    const val COMPILATION_LOG = "hotspot-compilation.log"
    /** The compilation log as it comes back from a node, gzipped. */
    const val COMPILATION_LOG_GZ = "$COMPILATION_LOG.gz"
    val artifacts = listOf(JFR, RESOURCES)

    /** The measured CLI's artifacts, with a recording and compilation log only when requested. */
    fun artifacts(compilation: String?, profileCli: Boolean = true): List<String> =
        (if (profileCli) artifacts else listOf(RESOURCES)) +
            if (compilation == Experiments.COMPILATION_JFR_LOG) listOf(COMPILATION_LOG) else emptyList()

    /** Whether the lab itself runs on macOS, as the lab Mac does: its own profiling launcher is then the macOS one. */
    val localMacOs: Boolean get() = System.getProperty("os.name").orEmpty().startsWith("Mac")

    /**
     * The CLI JVM's options for a profiled case writing into [directory]: JFR under its `profile` settings, dumped on
     * exit. With [compilation] (an [ExperimentSpec.profileCompilation]) `jdk.Compilation` is recorded with no threshold,
     * so every compilation from the recording's start through the solve to shutdown is in it, and with `jfr+log`
     * HotSpot also writes its compilation log, inlining decisions included, which it completes when the JVM exits.
     */
    fun options(directory: String, compilation: String? = null): String {
        val settings = if (compilation == null) "" else ",jdk.Compilation#threshold=0ms"
        val jfr = "-XX:StartFlightRecording=filename=$directory/$JFR,settings=profile$settings,dumponexit=true"
        return if (compilation != Experiments.COMPILATION_JFR_LOG) jfr
        else "$jfr -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation -XX:LogFile=$directory/$COMPILATION_LOG"
    }

    /** What instruments a profiled case's CLI, in a word or two, for its manifest and its report. */
    fun instrumentation(compilation: String?): String = when (compilation) {
        null -> "jfr-profile"
        Experiments.COMPILATION_JFR -> "jfr-profile+compilation"
        else -> "jfr-profile+compilation+hotspot-log"
    }

    /** What `resources.txt`'s `peakRssKiB` measures on macOS ([macOs]) or Linux, for a measurement's manifest. */
    fun memory(macOs: Boolean): String =
        if (macOs) "BSD time -l, getrusage ru_maxrss of the CLI process (bytes on macOS, written as KiB), KiB"
        else "GNU time peak RSS of the CLI process, KiB"

    /**
     * The step that installs the measurement launcher in [worktree]'s CLI: with `EIGNEX_LAB_PROFILE_DIR` set, the CLI
     * runs under a measurement that writes `resources.txt` there, with the same keys in the same units on both
     * systems (`peakRssKiB`, `userSeconds`, `systemSeconds`, `wallSeconds`, `exit`). On Linux, an AWS node, GNU time
     * measures it, and `setpriv --pdeathsig` makes the CLI die with its timer. On macOS ([macOs]), the lab Mac, which
     * has no GNU time, BSD `time -l` measures it and the launcher converts its report. There the timer ignores SIGTERM,
     * SIGHUP and SIGINT, so it outlives a stop to write its report, and the launcher passes each on to the CLI, so a
     * case stopped at its limit still writes its JFR; the runner's kill of the case's process group reaches them all.
     * [time] is the timer, `/usr/bin/time` but in tests.
     */
    // Only generated launch scripts change. The installed solver jars, and the cached builds, stay byte-identical.
    fun install(worktree: String, macOs: Boolean = false, time: String = "/usr/bin/time"): String {
        val cli = "$worktree/$CLI"
        return """
            set -euo pipefail
            test -x ${quote(time)}
            ${if (macOs) "command -v perl >/dev/null; ${quote(time)} -l -o /dev/null true" else "command -v setpriv >/dev/null"}
            test -x ${quote(cli)}
            if ! test -f ${quote("$cli.lab-original")}; then
                cp -p ${quote(cli)} ${quote("$cli.lab-original")}
            fi
            cat > ${quote("$cli.lab-measurement")} <<'EIGNEX_LAB_CLI_MEASUREMENT'
            #!/usr/bin/env bash
            set -euo pipefail
            delegate="${'$'}{0}.lab-original"
            # A bare `--version` is the bench's check that the CLI starts, not the solve: it runs unmeasured, so it
            # cannot write over the solve's recording, log or resources.
            if [[ -n "${'$'}{EIGNEX_LAB_PROFILE_DIR:-}" && ! ( ${'$'}# -eq 1 && ${'$'}1 == --version ) ]]; then
${if (macOs) macMeasure(time) else linuxMeasure(time)}
            fi
            exec "${'$'}delegate" "${'$'}@"
            EIGNEX_LAB_CLI_MEASUREMENT
            chmod +x ${quote("$cli.lab-measurement")}
            mv ${quote("$cli.lab-measurement")} ${quote(cli)}
        """.trimIndent()
    }

    /**
     * Keep a measured case's manifest beside its artifacts in [files]: [record], the case's solve record as it came
     * back, as `solve-record.json`, and the manifest (`measurement.json`) saying where and how it ran.
     */
    fun writeManifest(
        files: Path, index: Int, attempt: Int, measurementId: String, instance: String, command: String, exit: Int,
        javaOptions: String, record: String?, macOs: Boolean, compilation: String? = null,
        profileCli: Boolean = true,
    ) {
        require(profileCli || compilation == null) { "compilation recording requires CLI profiling" }
        val recordHash = record?.takeIf { it.isNotEmpty() }?.let { snapshot ->
            files.resolve("solve-record.json").toFile().writeText(snapshot)
            MessageDigest.getInstance("SHA-256").digest(snapshot.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        files.resolve(MANIFEST).toFile().writeText(Json { encodeDefaults = true }.encodeToString(
            CliMeasurementManifest.serializer(),
            CliMeasurementManifest(
                index, attempt, measurementId, instance, command, exit, javaOptions, recordHash, memory = memory(macOs),
                instrumented = compilation != null,
                instrumentation = if (profileCli) instrumentation(compilation) else "external-time",
                compilation = compilation,
                allocation = if (profileCli) "JFR weighted allocation samples; statistical estimate" else "not collected",
                artifacts = artifacts(compilation, profileCli) + if (recordHash == null) emptyList() else listOf("solve-record.json"),
            ),
        ))
    }
}

// The measurements' shell, at the launcher's indentation in [CliMeasurements.install]'s template.

/**
 * Linux: GNU time over `setpriv --pdeathsig KILL`, so the CLI dies with its timer, then a check, once that is set, that
 * the timer is still its parent: one killed before then would otherwise leave the CLI running.
 */
private fun linuxMeasure(time: String) = """
                export EIGNEX_LAB_TIMER_PID=${'$'}${'$'}
                exec ${quote(time)} --format='peakRssKiB=%M\nuserSeconds=%U\nsystemSeconds=%S\nwallSeconds=%e\nexit=%x' \
                    --output="${'$'}EIGNEX_LAB_PROFILE_DIR/resources.txt" setpriv --pdeathsig KILL -- \
                    bash -c '[[ ${'$'}PPID == "${'$'}EIGNEX_LAB_TIMER_PID" ]] || kill -KILL ${'$'}${'$'}; exec "${'$'}@"' bash "${'$'}delegate" "${'$'}@"""".removePrefix("\n")

/**
 * macOS: BSD `time -l`, whose report the launcher turns into `resources.txt`; its `maximum resident set size` is in
 * bytes there. The timer starts with SIGTERM, SIGHUP and SIGINT ignored, and perl restores them for the CLI, which a
 * shell cannot do for a signal ignored when it started. The launcher passes each on to the timer's child, the CLI.
 */
private fun macMeasure(time: String) = """
                raw="${'$'}EIGNEX_LAB_PROFILE_DIR/time.txt"
                trap '' TERM HUP INT
                ${quote(time)} -l -o "${'$'}raw" perl -e '${'$'}SIG{${'$'}_} = "DEFAULT" for qw(TERM HUP INT); exec @ARGV or die "exec: ${'$'}!\n"' \
                    "${'$'}delegate" "${'$'}@" &
                timer=${'$'}!
                for signal in TERM HUP INT; do trap "pkill -${'$'}signal -P ${'$'}timer || true" "${'$'}signal"; done
                # A passed-on signal interrupts the wait: wait again until the timer has ended.
                while :; do
                    status=0
                    wait "${'$'}timer" || status=${'$'}?
                    kill -0 "${'$'}timer" 2>/dev/null || break
                done
                if [[ -f "${'$'}raw" ]]; then
                    awk -v code="${'$'}status" '
                        { for (i = 2; i <= NF; i++) if (${'$'}i == "real") wall = ${'$'}(i - 1); else if (${'$'}i == "user") user = ${'$'}(i - 1); else if (${'$'}i == "sys") sys = ${'$'}(i - 1) }
                        /maximum resident set size/ { rss = ${'$'}1 }
                        END { printf "peakRssKiB=%d\nuserSeconds=%.2f\nsystemSeconds=%.2f\nwallSeconds=%.2f\nexit=%d\n", int(rss / 1024), user, sys, wall, code }
                    ' "${'$'}raw" > "${'$'}EIGNEX_LAB_PROFILE_DIR/resources.txt"
                    rm -f "${'$'}raw"
                fi
                exit "${'$'}status"""".removePrefix("\n")

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
    /** Whether the case recorded every compilation ([ExperimentSpec.instrumented]): its times are no runtime measurement. */
    val instrumented: Boolean = false,
    val instrumentation: String = "jfr-profile",
    /** The job's [ExperimentSpec.profileCompilation], null without one. */
    val compilation: String? = null,
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
