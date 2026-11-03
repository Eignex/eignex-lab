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
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.FileTime
import kotlin.test.*

class ArtifactReuseTest {
    private val root = Files.createTempDirectory("artifact-reuse")
    private val bytes = "verified prepared distribution".toByteArray()
    private val sha = ExecutionStore.digest(String(bytes))
    private fun store() = LocalArtifactStore(root)
    private fun publish(job: Long = 1) { store().put(job, sha, bytes.size.toLong(), 0, bytes.inputStream()); store().remember(job, sha) }

    @Test fun `empty and invalid artifact batches never create a job directory`() {
        store().putAll(1, emptyMap())
        assertFalse(Files.exists(root.resolve("1")))
        assertFailsWith<IllegalArgumentException> { store().putAll(2, mapOf("invalid" to bytes)) }
        assertFalse(Files.exists(root.resolve("2")))
        assertFailsWith<IllegalArgumentException> { LocalArtifactStore(root, maxBytes = 4).putAll(3, mapOf(sha to bytes)) }
        assertFalse(Files.exists(root.resolve("3")))
        assertFailsWith<IllegalStateException> { LocalArtifactStore(root, writable = { false }).putAll(4, mapOf(sha to bytes)) }
        assertFalse(Files.exists(root.resolve("4")))
    }

    @Test fun `artifact batch fails without recreating a parent removed between completed writes`() {
        val second = "second artifact".toByteArray(); val secondSha = ExecutionStore.digest(String(second))
        val payloads = linkedMapOf(sha to bytes, secondSha to second)
        val parent = root.resolve("1"); val removed = root.resolve("removed")
        val disappearing = object : Map<String, ByteArray> by payloads {
            override val entries: Set<Map.Entry<String, ByteArray>> get() = object : AbstractSet<Map.Entry<String, ByteArray>>() {
                override val size get() = payloads.size
                override fun iterator(): Iterator<Map.Entry<String, ByteArray>> {
                    val iterator = payloads.entries.iterator()
                    return object : Iterator<Map.Entry<String, ByteArray>> {
                        override fun hasNext() = iterator.hasNext()
                        override fun next(): Map.Entry<String, ByteArray> = iterator.next().also { entry ->
                            if (entry.key == secondSha && Files.isRegularFile(parent.resolve(sha))) Files.move(parent, removed, ATOMIC_MOVE)
                        }
                    }
                }
            }
        }
        assertFailsWith<java.io.IOException> { store().putAll(1, disappearing) }
        assertFalse(Files.exists(parent), "a missing batch parent must not be silently recreated")
        assertContentEquals(bytes, Files.readAllBytes(removed.resolve(sha)))
        assertFalse(Files.exists(removed.resolve(secondSha)))
        // Once the original directory is restored, an ordinary retry verifies the first write and completes the rest.
        Files.move(removed, parent, ATOMIC_MOVE)
        store().putAll(1, payloads)
        for ((key, value) in payloads) assertContentEquals(value, Files.readAllBytes(store().file(1, key)))
    }

    @Test fun `batched writes retain checksum rejection and allow a later complete retry`() {
        val second = "second artifact".toByteArray(); val secondSha = ExecutionStore.digest(String(second))
        val corrupt = second.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFailsWith<IllegalStateException> { store().putAll(1, linkedMapOf(sha to bytes, secondSha to corrupt)) }
        assertContentEquals(bytes, Files.readAllBytes(store().file(1, sha)))
        assertFalse(Files.exists(root.resolve("1/$secondSha")))
        assertFalse(Files.exists(root.resolve("1/$secondSha.partial")))
        store().putAll(1, linkedMapOf(sha to bytes, secondSha to second))
        assertContentEquals(second, Files.readAllBytes(store().file(1, secondSha)))
    }

    @Test fun `reuse survives coordinator restart shares bytes and remains valid after original job removal`() {
        publish()
        assertEquals(bytes.size.toLong(), store().reuse(2, sha))
        assertTrue(Files.isSameFile(store().file(1, sha), store().file(2, sha)))
        deleteTree(root.resolve("1"))
        assertEquals(bytes.size.toLong(), store().reuse(3, sha))
        assertContentEquals(bytes, Files.readAllBytes(store().file(3, sha)))
    }

