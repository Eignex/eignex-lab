package com.eignex.lab.worker

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.nomad.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Evicted trees are durable tombstones. One process at a time deletes them, independently of job deadlines. */
internal class WorkerCacheCleanup(private val root: Path, executor: Executor? = null) {
    private val pending = AtomicInteger()
    private val executor = executor ?: Executors.newSingleThreadExecutor { work ->
        Thread(work, "worker-${root.fileName}-garbage").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    init { if (Files.isDirectory(root)) request() }

    fun request() {
        pending.incrementAndGet()
        try { executor.execute {
            try { WorkerCacheIo.background { reap() } }
            catch (_: java.io.IOException) { println("worker cache garbage cleanup deferred") }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            catch (_: java.util.concurrent.CancellationException) { /* Tombstones remain for another worker. */ }
            finally { pending.decrementAndGet() }
        } } catch (failure: Throwable) {
            pending.decrementAndGet()
            if (failure !is java.util.concurrent.RejectedExecutionException) throw failure
            println("worker cache garbage cleanup deferred")
        }
    }

    private fun reap() {
        while (Files.isDirectory(root)) {
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("cache cleanup interrupted")
            ProcessFilePin.tryLock(root.resolve("garbage.lock"))?.use {
                val garbage = Files.list(root).use { entries -> entries.filter {
                    val name = it.fileName.toString()
                    name.startsWith(".evict-") || name.startsWith(".build-trash-")
                }.toList() }
                for (path in garbage) deleteCacheGarbage(path)
                return
            }
            Thread.sleep(100)
        }
    }

    /** Tests and explicit maintenance drain reserved work before removing their cache root. */
    fun awaitIdle(timeoutMs: Long = 10_000): Boolean {
        require(timeoutMs >= 0)
        val started = System.nanoTime()
        while (pending.get() > 0) {
            if (System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(timeoutMs)) return false
            Thread.sleep(10)
        }
        return true
    }
}

/** macOS disk policy is thread-scoped: garbage must yield without lowering the job's I/O priority. */
internal object WorkerCacheIo {
    private data class Native(val get: java.lang.invoke.MethodHandle, val set: java.lang.invoke.MethodHandle)
    private val native by lazy {
        if (!System.getProperty("os.name").lowercase().contains("mac")) null else runCatching {
            val linker = java.lang.foreign.Linker.nativeLinker()
            val integer = java.lang.foreign.ValueLayout.JAVA_INT
            Native(linker.downcallHandle(linker.defaultLookup().find("getiopolicy_np").orElseThrow(),
                java.lang.foreign.FunctionDescriptor.of(integer, integer, integer)),
                linker.downcallHandle(linker.defaultLookup().find("setiopolicy_np").orElseThrow(),
                    java.lang.foreign.FunctionDescriptor.of(integer, integer, integer, integer)))
        }.getOrNull()
    }
    internal fun currentPolicy(): Int? = native?.get?.invoke(0, 1) as Int?
    fun <T> background(action: () -> T): T {
        val api = native ?: return action()
        val previous = api.get.invoke(0, 1) as Int
        // IOPOL_TYPE_DISK, IOPOL_SCOPE_THREAD, IOPOL_THROTTLE (Darwin sys/resource.h).
        if (previous < 0 || api.set.invoke(0, 1, 3) as Int != 0) return action()
        try { return action() } finally { api.set.invoke(0, 1, previous) }
    }
}

/** Delete [root] and everything under it without following symbolic links, which are removed as links. */
internal fun deleteCacheGarbage(root: Path) {
    fun active() {
        if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("tree cleanup interrupted")
    }
    active()
    Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
            active(); return java.nio.file.FileVisitResult.CONTINUE
        }
        override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
            active(); Files.deleteIfExists(file); return java.nio.file.FileVisitResult.CONTINUE
        }
        override fun visitFileFailed(file: Path, failure: java.io.IOException): java.nio.file.FileVisitResult {
            active()
            if (failure !is java.nio.file.NoSuchFileException) throw failure
            return java.nio.file.FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(dir: Path, failure: java.io.IOException?): java.nio.file.FileVisitResult {
            active()
            if (failure != null && failure !is java.nio.file.NoSuchFileException) throw failure
            Files.deleteIfExists(dir); return java.nio.file.FileVisitResult.CONTINUE
        }
    })
}

