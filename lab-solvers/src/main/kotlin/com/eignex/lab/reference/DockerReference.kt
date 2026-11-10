package com.eignex.lab.reference

import com.eignex.lab.checker.*
import com.eignex.lab.domain.*

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

    /** Best-effort cleanup of containers left by an interrupted JVM. */
    fun reapStragglers() {
        runCatching {
            val list = ProcessBuilder("docker", "ps", "-q", "--filter", "label=$label").start()
            val ids = list.inputStream.bufferedReader().use { it.readText() }.trim()
            list.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)
            if (ids.isNotEmpty()) kill(ids.lines(), EXIT_WAIT_MS * 2)
        }
    }

    @Volatile
    private var present = false

    /**
     * Builds [image] from the build context the lab ships as the classpath zip [context] when Docker lacks the tag, so a
     * release that asks for a new image tag runs without anyone building it on the host first. Cases on one host build it
     * once: they queue on a host-wide lock file and check the tag again. A failed build fails this case and the next one
     * tries again (Docker keeps the finished layers).
     */
    fun ensureBuilt(context: String, timeoutMillis: Long = BUILD_TIMEOUT_MS) {
        if (present || imageAvailable()) {
            present = true
            return
        }
        val bundle = DockerReference::class.java.getResource(context) ?: error("$solver: no build context $context")
        val name = image.replace(Regex("[^A-Za-z0-9.-]"), "_")
        java.io.RandomAccessFile(File(System.getProperty("java.io.tmpdir"), "eignex-lab-build-$name.lock"), "rw").use { lock ->
            lock.channel.lock().use {
                if (imageAvailable()) {
                    present = true
                    return
                }
                val dir = java.nio.file.Files.createTempDirectory("eignex-lab-image-$name").toFile()
                val log = File.createTempFile("eignex-lab-build-$name-", ".log")
                try {
                    java.util.zip.ZipInputStream(bundle.openStream()).use { zip ->
                        generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.forEach { entry ->
                            val target = File(dir, entry.name).canonicalFile
                            check(target.path.startsWith(dir.canonicalPath + File.separator)) { "bad entry ${entry.name}" }
                            target.parentFile.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                        }
                    }
                    System.err.println("$solver: building $image from the bundled $context")
                    val process = ProcessBuilder("docker", "build", "-t", image, dir.path)
                        .redirectErrorStream(true).redirectOutput(log).start()
                    val finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
                    if (!finished) {
                        process.descendants().forEach { it.destroyForcibly() }
                        process.destroyForcibly()
                    }
                    check(finished && process.exitValue() == 0 && imageAvailable()) {
                        "$solver: building $image failed (exit=${if (finished) process.exitValue() else "timeout"}): " +
                            log.readText().takeLast(ERROR_TAIL_CHARS)
                    }
                    present = true
                } finally {
                    dir.deleteRecursively()
                    log.delete()
                }
            }
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
            // Killing only the Docker client would leave the container running.
            val watched = WatchedProcess.start(ProcessBuilder(command).redirectError(stderr), timeoutMillis, input) {
                kill(listOf(name))
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
                    runCatching { kill(listOf(name)) }
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
        const val BUILD_TIMEOUT_MS = 30 * 60_000L
        const val EXIT_WAIT_MS = 5_000L
        const val ERROR_TAIL_CHARS = 200

        // Docker itself failed or could not start the container.
        val DOCKER_RUN_FAILED = 125..127
    }
}