    @Test fun `only explicitly registered artifacts can be reused and deleted or corrupt sources fall back`() {
        store().put(1, sha, bytes.size.toLong(), 0, bytes.inputStream())
        assertNull(store().reuse(2, sha))
        store().remember(1, sha)
        Files.writeString(root.resolve("1/$sha"), "corrupt")
        assertNull(store().reuse(2, sha)); assertEquals(0, store().offset(2, sha))
        assertFalse(Files.exists(root.resolve(".reuse/$sha")))
        Files.delete(root.resolve("1/$sha")); publish()
        deleteTree(root.resolve("1"))
        assertNull(store().reuse(2, sha))
        assertEquals(bytes.size.toLong(), store().put(2, sha, bytes.size.toLong(), 0, bytes.inputStream()))
    }

    @Test fun `reuse replaces an interrupted upload and respects volume hold`() {
        publish()
        store().put(2, sha, bytes.size.toLong(), 0, bytes.copyOfRange(0, 4).inputStream())
        assertEquals(4, store().offset(2, sha))
        assertFailsWith<IllegalStateException> { LocalArtifactStore(root, writable = { false }).reuse(2, sha) }
        assertEquals(bytes.size.toLong(), store().reuse(2, sha))
        assertFalse(Files.exists(root.resolve("2/$sha.partial")))
        assertEquals(sha, hashFile(store().file(2, sha)))
    }

    @Test fun `index retention bounds hints without retaining or deleting job data`() {
        val index = ArtifactReuseIndex(root, maxEntries = 2, ttlMs = 10000)
        val keys = (1..3).map { ExecutionStore.digest("archive-$it") }
        for ((i, key) in keys.withIndex()) {
            val dir = Files.createDirectories(root.resolve((i+1).toString()))
            Files.writeString(dir.resolve(key), "archive-${i+1}")
            index.remember((i+1).toLong(), key)
            Files.setLastModifiedTime(root.resolve(".reuse/$key"), FileTime.fromMillis(now() - 100 + i))
        }
        index.maintain()
        assertNull(index.find(keys[0])); assertNotNull(index.find(keys[1])); assertNotNull(index.find(keys[2]))
        index.maintain(now() + 11000)
        assertTrue(keys.all { index.find(it) == null })
        for ((i, key) in keys.withIndex()) assertTrue(Files.exists(root.resolve("${i+1}/$key")))
    }
    @Test fun `reuse enforces the receiving store size limit`() {
        publish()
        assertNull(LocalArtifactStore(root, maxBytes = 4).reuse(2, sha))
        assertFalse(Files.exists(root.resolve("2/$sha")))
    }

    @Test fun `result journal delegates preparation reuse without consuming internal capacity`() {
        publish()
        ResultJournal(root.resolve("journal"), store(), start = false).use { journal ->
            val before = journal.status().bytes
            assertEquals(bytes.size.toLong(), journal.reuse(2, sha))
            journal.remember(2, sha)
            assertEquals(before, journal.status().bytes)
            assertEquals(0, journal.status().batches)
            assertEquals(sha, hashFile(journal.file(2, sha)))
        }
    }

    @Test fun `fresh complete uploads stream checksums and resumed uploads verify the stored prefix`() {
        assertEquals(bytes.size.toLong(), store().put(1, sha, bytes.size.toLong(), 0, bytes.inputStream()))
        assertContentEquals(bytes, Files.readAllBytes(store().file(1, sha)))
        val wrong = bytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFailsWith<IllegalStateException> { store().put(2, sha, wrong.size.toLong(), 0, wrong.inputStream()) }
        assertFalse(Files.exists(root.resolve("2/$sha.partial")))
        store().put(3, sha, bytes.size.toLong(), 0, bytes.copyOfRange(0, 4).inputStream())
        assertEquals(bytes.size.toLong(), store().put(3, sha, bytes.size.toLong(), 4, bytes.copyOfRange(4, bytes.size).inputStream()))
        assertContentEquals(bytes, Files.readAllBytes(store().file(3, sha)))
        store().put(4, sha, bytes.size.toLong(), 0, wrong.copyOfRange(0, 4).inputStream())
        assertFailsWith<IllegalStateException> { store().put(4, sha, bytes.size.toLong(), 4, bytes.copyOfRange(4, bytes.size).inputStream()) }
        assertFalse(Files.exists(root.resolve("4/$sha.partial")))
        val empty = ExecutionStore.digest("")
        assertEquals(0L, store().put(5, empty, 0, 0, byteArrayOf().inputStream()))
        assertTrue(Files.isRegularFile(store().file(5, empty)))
    }

