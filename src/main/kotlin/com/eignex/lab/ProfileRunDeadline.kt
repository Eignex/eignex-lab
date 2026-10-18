package com.eignex.lab

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** One local profile's active-time allowance, including its setup and worktree cleanup. */
internal class ProfileRunDeadline(
    private val durationMillis: Long,
    private val nanoTime: () -> Long = System::nanoTime,
    private val cancelled: () -> Boolean = { false },
) : AutoCloseable {
    private val startedAt = nanoTime()
    private val cleanupReserveMillis = minOf(30_000, durationMillis.coerceAtLeast(0) / 10)
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "profile-run-deadline").apply { isDaemon = true }
    }

    fun remainingMillis(cleanup: Boolean = false): Long {
        val elapsed = TimeUnit.NANOSECONDS.toMillis(nanoTime() - startedAt)
        val remaining = durationMillis - elapsed - if (cleanup) 0 else cleanupReserveMillis
        if (remaining <= 0) throw ProfileRunTimeout()
        return remaining
    }

    fun <T> work(action: () -> T): T = execute(false, action)

    fun <T> cleanup(action: () -> T): T = execute(true, action)

    private fun <T> execute(cleanup: Boolean, action: () -> T): T {
        remainingMillis(cleanup)
        val execution = Execution {
            if (!cleanup && cancelled()) throw CancellationException("Profile job cancelled")
            remainingMillis(cleanup)
        }
        val future = worker.submit<T> { within(execution) { checkActive(); action().also { checkActive() } } }
        var failure: Throwable? = null
        try {
            while (true) {
                execution.checkActive()
                try {
                    return future.get(minOf(remainingMillis(cleanup), 100), TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // Poll cancellation even while setup is blocked in native I/O.
                }
            }
        } catch (error: ExecutionException) {
            val cause = error.cause ?: error
            failure = cause
            throw cause
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            future.cancel(true)
            try {
                execution.close()
            } catch (cleanupFailure: Throwable) {
                if (failure == null) throw cleanupFailure else failure.addSuppressed(cleanupFailure)
            }
        }
    }

    override fun close() {
        worker.shutdownNow()
    }

    private class Execution(private val remainingMillis: () -> Long) : AutoCloseable {
        private data class Owned(val process: Process, val startedAt: java.time.Instant?)
        private val processes = mutableListOf<Owned>()
        private var closed = false

        @Synchronized
        fun checkActive() {
            if (closed) throw ProfileRunTimeout()
            remainingMillis()
        }

        @Synchronized
        fun start(builder: ProcessBuilder): Process {
            checkActive()
            return builder.start().also { processes.add(Owned(it, it.info().startInstant().orElse(null))) }
        }

        override fun close() {
            val owned = synchronized(this) {
                closed = true
                processes.toList()
            }
            // Capture descendants before killing their parent, including Gradle's single-use daemon.
            val descendants = owned.flatMap { it.process.descendants().use { children -> children.toList() } }
            val groups = owned.filter { entry ->
                val current = ProcessHandle.of(entry.process.pid())
                !current.isPresent || entry.startedAt != null &&
                    current.get().info().startInstant().orElse(null) == entry.startedAt
            }.map { "-${it.process.pid()}" }
            if (groups.isNotEmpty()) {
                val signal = ProcessBuilder(listOf("kill", "-KILL", "--") + groups)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                if (!signal.waitFor(1, TimeUnit.SECONDS)) signal.destroyForcibly()
            }
            descendants.asReversed().forEach { it.destroyForcibly() }
            for ((process) in owned.asReversed()) {
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    companion object {
        private val current = ThreadLocal<Execution?>()

        fun checkActive() {
            current.get()?.checkActive()
        }

        fun isActive(): Boolean = current.get() != null

        fun start(builder: ProcessBuilder, alreadyInSession: Boolean = false): Process {
            val execution = current.get() ?: return builder.start()
            if (!alreadyInSession) builder.command(inSession(*builder.command().toTypedArray()))
            return execution.start(builder)
        }

        fun <T> task(action: () -> T): Callable<T> {
            val execution = current.get()
            return Callable { within(execution, action) }
        }

        private fun <T> within(execution: Execution?, action: () -> T): T {
            val previous = current.get()
            current.set(execution)
            try {
                return action()
            } finally {
                current.set(previous)
            }
        }
    }
}

internal class ProfileRunTimeout : IllegalStateException("Profile whole-run deadline exhausted")
