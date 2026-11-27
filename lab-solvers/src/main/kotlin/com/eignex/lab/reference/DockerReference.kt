package com.eignex.lab.reference

import java.io.File
import java.io.Writer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Shared container limits and lifecycle for reference solvers; adapters own arguments and verdicts. */
class DockerReference(private val image: String, private val label: String, private val solver: String) {
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

    fun exec(command: List<String>, name: String, timeoutMillis: Long, input: ((Writer) -> Unit)? = null): Run {
        // Parser chatter must be drained separately from stdout, which carries the solver's verdict.
        val stderr = File.createTempFile("$label-", ".err")
        try {
            val started = System.nanoTime()
            // Killing only the Docker client would leave the container running.
            val watched = WatchedProcess.start(ProcessBuilder(command).redirectError(stderr), timeoutMillis, input) {
                kill(name)
            }
            val process = watched.process
            try {
                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                val exit = runCatching { process.exitValue() }.getOrNull()
                val error = stderr.readText().takeLast(ERROR_TAIL_CHARS)
                // Docker launch failures must be retried rather than cached as undecided solver results.
                check(exit !in DOCKER_RUN_FAILED) { "$solver: docker run exited $exit: $error" }
                return Run(stdout, error, exit, elapsedMs, watched.timedOut)
            } catch (failure: Exception) {
                if (process.isAlive) {
                    runCatching { kill(name) }
                    process.destroyForcibly()
                }
                throw failure
            } finally {
                watched.close()
            }
        } finally {
            stderr.delete()
        }
    }

    private fun kill(name: String) {
        ProcessBuilder("docker", "kill", name)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    data class Run(val stdout: String, val stderr: String, val exit: Int?, val elapsedMs: Long, val timedOut: Boolean)

    private companion object {
        const val MEMORY_LIMIT = "6g"
        const val EXIT_WAIT_MS = 5_000L
        const val ERROR_TAIL_CHARS = 200

        // Docker itself failed or could not start the container.
        val DOCKER_RUN_FAILED = 125..127
    }
}
