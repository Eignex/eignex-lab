package com.eignex.lab

import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchInput
import com.eignex.lab.bench.BenchInputRoot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class SharedInputsTest {
    @Test fun `evicted local inputs can be restored from the immutable remote inventory`() {
        val root = Files.createTempDirectory("evicted-inputs")
        val transport = Transport()
        val sha = ExecutionStore.digest("model"); val key = "input-blobs/$sha"
        transport.objects[key] = "model".toByteArray()
        val inputs = listOf(SharedInput(InputFile("model", sha, 5), key))
        val cache = SharedInputs(root, transport, maxBytes = 0)
        cache.acquire(inputs).use { lease ->
            SharedInputCache(root.resolve(".lab-inputs"), maxBytes = 0).maintain(force = true)
            assertEquals("model", Files.readString(lease.directory.resolve("model")))
        }
        SharedInputCache(root.resolve(".lab-inputs"), maxBytes = 0).maintain(force = true)
        cache.acquire(inputs).use { lease -> assertEquals("model", Files.readString(lease.directory.resolve("model"))) }
        assertEquals(listOf(key, key), transport.gets)
        assertEquals(emptyList(), transport.puts)
    }

    private class Transport : InputTransport {
        val objects = mutableMapOf<String, ByteArray>()
        val gets = mutableListOf<String>()
        val puts = mutableListOf<String>()
        override fun matches(key: String, file: InputFile, md5: String) = objects[key]?.let {
            it.size.toLong() == file.bytes && java.security.MessageDigest.getInstance("SHA-256").digest(it).hex() == file.sha256
        } ?: false
        override fun put(key: String, source: Path, sha: String) { puts += key; objects[key] = Files.readAllBytes(source) }
        override fun get(key: String, destination: Path) { gets += key; Files.write(destination, objects.getValue(key)) }
    }

    @Test fun `planning transfers no bytes and checking fetches only the case inputs`() {
        val transport = Transport()
        val inputs = listOf("first", "second").map { name ->
            val sha = ExecutionStore.digest(name); val key = "input-blobs/$sha"
            transport.objects[key] = name.toByteArray()
            SharedInput(InputFile("collection/$name", sha, name.length.toLong()), key)
        }
        val cache = SharedInputs(Files.createTempDirectory("planned-inputs"), transport)
        val planned = cache.view(inputs)
        assertEquals(emptyList(), transport.gets)
        assertEquals(planned, cache.resolve(inputs, required = listOf("collection/first")))
        assertEquals(listOf(inputs.first().key), transport.gets)
        assertFalse(Files.exists(planned.resolve("collection/second")))
    }

    @Test fun `iterations acquire only their model data and include directories`() {
        val inputs = listOf("suite/model", "suite/data", "suite/includes/library", "unrelated/model").map { path ->
            SharedInput(InputFile(path, ExecutionStore.digest(path), path.length.toLong()), "input-blobs/${ExecutionStore.digest(path)}")
        }
        val transport = Transport()
        // An unrelated input is deliberately unavailable: preparing this iteration must never fetch it.
        for (input in inputs.dropLast(1)) transport.objects[input.key] = input.file.path.toByteArray()
        val problem = Problem("fixture", "case", model = "lab-corpus/suite/model", data = "lab-corpus/suite/data",
            includeDirs = listOf("lab-corpus/suite/includes", "lab-source/other/includes"))
        val paths = iterationInputPaths(listOf(WorkCase(0, 0, problem, 1, 0, 5), WorkCase(1, 1, problem, 1, 0, 5)))
        assertEquals(listOf("suite/model", "suite/data", "suite/includes"), paths)
        SharedInputs(Files.createTempDirectory("iteration-inputs"), transport).acquire(inputs, required = paths).use { lease ->
            for (input in inputs.dropLast(1)) assertEquals(input.file.path, Files.readString(lease.directory.resolve(input.file.path)))
            assertFalse(Files.exists(lease.directory.resolve(inputs.last().file.path)))
        }
        assertEquals(inputs.dropLast(1).map { it.key }, transport.gets)
        assertEquals(listOf(""), iterationInputPaths(listOf(WorkCase(0, 0, problem.copy(includeDirs = listOf("lab-corpus/")), 1, 0, 5))).takeLast(1))
    }

    @Test fun `iteration inputs include arbitrary corpus roles from a bench plan`() {
        val bench = BenchCase("fixture", inputs = listOf(
            BenchInput("weights.bin", BenchInputRoot.CORPUS, role = "weights"),
            BenchInput("source.conf", BenchInputRoot.SOURCE, role = "settings")))
        val problem = BenchRuns.problem(bench)
        assertEquals(listOf("weights.bin"), iterationInputPaths(listOf(WorkCase(0, 0, problem, 1, 0, 5))))
    }

    @Test fun `existing regional objects and native files are reused without uploading or copying data`() {
        val root = Files.createTempDirectory("shared-inputs")
        val model = root.resolve("collection/model.opb"); Files.createDirectories(model.parent); Files.writeString(model, "model")
        val transport = Transport().also { it.objects["corpus/collection/model.opb"] = "model".toByteArray() }
        val cache = SharedInputs(root, transport)
        val inputs = cache.publish(listOf("collection/model.opb"))
        val first = cache.resolve(inputs)
        val second = cache.resolve(inputs)
        assertEquals("corpus/collection/model.opb", inputs.single().key)
        assertEquals(first, second)
        assertTrue(Files.isSameFile(model, first.resolve("collection/model.opb")))
        assertEquals(emptyList(), transport.gets)
        assertEquals(emptyList(), transport.puts)
    }

    @Test fun `a cold replacement node downloads each needed file once and reuses it across views`() {
        val transport = Transport()
        val sha = ExecutionStore.digest("model"); val key = "input-blobs/$sha"
        transport.objects[key] = "model".toByteArray()
        val input = SharedInput(InputFile("collection/model.opb", sha, 5), key)
        val cache = SharedInputs(Files.createTempDirectory("cold-inputs"), transport)
        val first = cache.resolve(listOf(input))
        assertEquals(first, cache.resolve(listOf(input)))
        assertEquals("model", Files.readString(first.resolve(input.file.path)))
        assertEquals(listOf(key), transport.gets)
        assertEquals(emptyList(), transport.puts)
    }

    @Test fun `a newly fetched input is published once under its content hash`() {
        val root = Files.createTempDirectory("new-inputs")
        Files.writeString(root.resolve("model"), "new")
        val transport = Transport(); val cache = SharedInputs(root, transport)
        val first = cache.publish(listOf("model"))
        assertEquals(first, cache.publish(listOf("model")))
        assertEquals("input-blobs/${ExecutionStore.digest("new")}", first.single().key)
        assertEquals(listOf(first.single().key), transport.puts)
    }

    @Test fun `an existing compressed object is reused and decoded once by a replacement node`() {
        val root = Files.createTempDirectory("packed-inputs"); val plain = root.resolve("model.opb")
        Files.writeString(plain, "model")
        val packed = root.resolve("model.opb.zst")
        com.github.luben.zstd.ZstdOutputStreamNoFinalizer(Files.newOutputStream(packed)).use { it.write("model".toByteArray()) }
        val transport = Transport().also { it.objects["corpus/model.opb.zst"] = Files.readAllBytes(packed) }
        val input = SharedInputs(root, transport).publish(listOf("model.opb")).single()
        assertEquals("zstd", input.encoding)
        assertEquals("corpus/model.opb.zst", input.key)
        assertEquals(emptyList(), transport.puts)
        val nativeRoot = Files.createTempDirectory("native-packed-inputs")
        Files.write(nativeRoot.resolve("model.opb.zst"), Files.readAllBytes(packed))
        val nativeView = SharedInputs(nativeRoot, transport).resolve(listOf(input))
        assertEquals("model", Files.readString(nativeView.resolve("model.opb")))
        assertTrue(Files.isSameFile(nativeRoot.resolve("model.opb"), nativeView.resolve("model.opb")))
        assertEquals(emptyList(), transport.gets)
        val replacement = SharedInputs(Files.createTempDirectory("replacement-inputs"), transport)
        val view = replacement.resolve(listOf(input))
        assertEquals("model", Files.readString(view.resolve("model.opb")))
        assertEquals(view, replacement.resolve(listOf(input)))
        assertEquals(listOf(input.key), transport.gets)
    }

    @Test fun `corrupt remote bytes are never exposed to a job`() {
        val root = Files.createTempDirectory("corrupt-inputs"); val transport = Transport()
        val sha = ExecutionStore.digest("model"); val key = "input-blobs/$sha"
        transport.objects[key] = "wrong".toByteArray()
        assertFailsWith<IllegalStateException> { SharedInputs(root, transport).resolve(listOf(SharedInput(InputFile("model", sha, 5), key))) }
        assertFalse(Files.exists(root.resolve(".lab-inputs/blobs/$sha")))
    }

    @Test fun `different input versions keep independent immutable views`() {
        val root = Files.createTempDirectory("versioned-inputs"); val transport = Transport()
        fun input(text: String): SharedInput {
            val sha = ExecutionStore.digest(text); val key = "input-blobs/$sha"
            transport.objects[key] = text.toByteArray()
            return SharedInput(InputFile("collection/model", sha, text.length.toLong()), key)
        }
        val cache = SharedInputs(root, transport)
        val old = cache.resolve(listOf(input("old"))); val newer = cache.resolve(listOf(input("new")))
        assertEquals("old", Files.readString(old.resolve("collection/model")))
        assertEquals("new", Files.readString(newer.resolve("collection/model")))
        assertNotEquals(old, newer)
    }

    @Test fun `input aliases cannot publish files outside the configured cache`() {
        val root = Files.createTempDirectory("confined-inputs")
        val outside = Files.createTempFile("outside-input", ".txt"); Files.writeString(outside, "private")
        Files.createSymbolicLink(root.resolve("link"), outside)
        assertFailsWith<IllegalArgumentException> { SharedInputs(root, Transport()).publish(listOf("link")) }
    }
}
