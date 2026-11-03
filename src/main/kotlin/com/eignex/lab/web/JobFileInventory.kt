package com.eignex.lab.web

import com.eignex.lab.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.*

/** Complete inventories are built off request threads, including their filesystem validation and JSON encoding. */
internal class JobFileInventory(
    private val validate: (Long) -> Key,
    private val read: (Key) -> List<FileEntry>,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxJobs: Int = 16,
    private val maxNames: Int = 131072,
    private val maxBytes: Long = 64L * 1024 * 1024,
    private val maxPending: Int = 8,
    private val parallel: Int = 2,
    private val activeAgeMs: Long = 5_000,
    private val endedAgeMs: Long = 30_000,
    private val retryMs: Long = 2_000,
    private val idleMs: Long = 300_000,
    private val completed: () -> Unit = {},
) : AutoCloseable {
    data class Key(val job: Long, val version: Int, val status: Status, val trimmed: Boolean,
                   val logs: List<JobLogStamp> = emptyList())
    data class Snapshot(val key: Key, val at: Long, val files: List<FileEntry>, val json: String) {
        val charge: Long = json.length * 2L + files.sumOf { 192L + 2L * (it.path.length + (it.sha256?.length ?: 0)) }
    }
    private class Entry(var requested: Long, var snapshot: Snapshot? = null, var failed: Long? = null, var failure: ApiError? = null)
    private data class Task(val future: CompletableFuture<Snapshot>, var completedAt: Long? = null,
                            val completion: CompletableDeferred<Snapshot> = CompletableDeferred())
    private val entries = LinkedHashMap<Long, Entry>(16, .75f, true)
    private val tasks = mutableMapOf<Long, Task>()
    private val executor = ThreadPoolExecutor(parallel, parallel, 0, TimeUnit.SECONDS, ArrayBlockingQueue(maxPending),
        { action -> Thread(action, "job-file-inventory").apply { isDaemon = true } })
    private var closed = false
    private var names = 0
    private var bytes = 0L

    init {
        require(maxJobs > 0 && maxNames > 0 && maxBytes > 0 && parallel > 0 && maxPending >= parallel)
        require(activeAgeMs > 0 && endedAgeMs > 0 && retryMs > 0 && idleMs > 0)
    }

    /** A short bounded await never substitutes an empty or partial array for an inventory still being prepared. */
    fun get(job: Long, waitMs: Long = 1_000): Snapshot? {
        require(waitMs in 0..1_000)
        val task = request(job) ?: return null
        return try {
            task.future.get(waitMs, TimeUnit.MILLISECONDS).also { delivered(job, task) }
        } catch (_: TimeoutException) { null }
        catch (_: ExecutionException) { null }
        catch (_: CancellationException) { null }
    }

    /** Explicit inventory fetches can wait on this request's fresh validation without occupying an IO thread. */
    suspend fun await(job: Long, waitMs: Long): Snapshot? {
        require(waitMs in 1..30_000)
        val task = request(job) ?: return null
        return try {
            withTimeoutOrNull(waitMs) { task.completion.await() }?.also { delivered(job, task) }
        } catch (_: Throwable) {
            // A timed-out/disconnected caller never cancels shared work; shutdown/failure remains unavailable.
            currentCoroutineContext().ensureActive()
            null
        }
    }

    private fun delivered(job: Long, task: Task) = synchronized(entries) { tasks.remove(job, task) }

    private fun request(job: Long): Task? {
        return synchronized(entries) {
            if (closed) return null
            val now = clock()
            removeEntries { _, entry -> now - entry.requested > idleMs }
            // Oversized results have one bounded handoff slot instead of entering the retained snapshot cache.
            tasks.entries.removeIf { it.value.completedAt?.let { at -> now - at > endedAgeMs } == true }
            val entry = entries.getOrPut(job) { Entry(now) }.also { it.requested = now }
            while (entries.size > maxJobs) removeEntry(entries.keys.first())
            val prior = tasks[job]
            if (prior != null && !prior.future.isDone) return@synchronized prior
            // A completed oversized handoff still needs current trim/version/volume validation on the next poll.
            val handoff = prior?.future?.getNow(null)
            if (prior != null) tasks.remove(job, prior)
            if (entry.failed?.let { now - it < retryMs } == true || tasks.size >= maxPending) return null
            val future = CompletableFuture<Snapshot>()
            val created = Task(future)
            tasks[job] = created
            try {
                executor.execute {
                    try {
                        val key = validate(job)
                        val previous = synchronized(entries) { entry.snapshot ?: handoff }
                        val maximum = if (key.status.isTerminal) endedAgeMs else activeAgeMs
                        val snapshot = if (previous != null && previous.key == key && clock() - previous.at <= maximum) previous else {
                            val files = read(key)
                            check(validate(job) == key) { "file inventory changed during refresh" }
                            Snapshot(key, clock(), files, Json.encodeToString(files))
                        }
                        synchronized(entries) {
                            if (entries[job] === entry && !closed) {
                                entry.failed = null
                                entry.failure = null
                                if (snapshot.files.size <= maxNames && snapshot.charge <= maxBytes) {
                                    setSnapshot(entry, snapshot)
                                    while (names > maxNames || bytes > maxBytes) removeEntry(entries.keys.first())
                                }
                            }
                        }
                        synchronized(entries) {
                            created.completedAt = clock()
                            future.complete(snapshot)
                            created.completion.complete(snapshot)
                            if (entry.snapshot === snapshot) tasks.remove(job, created)
                        }
                    } catch (failure: Throwable) {
                        synchronized(entries) {
                            if (entries[job] === entry) { entry.failed = clock(); entry.failure = failure as? ApiError }
                            tasks.remove(job, created)
                        }
                        future.completeExceptionally(failure)
                        created.completion.completeExceptionally(failure)
                    } finally { completed() }
                }
            } catch (_: RejectedExecutionException) { tasks.remove(job, created); return null }
            created
        }
    }

    fun age(snapshot: Snapshot): Long = (clock() - snapshot.at).coerceAtLeast(0)

    fun failure(job: Long): ApiError? = synchronized(entries) { entries[job]?.failure }

    private fun setSnapshot(entry: Entry, snapshot: Snapshot) {
        entry.snapshot?.let { names -= it.files.size; bytes -= it.charge }
        entry.snapshot = snapshot
        names += snapshot.files.size; bytes += snapshot.charge
    }

    private fun removeEntry(job: Long) {
        entries.remove(job)?.snapshot?.let { names -= it.files.size; bytes -= it.charge }
    }

    private fun removeEntries(predicate: (Long, Entry) -> Boolean) {
        entries.filter { predicate(it.key, it.value) }.keys.toList().forEach(::removeEntry)
    }

    override fun close() {
        synchronized(entries) {
            closed = true
            tasks.values.forEach { it.future.cancel(true); it.completion.cancel() }
            tasks.clear(); entries.clear(); names = 0; bytes = 0
        }
        executor.shutdownNow()
    }
}
