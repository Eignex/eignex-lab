package com.eignex.lab

import com.eignex.lab.worker.BuildDiskBudget
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class BuildDiskBudgetTest {
    @Test fun `blocked disk inventory does not block compiler lease polling`() {
        val root = Files.createTempDirectory("build-disk-heartbeats")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var thread: Thread? = null
        val monitor = BuildDiskBudget(root, root, 10, available = { 2L * 1024 * 1024 * 1024 },
            inventory = { check -> entered.countDown(); release.await(); check(); 5L }, start = { thread = it; it.start() })
        val foreground = Executors.newSingleThreadExecutor()
        try {
            monitor.check()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            foreground.submit { repeat(10) { monitor.check() } }.get(1, TimeUnit.SECONDS)
            assertTrue(thread!!.isAlive, "lease polls returned while inventory was still blocked")
        } finally {
            monitor.close(); release.countDown(); thread?.join(5000); foreground.shutdownNow()
        }
    }

    @Test fun `asynchronous staging overflow is reported by the next lease poll`() {
        val root = Files.createTempDirectory("build-disk-overflow")
        var thread: Thread? = null
        val monitor = BuildDiskBudget(root, root, 10, available = { 2L * 1024 * 1024 * 1024 },
            inventory = { 11L }, start = { thread = it; it.start() })
        try {
            runCatching { monitor.check() } // A very fast scan may already have finished.
            thread!!.join(5000)
            val failure = assertFailsWith<IllegalArgumentException> { monitor.check() }
            assertEquals("build staging exceeds disk budget", failure.message)
        } finally { monitor.close() }
    }

    @Test fun `low free space prevents a new command before inventory starts`() {
        val root = Files.createTempDirectory("build-disk-held")
        val monitor = BuildDiskBudget(root, root, 10, available = { 1024 }, inventory = { error("must not start") })
        try {
            val failure = assertFailsWith<IllegalStateException> { monitor.check() }
            assertEquals("build disk held", failure.message)
        } finally { monitor.close() }
    }

    @Test fun `the real staging walk enforces its byte limit`() {
        val root = Files.createTempDirectory("build-disk-real-inventory")
        Files.writeString(root.resolve("output"), "too large")
        var thread: Thread? = null
        val monitor = BuildDiskBudget(root, root, 4, available = { 2L * 1024 * 1024 * 1024 },
            start = { thread = it; it.start() })
        try {
            runCatching { monitor.check() }
            thread!!.join(5000)
            assertFailsWith<IllegalArgumentException> { monitor.check() }
        } finally { monitor.close() }
    }
}
