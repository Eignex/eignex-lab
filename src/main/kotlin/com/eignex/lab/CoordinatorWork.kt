package com.eignex.lab

import java.util.concurrent.*

/** Bounded work with no executor backlog. Reconciliation polls results without waiting for disk or subprocesses. */
internal class CoordinatorWork<K, V : Any>(name: String, parallelism: Int = 2) : AutoCloseable {
    private val tasks = mutableMapOf<K, FutureTask<V>>()
    private val executor = ThreadPoolExecutor(parallelism, parallelism, 0, TimeUnit.SECONDS, SynchronousQueue(),
        { action -> Thread(action, name).apply { isDaemon = true } })

    @Synchronized
    fun poll(key: K, work: () -> V): V? {
        val task = tasks[key] ?: start(key, work, false) ?: return null
        if (!task.isDone) return null
        tasks.remove(key, task)
        return try { task.get() } catch (failure: ExecutionException) { throw failure.cause ?: failure }
    }

    /** Best-effort diagnostic writes coalesce by key and do not retain unconsumed completion results. */
    @Synchronized
    fun submit(key: K, work: () -> V): Boolean = key !in tasks && start(key, work, true) != null

    private fun start(key: K, work: () -> V, forget: Boolean): FutureTask<V>? {
        val task = object : FutureTask<V>(Callable(work)) {
            override fun done() {
                if (!forget) return
                try { get() } catch (failure: Exception) {
                    System.err.println("coordinator background work: ${(failure.cause ?: failure).javaClass.simpleName}")
                } finally { synchronized(this@CoordinatorWork) { tasks.remove(key, this) } }
            }
        }
        tasks[key] = task
        return try { executor.execute(task); task } catch (_: RejectedExecutionException) { tasks.remove(key, task); null }
    }

    @Synchronized
    fun retain(keys: Set<K>) { tasks.entries.removeIf { it.key !in keys && it.value.isDone } }

    override fun close() { executor.shutdownNow() }
}
