package com.eignex.lab.checker

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlinx.serialization.Serializable

/** OS limits for one native SMT tool invocation. */
data class ToolLimits(val cpuSeconds: Long, val timeoutMs: Long, val memoryBytes: Long = ToolMemoryReceipt.LIMIT) {
    init {
        require(cpuSeconds in 1..86_400 && timeoutMs in 1..86_400_000)
        require(memoryBytes in 1..ToolMemoryReceipt.LIMIT)
    }
}

/** Execution and resource evidence for the command that produced a tool's bounded output. */
@Serializable
data class ToolResourceReceipt(
    val command: List<String>,
    val toolCommand: List<String>,
    val cpuSeconds: Long,
    val timeoutMs: Long,
    val fileLimitBytes: Long,
    val exit: Int?,
    val elapsedMs: Long,
    val timedOut: Boolean,
    val cleanupComplete: Boolean,
    val memory: ToolMemoryReceipt,
    val stdinSha256: String,
    val stdoutBytes: Long,
    val stderrBytes: Long,
    val stdoutCapturedBytes: Int,
    val stderrCapturedBytes: Int,
    val stdoutSha256: String,
    val stderrSha256: String,
    val utf8Valid: Boolean,
    val inputFiles: Map<String, String> = emptyMap(),
    val schemaVersion: Int = 1,
    val policy: String = "smt-tool-envelope-v1",
) {
    /** Complete limits and output, with no timeout, memory excess or unreaped child. */
    fun qualified(): Boolean = schemaVersion == 1 && policy == "smt-tool-envelope-v1" &&
        command.isNotEmpty() && toolCommand.isNotEmpty() && cpuSeconds in 1..86_400 && timeoutMs in 1..86_400_000 &&
        fileLimitBytes == (if (memory.policy == ToolMemoryReceipt.DARWIN) ToolProcess.OUTPUT_CAP / 2L else ToolProcess.OUTPUT_CAP.toLong()) &&
        exit != null && !timedOut && cleanupComplete &&
        elapsedMs in 0..timeoutMs && memory.qualified() && utf8Valid &&
        stdoutBytes in 0 until fileLimitBytes && stderrBytes in 0 until fileLimitBytes &&
        stdoutBytes == stdoutCapturedBytes.toLong() && stderrBytes == stderrCapturedBytes.toLong() &&
        listOf(stdinSha256, stdoutSha256, stderrSha256).all { it.matches(Regex("[0-9a-f]{64}")) } &&
        inputFiles.all { (path, hash) -> File(path).isAbsolute && hash.matches(Regex("[0-9a-f]{64}")) } && commandMatches()

    private fun commandMatches(): Boolean {
        val darwin = memory.policy == ToolMemoryReceipt.DARWIN
        val timing = if (darwin) command.getOrNull(7)?.let(::File)?.takeIf { it.isAbsolute } ?: return false else File("/unused")
        return command == BoundedToolProcess.command(toolCommand, ToolLimits(cpuSeconds, timeoutMs, memory.limitBytes),
            if (darwin) "Mac OS X" else "Linux", timing)
    }
}

internal object BoundedToolProcess {
    fun command(arguments: List<String>, limits: ToolLimits, host: String, timing: File): List<String> = when (host) {
        "Linux" -> listOf(
            "/usr/bin/timeout", "--signal=KILL", "${limits.timeoutMs / 1000}.${(limits.timeoutMs % 1000).toString().padStart(3, '0')}s",
            "/usr/bin/prlimit", "--as=${limits.memoryBytes}", "--cpu=${limits.cpuSeconds}",
            "--fsize=${ToolProcess.OUTPUT_CAP}", "--core=0", "--",
        ) + arguments
        "Mac OS X" -> listOf(
            "/bin/sh", "-c", "ulimit -t ${limits.cpuSeconds} && ulimit -f 1024 && ulimit -c 0 && exec \"\$@\"",
            "smt-tool", "/usr/bin/time", "-l", "-o", timing.absolutePath,
        ) + arguments
        else -> error("unsupported SMT tool resource controls: $host")
    }

