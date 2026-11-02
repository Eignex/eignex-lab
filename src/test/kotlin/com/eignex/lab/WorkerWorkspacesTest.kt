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
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.test.*

class WorkerWorkspacesTest {
    @Test fun `ordinary Git status preserves sealed workspaces while detecting dirt and allowing required writes`() {
        val root = Files.createTempDirectory("workspace-git-status")
        val source = Files.createDirectory(root.resolve("source"))
        val home = Files.createDirectory(root.resolve("home"))
        val base = mapOf("PATH" to System.getenv("PATH").orEmpty(), "HOME" to home.toString())
        val environment = workerExecutionEnvironment(CliMeasurements.localMacOs, base)
        val workspaces = WorkerWorkspaces(root.resolve("workspaces"))
        fun git(tree: Path, vararg args: String, env: Map<String, String> = environment): String {
            val process = ProcessBuilder(listOf("git", "-c", "core.hooksPath=/dev/null", "-C", tree.toString()) + args)
                .redirectErrorStream(true).apply {
                    environment().clear(); environment().putAll(env); environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                }.start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
            return output
        }
        fun sealed(key: String): Path = workspaces.create(key, {}) { copyPrivateTree(source, it) {} }.also { tree ->
            // Force Git's cached stat data to be stale without changing any source bytes.
            Files.setLastModifiedTime(tree.resolve("code"), FileTime.fromMillis(123456789000))
            workspaces.seal(key, tree) {}
        }
        try {
            git(source, "init", "-q")
            git(source, "config", "user.name", "Fixture")
            git(source, "config", "user.email", "fixture@example.invalid")
            git(source, "config", "commit.gpgsign", "false")
            git(source, "config", "core.fsmonitor", "false")
            git(source, "config", "core.trustctime", "true")
            git(source, "config", "core.checkstat", "default")
            Files.writeString(source.resolve("code"), "fixture")
            git(source, "add", "code"); git(source, "commit", "-qm", "fixture")
            val key = "1".repeat(64); val tree = sealed(key)
            val index = tree.resolve(".git/index"); val before = hashFile(index)
            assertEquals("", git(tree, "status", "--porcelain"))
            assertEquals(before, hashFile(index))
            assertEquals(tree, workspaces.reuse(key) {})
            assertEquals("", git(tree, "status", "--porcelain"))
            assertEquals(tree, workspaces.reuse(key) {}, "later rows must retain the same sealed private tree")

            val controlKey = "2".repeat(64); val control = sealed(controlKey)
            val controlIndex = hashFile(control.resolve(".git/index"))
            val explicit = workerExecutionEnvironment(CliMeasurements.localMacOs, base + ("GIT_OPTIONAL_LOCKS" to "1"))
            assertEquals("", git(control, "status", "--porcelain", env = explicit))
            assertNotEquals(controlIndex, hashFile(control.resolve(".git/index")), "fixture must exercise optional index refresh")
            assertNull(workspaces.reuse(controlKey) {}, "an explicitly refreshed index still fails the ordinary checksum check")

            Files.writeString(tree.resolve("code"), "mutated")
            assertEquals(" M code\n", git(tree, "status", "--porcelain"))
            assertEquals(before, hashFile(index), "dirty detection must not require index writes")
            assertNull(workspaces.reuse(key) {}, "real source changes remain invalid")
            git(tree, "add", "code")
            assertNotEquals(before, hashFile(index), "required staging writes must still work")
            assertEquals("M  code\n", git(tree, "status", "--porcelain"))
            git(tree, "checkout", "HEAD", "--", "code")
            assertEquals("fixture", Files.readString(tree.resolve("code")))
            assertEquals("", git(tree, "status", "--porcelain"))
        } finally {
            assertTrue(workspaces.awaitCleanupIdle())
            deleteTree(root)
        }
    }

    @Test fun `optional Git refresh defaults apply only to execution and explicit settings survive`() {
        val base = mapOf("PATH" to "/fixture/bin", "HOME" to "/fixture/home")
        assertFalse("GIT_OPTIONAL_LOCKS" in workerEnvironment(false, base))
        assertEquals("0", workerExecutionEnvironment(false, base)["GIT_OPTIONAL_LOCKS"])
        assertEquals("0", workerExecutionEnvironment(true, base)["GIT_OPTIONAL_LOCKS"])
        assertEquals("1", workerExecutionEnvironment(false, base + ("GIT_OPTIONAL_LOCKS" to "1"))["GIT_OPTIONAL_LOCKS"])
    }

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
