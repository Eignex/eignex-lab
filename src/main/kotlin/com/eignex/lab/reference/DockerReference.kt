package com.eignex.lab.reference

import java.io.File
import java.io.Writer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Shared container limits and lifecycle for reference solvers; adapters own arguments and verdicts. */
internal class DockerReference(private val image: String, private val label: String, private val solver: String) {
    private val seq = AtomicLong()

    fun containerName(): String = "$label-${ProcessHandle.current().pid()}-${seq.incrementAndGet()}"

    fun command(
        name: String,
        arguments: List<String>,
        interactive: Boolean = false,
        cpus: Int = 1,
        mounts: List<String> = emptyList(),
    ): List<String> = buildList {
        addAll(listOf("docker", "run", "--rm", "--log-driver", "none"))
        if (interactive) add("-i")
        addAll(listOf("--name", name, "--memory", MEMORY_LIMIT, "--memory-swap", MEMORY_LIMIT,
            "--cpus", cpus.toString(), "--label", label))
        mounts.forEach { addAll(listOf("-v", it)) }
        add(image)
        addAll(arguments)
    }

    /** Best-effort cleanup of containers left by an interrupted JVM. */
    fun reapStragglers() {
        runCatching {
            val list = ProcessBuilder("docker", "ps", "-q", "--filter", "label=$label").start()
            val ids = list.inputStream.bufferedReader().use { it.readText() }.trim()
            list.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)
            if (ids.isNotEmpty()) kill(ids.lines(), EXIT_WAIT_MS * 2)
        }
    }

    fun imageAvailable(): Boolean = runCatching {
        val process = ProcessBuilder("docker", "image", "inspect", image)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS) && process.exitValue() == 0
    }.getOrDefault(false)

    fun exec(command: List<String>, name: String, timeoutMillis: Long, input: ((Writer) -> Unit)? = null): Run {
        // Parser chatter must be drained separately from stdout, which carries the solver's verdict.
        val stderr = File.createTempFile("$label-", ".err")
        try {
            val started = System.nanoTime()
            val process = ProcessBuilder(command).redirectError(stderr).start()
            // Solvers can print while reading large inputs; feed stdin concurrently to avoid pipe deadlocks.
            val writer = Thread {
                runCatching { process.outputStream.bufferedWriter().use { input?.invoke(it) } }
            }.apply { isDaemon = true; start() }
            val timedOut = AtomicBoolean()
            val watchdog = Thread {
                runCatching {
                    if (!process.waitFor(timeoutMillis * 2 + GRACE_MS, TimeUnit.MILLISECONDS)) {
                        timedOut.set(true)
                        // Killing only the Docker client would leave the container running.
                        try { kill(listOf(name)) } finally { process.destroyForcibly() }
                    }
                }
            }.apply { isDaemon = true; start() }
            try {
                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                val exit = runCatching { process.exitValue() }.getOrNull()
                val error = stderr.readText().takeLast(ERROR_TAIL_CHARS)
                // Docker launch failures must be retried rather than cached as undecided solver results.
                check(exit !in DOCKER_RUN_FAILED) { "$solver: docker run exited $exit: $error" }
                return Run(stdout, error, exit, elapsedMs, timedOut.get())
            } catch (failure: Exception) {
                if (process.isAlive) {
                    runCatching { kill(listOf(name)) }
                    process.destroyForcibly()
                }
                throw failure
            } finally {
                watchdog.interrupt()
                writer.interrupt()
            }
        } finally {
            stderr.delete()
        }
    }

    private fun kill(names: List<String>, timeoutMillis: Long = EXIT_WAIT_MS) {
        ProcessBuilder(listOf("docker", "kill") + names)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    data class Run(val stdout: String, val stderr: String, val exit: Int?, val elapsedMs: Long, val timedOut: Boolean)

    private companion object {
        const val MEMORY_LIMIT = "6g"
        const val EXIT_WAIT_MS = 5_000L
        const val GRACE_MS = 20_000L
        const val ERROR_TAIL_CHARS = 200

        // Docker itself failed or could not start the container.
        val DOCKER_RUN_FAILED = 125..127
    }
}
