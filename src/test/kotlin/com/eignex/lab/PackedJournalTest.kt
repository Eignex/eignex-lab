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

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.*

class PackedJournalTest {
    private val root = Files.createTempDirectory("packed-journal")
    private val external = LocalArtifactStore(root.resolve("external"))
    private val bytes = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
    private val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private val blobs = listOf(ResultBlob(sha, bytes.size.toLong()))

    @Test fun `mixed packed retries reserve and publish only new blobs within the disk cap`() {
        val fresh = "fresh".toByteArray(); val freshSha = ExecutionStore.digest("fresh")
        val mixed = blobs + ResultBlob(freshSha, fresh.size.toLong())
        val writes = mutableListOf<String>()
        val counted = object : ArtifactStore by external {
            override fun put(job: Long, sha: String, bytes: Long, offset: Long, input: InputStream): Long {
                writes += sha; return external.put(job, sha, bytes, offset, input)
            }
            // Keep this provider's publication fault/counter on the new complete-input path too.
            override fun putComplete(job: Long, blobs: List<ResultBlob>, source: (ResultBlob) -> java.io.InputStream) {
                putAll(job, blobs.associate { blob -> blob.sha to source(blob).use { it.readAllBytes() } })
            }
            override fun putAll(job: Long, blobs: Map<String, ByteArray>) {
                writes.addAll(blobs.keys); external.putAll(job, blobs)
            }
        }
        val path = root.resolve("journal")
        val limit = 4L * 1024 * 1024
        ResultJournal(path, counted, maxBytes = limit, start = false).use { journal ->
            journal.putPacked(1, blobs, bytes.inputStream())
            val before = journal.status().bytes
            journal.putPacked(1, mixed, (bytes + fresh).inputStream())
            assertEquals(2, journal.status().batches)
            assertTrue(journal.status().bytes - before < 32 * 1024, "duplicate payload cannot consume another reservation or pack")
            assertTrue(journal.status().bytes <= limit)
        }
        ResultJournal(path, counted, maxBytes = limit, start = false).use { recovered ->
            assertEquals(2, recovered.status().batches)
            assertTrue(recovered.drainGroup()); assertEquals(0, recovered.status().batches)
            assertEquals(mapOf(sha to 1, freshSha to 1), writes.groupingBy { it }.eachCount())
            assertContentEquals(bytes, external.open(1, sha).use { it.readAllBytes() })
            assertContentEquals(fresh, external.open(1, freshSha).use { it.readAllBytes() })
        }
    }

    @Test fun `discarded duplicate bytes still pass checksums and failures return only the new reservation`() {
        val fresh = "fresh".toByteArray(); val freshSha = ExecutionStore.digest("fresh")
        val mixed = blobs + ResultBlob(freshSha, fresh.size.toLong())
        ResultJournal(root.resolve("journal"), external, maxBytes = 4L * 1024 * 1024, start = false).use { journal ->
            journal.putPacked(1, blobs, bytes.inputStream())
            val before = journal.status().bytes
            val corrupt = bytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
            assertFailsWith<IllegalArgumentException> { journal.putPacked(1, mixed, (corrupt + fresh).inputStream()) }
            assertEquals(before, journal.status().bytes); assertEquals(1, journal.status().batches)
            assertEquals(0, journal.offset(1, freshSha))
            assertContentEquals(bytes, journal.open(1, sha).use { it.readAllBytes() })
            journal.putPacked(1, mixed, (bytes + fresh).inputStream())
            assertEquals(2, journal.status().batches)
        }
    }

