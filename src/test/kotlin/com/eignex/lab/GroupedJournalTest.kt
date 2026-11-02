package com.eignex.lab

import com.eignex.lab.store.*
import com.eignex.lab.util.hex
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.*

class GroupedJournalTest {
    private val root = Files.createTempDirectory("grouped-journal")
    private val external = LocalArtifactStore(root.resolve("external"))
    private val path = root.resolve("journal")
    private fun blobs(text: String) = mapOf(ExecutionStore.digest(text) to text.toByteArray())
    private fun read(store: ArtifactStore, job: Long, text: String) = store.open(job, ExecutionStore.digest(text)).use { String(it.readAllBytes()) }
    private fun destination(flush: (Path) -> Unit): ArtifactStore = object : ArtifactStore by external {
        override fun publishBatch(job: Long, publish: () -> Unit) = external.publishBatch(job, flush, publish)
    }

    @Test fun `same job packs share a waited barrier with bounded count and separate job ownership`() {
        val publishedJobs = mutableListOf<Long>(); var retained = 0
        lateinit var store: ResultJournal
        val destination = destination { directory ->
            publishedJobs.add(directory.fileName.toString().toLong())
            assertEquals(retained, store.status().batches, "no selected pack may be reclaimed before the shared barrier")
        }
        ResultJournal(path, destination, start = false).use {
            store = it
            repeat(136) { n -> store.putAll(1, blobs("first-$n")) }
            repeat(2) { n -> store.putAll(2, blobs("second-$n")) }
            retained = 138; assertTrue(store.drainGroup()); assertEquals(10, store.status().batches)
            retained = 10; assertTrue(store.drainGroup()); assertEquals(2, store.status().batches)
            retained = 2; assertTrue(store.drainGroup()); assertEquals(0, store.status().batches)
            assertEquals(listOf(1L, 1L, 2L), publishedJobs)
            repeat(136) { n -> assertEquals("first-$n", read(store, 1, "first-$n")) }
        }
    }

