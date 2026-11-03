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

}
