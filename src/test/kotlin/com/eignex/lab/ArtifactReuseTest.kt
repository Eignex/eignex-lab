package com.eignex.lab

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.*

class ArtifactReuseTest {
    private val root = Files.createTempDirectory("artifact-reuse")
    private val bytes = "verified prepared distribution".toByteArray()
    private val sha = ExecutionStore.digest(String(bytes))
    private fun store() = LocalArtifactStore(root)
    private fun publish(job: Long = 1) { store().put(job, sha, bytes.size.toLong(), 0, bytes.inputStream()); store().remember(job, sha) }

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

}
