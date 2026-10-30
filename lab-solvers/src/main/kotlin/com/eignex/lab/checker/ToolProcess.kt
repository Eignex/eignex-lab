package com.eignex.lab.checker

import com.eignex.lab.domain.*
import com.eignex.lab.reference.*

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** An external checker's exit, bounded output and deadline outcome, with [resources] for bounded SMT runs. */
data class ToolRun(
    val exit: Int?, val stdout: String, val stderr: String, val timedOut: Boolean, val elapsedMs: Long,
    val resources: ToolResourceReceipt? = null,
) {
    /** Its output's last lines, for a reason. */
    fun tail(lines: Int = 6): String = (stdout + "\n" + stderr).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        .toList().takeLast(lines).joinToString(" / ").take(REASON_CAP)
}

internal const val REASON_CAP = 500

/** The most of a [LabCheck.diagnostic] kept. */
internal const val DIAGNOSTIC_CAP = 2_000

/** Runs one external checker: in [dir], with [stdin] as input when given, killed with its children after [timeoutMs]. */
object ToolProcess {
    const val OUTPUT_CAP = 1 shl 20

    fun run(
        command: List<String>, dir: File, timeoutMs: Long, stdin: File? = null, env: Map<String, String> = emptyMap(),
        limits: ToolLimits? = null,
        inputFiles: List<File> = emptyList(),
    ): ToolRun {
        if (limits != null) {
            require(limits.timeoutMs == timeoutMs)
            return BoundedToolProcess.run(command, dir, limits, stdin, env, inputFiles)
        }
        val out = File(dir, "tool.stdout")
        val err = File(dir, "tool.stderr")
        val started = System.nanoTime()
        val process = ProcessBuilder(command).directory(dir)
            .redirectOutput(out).redirectError(err)
            .redirectInput(stdin?.let { ProcessBuilder.Redirect.from(it) } ?: ProcessBuilder.Redirect.from(File("/dev/null")))
            .apply { environment().putAll(env) }
            .start()
        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            kill(process)
            throw e
        }
        if (!finished) kill(process)
        val elapsed = (System.nanoTime() - started) / 1_000_000
        return ToolRun(if (finished) process.exitValue() else null, read(out), read(err), !finished, elapsed)
    }

    private fun kill(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
    }

    private fun read(file: File): String = if (!file.isFile) "" else file.inputStream().use { it.readNBytes(OUTPUT_CAP) }.decodeToString()
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
