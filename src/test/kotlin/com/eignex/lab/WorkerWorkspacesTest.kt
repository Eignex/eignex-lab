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
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.test.*

class WorkerWorkspacesTest {
    @Test fun `invalid workspace replacement detaches one tree without waiting for deletion`() {
        val root = Files.createTempDirectory("workspace-detachment")
        val queued = mutableListOf<Runnable>()
        val cache = WorkerWorkspaces(root, Executor { queued += it }); val key = "c".repeat(64)
        fun create() = cache.create(key, {}) { Files.createDirectory(it); Files.writeString(it.resolve("code"), "trusted") }
        fun garbage() = Files.list(root).use { paths -> paths.filter { it.fileName.toString().startsWith(".evict-") }.toList() }
        try {
            val first = create(); cache.seal(key, first) {}
            Files.writeString(first.resolve("code"), "corrupt")
            assertNull(cache.reuse(key) {})
            val next = create()
            assertEquals("trusted", Files.readString(next.resolve("code")))
            assertEquals("corrupt", Files.readString(garbage().single().resolve("code")))
            assertFalse(cache.awaitCleanupIdle(0), "replacement must not await queued garbage deletion")
            assertNull(cache.reuse(key) {}, "an unsealed replacement cannot inherit the old proof")
            cache.seal(key, next) {}; assertEquals(next, cache.reuse(key) {})
            // A second miss cannot add another generation while the first garbage tree is pending.
            Files.writeString(next.resolve("code"), "another")
            assertNull(cache.reuse(key) {})
            create()
            assertEquals(1, garbage().size)
            while (queued.isNotEmpty()) queued.removeAt(0).run()
            assertTrue(cache.awaitCleanupIdle(0)); assertTrue(garbage().isEmpty())
            assertEquals("trusted", Files.readString(next.resolve("code")))
        } finally {
            while (queued.isNotEmpty()) queued.removeAt(0).run()
            deleteTree(root)
        }
    }

    @Test fun `failed cleanup remains bounded and a later worker can reap retained garbage`() {
        val root = Files.createTempDirectory("workspace-cleanup-rejected")
        val key = "d".repeat(64)
        val cache = WorkerWorkspaces(root, Executor { throw RejectedExecutionException("fixture") })
        val outside = Files.writeString(root.resolve("outside"), "keep")
        try {
            repeat(3) { pass ->
                cache.create(key, {}) { tree ->
                    Files.createDirectory(tree); Files.createSymbolicLink(tree.resolve("link"), outside)
                    Files.writeString(tree.resolve("code"), "pass-$pass")
                }
            }
            assertEquals(1L, Files.list(root).use { paths -> paths.filter { it.fileName.toString().startsWith(".evict-") }.count() })
            assertEquals("keep", Files.readString(outside))
            val queued = mutableListOf<Runnable>()
            val recovered = WorkerWorkspaces(root, Executor { queued += it })
            assertNull(recovered.reuse(key) {}, "a new process never trusts another process's private mutable tree")
            while (queued.isNotEmpty()) queued.removeAt(0).run()
            assertTrue(recovered.awaitCleanupIdle(0))
            assertFalse(Files.list(root).use { paths -> paths.anyMatch { it.fileName.toString().startsWith(".evict-") } })
            assertEquals("keep", Files.readString(outside))
        } finally { deleteTree(root) }
    }

    @Test fun `unsupported atomic detachment keeps synchronous replacement and failed copies unsealed`() {
        val root = Files.createTempDirectory("workspace-detach-fallback")
        val queued = mutableListOf<Runnable>(); val key = "f".repeat(64)
        val cache = WorkerWorkspaces(root, Executor { queued += it }, { from, to ->
            throw java.nio.file.AtomicMoveNotSupportedException(from.toString(), to.toString(), "fixture")
        })
        try {
            val tree = cache.create(key, {}) { Files.createDirectory(it); Files.writeString(it.resolve("code"), "original") }
            cache.seal(key, tree) {}
            assertFailsWith<IllegalStateException> {
                cache.create(key, {}) { replacement ->
                    assertFalse(Files.exists(replacement, NOFOLLOW_LINKS), "fallback must finish deleting the old tree")
                    Files.createDirectory(replacement); Files.writeString(replacement.resolve("code"), "partial")
                    error("copy failed")
                }
            }
            assertNull(cache.reuse(key) {}, "failed copies must not inherit the original ready entry")
            assertFalse(Files.exists(root.resolve("$key.verified.json"), NOFOLLOW_LINKS))
            assertFalse(Files.list(root).use { paths -> paths.anyMatch { it.fileName.toString().startsWith(".evict-") } })
            assertEquals("partial", Files.readString(tree.resolve("code")))
        } finally {
            while (queued.isNotEmpty()) queued.removeAt(0).run()
            deleteTree(root)
        }
    }