    @Test fun `complete bounded publication preserves full files and rejects invalid bounds before creating directories`() {
        val values = (0..126).map { n -> ByteArray(if (n == 0) 16384 else 8192) { ((it + n) % 251).toByte() } } + byteArrayOf()
        val payloads = values.associateBy { java.security.MessageDigest.getInstance("SHA-256").digest(it).hex() }
        val descriptors = payloads.map { ResultBlob(it.key, it.value.size.toLong()) }
        assertEquals(128, descriptors.size); assertEquals(INLINE_BYTES.toLong(), descriptors.sumOf { it.bytes })
        store().putComplete(1, descriptors) { payloads.getValue(it.sha).inputStream() }
        store().putComplete(1, descriptors) { error("verified full content must not open a redundant source") }
        for ((key, value) in payloads) assertContentEquals(value, Files.readAllBytes(store().file(1, key)))
        val tiny = (0..128).map { ResultBlob(ExecutionStore.digest("tiny-$it"), 1) }
        assertFailsWith<IllegalArgumentException> { store().putComplete(2, tiny) { error("invalid source opened") } }
        assertFailsWith<IllegalArgumentException> { store().putComplete(3, listOf(ResultBlob(sha, INLINE_BYTES + 1L))) { error("invalid source opened") } }
        assertFailsWith<IllegalArgumentException> { store().putComplete(4, listOf(ResultBlob("invalid", 1))) { error("invalid source opened") } }
        assertFalse(Files.exists(root.resolve("2"))); assertFalse(Files.exists(root.resolve("3"))); assertFalse(Files.exists(root.resolve("4")))
        assertFailsWith<IllegalStateException> { LocalArtifactStore(root, writable = { false }).putComplete(5, listOf(ResultBlob(sha, bytes.size.toLong()))) { bytes.inputStream() } }
        assertFalse(Files.exists(root.resolve("5")))
        assertFailsWith<IllegalArgumentException> { LocalArtifactStore(root, maxBytes = 4).putComplete(6, listOf(ResultBlob(sha, bytes.size.toLong()))) { bytes.inputStream() } }
        assertFalse(Files.exists(root.resolve("6")))
    }

    @Test fun `complete publication never truncates a partial symlink target`() {
        val outside = root.resolve("outside"); Files.writeString(outside, "retained outside bytes")
        Files.createDirectories(root.resolve("1")); Files.createSymbolicLink(root.resolve("1/$sha.partial"), outside)
        assertFailsWith<java.io.IOException> { store().putComplete(1, listOf(ResultBlob(sha, bytes.size.toLong()))) { bytes.inputStream() } }
        assertEquals("retained outside bytes", Files.readString(outside))
        assertFalse(Files.exists(root.resolve("1/$sha")))
    }

    @Test fun `complete publication retains checksum checks source failures and corrupt full rejection`() {
        val descriptor = listOf(ResultBlob(sha, bytes.size.toLong()))
        store().put(1, sha, bytes.size.toLong(), 0, bytes.copyOfRange(0, 3).inputStream())
        assertFailsWith<java.io.IOException> { store().putComplete(1, descriptor) { throw java.io.IOException("source unavailable") } }
        assertEquals(3L, store().offset(1, sha), "an unopened complete source cannot discard existing partial progress")
        assertFailsWith<IllegalStateException> { store().putComplete(1, descriptor) { ByteArray(bytes.size) { 42 }.inputStream() } }
        assertFalse(Files.exists(root.resolve("1/$sha"))); assertFalse(Files.exists(root.resolve("1/$sha.partial")))
        assertFailsWith<IllegalStateException> { store().putComplete(1, descriptor) { bytes.copyOfRange(0, 2).inputStream() } }
        assertFalse(Files.exists(root.resolve("1/$sha")))
        store().putComplete(1, descriptor) { bytes.inputStream() }
        Files.write(root.resolve("1/$sha"), ByteArray(bytes.size) { 42 })
        assertFailsWith<IllegalArgumentException> { store().putComplete(1, descriptor) { error("corrupt full file must not be overwritten from source") } }
        assertContentEquals(ByteArray(bytes.size) { 42 }, Files.readAllBytes(root.resolve("1/$sha")))
    }

