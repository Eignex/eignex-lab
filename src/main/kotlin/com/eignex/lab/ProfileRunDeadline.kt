package com.eignex.lab

import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One local profile's active-time allowance, including its setup and worktree cleanup. When it runs out during a
 * stage, the stage's process groups get SIGTERM and up to [graceMillis] (at most a tenth of the allowance, reserved
 * from the work's share) to exit, so a profile's JVM can run its shutdown hooks and JFR can dump its recording, before
 * SIGKILL ends what is left. A cancel, or any other failure, kills them outright.
 */
internal class ProfileRunDeadline(
    private val durationMillis: Long,
    private val nanoTime: () -> Long = System::nanoTime,
    private val cancelled: () -> Boolean = { false },
    graceMillis: Long = 0,
) : AutoCloseable {
    private val startedAt = nanoTime()
    private val cleanupReserveMillis = minOf(30_000, durationMillis.coerceAtLeast(0) / 10)

    /** How long a stage's processes get between SIGTERM and SIGKILL when the allowance runs out. */
    val graceMillis = minOf(graceMillis.coerceAtLeast(0), durationMillis.coerceAtLeast(0) / 10)
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "profile-run-deadline").apply { isDaemon = true }
    }
    private val cancellationChecks = Executors.newSingleThreadExecutor { task ->
        Thread(task, "profile-cancellation").apply { isDaemon = true }
    }
    private val cancellationSeen = AtomicBoolean()

    fun remainingMillis(cleanup: Boolean = false): Long {
        val elapsed = TimeUnit.NANOSECONDS.toMillis(nanoTime() - startedAt)
        val remaining = durationMillis - elapsed - if (cleanup) 0 else cleanupReserveMillis + graceMillis
        if (remaining <= 0) throw ProfileRunTimeout()
        return remaining
    }

    fun <T> work(action: () -> T): T = execute(false, action)

    fun <T> cleanup(action: () -> T): T = execute(true, action)

    private fun <T> execute(cleanup: Boolean, action: () -> T): T {
        remainingMillis(cleanup)
        val execution = Execution(if (cleanup) 0 else graceMillis) {
            if (!cleanup && cancellationSeen.get()) throw CancellationException("Profile job cancelled")
            remainingMillis(cleanup)
        }
        val future = worker.submit<T> { within(execution) { checkActive(); action().also { checkActive() } } }
        var cancellation: Future<Boolean>? = if (cleanup) null else cancellationChecks.submit<Boolean> { cancelled() }
        var failure: Throwable? = null
        try {
            while (true) {
                cancellation?.takeIf { it.isDone }?.let { check ->
                    if (check.get()) cancellationSeen.set(true)
                    cancellation = cancellationChecks.submit<Boolean> { cancelled() }
                }
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
            cancellation?.cancel(true)
            future.cancel(true)
            try {
                execution.close(graceful = failure is ProfileRunTimeout)
            } catch (cleanupFailure: Throwable) {
                if (failure == null) throw cleanupFailure else failure.addSuppressed(cleanupFailure)
            }
        }
    }

    override fun close() {
        worker.shutdownNow()
        cancellationChecks.shutdownNow()
    }

    private class Execution(val graceMillis: Long, private val remainingMillis: () -> Long) : AutoCloseable {
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

        override fun close() = close(graceful = false)

        /** End every process the stage started: [graceful]ly, SIGTERM first and SIGKILL after [graceMillis]. */
        fun close(graceful: Boolean) {
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
            }.map { it.process.pid() }
            if (groups.isNotEmpty()) stopGroups(groups, if (graceful) graceMillis else 0)
            descendants.asReversed().forEach { it.destroyForcibly() }
            for ((process) in owned.asReversed()) {
                if (process.isAlive) process.destroyForcibly()
            }
            // destroyForcibly only requests termination. Do not release a stage while its helper can still run.
            val stoppedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var interrupted = Thread.interrupted()
            try {
                for ((process) in owned.asReversed()) {
                    while (process.isAlive) {
                        val remaining = stoppedBy - System.nanoTime()
                        check(remaining > 0) { "Profile stage process did not stop" }
                        try {
                            process.waitFor(remaining, TimeUnit.NANOSECONDS)
                        } catch (_: InterruptedException) {
                            interrupted = true
                        }
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }

    companion object {
        private val current = ThreadLocal<Execution?>()

        fun checkActive() {
            current.get()?.checkActive()
        }

        fun isActive(): Boolean = current.get() != null

        /** The grace the current stage's processes get between SIGTERM and SIGKILL when its allowance runs out. */
        fun graceMillis(): Long = current.get()?.graceMillis ?: 0

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

internal class ProfileRunTimeout : IllegalStateException("Whole-run deadline exhausted")