    @Test fun `mixed retries cannot discard existing references until their failed publication barrier recovers`() {
        val fresh = "fresh".toByteArray(); val freshSha = ExecutionStore.digest("fresh")
        val mixed = blobs + ResultBlob(freshSha, fresh.size.toLong())
        var fail = false
        val path = root.resolve("journal")
        val limit = 4L * 1024 * 1024
        ResultJournal(path, external, maxBytes = limit, start = false, syncDirectory = {
            if (fail) throw java.io.IOException("injected sync failure")
            java.nio.channels.FileChannel.open(it, java.nio.file.StandardOpenOption.READ).use { channel -> channel.force(true) }
        }).use { journal ->
            fail = true
            assertFailsWith<java.io.IOException> { journal.putPacked(1, blobs, bytes.inputStream()) }
            val before = journal.status().bytes
            val unread = object : InputStream() { override fun read(): Int = error("existing reference barrier must precede body") }
            assertFailsWith<java.io.IOException> { journal.putPacked(1, mixed, unread) }
            assertEquals(before, journal.status().bytes); assertEquals(1, journal.status().batches)
            assertFalse(journal.drainOne())
            fail = false
            journal.putPacked(1, mixed, (bytes + fresh).inputStream())
            assertEquals(2, journal.status().batches); assertTrue(journal.status().bytes <= limit)
        }
        ResultJournal(path, external, maxBytes = limit, start = false).use { recovered ->
            assertTrue(recovered.drainGroup()); assertEquals(8192, recovered.status().bytes)
            assertContentEquals(bytes, external.open(1, sha).use { it.readAllBytes() })
            assertContentEquals(fresh, external.open(1, freshSha).use { it.readAllBytes() })
        }
    }

