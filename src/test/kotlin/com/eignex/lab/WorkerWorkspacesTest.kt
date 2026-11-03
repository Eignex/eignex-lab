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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class WorkerWorkspacesTest {
    @Test fun `slot staging overlaps independent reuse within the existing CPU and concurrency budget`() {
        val slots = List(6) { WorkerSlotStaging.Slot("source", "key-$it", it) }
        val started = CountDownLatch(2); val release = CountDownLatch(1)
        val active = AtomicInteger(); val maximum = AtomicInteger(); val observed = AtomicReference<Throwable?>()
        val finished = AtomicReference<WorkerSlotStaging.Staged?>()
        val staging = WorkerSlotStaging(ResourceBudget(2000, 256, 6), slots.size) {}
        val worker = Thread {
            try { staging.use {
                finished.set(it.stage(slots, reuse = { slot, check ->
                    val count = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, count) }; started.countDown()
                    try { assertTrue(release.await(10, TimeUnit.SECONDS)); check(); Path.of("private", slot.key) }
                    finally { active.decrementAndGet() }
                }, source = { _, _, _ -> error("warm slots must not acquire a source") },
                    copy = { _, _, _ -> error("warm slots must not copy") }))
            } } catch (error: Throwable) { observed.set(error) }
        }
        try {
            assertEquals(2, staging.parallelism)
            worker.start(); assertTrue(started.await(10, TimeUnit.SECONDS), "independent slots did not overlap")
            assertEquals(2, active.get())
        } finally { release.countDown(); worker.join(15_000) }
        assertFalse(worker.isAlive); observed.get()?.let { throw it }
        assertEquals(2, maximum.get()); assertEquals(0, active.get())
        assertEquals(slots.map { it.key }, assertNotNull(finished.get()).paths.keys.toList())
        assertEquals(0, finished.get()?.fresh)
        for ((budget, count, expected) in listOf(
            Triple(ResourceBudget(8000, 256, 1), 6, 1), Triple(ResourceBudget(8000, 256, 6), 1, 1),
            Triple(ResourceBudget(500, 256, 6), 6, 1))) {
            WorkerSlotStaging(budget, count) {}.use { assertEquals(expected, it.parallelism) }
        }
    }

    @Test fun `missing slots acquire each source once on its owner thread and preserve stable slot paths`() {
        val owner = Thread.currentThread(); val pinned = AtomicBoolean()
        val copies = CountDownLatch(2); val acquisitions = linkedMapOf<String, Int>()
        val slots = listOf(WorkerSlotStaging.Slot("first", "a", 0), WorkerSlotStaging.Slot("first", "b", 1),
            WorkerSlotStaging.Slot("second", "c", 0), WorkerSlotStaging.Slot("warm", "d", 0))
        val result = WorkerSlotStaging(ResourceBudget(2000, 256, 2), slots.size) {}.use { staging ->
            staging.stage(slots, reuse = { slot, _ -> if (slot.source == "warm") Path.of("warm") else null },
                source = { sha, check, consume ->
                    assertSame(owner, Thread.currentThread()); check(); assertFalse(pinned.getAndSet(true))
                    acquisitions[sha] = acquisitions.getOrDefault(sha, 0) + 1
                    try { consume(Path.of(sha)) } finally { pinned.set(false) }
                }, copy = { slot, source, check ->
                    assertTrue(pinned.get()); assertNotSame(owner, Thread.currentThread())
                    if (slot.source == "first") { copies.countDown(); assertTrue(copies.await(10, TimeUnit.SECONDS)) }
                    check(); source.resolve(slot.number.toString())
                })
        }
        assertEquals(mapOf("first" to 1, "second" to 1), acquisitions); assertFalse(pinned.get())
        assertEquals(mapOf("a" to Path.of("first/0"), "b" to Path.of("first/1"), "c" to Path.of("second/0"), "d" to Path.of("warm")), result.paths)
        assertEquals(3, result.fresh)
        WorkerSlotStaging(ResourceBudget(1000, 256), 2) {}.use { staging ->
            assertFailsWith<IllegalArgumentException> { staging.stage(listOf(slots[0], slots[0]),
                reuse = { _, _ -> error("duplicate keys must fail before work") }, source = { _, _, _ -> error("source") },
                copy = { _, _, _ -> error("copy") }) }
        }
    }

    @Test fun `a failed slot retains its source pin until an interrupted sibling actually leaves its copy`() {
        val entered = CountDownLatch(2); val interrupted = CountDownLatch(1); val release = CountDownLatch(1)
        val done = CountDownLatch(1); val pinned = AtomicBoolean(); val observed = AtomicReference<Throwable?>()
        val failure = IllegalStateException("copy failed")
        val slots = List(2) { WorkerSlotStaging.Slot("source", "key-$it", it) }
        val worker = Thread {
            try { WorkerSlotStaging(ResourceBudget(2000, 256, 2), slots.size) {}.use { staging ->
                staging.stage(slots, reuse = { _, _ -> null }, source = { _, _, consume ->
                    pinned.set(true); try { consume(Path.of("source")) } finally { pinned.set(false) }
                }, copy = { slot, source, check ->
                    entered.countDown(); assertTrue(entered.await(10, TimeUnit.SECONDS))
                    if (slot.number == 1) throw failure
                    while (release.count > 0) try { release.await() }
                    catch (_: InterruptedException) { interrupted.countDown() }
                    check(); source
                })
            } } catch (error: Throwable) { observed.set(error) } finally { done.countDown() }
        }
        try {
            worker.start(); assertTrue(interrupted.await(10, TimeUnit.SECONDS))
            assertTrue(pinned.get(), "Future cancellation must not release a source still used by a copy")
            assertEquals(1L, done.count)
        } finally { release.countDown(); worker.join(15_000) }
        assertFalse(worker.isAlive); assertFalse(pinned.get()); assertSame(failure, observed.get())
    }

    @Test fun `lease deadline and caller interruption join real staging tasks before returning`() {
        for (reason in listOf("lease", "deadline", "interrupt")) {
            val entered = CountDownLatch(1); val interrupted = CountDownLatch(1); val release = CountDownLatch(1)
            val done = CountDownLatch(1); val pinned = AtomicBoolean(); val restoredInterrupt = AtomicBoolean()
            val cancelled = AtomicReference<Throwable?>(); val observed = AtomicReference<Throwable?>()
            val failure = if (reason == "lease") ExecutionFenced("lease expired") else RunDeadlineExceeded()
            val worker = Thread {
                try { WorkerSlotStaging(ResourceBudget(1000, 256), 1, { cancelled.get()?.let { throw it } }).use { staging ->
                    staging.stage(listOf(WorkerSlotStaging.Slot("source", "key", 0)), reuse = { _, _ -> null },
                        source = { _, _, consume ->
                            pinned.set(true)
                            try { consume(Path.of("source")) } finally {
                                assertFalse(Thread.currentThread().isInterrupted, "pin-release lock waits must see deferred interruption")
                                pinned.set(false)
                            }
                        },
                        copy = { _, source, check ->
                            entered.countDown()
                            while (release.count > 0) try { release.await() }
                            catch (_: InterruptedException) { interrupted.countDown() }
                            check(); source
                        })
                } } catch (error: Throwable) { observed.set(error) }
                finally { restoredInterrupt.set(Thread.currentThread().isInterrupted); done.countDown() }
            }
            try {
                worker.start(); assertTrue(entered.await(10, TimeUnit.SECONDS))
                if (reason == "interrupt") worker.interrupt() else cancelled.set(failure)
                assertTrue(interrupted.await(10, TimeUnit.SECONDS))
                assertTrue(pinned.get()); assertEquals(1L, done.count)
            } finally { release.countDown(); worker.join(15_000) }
            assertFalse(worker.isAlive); assertFalse(pinned.get())
            if (reason == "interrupt") { assertIs<InterruptedException>(observed.get()); assertTrue(restoredInterrupt.get()) }
            else { assertSame(failure, observed.get()); assertFalse(restoredInterrupt.get()) }
        }
    }

    @Test fun `parallel replacements keep one tombstone without holding the eviction guard across copies`() {
        val root = Files.createTempDirectory("parallel-workspaces"); val queued = ConcurrentLinkedQueue<Runnable>()
        val cache = WorkerWorkspaces(root, Executor { queued.add(it) })
        val keys = (0..512).map { ExecutionStore.digest("slot-$it") }.distinctBy { fileLockFor(root.resolve(it)) }.take(2)
        val executor = Executors.newFixedThreadPool(2); val copying = CountDownLatch(2)
        try {
            for (key in keys) cache.create(key, {}) { Files.createDirectory(it); Files.writeString(it.resolve("code"), "old") }
            val replacements = keys.map { key -> executor.submit<Path> {
                cache.create(key, {}) { tree ->
                    copying.countDown(); assertTrue(copying.await(10, TimeUnit.SECONDS), "global eviction guard serialized private copies")
                    Files.createDirectory(tree); Files.writeString(tree.resolve("code"), "new")
                }.also { cache.seal(key, it) {} }
            } }
            replacements.forEach { assertEquals("new", Files.readString(it.get(15, TimeUnit.SECONDS).resolve("code"))) }
            assertEquals(1L, Files.list(root).use { paths -> paths.filter { it.fileName.toString().startsWith(".evict-") }.count() })
            for (key in keys) assertNotNull(cache.reuse(key) {})
        } finally {
            executor.shutdownNow(); assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
            while (true) (queued.poll() ?: break).run()
            assertTrue(cache.awaitCleanupIdle()); deleteTree(root)
        }
    }

    @Test fun `execution copy handoff is immediately reusable and still rejects mutations and prunes outputs`() {
        val root = Files.createTempDirectory("execution-copy-handoff")
        val source = Files.createDirectory(root.resolve("source")); Files.writeString(source.resolve("code"), "fixture")
        TreeVerification().capture(source, root.resolve("source.verified.json"))
        val cache = WorkerWorkspaces(root.resolve("workspaces")); val key = "3".repeat(64)
        try {
            val tree = cache.copySealed(key, source) {}
            assertFalse(Files.exists(root.resolve("workspaces/$key.verified.json")))
            assertEquals(tree, cache.reuse(key) {}, "execution copies must be ready without a second explicit seal")
            Files.writeString(tree.resolve("generated"), "output")
            assertEquals(tree, cache.reuse(key) {})
            assertFalse(Files.exists(tree.resolve("generated")))
            val modified = Files.getLastModifiedTime(tree.resolve("code"))
            Files.writeString(tree.resolve("code"), "corrupt"); Files.setLastModifiedTime(tree.resolve("code"), modified)
            assertNull(cache.reuse(key) {}, "handoff must retain the copied source identity")
            assertEquals("fixture", Files.readString(source.resolve("code")))
        } finally { assertTrue(cache.awaitCleanupIdle()); deleteTree(root) }
    }

    @Test fun `execution copies without usable source proof fall back to checksum sealing in memory`() {
        val root = Files.createTempDirectory("execution-copy-fallback")
        val source = Files.createDirectory(root.resolve("source")); Files.writeString(source.resolve("code"), "fixture")
        val cache = WorkerWorkspaces(root.resolve("workspaces"))
        try {
            for ((index, proof) in listOf<String?>(null, "damaged receipt").withIndex()) {
                if (proof != null) Files.writeString(root.resolve("source.verified.json"), proof)
                val key = (index + 4).toString().repeat(64)
                val tree = cache.copySealed(key, source) {}
                assertFalse(Files.exists(root.resolve("workspaces/$key.verified.json")))
                assertEquals(tree, cache.reuse(key) {})
                Files.writeString(tree.resolve("code"), "corrupt")
                assertNull(cache.reuse(key) {})
            }
        } finally { assertTrue(cache.awaitCleanupIdle()); deleteTree(root) }
    }

    @Test fun `cancellation during execution copying cannot publish a ready workspace`() {
        val root = Files.createTempDirectory("execution-copy-cancel")
        val source = Files.createDirectory(root.resolve("source")); Files.writeString(source.resolve("code"), "fixture")
        TreeVerification().capture(source, root.resolve("source.verified.json"))
        val workspace = root.resolve("workspaces"); val cache = WorkerWorkspaces(workspace); val key = "6".repeat(64)
        try {
            assertFailsWith<CancellationException> {
                cache.copySealed(key, source) {
                    if (Files.exists(workspace.resolve("$key/code"))) throw CancellationException("copied file")
                }
            }
            assertTrue(Files.exists(workspace.resolve("$key/code")), "fixture must reach private file copying")
            assertFalse(Files.exists(workspace.resolve("$key.verified.json")))
            assertNull(cache.reuse(key) {})
        } finally { assertTrue(cache.awaitCleanupIdle()); deleteTree(root) }
    }

    @Test fun `execution memory receipts cannot survive a workspace owner restart`() {
        val root = Files.createTempDirectory("execution-copy-restart")
        val source = Files.createDirectory(root.resolve("source")); Files.writeString(source.resolve("code"), "fixture")
        TreeVerification().capture(source, root.resolve("source.verified.json"))
        val workspace = root.resolve("workspaces"); val cache = WorkerWorkspaces(workspace); val key = "8".repeat(64)
        var restarted: WorkerWorkspaces? = null
        try {
            val tree = cache.copySealed(key, source) {}
            assertEquals(tree, cache.reuse(key) {})
            assertFalse(Files.exists(workspace.resolve("$key.verified.json")))
            restarted = WorkerWorkspaces(workspace)
            assertNull(restarted.reuse(key) {}, "a new owner must copy rather than trust old private state")
            cache.create(key, {}) { Files.createDirectory(it); Files.writeString(it.resolve("code"), "partial") }
            assertNull(cache.reuse(key) {}, "replacement must evict its old memory proof")
        } finally {
            assertTrue(cache.awaitCleanupIdle()); assertTrue(restarted?.awaitCleanupIdle() ?: true); deleteTree(root)
        }
    }

    @Test fun `preparation copies remain unsealed until trusted setup has finished`() {
        val root = Files.createTempDirectory("preparation-copy-setup")
        val source = Files.createDirectory(root.resolve("source")); Files.writeString(source.resolve("code"), "fixture")
        TreeVerification().capture(source, root.resolve("source.verified.json"))
        val cache = WorkerWorkspaces(root.resolve("workspaces")); val key = "7".repeat(64)
        try {
            val tree = cache.copy(key, source, {}, false)
            assertTrue(Files.isRegularFile(root.resolve("workspaces/$key.verified.json")), "preparation copy proofs remain persistent")
            assertNull(cache.reuse(key) {})
            Files.writeString(tree.resolve("setup"), "prepared")
            cache.seal(key, tree) {}
            assertTrue(Files.isRegularFile(root.resolve("workspaces/$key.verified.json")), "post-setup seals remain persistent")
            assertEquals(tree, cache.reuse(key) {})
            assertEquals("prepared", Files.readString(tree.resolve("setup")))
        } finally { assertTrue(cache.awaitCleanupIdle()); deleteTree(root) }
    }

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
        val cache = WorkerWorkspaces(root)
        try {
            val key = "a".repeat(64)
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
        } finally {
            assertTrue(cache.awaitCleanupIdle())
            deleteTree(root)
        }
    }

    @Test fun `unsealed setup cannot be reused after failure or a worker restart`() {
        val root = Files.createTempDirectory("unsealed-workspaces")
        val cache = WorkerWorkspaces(root)
        var restarted: WorkerWorkspaces? = null
        try {
            val key = "b".repeat(64)
            val tree = cache.create(key, {}) { Files.createDirectory(it); Files.writeString(it.resolve("code"), "fixture") }
            assertNull(cache.reuse(key) {})
            cache.seal(key, tree) {}
            assertNotNull(cache.reuse(key) {})
            restarted = WorkerWorkspaces(root)
            assertNull(restarted.reuse(key) {})
        } finally {
            assertTrue(cache.awaitCleanupIdle())
            assertTrue(restarted?.awaitCleanupIdle() ?: true)
            deleteTree(root)
        }
    }
}
