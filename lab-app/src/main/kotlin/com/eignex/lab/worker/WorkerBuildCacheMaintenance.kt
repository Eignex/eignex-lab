package com.eignex.lab.worker

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*

/** The build cache's background accounting and eviction sweep, which also drains detached cold-build garbage. */
internal class WorkerBuildCacheMaintenance(private val root: Path, private val maxBytes: Long,
                                           private val dependencyBytes: (Path) -> Long,
                                           private val startMaintenance: (Thread) -> Unit) {
    private val maintenanceRunning = java.util.concurrent.atomic.AtomicBoolean()
    val discardedBuilds = java.util.concurrent.ConcurrentLinkedQueue<Path>()
    @Serializable private data class DependencyUsage(val key: String, val modified: String, val bytes: Long)

    /** Foreground preparation never performs an unbounded legacy inventory scan. */
    fun requestMaintenance() {
        if (!maintenanceRunning.compareAndSet(false, true)) return
        val thread = Thread({
            try { WorkerCacheIo.background {
                // A previous sweep may still be throttled. Detached cold-build garbage must nevertheless
                // drain without delaying preparation, and remains discoverable after a worker crash.
                while (true) deleteCacheGarbage(discardedBuilds.poll() ?: break)
                maintain(wait = false)
            } }
            catch (_: java.io.IOException) { println("worker build cache cleanup deferred") }
            finally {
                synchronized(maintenanceRunning) {
                    maintenanceRunning.set(false)
                    if (discardedBuilds.isNotEmpty()) requestMaintenance()
                }
            }
        }, "worker-build-cleanup").apply { isDaemon = true }
        try { startMaintenance(thread) }
        catch (failure: Throwable) { maintenanceRunning.set(false); throw failure }
    }

    /** The sweep pin serializes accounting; the staging lock covers only snapshots and atomic renames.
     * Published manifests account immutable builds. Dependency homes are scanned once per dirty generation,
     * under their own pin, outside the staging lock. Active/unknown homes count conservatively.
     * Startup accounting yields disk priority to foreground staging, just like ordinary cache garbage. */
    fun maintain(at: Long = now(), wait: Boolean = true): Unit = WorkerCacheIo.background {
        // The request flag is reserved before Thread.start(), so a synchronous caller also drains
        // requests that have not been scheduled yet, before it concludes maintenance is finished.
        if (wait) check(awaitMaintenanceIdle(Long.MAX_VALUE))
        Files.createDirectories(root)
        val sweep = root.resolve("maintenance-sweep.lock")
        if (wait) buildCacheLocked(sweep, { null }, { Long.MAX_VALUE }) { maintainSweep(at) }
        else ProcessFilePin.tryLock(sweep)?.use { maintainSweep(at) }
    }

    /** After foreground users finish, wait until this instance has no pending filesystem work. */
    fun awaitMaintenanceIdle(timeoutMs: Long = 10_000): Boolean {
        require(timeoutMs >= 0)
        val started = System.nanoTime()
        val timeoutNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (synchronized(maintenanceRunning) { maintenanceRunning.get() }) {
            checkMaintenanceActive()
            if (System.nanoTime() - started >= timeoutNanos) return false
            Thread.sleep(10)
        }
        return true
    }

    private fun <T> maintenanceLock(use: () -> T): T = buildCacheLocked(root.resolve("maintenance.lock"), { null }, { Long.MAX_VALUE }, use = use)

    private fun maintainSweep(at: Long) {
        data class Snapshot(val builds: List<Path>, val dependencies: List<Path>, val garbage: List<Path>)
        val snapshot = maintenanceLock {
            val stamp = root.resolve("maintenance.timestamp")
            if (Files.exists(stamp) && at - Files.getLastModifiedTime(stamp).toMillis() < 60_000) return@maintenanceLock null
            Files.writeString(stamp, ""); Files.setLastModifiedTime(stamp, java.nio.file.attribute.FileTime.fromMillis(at))
            val garbage = WorkerBuildStaging(root).reapLocked(at).toMutableList()
            val builds = Files.list(root).use { paths -> paths.filter { SHA256.matches(it.fileName.toString()) }.toList() }
            val dependencies = root.resolve("dependencies").let { directory ->
                if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) emptyList() else Files.list(directory).use { it.toList() }
            }
            Files.list(root).use { paths -> garbage.addAll(paths.filter { it.fileName.toString().startsWith(".evict-") }.toList()) }
            Snapshot(builds, dependencies, garbage)
        } ?: return
        data class Candidate(val path: Path, val lock: Path, val bytes: Long, val used: Long, val key: String?, val usage: DependencyUsage? = null, val accounted: Boolean = true)
        val candidates = mutableListOf<Candidate>()
        val unknown = maxBytes.coerceAtLeast(1)
        fun attributes(path: Path) = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        for (path in snapshot.builds) {
            checkMaintenanceActive()
            if (!Files.isDirectory(path, NOFOLLOW_LINKS)) continue
            val manifest = path.resolve("manifest.json")
            val entry = runCatching { Json.decodeFromString<WorkerBuildCache.Entry>(Files.readString(manifest)) }.getOrNull()
            val bytes = runCatching {
                require(entry != null && entry.identity == path.fileName.toString())
                entry.files.fold(0L) { total, file -> require(file.bytes >= 0); Math.addExact(total, file.bytes) }
            }.getOrDefault(unknown)
            val used = runCatching { Files.getLastModifiedTime(manifest).toMillis() }.getOrDefault(0)
            candidates += Candidate(path, root.resolve("${path.fileName}.pin"), bytes, used, attributes(path).fileKey()?.toString())
        }
        var accountingIncomplete = false
        for (path in snapshot.dependencies) {
            checkMaintenanceActive()
            if (!Files.isDirectory(path, NOFOLLOW_LINKS)) continue
            val lock = root.resolve("dependencies-${path.fileName}.lock")
            var usage: DependencyUsage? = null
            var measuredBytes: Long? = null
            val owner = ProcessFilePin.tryLock(lock)
            if (owner == null) {
                // Older workers do not invalidate usage records themselves. Observing their live pin
                // makes the old size dirty too, so their released growth must be measured again.
                Files.deleteIfExists(usageFile(path)); accountingIncomplete = true
            }
            owner?.use {
                usage = knownDependencyUsage(path) ?: run {
                    val before = attributes(path)
                    val bytes = dependencyBytes(path)
                    measuredBytes = bytes
                    checkMaintenanceActive()
                    val after = attributes(path)
                    require(bytes >= 0 && before.fileKey() == after.fileKey() && before.lastModifiedTime() == after.lastModifiedTime()) { "dependency cache changed during accounting" }
                    after.fileKey()?.toString()?.let { key ->
                        DependencyUsage(key, after.lastModifiedTime().toString(), bytes).also { recordDependencyUsage(path, it) }
                    }
                }
            }
            val attrs = attributes(path)
            candidates += Candidate(path, lock, usage?.bytes ?: measuredBytes ?: 0, attrs.lastModifiedTime().toMillis(),
                attrs.fileKey()?.toString(), usage, accounted = usage != null || measuredBytes != null)
        }
        fun saturated(a: Long, b: Long) = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b
        val accounted = candidates.filter { it.accounted }
        var bytes = accounted.fold(0L) { total, candidate -> saturated(total, candidate.bytes) }
        val garbage = snapshot.garbage.toMutableList()
        // Unknown active homes cannot justify eviction of warm data within the known budget.
        // Still reclaim proven excess among accounted entries; reconcile unknown growth after release.
        if (accountingIncomplete) maintenanceLock { Files.deleteIfExists(root.resolve("maintenance.timestamp")) }
        for (candidate in accounted.sortedBy { it.used }) {
            checkMaintenanceActive()
            if (bytes <= maxBytes) break
            ProcessFilePin.tryLock(candidate.lock)?.use {
                maintenanceLock {
                    if (!Files.isDirectory(candidate.path, NOFOLLOW_LINKS)) return@maintenanceLock
                    if (attributes(candidate.path).fileKey()?.toString() != candidate.key) return@maintenanceLock
                    if (candidate.usage != null && knownDependencyUsage(candidate.path) != candidate.usage) return@maintenanceLock
                    val trash = root.resolve(".evict-${java.util.UUID.randomUUID()}")
                    Files.move(candidate.path, trash, ATOMIC_MOVE)
                    if (candidate.path.parent.fileName.toString() == "dependencies") Files.deleteIfExists(usageFile(candidate.path))
                    garbage.add(trash); bytes = (bytes - candidate.bytes).coerceAtLeast(0)
                }
            }
        }
        for (path in garbage.distinct()) {
            checkMaintenanceActive()
            deleteCacheGarbage(path)
        }
        if (garbage.isNotEmpty()) println("worker build cache cleanup: trees=${garbage.size}")
    }

    private fun usageFile(path: Path) = root.resolve("dependency-sizes/${path.fileName}.json")
    private fun knownDependencyUsage(path: Path): DependencyUsage? = runCatching {
        val usage = Json.decodeFromString<DependencyUsage>(Files.readString(usageFile(path)))
        val attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        usage.takeIf { it.bytes >= 0 && attrs.isDirectory && it.key == attrs.fileKey()?.toString() && it.modified == attrs.lastModifiedTime().toString() }
    }.getOrNull()
    private fun recordDependencyUsage(path: Path, usage: DependencyUsage) {
        val target = usageFile(path); Files.createDirectories(target.parent)
        target.writeAtomically(Json.encodeToString(usage), durable = false)
    }
}

private fun checkMaintenanceActive() {
    if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("cache cleanup cancelled")
}

internal fun dependencyTreeBytes(path: Path): Long = treeBytes(path, checkActive = ::checkMaintenanceActive)