    @Test fun `providers without complete publication support retain confirmed offset resume and full verification`() {
        val local = store(); val offsets = mutableListOf<Long>()
        val fallback = object : ArtifactStore {
            override fun offset(job: Long, sha: String) = local.offset(job, sha)
            override fun file(job: Long, sha: String) = local.file(job, sha)
            override fun put(job: Long, sha: String, bytes: Long, offset: Long, input: java.io.InputStream): Long {
                offsets += offset
                return local.put(job, sha, bytes, offset, input)
            }
        }
        local.put(1, sha, bytes.size.toLong(), 0, bytes.copyOfRange(0, 3).inputStream())
        fallback.putComplete(1, listOf(ResultBlob(sha, bytes.size.toLong()))) { bytes.inputStream() }
        fallback.putComplete(1, listOf(ResultBlob(sha, bytes.size.toLong()))) { error("complete fallback must not open source") }
        assertEquals(listOf(3L, bytes.size.toLong()), offsets)
        assertContentEquals(bytes, Files.readAllBytes(local.file(1, sha)))
        Files.write(root.resolve("1/$sha"), ByteArray(bytes.size) { 42 })
        assertFailsWith<IllegalArgumentException> { fallback.putComplete(1, listOf(ResultBlob(sha, bytes.size.toLong()))) { error("source opened") } }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = [false, true])
    fun `complete publication serializes ordinary resumption without mixing bytes`(succeed: Boolean) {
        val local = store(); val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val resumeEntered = java.util.concurrent.CountDownLatch(1); val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            local.put(1, sha, bytes.size.toLong(), 0, bytes.copyOfRange(0, 3).inputStream())
            val complete = pool.submit {
                local.putComplete(1, listOf(ResultBlob(sha, bytes.size.toLong()))) {
                    entered.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    if (succeed) bytes.inputStream() else object : java.io.InputStream() {
                        var sent = false
                        override fun read(): Int = error("bulk expected")
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            if (sent) throw java.io.IOException("interrupted complete source")
                            bytes.copyInto(buffer, offset, 0, 4); sent = true; return 4
                        }
                    }
                }
            }
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val resumed = pool.submit<Long> {
                resumeEntered.countDown()
                local.put(1, sha, bytes.size.toLong(), 3, bytes.copyOfRange(3, bytes.size).inputStream())
            }
            assertTrue(resumeEntered.await(3, java.util.concurrent.TimeUnit.SECONDS)); assertFalse(resumed.isDone)
            release.countDown()
            if (succeed) {
                complete.get(3, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(bytes.size.toLong(), resumed.get(3, java.util.concurrent.TimeUnit.SECONDS))
            } else {
                assertIs<java.io.IOException>(assertFailsWith<java.util.concurrent.ExecutionException> { complete.get(3, java.util.concurrent.TimeUnit.SECONDS) }.cause)
                assertIs<IllegalArgumentException>(assertFailsWith<java.util.concurrent.ExecutionException> { resumed.get(3, java.util.concurrent.TimeUnit.SECONDS) }.cause)
                assertEquals(4L, local.offset(1, sha)); assertFalse(Files.exists(root.resolve("1/$sha")))
                local.putComplete(1, listOf(ResultBlob(sha, bytes.size.toLong()))) { bytes.inputStream() }
            }
            assertContentEquals(bytes, Files.readAllBytes(local.file(1, sha)))
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) }
    }

    @Test fun `complete batch cannot recreate a parent removed after its first completed member`() {
        val second = "second complete artifact".toByteArray(); val secondSha = ExecutionStore.digest(String(second))
        val parent = root.resolve("1"); val removed = root.resolve("removed-complete")
        val descriptors = listOf(ResultBlob(sha, bytes.size.toLong()), ResultBlob(secondSha, second.size.toLong()))
        assertFailsWith<java.io.IOException> {
            store().putComplete(1, descriptors) { blob ->
                if (blob.sha == secondSha) { Files.move(parent, removed, ATOMIC_MOVE); second.inputStream() }
                else bytes.inputStream()
            }
        }
        assertFalse(Files.exists(parent)); assertContentEquals(bytes, Files.readAllBytes(removed.resolve(sha)))
        Files.move(removed, parent, ATOMIC_MOVE)
        store().putComplete(1, descriptors) { if (it.sha == sha) error("full member reopened") else second.inputStream() }
        assertContentEquals(second, Files.readAllBytes(store().file(1, secondSha)))
    }

}
