package com.eignex.lab

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
