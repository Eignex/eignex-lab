package com.eignex.lab

import com.eignex.lab.util.*
import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.execution.*
import com.eignex.lab.worker.*
import com.eignex.lab.nomad.*
import com.eignex.lab.web.*
import com.eignex.lab.cli.*

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.*

class SharedInputCacheTest {
    @Test fun `unchanged idle views reuse inode inventories and a touched view is inventoried again`() {
        val root = Files.createTempDirectory("input-inventory-reuse")
        val view = Files.createDirectories(root.resolve("views/${ExecutionStore.digest("cached")}"))
        Files.writeString(view.resolve("model"), "model")
        var scans = 0
        val cache = SharedInputCache(root, beforeInventory = { scans++ })
        cache.maintain(force = true)
        cache.maintain(force = true)
        assertEquals(1, scans, "unchanged views must not be recursively scanned again")
        Files.writeString(view.resolve("other"), "other")
        Files.setLastModifiedTime(view, FileTime.fromMillis(now() + 2000))
        cache.maintain(force = true)
        assertEquals(2, scans)
        SharedInputCache(root, maxBytes = 0).maintain(force = true)
        assertFalse(Files.exists(view), "cached inventories still enforce eviction")
    }

    @Test fun `slow cache inventory never blocks acquiring or releasing a reader`() {
        val root = Files.createTempDirectory("input-background-inventory")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var maintenance: Thread? = null
        val cache = SharedInputCache(root, maxBytes = 0, startMaintenance = {
            maintenance = it; it.start()
        }, beforeInventory = { entered.countDown(); release.await() })
        val view = Files.createDirectories(root.resolve("views/${ExecutionStore.digest("active")}"))
        Files.writeString(view.resolve("model"), "model")
        val first = cache.pin(view)
        val foreground = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            foreground.submit {
                cache.pin(view).use { assertEquals("model", Files.readString(view.resolve("model"))) }
                first.close()
            }.get(1, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(maintenance!!.isAlive, "foreground finished while inventory was still blocked")
        } finally {
            release.countDown(); first.close(); maintenance?.join(5000)
            foreground.shutdownNow()
        }
        assertFalse(maintenance!!.isAlive)
        cache.maintain(force = true)
        assertFalse(Files.exists(view), "the asynchronous path must still enforce the cache quota")
    }