    fun run(arguments: List<String>, directory: File, limits: ToolLimits, stdin: File?, env: Map<String, String>, inputs: List<File>): ToolRun {
        require(arguments.isNotEmpty())
        val host = System.getProperty("os.name")
        val captures = File(directory, "tool-resources-${UUID.randomUUID()}")
        check(captures.mkdir()) { "cannot create SMT resource capture directory" }
        val memory = if (host == "Mac OS X") DarwinToolMemory(captures, limits.memoryBytes) else null
        val command = command(arguments, limits, host, memory?.timingFile ?: File(captures, "memory.time"))
        val out = File(captures, "tool.stdout")
        val err = File(captures, "tool.stderr")
        val inputHash = stdin?.let(::sha256) ?: sha256(byteArrayOf())
        val inputHashes = inputs.associate { it.canonicalPath to sha256(it) }
        val started = System.nanoTime()
        var process: Process? = null
        var exit: Int? = null
        var timedOut = false
        var cleanupComplete = false
        val gate = Any()
        var stopping = false
        val shutdown = Thread {
            synchronized(gate) {
                stopping = true
                process?.let(::stop)
            }
        }
        Runtime.getRuntime().addShutdownHook(shutdown)
        try {
            val child = synchronized(gate) {
                check(!stopping && !Thread.currentThread().isInterrupted) { "SMT tool cancelled" }
                ProcessBuilder(command).directory(directory)
                    .redirectInput(stdin?.let { ProcessBuilder.Redirect.from(it) } ?: ProcessBuilder.Redirect.from(File("/dev/null")))
                    .redirectOutput(out).redirectError(err).apply { environment().putAll(env) }
                    .start().also { process = it }
            }
            while (child.isAlive) {
                val remaining = limits.timeoutMs - (System.nanoTime() - started) / 1_000_000
                if (remaining <= 0) {
                    timedOut = true
                    break
                }
                if (memory != null && !memory.sample(child)) break
                child.waitFor(minOf(25, remaining), TimeUnit.MILLISECONDS)
            }
            if (!child.isAlive) exit = child.exitValue()
        } finally {
            synchronized(gate) {
                cleanupComplete = process?.let(::stop) ?: true
                if (cleanupComplete && exit == null) exit = process?.exitValue()
                runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
            }
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        if (elapsed >= limits.timeoutMs && (exit == null || exit == 124 || exit == 137)) timedOut = true
        val stdout = out.inputStream().use { it.readNBytes(ToolProcess.OUTPUT_CAP + 1) }
        val stderr = err.inputStream().use { it.readNBytes(ToolProcess.OUTPUT_CAP + 1) }
        val outText = runCatching { stdout.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
        val errText = runCatching { stderr.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
        val receipt = ToolResourceReceipt(
            command, arguments.toList(), limits.cpuSeconds, limits.timeoutMs,
            if (host == "Mac OS X") ToolProcess.OUTPUT_CAP / 2L else ToolProcess.OUTPUT_CAP.toLong(),
            exit, elapsed, timedOut, cleanupComplete,
            memory?.finish() ?: ToolMemoryReceipt(ToolMemoryReceipt.LINUX, limits.memoryBytes, complete = true, exceeded = false),
            inputHash, out.length(), err.length(), stdout.size, stderr.size, sha256(stdout), sha256(stderr),
            outText != null && errText != null, inputHashes,
        )
        return ToolRun(if (timedOut) null else exit, outText.orEmpty(), errText.orEmpty(), timedOut, elapsed, receipt)
    }

    private fun stop(process: Process): Boolean {
        val interrupted = Thread.interrupted()
        return try {
            val deadline = System.nanoTime() + 5_000_000_000
            val children = process.descendants().use { it.toList() }
            children.asReversed().forEach { it.destroyForcibly() }
            // The timing parent reaps its child and writes the kernel high-water observation.
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
            val reaped = process.waitFor(remaining, TimeUnit.MILLISECONDS)
            while (children.any { it.isAlive } && System.nanoTime() < deadline) Thread.sleep(10)
            reaped && children.none { it.isAlive }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
