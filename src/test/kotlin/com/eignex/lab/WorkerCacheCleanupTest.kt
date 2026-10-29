package com.eignex.lab

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WorkerCacheCleanupTest {
    @Test fun `cold input publication does not wait for unrelated garbage deletion`() {
        val root = Files.createTempDirectory("input-publication-garbage")
        val queued = mutableListOf<Runnable>()
        val cacheRoot = Files.createDirectory(root.resolve("cache"))
        val garbage = Files.createDirectory(cacheRoot.resolve(".evict-old"))
        Files.writeString(garbage.resolve("data"), "old")
        val source = Files.createDirectory(root.resolve("source")); Files.writeString(source.resolve("data"), "new")
        val identity = WorkerBundle("a".repeat(64), listOf(InputFile("data", ExecutionStore.digest("new"), 3)),
            Repositories.DEFAULT, "a".repeat(40), "linux", "amd64", "fixture")
        val zip = root.resolve("input.zip"); val bundle = archive(source, zip, identity) {}
        val cache = WorkerInputCache(cacheRoot, maxBytes = 0, cleanupExecutor = Executor { queued += it })
        try {
            cache.use(bundle, {}, { Files.newInputStream(zip) }) { tree ->
                assertEquals("new", Files.readString(tree.resolve("data")))
                assertTrue(Files.exists(garbage), "callback must not wait for old cache deletion")
            }
            assertFalse(cache.awaitCleanupIdle(0), "reserved but not started cleanup must remain pending")
            while (queued.isNotEmpty()) queued.removeAt(0).run()
            assertTrue(cache.awaitCleanupIdle(0)); assertFalse(Files.exists(garbage))
            cache.maintain()
            assertFalse(Files.exists(cacheRoot.resolve("entries/${inputCacheKey(bundle)}")), "quota eviction must still hide unused entries immediately")
        } finally {
            while (queued.isNotEmpty()) queued.removeAt(0).run()
            deleteTree(root)
        }
    }

    @Test fun `cold archive publication does not wait for unrelated garbage deletion`() {
        val root = Files.createTempDirectory("archive-publication-garbage")
        val queued = mutableListOf<Runnable>()
        val cacheRoot = Files.createDirectory(root.resolve("cache"))
        val garbage = Files.createDirectory(cacheRoot.resolve(".evict-old")); Files.writeString(garbage.resolve("data"), "old")
        val source = Files.createDirectory(root.resolve("source")); Files.writeString(source.resolve("data"), "new")
        val identity = WorkerBundle("a".repeat(64), listOf(InputFile("data", ExecutionStore.digest("new"), 3)),
            Repositories.DEFAULT, "a".repeat(40), "linux", "amd64", "fixture")
        val cache = WorkerArchiveCache(cacheRoot, maxBytes = 0, cleanupExecutor = Executor { queued += it })
        try {
            cache.use(source, identity, false, {}) { zip, bundle ->
                assertEquals(bundle.sha256, hashFile(zip)); assertTrue(Files.exists(garbage))
            }
            assertFalse(cache.awaitCleanupIdle(0))
            while (queued.isNotEmpty()) queued.removeAt(0).run()
            assertTrue(cache.awaitCleanupIdle(0)); assertFalse(Files.exists(garbage))
        } finally {
            while (queued.isNotEmpty()) queued.removeAt(0).run()
            deleteTree(root)
        }
    }

    @Test fun `another cleanup owner never blocks the publishing thread`() {
        val root = Files.createTempDirectory("serialized-cache-garbage")
        val garbage = Files.createDirectory(root.resolve(".evict-old")); Files.writeString(garbage.resolve("data"), "old")
        val pool = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        try {
            ProcessFilePin.tryLock(root.resolve("garbage.lock"))!!.use {
                val waiting = WorkerCacheCleanup(root, Executor { work -> pool.execute { entered.countDown(); work.run() } })
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertFalse(waiting.awaitIdle(0)); assertTrue(Files.exists(garbage))
                // Metadata ownership remains independent of the deletion owner.
                ProcessFilePin.tryLock(root.resolve("maintenance.lock"))!!.close()
            }
            pool.shutdown(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
            assertFalse(Files.exists(garbage))
        } finally { pool.shutdownNow(); deleteTree(root) }
    }

    @Test fun `concurrent tree deletion tolerates disappearing entries and preserves link targets`() {
        val parent = Files.createTempDirectory("concurrent-tree-deletion")
        val outside = Files.writeString(parent.resolve("outside"), "keep")
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(4) { pass ->
                val root = Files.createDirectory(parent.resolve("tree-$pass"))
                repeat(128) { n -> Files.writeString(Files.createDirectories(root.resolve("d${n / 16}")).resolve("f$n"), "data") }
                Files.createSymbolicLink(root.resolve("link"), outside)
                val start = CountDownLatch(1)
                val jobs = (0..1).map { pool.submit { start.await(); deleteTree(root) } }
                start.countDown(); jobs.forEach { it.get(5, TimeUnit.SECONDS) }
                assertFalse(Files.exists(root)); assertEquals("keep", Files.readString(outside))
            }
        } finally { pool.shutdownNow(); deleteTree(parent) }
    }
    @Test fun `Mac garbage disk policy is restored after success and failure`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(CliMeasurements.localMacOs)
        val original = assertNotNull(WorkerCacheIo.currentPolicy())
        WorkerCacheIo.background { assertEquals(3, WorkerCacheIo.currentPolicy()) }
        assertEquals(original, WorkerCacheIo.currentPolicy())
        assertFailsWith<IllegalStateException> { WorkerCacheIo.background { error("cleanup failed") } }
        assertEquals(original, WorkerCacheIo.currentPolicy())
    }

}