    @Test fun `a reader acquired after inventory discovery remains protected from eviction`() {
        val root = Files.createTempDirectory("input-concurrent-pin")
        val view = Files.createDirectories(root.resolve("views/${ExecutionStore.digest("late-reader")}"))
        Files.writeString(view.resolve("model"), "model")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val cache = SharedInputCache(root, maxBytes = 0, beforeInventory = { entered.countDown(); release.await() })
        val sweep = Thread { cache.maintain(force = true) }.apply { start() }
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            cache.pin(view).use {
                release.countDown(); sweep.join(5000)
                assertFalse(sweep.isAlive)
                assertEquals("model", Files.readString(view.resolve("model")))
            }
        } finally { release.countDown(); sweep.join(5000) }
        cache.maintain(force = true)
        assertFalse(Files.exists(view))
    }

    @Test fun `evicting an old blob and view stops when enough space has been reclaimed`() {
        val root = Files.createTempDirectory("input-blob-lru")
        val blobs = Files.createDirectories(root.resolve("blobs"))
        val at = now()
        val views = listOf("old", "recent").mapIndexed { index, name ->
            val sha = ExecutionStore.digest(name)
            val blob = Files.writeString(blobs.resolve(sha), name)
            val view = Files.createDirectories(root.resolve("views/$sha"))
            Files.createLink(view.resolve("model"), blob)
            Files.setLastModifiedTime(view, FileTime.fromMillis(at - (1 - index) * 1000))
            view
        }
        SharedInputCache(root, maxBytes = 6).maintain(at, force = true)
        assertFalse(Files.exists(views.first()))
        assertEquals("recent", Files.readString(views.last().resolve("model")))
        assertTrue(Files.exists(blobs.resolve(ExecutionStore.digest("recent"))))
    }

    @Test fun `old transfers are removed only after their writer releases its blob lock`() {
        val root = Files.createTempDirectory("input-transfer-retention")
        val blobs = Files.createDirectories(root.resolve("blobs"))
        val sha = ExecutionStore.digest("model")
        val partial = Files.writeString(blobs.resolve(".$sha-input-orphan.partial"), "partial")
        val at = now()
        Files.setLastModifiedTime(partial, FileTime.fromMillis(at - 48L * 60 * 60 * 1000))
        val cache = SharedInputCache(root)
        checkNotNull(ProcessFilePin.tryLock(blobs.resolve("$sha.lock"))).use {
            cache.maintain(at, force = true)
            assertTrue(Files.exists(partial))
            assertNull(ProcessFilePin.tryLock(blobs.resolve("$sha.lock")), "cleanup must retain the writer's ownership")
        }
        cache.maintain(at, force = true)
        assertFalse(Files.exists(partial))
    }

    @Test fun `all concurrent readers remain pinned while idle views are evicted`() {
        val root = Files.createTempDirectory("input-retention")
        val cache = SharedInputCache(root, maxBytes = 0)
        val view = Files.createDirectories(root.resolve("views/${ExecutionStore.digest("active")}"))
        Files.writeString(view.resolve("model"), "model")
        val first = cache.pin(view); val second = cache.pin(view)
        try {
            first.close()
            cache.maintain(force = true)
            assertEquals("model", Files.readString(view.resolve("model")))
        } finally { second.close() }
        cache.maintain(force = true)
        assertFalse(Files.exists(view))
    }

    @Test fun `hard links count once and eviction preserves the original consumer corpus`() {
        val consumer = Files.createTempDirectory("consumer-inputs")
        val original = Files.writeString(consumer.resolve("original"), "12345")
        val root = consumer.resolve(".lab-inputs")
        val blobs = Files.createDirectories(root.resolve("blobs"))
        val blob = Files.createLink(blobs.resolve(ExecutionStore.digest("12345")), original)
        val views = listOf("first", "second").map { name ->
            Files.createDirectories(root.resolve("views/${ExecutionStore.digest(name)}")).also { Files.createLink(it.resolve("model"), blob) }
        }
        SharedInputCache(root, maxBytes = 5).maintain(force = true)
        assertTrue(views.all { Files.exists(it.resolve("model")) })
        SharedInputCache(root, maxBytes = 0).maintain(force = true)
        assertFalse(Files.exists(blob))
        assertTrue(views.none(Files::exists))
        assertEquals("12345", Files.readString(original))
    }

    @Test fun `least recently used idle view is removed before the recent one`() {
        val root = Files.createTempDirectory("input-lru")
        val old = Files.createDirectories(root.resolve("views/${ExecutionStore.digest("old")}"))
        val recent = Files.createDirectories(root.resolve("views/${ExecutionStore.digest("recent")}"))
        Files.writeString(old.resolve("model"), "old"); Files.writeString(recent.resolve("model"), "recent")
        val at = now()
        Files.setLastModifiedTime(old, FileTime.fromMillis(at - 1000))
        Files.setLastModifiedTime(recent, FileTime.fromMillis(at))
        SharedInputCache(root, maxBytes = 6).maintain(at, force = true)
        assertFalse(Files.exists(old))
        assertEquals("recent", Files.readString(recent.resolve("model")))
    }

    @Test fun `abandoned process pins do not preserve expired inputs`() {
        val root = Files.createTempDirectory("input-abandoned")
        val name = ExecutionStore.digest("abandoned")
        val view = Files.createDirectories(root.resolve("views/$name"))
        Files.writeString(view.resolve("model"), "model")
        Files.createDirectories(root.resolve("pins")); Files.writeString(root.resolve("pins/$name.crashed"), "")
        val at = now()
        Files.setLastModifiedTime(view, FileTime.fromMillis(at - 1000))
        SharedInputCache(root, maxBytes = Long.MAX_VALUE, maxAgeMs = 500).maintain(at, force = true)
        assertFalse(Files.exists(view))
        assertEquals(0, Files.list(root.resolve("pins")).use { it.count() }.toInt())
    }
}
