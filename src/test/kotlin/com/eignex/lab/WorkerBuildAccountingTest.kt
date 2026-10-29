package com.eignex.lab

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class WorkerBuildAccountingTest {
    @Test fun `legacy dependency accounting never holds the staging lock and unchanged homes skip recursive scans`() {
        val root = Files.createTempDirectory("dependency-accounting")
        val home = Files.createDirectories(root.resolve("dependencies/${"a".repeat(64)}"))
        Files.writeString(home.resolve("dependency"), "ten bytes!")
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val scans = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val cache = WorkerBuildCache(root, ProcessSupervisor(root.resolve("unused")), dependencyBytes = {
                scans.incrementAndGet(); entered.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
                10L
            })
            val at = now()
            val cleanup = executor.submit { cache.maintain(at) }
            assertTrue(entered.await(5, TimeUnit.SECONDS), "legacy accounting did not start")
            val storage = WorkerBuildStaging(root)
            val lease = executor.submit<WorkerBuildStaging.Lease> { storage.create {} }.get(2, TimeUnit.SECONDS)
            assertFalse(cleanup.isDone, "accounting must still be blocked while staging proceeds")
            assertTrue(Files.isDirectory(lease.directory))
            storage.close(lease)
            release.countDown(); cleanup.get(5, TimeUnit.SECONDS)
            assertEquals(1, scans.get())
            assertTrue(Files.isRegularFile(root.resolve("dependency-sizes/${home.fileName}.json")))
            cache.maintain(at + 61_000)
            assertEquals(1, scans.get(), "unchanged legacy home must use its byte record")
        } finally { release.countDown(); executor.shutdownNow(); deleteTree(root) }
    }

    @Test fun `failed dependency writers invalidate size records before mutation and budget eviction uses refreshed bytes`() {
        val root = Files.createTempDirectory("dependency-dirty")
        try {
            val key = "b".repeat(64)
            val home = Files.createDirectories(root.resolve("dependencies/$key"))
            Files.writeString(home.resolve("old"), "small")
            val cache = WorkerBuildCache(root, ProcessSupervisor(root.resolve("unused")), maxBytes = 10)
            cache.maintain()
            val record = root.resolve("dependency-sizes/$key.json")
            assertTrue(Files.exists(record))
            assertFailsWith<IllegalStateException> {
                cache.dependencyHome(key, { null }, { 10_000 }) { path ->
                    assertFalse(Files.exists(record), "size must be dirty before Gradle enters")
                    Files.createDirectories(path.resolve("nested"))
                    Files.writeString(path.resolve("nested/new"), "much larger than budget")
                    cache.maintain(now() + 120_000)
                    assertTrue(Files.exists(path), "an active Gradle home cannot be evicted")
                    error("failed compiler")
                }
            }
            cache.maintain(now() + 240_000)
            assertFalse(Files.exists(home), "failed build's dependency growth must count toward eviction")
            assertFalse(Files.exists(record))
        } finally { deleteTree(root) }
    }

    @Test fun `published manifests account build bytes without scanning build file contents`() {
        val root = Files.createTempDirectory("build-accounting-manifest")
        try {
            val key = "c".repeat(64); val build = Files.createDirectory(root.resolve(key))
            Files.createDirectory(build.resolve("tree")); Files.writeString(build.resolve("tree/data"), "bytes")
            Files.writeString(build.resolve("manifest.json"), """{"identity":"$key","files":[{"path":"data","sha256":"${"d".repeat(64)}","bytes":5}]}""")
            val cache = WorkerBuildCache(root, ProcessSupervisor(root.resolve("unused")), maxBytes = 4,
                dependencyBytes = { error("published build must use its manifest") })
            ProcessFilePin.tryLock(root.resolve("$key.pin"))!!.use {
                cache.maintain(); assertTrue(Files.isDirectory(build))
            }
            cache.maintain(now() + 61_000)
            assertFalse(Files.exists(build))
        } finally { deleteTree(root) }
    }
    @Test fun `foreign dirty dependency pin prevents eviction until the writer releases it`() {
        val root = Files.createTempDirectory("foreign-dependency-accounting")
        val key = "e".repeat(64)
        val home = Files.createDirectories(root.resolve("dependencies/$key"))
        Files.writeString(home.resolve("old"), "small")
        Files.createDirectory(home.resolve("nested"))
        val homeModified = Files.getLastModifiedTime(home)
        val cache = WorkerBuildCache(root, ProcessSupervisor(root.resolve("unused")), maxBytes = 10)
        cache.maintain()
        val record = root.resolve("dependency-sizes/$key.json")
        assertTrue(Files.exists(record))
        val process = ProcessBuilder("python3", "-c", """
            import fcntl, pathlib, sys
            with open(sys.argv[1], 'a') as pin:
                fcntl.lockf(pin, fcntl.LOCK_EX)
                pathlib.Path(sys.argv[3]).write_text('larger than the byte budget')
                print('ready', flush=True)
                sys.stdin.readline()
        """.trimIndent(), root.resolve("dependencies-$key.lock").toString(),
            root.resolve("dependency-sizes/$key.json").toString(), home.resolve("nested/new").toString()).start()
        try {
            assertEquals("ready", process.inputStream.bufferedReader().readLine())
            assertEquals(homeModified, Files.getLastModifiedTime(home), "nested growth leaves the root stamp unchanged")
            cache.maintain(now() + 61_000)
            assertTrue(Files.isDirectory(home), "foreign active dependency home must remain pinned")
            assertFalse(Files.exists(record), "observed legacy writer must invalidate its prior size record")
            val storage = WorkerBuildStaging(root)
            val lease = storage.create {}; storage.close(lease)
            process.outputStream.write(10); process.outputStream.flush()
            assertEquals(0, process.waitFor())
            cache.maintain(now() + 122_000)
            assertFalse(Files.exists(home))
        } finally { process.destroyForcibly(); deleteTree(root) }
    }

    @Test fun `cancelled accounting releases sweep and dependency pins without publishing a partial size`() {
        val root = Files.createTempDirectory("cancelled-dependency-accounting")
        val key = "f".repeat(64)
        Files.createDirectories(root.resolve("dependencies/$key"))
        val entered = CountDownLatch(1); val stopped = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val cache = WorkerBuildCache(root, ProcessSupervisor(root.resolve("unused")), dependencyBytes = {
                entered.countDown(); CountDownLatch(1).await(10, TimeUnit.SECONDS); 0L
            })
            val future = executor.submit { try { cache.maintain() } finally { stopped.countDown() } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            future.cancel(true); assertTrue(stopped.await(5, TimeUnit.SECONDS))
            assertFalse(Files.exists(root.resolve("dependency-sizes/$key.json")))
            ProcessFilePin.tryLock(root.resolve("maintenance-sweep.lock"))!!.close()
            ProcessFilePin.tryLock(root.resolve("dependencies-$key.lock"))!!.close()
            val storage = WorkerBuildStaging(root); val lease = storage.create {}; storage.close(lease)
        } finally { executor.shutdownNow(); deleteTree(root) }
    }

}
