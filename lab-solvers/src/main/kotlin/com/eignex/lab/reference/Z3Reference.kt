package com.eignex.lab.reference

import com.eignex.lab.checker.*
import com.eignex.lab.domain.*

import com.eignex.lab.checker.ToolLimits
import com.eignex.lab.checker.ToolProcess
import com.eignex.lab.checker.ToolResourceReceipt
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json

object Z3Reference {
    private val binary by lazy {
        val requested = System.getenv("LAB_Z3") ?: "z3"
        val file = if (File(requested).isAbsolute || File(requested).parent != null) {
            File(requested)
        } else {
            System.getenv("PATH").orEmpty().split(File.pathSeparator)
                .map { File(it, requested) }.firstOrNull { it.isFile && it.canExecute() }
                ?: error("z3 executable not found: $requested")
        }
        file.canonicalFile.also { require(it.isFile && it.canExecute()) { "not executable: $it" } }
    }
    private val memoryMb = (System.getenv("LAB_Z3_MEMORY_MB") ?: "2048").toInt().also {
        require(it > 0) { "z3 memory limit must be positive" }
    }
    private const val EXIT_WAIT_MS = 5_000L
    private const val POLICY = "smt-reference-v4"
    private val resourceJson = Json { encodeDefaults = true }
    private val options = listOf("-model", "sat.threads=1", "parallel.enable=false", "pp.decimal=false")

    fun available(): Boolean = runCatching { NativeReference.available(binary.path) }.getOrDefault(false)

    private val build by lazy {
        val proc = ProcessBuilder(binary.path, "--version").redirectErrorStream(true).start()
        try {
            check(proc.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)) { "z3 version timed out" }
            check(proc.exitValue() == 0) { "z3 version failed" }
            val version = proc.inputStream.bufferedReader().readText().trim()
            val digest = MessageDigest.getInstance("SHA-256")
            binary.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
            "$version|sha256=$hash"
        } finally {
            if (proc.isAlive) proc.destroyForcibly()
        }
    }

    fun identity(): String = "$build|memoryMb=$memoryMb|${options.joinToString(",")}|$POLICY"

    fun run(instance: Instance, budget: Budget): ReferenceResult =
        CorpusFiles.withPlainFile(instance.model) { file ->
            val identity = identity()
            val cmd = listOf(
                binary.path, "-T:${(budget.timeoutMillis / 1000).coerceAtLeast(1)}", "-memory:$memoryMb", "-smt2",
            ) + options + "-file:${file.absolutePath}"
            val work = Files.createTempDirectory("z3-reference").toFile()
            try {
                val limits = ToolLimits((budget.timeoutMillis / 1000 + if (budget.timeoutMillis % 1000 == 0L) 0 else 1).coerceAtLeast(1),
                    Math.addExact(budget.timeoutMillis, 5_000L))
                val run = ToolProcess.run(cmd, work, limits.timeoutMs, limits = limits, inputFiles = listOf(file))
                val receipt = checkNotNull(run.resources)
                parse(run.stdout, run.elapsedMs, receipt.command, run.stderr, run.exit ?: 137, run.timedOut,
                    resourcesQualified = receipt.qualified()).let { result ->
                    result.copy(stats = result.stats + mapOf(
                        "referenceVersion" to identity,
                        "referenceBinary" to binary.path,
                        "referenceModelFormat" to "root-obj-requested",
                        "referenceValidation" to "solver-reported",
                        "referenceResourceReceipt" to resourceJson.encodeToString(ToolResourceReceipt.serializer(), receipt),
                        "referenceResourceQualified" to receipt.qualified().toString(),
                    ))
                }
            } finally {
                work.deleteRecursively()
            }
        }

    fun parse(
        stdout: String,
        elapsedMs: Long,
        cmd: List<String>,
        stderr: String = "",
        exitCode: Int = 0,
        killed: Boolean = false,
        resourcesQualified: Boolean = true,
    ): ReferenceResult {
        val verdict = stdout.lineSequence().map { it.trim() }
            .lastOrNull { it == "sat" || it == "unsat" || it == "unknown" }
        val error = stdout.lineSequence().any { it.trimStart().startsWith("(error ") }
        val status = when {
            killed || stdout.lineSequence().any { it.trim() == "timeout" } -> "timeout"
            !resourcesQualified -> "resource-rejected"
            error -> "error"
            stderr.isNotBlank() -> "error"
            exitCode != 0 && verdict != "unknown" -> "error"
            else -> verdict ?: "error"
        }
        val feasible = when (status) {
            "sat" -> true
            "unsat" -> false
            else -> null
        }
        val timeMs = elapsedMs.takeIf { feasible == true }
        return ReferenceResult(
            feasible = feasible,
            objective = null,
            elapsedMs = elapsedMs,
            timeToBestMs = timeMs,
            timeToFirstFeasibleMs = timeMs,
            proven = feasible != null,
            stats = buildMap {
                put("solveTime", (elapsedMs / 1000.0).toString())
                put("maximize", "false")
                put("referenceStatus", status)
                put("referenceExitCode", exitCode.toString())
                put("referenceWatchdogKilled", killed.toString())
                put("referenceStderr", stderr)
                if (status == "error") put("referenceError", "solver output or exit did not establish a verdict")
                if (status == "resource-rejected") put("referenceError", "SMT tool resource envelope was incomplete or exceeded")
            },
            rawOutput = stdout,
            command = cmd.joinToString(" "),
        )
    }
}
