package com.eignex.lab

import java.nio.file.Files
import java.nio.file.Path

/**
 * Starts `eignex-lab process-supervisor` for a test, in a JVM of its own as on a host, with the memory settings the
 * worker entrypoint gives it. Set `LAB_TEST_SUPERVISOR` to another command speaking the protocol (such as an older
 * release's `bin/eignex-lab process-supervisor`) to run the same tests against it.
 */
object SupervisorProcess {
    val jvmOptions = listOf("-Xms4m", "-Xmx48m", "-Xss512k", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1", "-XX:CICompilerCount=1")

    fun command(socket: Path, localLock: Path? = null): List<String> {
        val other = System.getenv("LAB_TEST_SUPERVISOR")?.takeIf { it.isNotBlank() }?.split(" ")
        val base = other ?: (listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString()) + jvmOptions +
            listOf("-cp", System.getProperty("java.class.path"), "com.eignex.lab.cli.MainKt", "process-supervisor"))
        return base + listOf("--socket", socket.toString()) + (localLock?.let { listOf("--local-lock", it.toString()) } ?: emptyList())
    }

    /** The started supervisor, once its socket exists; its error output goes to [errors]. */
    fun start(socket: Path, errors: Path, localLock: Path? = null, timeoutMs: Long = 10_000): Process {
        val process = ProcessBuilder(command(socket, localLock)).redirectError(errors.toFile()).start()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!Files.exists(socket)) {
            check(process.isAlive && System.nanoTime() < deadline) { runCatching { Files.readString(errors) }.getOrDefault("supervisor did not start") }
            Thread.sleep(20)
        }
        return process
    }
}
