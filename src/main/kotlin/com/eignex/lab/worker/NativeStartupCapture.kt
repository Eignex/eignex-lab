package com.eignex.lab.worker

import com.eignex.lab.util.ProcessSupervisor
import com.eignex.lab.util.ProcessTermination
import com.eignex.lab.util.deleteTree
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.concurrent.thread

/** Case-owned, opt-in native samples. Raw sampler output is private until its identity checks finish. */
internal class NativeStartupCapture(
    command: List<String>,
    private val worktree: Path,
    private val output: Path,
    scratch: Path,
    private val binding: Binding,
    private val supervisor: ProcessSupervisor,
    private val remainingMs: () -> Long,
    mac: Boolean,
    private val sampleTool: Path = Path.of("/usr/bin/sample"),
) : AutoCloseable {
    @Serializable
    data class Binding(val session: String, val caseIndex: Int, val generation: Int, val worker: String, val sourceCommit: String)

    @Serializable
    data class Target(val rootPid: Long, val rootStartedAt: String, val pid: Long, val startedAt: String,
                      val executable: String, val option: String, val fileKey: String?, val executableBytes: Long,
                      val executableModifiedAt: String)

    @Serializable
    data class Receipt(val binding: Binding, val outcome: String, val target: Target? = null,
                       val elapsedMs: Long = 0, val cpuBeforeNs: Long? = null, val cpuAfterNs: Long? = null,
                       val exitCode: Int? = null, val signal: Int? = null, val cancelled: Boolean = false,
                       val timedOut: Boolean = false,
                       val sample: String? = null, val sampleVerified: Boolean = false, val diagnostic: String? = null,
                       val filesystemTrace: String = "unavailable: privileged filesystem tracing is not enabled",
                       val schedulerTrace: String = "unavailable: scheduler tracing is not enabled",
                       val policy: String = "native-startup-sample-v1")

    private val lock = Any()
    private val began = Instant.now()
    private val staging = scratch.resolve(".native-startup-${UUID.randomUUID()}")
    private val marker = staging.resolve("root.pid")
    private val enabled = mac && Files.isExecutable(sampleTool)
    val argv: List<String> = if (enabled) wrap(command, marker) else command
    private var closed = false
    private var active: Receipt? = null
    private var activeStartedNs = 0L
    @Volatile private var captures = 0
    private val seen = mutableSetOf<String>()
    private val monitor: Thread?

    init {
        Files.createDirectories(output)
        if (enabled) {
            Files.createDirectories(staging)
            monitor = thread(name = "native-startup-${binding.caseIndex}", isDaemon = true) {
                try { observe() }
                catch (_: InterruptedException) { /* The owning case ended or lost its lease. */ }
                catch (failure: Exception) {
                    publish(Receipt(binding, "observation-unavailable", diagnostic = failure.toString().take(2048)))
                } finally { runCatching { deleteTree(staging) } }
            }
        } else {
            monitor = null
            publish(Receipt(binding, if (mac) "sample-tool-unavailable" else "unsupported-platform"))
        }
    }

    private fun observe() {
        while (!Thread.currentThread().isInterrupted && remainingMs() > 0 && captures < MAX_CAPTURES) {
            val root = root()
            if (root != null) {
                root.descendants().use { children ->
                    children.limit(MAX_DESCENDANTS).forEach { child ->
                        if (captures >= MAX_CAPTURES || Thread.currentThread().isInterrupted) return@forEach
                        val target = target(root, child) ?: return@forEach
                        if (Instant.parse(target.startedAt).plusMillis(TRIGGER_MS).isAfter(Instant.now())) return@forEach
                        if (seen.add("${target.pid}:${target.startedAt}")) {
                            captures++
                            capture(target)
                        }
                    }
                }
            }
            Thread.sleep(POLL_MS)
        }
    }

    private fun root(): ProcessHandle? {
        if (!Files.isRegularFile(marker) || Files.size(marker) > 32) return null
        val text = Files.newInputStream(marker).use { String(it.readNBytes(33), Charsets.US_ASCII) }
        if (text.length > 32) return null
        val pid = text.trim().toLongOrNull() ?: return null
        val root = ProcessHandle.of(pid).orElse(null) ?: return null
        val info = root.info()
        val started = info.startInstant().orElse(null) ?: return null
        if (started.isBefore(began.minusSeconds(2)) || !root.isAlive) return null
        // A fresh private marker alone is not authority to sample an unrelated process.
        if (info.arguments().orElse(null)?.toList() != argv.drop(1)) return null
        return root
    }

    private fun target(root: ProcessHandle, child: ProcessHandle): Target? {
        val info = child.info()
        val executable = info.command().orElse(null) ?: return null
        val arguments = info.arguments().orElse(null)?.toList() ?: return null
        val path = Path.of(executable).toRealPath()
        if (!matches(path.toString(), arguments, worktree.toRealPath())) return null
        val start = info.startInstant().orElse(null) ?: return null
        val rootStart = root.info().startInstant().orElse(null) ?: return null
        val attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java)
        return Target(root.pid(), rootStart.toString(), child.pid(), start.toString(), path.toString(), arguments.single(),
            attributes.fileKey()?.toString(), attributes.size(), attributes.lastModifiedTime().toString())
    }

    private fun current(target: Target): ProcessHandle? {
        val root = root() ?: return null
        if (root.pid() != target.rootPid || root.info().startInstant().orElse(null)?.toString() != target.rootStartedAt) return null
        val child = ProcessHandle.of(target.pid).orElse(null) ?: return null
        val executable = child.info().command().orElse(null)?.let { Path.of(it).toRealPath().toString() }
        if (!child.isAlive || !sameProcess(target, child.info().startInstant().orElse(null)?.toString(),
                executable, child.info().arguments().orElse(null)?.toList())) return null
        val attributes = Files.readAttributes(Path.of(target.executable), java.nio.file.attribute.BasicFileAttributes::class.java)
        if (attributes.fileKey()?.toString() != target.fileKey || attributes.size() != target.executableBytes ||
            attributes.lastModifiedTime().toString() != target.executableModifiedAt) return null
        return root.descendants().use { descendants -> if (descendants.anyMatch { it.pid() == target.pid }) child else null }
    }

    private fun capture(target: Target) {
        val child = current(target) ?: return
        val started = System.nanoTime()
        val cpuBefore = child.info().totalCpuDuration().orElse(null)?.toNanos()
        val sample = staging.resolve("sample-$captures.txt")
        val stdout = staging.resolve("sample-$captures.out")
        val stderr = staging.resolve("sample-$captures.err")
        val initial = Receipt(binding, "sampling", target, cpuBeforeNs = cpuBefore)
        synchronized(lock) { if (closed) return; active = initial; activeStartedNs = started; write(initial) }
        var termination: ProcessTermination? = null
        var failure: String? = null
        try {
            supervisor.run(sampleCommand(sampleTool, target.pid, sample), worktree, emptyMap(), stdout, stderr,
                2, graceMs = 0, runRemainingMs = remainingMs().coerceAtMost(SAMPLE_LIMIT_MS),
                observeTermination = { termination = it }, remainingLeaseMs = {
                    if (Thread.currentThread().isInterrupted || current(target) == null) {
                        throw CancellationException("sample target ended or changed identity")
                    }
                    remainingMs().coerceAtMost(SAMPLE_LIMIT_MS)
                })
        } catch (error: Exception) { failure = error.toString().take(2048) }
        val after = current(target)
        val cpuAfter = after?.info()?.totalCpuDuration()?.orElse(null)?.toNanos()
        val valid = after != null && termination?.exitCode == 0 && Files.isRegularFile(sample) && Files.size(sample) in 1..MAX_BYTES
        synchronized(lock) {
            if (closed) return
            val name = snapshot(sample, "sample-$captures${if (valid) "" else ".partial"}.txt")
            val diagnostics = listOfNotNull(failure, boundedText(stderr), boundedText(stdout)).joinToString("\n").take(4096)
            write(initial.copy(outcome = if (valid) "captured" else "sample-unavailable",
                elapsedMs = (System.nanoTime() - started) / 1_000_000, cpuAfterNs = cpuAfter,
                exitCode = termination?.exitCode, signal = termination?.signal,
                timedOut = termination?.runTimedOut == true,
                cancelled = Thread.currentThread().isInterrupted || after == null,
                sample = name, sampleVerified = valid, diagnostic = diagnostics.takeIf { it.isNotEmpty() }))
            active = null
        }
    }

    private fun boundedText(path: Path): String? = path.takeIf(Files::isRegularFile)?.let {
        Files.newInputStream(it).use { stream -> String(stream.readNBytes(2048), Charsets.UTF_8) }
    }

    private fun snapshot(path: Path, name: String): String? {
        if (!Files.isRegularFile(path)) return null
        val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_BYTES.toInt()) }
        if (bytes.isEmpty()) return null
        Files.write(output.resolve(name), bytes)
        return name
    }

    private fun publish(receipt: Receipt) = synchronized(lock) { if (!closed) write(receipt) }
    private fun write(receipt: Receipt) {
        Files.writeString(output.resolve(if (receipt.target == null) "status.json" else "capture-$captures.json"), receiptsJson.encodeToString(receipt))
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            active?.let {
                val partial = runCatching { snapshot(staging.resolve("sample-$captures.txt"), "sample-$captures.partial.txt") }.getOrNull()
                write(it.copy(outcome = "capture-cancelled", cancelled = true, sample = partial,
                    elapsedMs = (System.nanoTime() - activeStartedNs) / 1_000_000))
            }
            if (enabled && !Files.exists(output.resolve("status.json"))) {
                write(Receipt(binding, if (captures == 0) "no-matching-query-observed" else "finished",
                    diagnostic = "captureAttempts=$captures"))
            }
        }
        // Closing the supervisor connection kills the sampler's own tree; it never signals the target.
        monitor?.interrupt()
    }

    companion object {
        private const val POLL_MS = 50L
        private const val TRIGGER_MS = 500L
        private const val SAMPLE_LIMIT_MS = 2000L
        private const val MAX_CAPTURES = 2
        private const val MAX_DESCENDANTS = 128L
        private const val MAX_BYTES = 1024L * 1024
        private val receiptsJson = Json { encodeDefaults = true }
        private const val WRAPPER = "printf '%s\\n' \"\$\$\" > \"\$1\"; shift; \"\$@\" & child=\$!; wait \"\$child\"; exit \$?"

        internal fun wrap(command: List<String>, marker: Path): List<String> =
            listOf("bash", "-c", WRAPPER, "lab-native-startup", marker.toString()) + command

        internal fun matches(executable: String, arguments: List<String>, worktree: Path): Boolean =
            Path.of(executable).normalize().let { it.isAbsolute && it.startsWith(worktree.normalize()) && it.fileName.toString() == "minizinc" } &&
                arguments.size == 1 && arguments.single() in setOf("--config-dirs", "--version")

        internal fun sameProcess(target: Target, startedAt: String?, executable: String?, arguments: List<String>?): Boolean =
            target.startedAt == startedAt && target.executable == executable && arguments == listOf(target.option)

        internal fun sampleCommand(tool: Path, pid: Long, output: Path): List<String> {
            require(pid > 0)
            // The file ceiling applies only to the sampler, not the workload it observes.
            return listOf("bash", "-c", "ulimit -f 1024 || exit 125; exec \"\$1\" \"\$2\" 1 10 -mayDie -file \"\$3\"",
                "lab-native-sample", tool.toString(), pid.toString(), output.toString())
        }
    }
}
