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
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class WorkerTreesTest {
    @Test fun `cancelled private copy holds its source callback until the owned child actually exits`() {
        val child = HeldCopyProcess()
        val expected = IllegalStateException("source lease fenced")
        val copyThread = AtomicReference<Thread>()
        val pinReleased = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val coordinator = Thread {
            try {
                WorkerSlotStaging(ResourceBudget(1000, 256), 1) {}.use { staging ->
                    staging.stage(listOf(WorkerSlotStaging.Slot("source", "private", 0)),
                        reuse = { _, _ -> null },
                        source = { _, _, consume ->
                            try { consume(Path.of("pinned-source")) } finally { pinReleased.countDown() }
                        },
                        copy = { _, _, _ ->
                            copyThread.set(Thread.currentThread())
                            withOwnedCopyProcess<Path>(child) { throw expected }
                        })
                }
            } catch (error: Throwable) { failure.set(error) }
            finally { finished.countDown() }
        }
        try {
            coordinator.start()
            assertTrue(child.destroyed.await(10, TimeUnit.SECONDS))
            assertTrue(child.falseWait.await(10, TimeUnit.SECONDS))
            copyThread.get().interrupt()
            assertTrue(child.interruptedWait.await(10, TimeUnit.SECONDS))
            assertTrue(child.isAlive)
            assertEquals(1L, pinReleased.count, "source pin released while cp still owns its input")
            assertEquals(1L, finished.count, "cancelled staging returned before actual cp exit")
            child.exit.countDown()
            assertTrue(finished.await(10, TimeUnit.SECONDS))
            assertSame(expected, failure.get())
            assertIs<InterruptedException>(expected.suppressed.single())
            assertEquals(0L, pinReleased.count)
        } finally {
            child.exit.countDown()
            coordinator.join(10_000)
            assertFalse(coordinator.isAlive)
        }
    }

    @Test fun `copy cleanup reports interruption with a cleared flag after joining its child`() {
        val child = HeldCopyProcess()
        val finalizerRan = AtomicBoolean()
        val finalizerInterrupted = AtomicBoolean(true)
        val failure = AtomicReference<Throwable>()
        val finished = CountDownLatch(1)
        val copier = Thread {
            try {
                try { withOwnedCopyProcess(child) { Thread.currentThread().interrupt() } }
                finally {
                    finalizerInterrupted.set(Thread.currentThread().isInterrupted)
                    finalizerRan.set(true)
                }
            } catch (error: Throwable) { failure.set(error) }
            finally { finished.countDown() }
        }
        try {
            copier.start()
            assertTrue(child.falseWait.await(10, TimeUnit.SECONDS))
            assertFalse(finalizerRan.get())
            assertTrue(child.isAlive)
            child.exit.countDown()
            assertTrue(finished.await(10, TimeUnit.SECONDS))
            assertIs<InterruptedException>(failure.get())
            assertTrue(finalizerRan.get())
            assertFalse(finalizerInterrupted.get(), "source finalizer must be able to acquire interruptible pins")
        } finally {
            child.exit.countDown()
            copier.join(10_000)
            assertFalse(copier.isAlive)
        }
    }

    /** Force-destroy is only a request: the process deliberately stays alive until the test releases it. */
    private class HeldCopyProcess : Process() {
        val exit = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        val falseWait = CountDownLatch(1)
        val interruptedWait = CountDownLatch(1)
        private val waits = AtomicInteger()
        override fun getOutputStream() = java.io.OutputStream.nullOutputStream()
        override fun getInputStream() = java.io.InputStream.nullInputStream()
        override fun getErrorStream() = java.io.InputStream.nullInputStream()
        override fun isAlive() = exit.count != 0L
        override fun exitValue(): Int {
            if (isAlive) throw IllegalThreadStateException("copy still alive")
            return 0
        }
        override fun waitFor(): Int { exit.await(); return exitValue() }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            if (waits.getAndIncrement() == 0) { falseWait.countDown(); return false }
            return try { exit.await(timeout, unit) }
            catch (error: InterruptedException) { interruptedWait.countDown(); throw error }
        }
        override fun destroy() { destroyed.countDown() }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    @Test fun `declared workspaces copy only named roots and preserve private bytes and empty directories`() {
        val root = Files.createTempDirectory("declared-copy")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            val runtime = Files.createDirectories(source.resolve("module/runtime/empty/deep"))
            val jar = source.resolve("module/runtime/app.jar"); Files.writeString(jar, "runtime")
            Files.createDirectories(source.resolve("module/build/classes"))
            Files.writeString(source.resolve("module/build/classes/Unused.class"), "compiler scratch")
            Files.writeString(source.resolve("manifest.json"), "manifest")
            val proof = source.resolveSibling("source.verified.json"); TreeVerification().capture(source, proof)
            val workspaces = WorkerWorkspaces(root.resolve("private"))
            val tree = workspaces.copy("a".repeat(64), source, {}, false,
                listOf("module/runtime/app.jar", "module/runtime", "manifest.json", "module/runtime"))
            assertEquals("runtime", Files.readString(tree.resolve("module/runtime/app.jar")))
            assertTrue(Files.isDirectory(tree.resolve(source.relativize(runtime))))
            assertFalse(Files.exists(tree.resolve("module/build")))
            workspaces.seal("a".repeat(64), tree, {})
            assertEquals(tree, workspaces.reuse("a".repeat(64), {}))
            Files.writeString(tree.resolve("module/runtime/app.jar"), "private")
            assertEquals("runtime", Files.readString(jar))
            assertNull(workspaces.reuse("a".repeat(64), {}))
            assertFailsWith<IllegalArgumentException> { WorkerTrees.copySelected(source, root.resolve("escape"), listOf("../outside"), {}) }
            assertFails { WorkerTrees.copySelected(source, root.resolve("missing"), listOf("absent"), {}) }
        } finally { deleteTree(root) }
    }

    @Test fun `default runtime copies omit compiler intermediates but retain distributions sources and Git`() {
        val root = Files.createTempDirectory("runtime-copy")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            val kept = listOf("module/build/install/app/lib/app.jar", "module/build/libs/app.jar", "module/src/Main.kt", ".git/objects/pack/commit.pack", "build/provenance.json")
            val omitted = listOf("module/build/classes/Main.class", "module/build/kotlin/cache", "module/build/generated/source", "build/tmp/scratch")
            for (name in kept + omitted) {
                val file = source.resolve(name); Files.createDirectories(file.parent); Files.writeString(file, name)
            }
            val private = root.resolve("runtime")
            WorkerTrees.copyRuntime(source, private) {}
            for (name in kept) assertEquals(name, Files.readString(private.resolve(name)))
            for (name in omitted) assertFalse(Files.exists(private.resolve(name)), name)
            Files.writeString(private.resolve(kept.first()), "changed privately")
            assertEquals(kept.first(), Files.readString(source.resolve(kept.first())))
            val output = root.resolve("runtime.zip")
            val identity = WorkerBundle("a".repeat(64), listOf(InputFile("placeholder", "b".repeat(64), 0)), "Eignex/example", "a".repeat(40), "linux", "amd64", "fixture")
            val bundle = archive(source, output, identity, runtimeOnly = true) {}
            assertEquals(kept.toSet(), bundle.files.map { it.path }.toSet())
            assertEquals(hashFile(output), bundle.sha256)
            val repeated = root.resolve("repeated.zip")
            assertEquals(bundle, archive(source, repeated, identity, runtimeOnly = true) {})
            assertContentEquals(Files.readAllBytes(output), Files.readAllBytes(repeated))
            val restored = VerifiedCache(root.resolve("verified")).stage(bundle) { Files.newInputStream(output) }
            for (name in kept) assertEquals(name, Files.readString(restored.resolve(name)))
            val oldOutput = root.resolve("full.zip")
            val oldBundle = archive(source, oldOutput, identity) {}
            val inputCache = WorkerInputCache(root.resolve("inputs")); var downloads = 0
            inputCache.useRuntime(oldBundle, {}, { downloads++; Files.newInputStream(oldOutput) }) { runtime ->
                for (name in kept) assertTrue(Files.exists(runtime.resolve(name)))
                for (name in omitted) assertFalse(Files.exists(runtime.resolve(name)))
                assertEquals(runtime, inputCache.useRuntime(oldBundle, {}, { error("warm runtime") }) { it })
            }
            inputCache.use(oldBundle, {}, { downloads++; Files.newInputStream(oldOutput) }) { full ->
                for (name in omitted) assertTrue(Files.exists(full.resolve(name)))
            }
            assertEquals(2, downloads)
            val corruptSkipped = oldBundle.copy(files = oldBundle.files.map { if (it.path == omitted.first()) it.copy(sha256 = "0".repeat(64)) else it })
            assertFailsWith<IllegalArgumentException> {
                inputCache.useRuntime(corruptSkipped, {}, { Files.newInputStream(oldOutput) }) { error("corrupt discarded input accepted") }
            }
            val complete = root.resolve("complete")
            WorkerTrees.copy(source, complete) {}
            for (name in omitted) assertEquals(name, Files.readString(complete.resolve(name)))
        } finally { deleteTree(root) }
    }
    @Test fun `runtime archives do not walk excluded compiler trees`() {
        val root = Files.createTempDirectory("runtime-archive-inventory")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            val scratch = Files.createDirectories(source.resolve("module/build/classes"))
            repeat(400) { Files.writeString(scratch.resolve("Class$it.class"), "compiled") }
            Files.writeString(source.resolve("runtime.jar"), "distribution")
            val identity = WorkerBundle("a".repeat(64), listOf(InputFile("placeholder", "b".repeat(64), 0)), "Eignex/klause", "a".repeat(40), "linux", "amd64", "fixture")
            var checks = 0
            val runtime = archive(source, root.resolve("runtime.zip"), identity, runtimeOnly = true) { checks++ }
            assertEquals(listOf("runtime.jar"), runtime.files.map { it.path })
            assertTrue(checks < 30, "runtime archive visited discarded compiler files: $checks")
            val full = archive(source, root.resolve("full.zip"), identity) {}
            assertEquals(401, full.files.size)
        } finally { deleteTree(root) }
    }

}