    @Test fun `workspace cancellation preserves ownership before and after atomic detachment`() {
        val root = Files.createTempDirectory("workspace-detach-cancellation")
        val queued = mutableListOf<Runnable>()
        val cache = WorkerWorkspaces(root, Executor { queued += it }); val key = "e".repeat(64)
        val tree = cache.create(key, {}) { Files.createDirectory(it); Files.writeString(it.resolve("code"), "fixture") }
        cache.seal(key, tree) {}
        try {
            assertFailsWith<CancellationException> {
                cache.create(key, { throw CancellationException("before mutation") }) { error("must not copy") }
            }
            assertEquals(tree, cache.reuse(key) {})
            assertFailsWith<CancellationException> {
                cache.create(key, {
                    if (Files.list(root).use { paths -> paths.anyMatch { it.fileName.toString().startsWith(".evict-") } }) {
                        throw CancellationException("after detachment")
                    }
                }) { error("must not copy") }
            }
            assertFalse(Files.exists(tree, NOFOLLOW_LINKS))
            assertFalse(Files.exists(root.resolve("$key.verified.json"), NOFOLLOW_LINKS))
            assertNull(cache.reuse(key) {})
            assertFalse(cache.awaitCleanupIdle(0))
            while (queued.isNotEmpty()) queued.removeAt(0).run()
            assertTrue(cache.awaitCleanupIdle(0))
        } finally {
            while (queued.isNotEmpty()) queued.removeAt(0).run()
            deleteTree(root)
        }
    }

    @Test fun `sealed private workspaces retain setup reject changed files and remove generated outputs`() {
        val root = Files.createTempDirectory("private-workspaces")
        try {
            val cache = WorkerWorkspaces(root); val key = "a".repeat(64)
            assertNull(cache.reuse(key) {})
            val tree = cache.create(key, {}) { Files.createDirectories(it); Files.writeString(it.resolve("code"), "fixture") }
            Files.writeString(tree.resolve("provenance.json"), tree.toString())
            cache.seal(key, tree) {}
            assertEquals(tree, cache.reuse(key) {})
            val file = tree.resolve("code"); val modified = Files.getLastModifiedTime(file)
            Files.writeString(file, "corrupt"); Files.setLastModifiedTime(file, modified)
            assertNull(cache.reuse(key) {})
            val restored = cache.create(key, {}) { Files.createDirectories(it); Files.writeString(it.resolve("code"), "fixture") }
            assertFalse(Files.exists(restored.resolve("provenance.json")))
            cache.seal(key, restored) {}
            Files.writeString(restored.resolve("extra"), "output")
            val outside = root.resolve("outside").also { Files.writeString(it, "outside") }
            Files.createSymbolicLink(restored.resolve("extra-link"), outside)
            assertEquals(restored, cache.reuse(key) {})
            assertFalse(Files.exists(restored.resolve("extra")))
            assertFalse(Files.exists(restored.resolve("extra-link"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertEquals("outside", Files.readString(outside))
        } finally { deleteTree(root) }
    }

    @Test fun `unsealed setup cannot be reused after failure or a worker restart`() {
        val root = Files.createTempDirectory("unsealed-workspaces")
        try {
            val key = "b".repeat(64); val cache = WorkerWorkspaces(root)
            val tree = cache.create(key, {}) { Files.createDirectory(it); Files.writeString(it.resolve("code"), "fixture") }
            assertNull(cache.reuse(key) {})
            cache.seal(key, tree) {}
            assertNotNull(cache.reuse(key) {})
            assertNull(WorkerWorkspaces(root).reuse(key) {})
        } finally { deleteTree(root) }
    }
}