    @Test fun `competing mixed senders retain only new reservations while their shared duplicate drains`() {
        val entered = CountDownLatch(2); val release = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        val fresh = listOf("first", "second").map { it.toByteArray() }
        val path = root.resolve("journal"); val limit = 4L * 1024 * 1024
        try {
            ResultJournal(path, external, maxBytes = limit, start = false).use { journal ->
                journal.putPacked(1, blobs, bytes.inputStream())
                val futures = fresh.map { value -> pool.submit {
                    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(value).hex()
                    val input = object : ByteArrayInputStream(bytes + value) {
                        private var waiting = true
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            if (waiting) { waiting = false; entered.countDown(); check(release.await(5, SECONDS)) }
                            return super.read(buffer, offset, length)
                        }
                    }
                    journal.putPacked(1, blobs + ResultBlob(hash, value.size.toLong()), input)
                } }
                assertTrue(entered.await(3, SECONDS), "both small new reservations must fit beside the retained duplicate")
                assertTrue(journal.status().bytes <= limit)
                assertTrue(journal.drainOne(), "receivers cannot hold the commit monitor or pin duplicate packs")
                release.countDown(); futures.forEach { it.get(5, SECONDS) }
                assertEquals(2, journal.status().batches)
            }
            ResultJournal(path, external, maxBytes = limit, start = false).use { recovered ->
                assertEquals(2, recovered.status().batches); assertTrue(recovered.status().bytes < 64 * 1024)
                assertTrue(recovered.drainGroup()); assertEquals(8192, recovered.status().bytes)
                for (value in fresh + listOf(bytes)) {
                    val hash = java.security.MessageDigest.getInstance("SHA-256").digest(value).hex()
                    assertContentEquals(value, external.open(1, hash).use { it.readAllBytes() })
                }
            }
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `a near maximum capture reserves ZIP framing without exceeding the disk cap`() {
        val large = ByteArray(PACKED_BYTES - 1024)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(large).hex()
        ResultJournal(root.resolve("maximum"), external, start = false).use { journal ->
            journal.putPacked(1, listOf(ResultBlob(hash, large.size.toLong())), large.inputStream())
            assertEquals(large.size.toLong(), journal.offset(1, hash))
            assertTrue(journal.status().bytes <= journal.status().limit)
            assertTrue(journal.drainOne())
        }
    }

    @Test fun `a failed packed publication barrier is retained and a duplicate retry syncs before acceptance`() {
        var fail = false
        ResultJournal(root.resolve("journal"), external, start = false, syncDirectory = {
            if (fail) { fail = false; throw java.io.IOException("injected sync failure") }
            java.nio.channels.FileChannel.open(it, java.nio.file.StandardOpenOption.READ).use { channel -> channel.force(true) }
        }).use { journal ->
            fail = true
            assertFailsWith<java.io.IOException> { journal.putPacked(1, blobs, bytes.inputStream()) }
            assertEquals(1, journal.status().batches); assertFalse(journal.drainOne())
            journal.putPacked(1, blobs, bytes.inputStream())
            assertEquals(1, journal.status().batches); assertTrue(journal.drainOne())
        }
    }

    @Test fun `large streamed captures survive restart and external publication before reclaim`() {
        val path = root.resolve("journal")
        ResultJournal(path, external, start = false).use { journal ->
            journal.putPacked(1, blobs, bytes.inputStream())
            journal.open(1, sha).use { assertContentEquals(bytes, it.readAllBytes()) }
            assertEquals(1, journal.status().batches)
            assertFailsWith<IllegalArgumentException> { external.file(1, sha) }
        }
        ResultJournal(path, external, start = false).use { journal ->
            assertTrue(journal.drainOne()); assertEquals(0, journal.status().batches)
            assertEquals(sha, hashFile(external.file(1, sha))); assertNotNull(journal.status().lastDrainAt)
        }
    }

    @Test fun `truncated corrupt and oversized bodies cannot publish or leak reservations`() {
        ResultJournal(root.resolve("journal"), external, start = false).use { journal ->
            for (body in listOf(byteArrayOf(1), bytes + byteArrayOf(1), ByteArray(bytes.size))) {
                assertFailsWith<IllegalArgumentException> { journal.putPacked(1, blobs, body.inputStream()) }
                assertEquals(0, journal.status().batches); assertEquals(8192, journal.status().bytes)
            }
            assertFailsWith<IllegalArgumentException> { journal.putPacked(1, listOf(ResultBlob(sha, PACKED_BYTES + 1L)), bytes.inputStream()) }
        }
    }

    @Test fun `slow senders reserve quota but never block a small commit`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val pool = Executors.newSingleThreadExecutor()
        ResultJournal(root.resolve("journal"), external, maxBytes = 4L * 1024 * 1024, start = false).use { journal ->
            val input = object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown(); check(release.await(5, SECONDS)); return super.read(buffer, offset, length)
                }
            }
            try {
                val sending = pool.submit { journal.putPacked(1, blobs, input) }
                assertTrue(entered.await(2, SECONDS))
                journal.putAll(2, mapOf(ExecutionStore.digest("small") to "small".toByteArray()))
                assertTrue(journal.status().bytes > bytes.size)
                val unread = object : InputStream() { override fun read(): Int = error("quota checked before body") }
                assertFailsWith<IllegalStateException> { journal.putPacked(3, blobs, unread) }
                release.countDown(); sending.get(3, SECONDS)
                assertEquals(2, journal.status().batches)
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun `failed external drains report retained data and clear the error after recovery`() {
        var fail = true
        val unreliable = object : ArtifactStore by external {
            override fun put(job: Long, sha: String, bytes: Long, offset: Long, input: InputStream): Long {
                if (fail) throw java.io.IOException("offline")
                return external.put(job, sha, bytes, offset, input)
            }
        }
        ResultJournal(root.resolve("journal"), unreliable, start = false).use { journal ->
            journal.putPacked(1, blobs, bytes.inputStream())
            assertFailsWith<java.io.IOException> { journal.drainOne() }
            assertEquals(1, journal.status().drainErrors); assertEquals("IOException", journal.status().lastError)
            assertEquals(1, journal.status().batches)
            fail = false
            // The stable-path compatibility path explicitly requests publication without the background retry delay.
            assertEquals(sha, hashFile(journal.file(1, sha))); assertNull(journal.status().lastError)
        }
    }
}
