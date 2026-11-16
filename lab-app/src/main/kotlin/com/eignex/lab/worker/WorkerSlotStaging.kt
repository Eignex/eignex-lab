package com.eignex.lab.worker

import com.eignex.lab.domain.ResourceBudget
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Only independent private slots run here. Source acquisition stays on the caller that owns its cache pins. */
internal class WorkerSlotStaging(budget: ResourceBudget, slots: Int, private val checkRun: () -> Unit) : AutoCloseable {
    data class Slot(val source: String, val key: String, val number: Int)
    data class Staged(val paths: Map<String, Path>, val fresh: Int)
    internal val parallelism = minOf(budget.parallel, (budget.cpuMillis / 1000).coerceAtLeast(1), slots)
    init { require(slots > 0) }
    private val executor = Executors.newFixedThreadPool(parallelism) { work ->
        Thread(work, "worker-slot-staging").apply { isDaemon = true }
    }
    private val failure = AtomicReference<Throwable?>()
    private val closed = AtomicBoolean()
    private var restoreInterrupt = false // Only the orchestrator calls parallel/close.

    private fun active() {
        if (failure.get() != null || Thread.currentThread().isInterrupted) throw CancellationException("slot staging cancelled")
        checkRun()
    }

    private fun coordinatorActive() {
        if (Thread.interrupted()) {
            restoreInterrupt = true
            throw InterruptedException("slot staging interrupted")
        }
        failure.get()?.let { throw it }
        checkRun()
    }

    fun stage(slots: List<Slot>, reuse: (Slot, () -> Unit) -> Path?,
              source: (String, () -> Unit, (Path) -> Unit) -> Unit,
              copy: (Slot, Path, () -> Unit) -> Path): Staged {
        coordinatorActive()
        require(slots.map { it.key }.distinct().size == slots.size) { "duplicate private workspace slot" }
        val paths = linkedMapOf<String, Path>()
        val missing = mutableListOf<Slot>()
        parallel(slots) { slot -> reuse(slot, ::active) }.forEachIndexed { index, tree ->
            if (tree == null) missing += slots[index] else paths[slots[index].key] = tree
        }
        // Acquiring one source per group avoids duplicate builds and exclusive build-pin serialization per slot.
        // The callback cannot return (and release input/build/dependency pins) while a child still uses its source.
        for ((sha, group) in missing.groupBy { it.source }) source(sha, ::coordinatorActive) { tree ->
            parallel(group) { slot -> copy(slot, tree, ::active) }.forEachIndexed { index, copied ->
                paths[group[index].key] = copied
            }
        }
        coordinatorActive()
        return Staged(slots.associate { it.key to paths.getValue(it.key) }, missing.size)
    }

    private fun <T, R> parallel(items: List<T>, action: (T) -> R): List<R> {
        val completed = ExecutorCompletionService<Pair<Int, R>>(executor)
        val submitted = mutableListOf<Future<Pair<Int, R>>>()
        val results = HashMap<Int, R>()
        var next = 0
        var interrupted = false
        fun submit() {
            val index = next++
            submitted += completed.submit {
                try { active(); (index to action(items[index])).also { active() } }
                catch (error: Throwable) { failure.compareAndSet(null, error); throw error }
            }
        }
        try {
            check(!closed.get()) { "slot staging already closed" }
            repeat(minOf(parallelism, items.size)) { submit() }
            while (results.size < items.size) {
                coordinatorActive()
                val finished = try { completed.poll(50, TimeUnit.MILLISECONDS) }
                    catch (error: InterruptedException) { interrupted = true; throw error }
                    ?: continue
                val (index, value) = finished.get()
                results[index] = value
                if (next < items.size) submit()
            }
            return items.indices.map { results.getValue(it) }
        } catch (error: Throwable) {
            val cause = if (error is java.util.concurrent.ExecutionException) error.cause ?: error else error
            failure.compareAndSet(null, cause)
            submitted.forEach { it.cancel(true) }
            // A cancelled Future is already "done" even when its filesystem call has not returned. Join actual
            // executor termination before unwinding the source callback or allowing attempt/session cleanup.
            stop(interrupted)
            throw requireNotNull(failure.get())
        }
    }

    private fun stop(interrupted: Boolean = false) {
        restoreInterrupt = Thread.interrupted() || restoreInterrupt || interrupted
        if (!closed.compareAndSet(false, true)) return
        executor.shutdownNow()
        while (true) try {
            if (executor.awaitTermination(50, TimeUnit.MILLISECONDS)) break
        } catch (_: InterruptedException) { restoreInterrupt = true }
    }

    /** Join before starting consumers, but defer restoring interruption until enclosing input pins close too. */
    fun finish() {
        stop()
        if (restoreInterrupt) {
            val error = InterruptedException("slot staging interrupted while joining")
            failure.compareAndSet(null, error)
            throw requireNotNull(failure.get())
        }
    }

    override fun close() {
        stop()
        if (restoreInterrupt) Thread.currentThread().interrupt()
    }
}