    @Test fun `group bytes are bounded and an existing oversized pack publishes alone`() {
        var barriers = 0
        ResultJournal(path, destination { barriers++ }, start = false).use { store ->
            repeat(9) { n ->
                val bytes = ByteArray(512 * 1024) { ((it + n) % 251).toByte() }
                store.putAll(1, mapOf(MessageDigest.getInstance("SHA-256").digest(bytes).hex() to bytes))
            }
            assertTrue(store.drainGroup()); assertEquals(1, store.status().batches); assertEquals(1, barriers)
            assertTrue(store.drainGroup()); assertEquals(0, store.status().batches)
            val bytes = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() }
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
            store.putPacked(2, listOf(ResultBlob(sha, bytes.size.toLong())), bytes.inputStream())
            store.putAll(2, blobs("small"))
            assertTrue(store.drainGroup()); assertEquals(1, store.status().batches)
            assertContentEquals(bytes, store.open(2, sha).use { it.readAllBytes() })
            assertTrue(store.drainGroup()); assertEquals(0, store.status().batches); assertEquals(4, barriers)
        }
    }

    @Test fun `a failed shared barrier retains every pack and recovery repeats the barrier over complete files`() {
        var fail = true; var barriers = 0; var clock = 1000L
        val destination = destination { barriers++; if (fail) throw IOException("injected shared barrier failure") }
        ResultJournal(path, destination, start = false, retryClock = { clock }).use { store ->
            repeat(4) { n -> store.putAll(1, blobs("value-$n")) }
            val charged = store.status().bytes
            assertFailsWith<IOException> { store.drainGroup() }
            assertEquals(4, store.status().batches); assertEquals(charged, store.status().bytes)
            assertFalse(store.drainGroup(), "failed members keep their retry backoff")
            repeat(4) { n -> assertEquals("value-$n", read(store, 1, "value-$n")) }
        }
        fail = false
        ResultJournal(path, destination, start = false).use { recovered ->
            assertTrue(recovered.drainGroup()); assertEquals(0, recovered.status().batches)
            assertEquals(2, barriers, "complete external files still require the previously failed waited barrier")
        }
    }

    @Test fun `a permanently bad pack cannot trap healthy members of its failed group`() {
        var clock = 1000L
        val bad = ExecutionStore.digest("value-1")
        val destination = object : ArtifactStore by external {
            override fun publishBatch(job: Long, publish: () -> Unit) = external.publishBatch(job, { }, publish)
            override fun putAll(job: Long, blobs: Map<String, ByteArray>) {
                if (bad in blobs) throw IOException("permanently failing pack")
                external.putAll(job, blobs)
            }
        }
        ResultJournal(path, destination, start = false, retryClock = { clock }).use { store ->
            repeat(5) { n -> store.putAll(1, blobs("value-$n")) }
            val charged = store.status().bytes
            assertFailsWith<IOException> { store.drainGroup() }
            assertEquals(5, store.status().batches); assertEquals(charged, store.status().bytes)
            assertFalse(store.drainGroup())
            clock += 5000
            repeat(5) { try { store.drainGroup() } catch (_: IOException) { } }
            assertEquals(1, store.status().batches)
            assertTrue(store.status().bytes > 8192); assertTrue(store.status().bytes < charged)
            assertEquals("IOException", store.status().lastError)
            assertEquals("value-1", read(store, 1, "value-1"))
            for (n in listOf(0, 2, 3, 4)) assertEquals("value-$n", read(external, 1, "value-$n"))
            assertFalse(store.drainGroup())
            clock += 5000
            assertFailsWith<IOException> { store.drainGroup() }
            assertEquals(1, store.status().batches)
        }
    }

    @Test fun `restart retries a partially written group without losing acknowledged packs`() {
        var fail = true; var barriers = 0
        val destination = object : ArtifactStore by external {
            override fun publishBatch(job: Long, publish: () -> Unit) = external.publishBatch(job, { barriers++ }, publish)
            override fun putAll(job: Long, blobs: Map<String, ByteArray>) {
                if (fail && ExecutionStore.digest("second") in blobs) throw IOException("interrupted group publication")
                external.putAll(job, blobs)
            }
        }
        ResultJournal(path, destination, start = false).use { store ->
            for (text in listOf("first", "second", "third")) store.putAll(1, blobs(text))
            assertFailsWith<IOException> { store.drainGroup() }
            assertEquals(3, store.status().batches); assertEquals(0, barriers)
            assertEquals("first", read(external, 1, "first"))
            assertEquals(0L, external.offset(1, ExecutionStore.digest("second")))
            assertEquals(0L, external.offset(1, ExecutionStore.digest("third")))
        }
        fail = false
        ResultJournal(path, destination, start = false).use { recovered ->
            assertTrue(recovered.drainGroup()); assertEquals(0, recovered.status().batches)
            assertEquals(1, barriers)
            for (text in listOf("first", "second", "third")) assertEquals(text, read(recovered, 1, text))
        }
    }

    @Test fun `concurrent groups reserve disjoint packs without blocking readers or another acknowledgement`() {
        val entered = CountDownLatch(2); val release = CountDownLatch(1)
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val destination = object : ArtifactStore by external {
            override fun publishBatch(job: Long, publish: () -> Unit) = external.publishBatch(job, {
                entered.countDown(); check(release.await(10, SECONDS))
            }, publish)
            override fun putAll(job: Long, blobs: Map<String, ByteArray>) {
                for (sha in blobs.keys) assertTrue(seen.add(sha), "no pack may have two publishing owners")
                external.putAll(job, blobs)
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        ResultJournal(path, destination, start = false).use { store ->
            try {
                repeat(256) { n -> store.putAll(1, blobs("value-$n")) }
                val drains = (1..2).map { pool.submit<Boolean> { store.drainGroup() } }
                assertTrue(entered.await(5, SECONDS))
                assertEquals(256, seen.size); assertEquals(256, store.status().batches)
                assertEquals("value-0", read(store, 1, "value-0")); assertEquals("value-255", read(store, 1, "value-255"))
                store.putAll(2, blobs("new")); assertEquals("new", read(store, 2, "new"))
                release.countDown(); drains.forEach { assertTrue(it.get(3, SECONDS)) }
                assertEquals(1, store.status().batches)
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun `post barrier cleanup failures release all reservations without republishing flushed members`() {
        var failCleanup = false; var clock = 1000L; var barriers = 0
        ResultJournal(path, destination { barriers++ }, start = false, retryClock = { clock }, syncDirectory = {
            if (failCleanup) { failCleanup = false; throw IOException("injected cleanup barrier failure") }
        }).use { store ->
            repeat(3) { n -> store.putAll(1, blobs("value-$n")) }
            failCleanup = true
            assertFailsWith<IOException> { store.drainGroup() }
            assertEquals(1, barriers); assertEquals(1, store.status().batches)
            assertEquals(1L, store.status().drainErrors)
            assertTrue(store.status().bytes > 8192)
            assertFalse(store.drainGroup()); assertEquals(1, store.status().batches)
            clock += 5000
            assertFalse(store.drainGroup()); assertEquals(0, store.status().batches)
            assertEquals(8192L, store.status().bytes); assertEquals(1, barriers)
            repeat(3) { n -> assertEquals("value-$n", read(store, 1, "value-$n")) }
        }
    }
}
