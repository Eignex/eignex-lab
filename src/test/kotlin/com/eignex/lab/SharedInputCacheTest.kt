package com.eignex.lab

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.*

class SharedInputCacheTest {
    @Test fun `old transfers are removed only after their writer releases its blob lock`() {
        val root = Files.createTempDirectory("input-transfer-retention")
        val blobs = Files.createDirectories(root.resolve("blobs"))
        val sha = ExecutionStore.digest("model")
        val partial = Files.writeString(blobs.resolve(".$sha-input-orphan.partial"), "partial")
        val at = now()
        Files.setLastModifiedTime(partial, FileTime.fromMillis(at - 48L * 60 * 60 * 1000))
        val cache = SharedInputCache(root)
        java.nio.channels.FileChannel.open(blobs.resolve("$sha.lock"), java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE).use { channel -> channel.lock().use {
            cache.maintain(at, force = true)
            assertTrue(Files.exists(partial))
        } }
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
