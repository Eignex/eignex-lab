package com.eignex.lab

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ResultJournalTest {
    private val root = Files.createTempDirectory("result-journal")
    private val external = LocalArtifactStore(root.resolve("external"))
    private val journal = root.resolve("internal")
    private fun blobs(text: String) = mapOf(ExecutionStore.digest(text) to text.toByteArray())
    private fun read(store: ArtifactStore, job: Long, text: String) = store.open(job, ExecutionStore.digest(text)).use { String(it.readAllBytes()) }

    @Test fun `small complete preparation uploads acknowledge durable journal data without external writes`() {
        val blocked = object : ArtifactStore by external {
            override fun put(job: Long, sha: String, bytes: Long, offset: Long, input: java.io.InputStream): Long = error("external write on acknowledgement path")
        }
        val value = "preparation timings"; val sha = ExecutionStore.digest(value)
        ResultJournal(journal, blocked, start = false).use { store ->
            assertEquals(value.length.toLong(), store.put(1, sha, value.length.toLong(), 0, value.byteInputStream()))
            assertEquals(value, read(store, 1, value)); assertEquals(1, store.status().batches)
            assertEquals(0, external.offset(1, sha))
            assertFailsWith<IllegalArgumentException> { store.put(2, "0".repeat(64), 3, 0, "bad".byteInputStream()) }
            assertFailsWith<IllegalArgumentException> { store.put(2, sha, 1, 0, value.byteInputStream()) }
        }
        ResultJournal(journal, external, start = false).use { recovered ->
            assertEquals(value, read(recovered, 1, value)); assertTrue(recovered.drainOne())
            assertEquals(value, read(external, 1, value))
        }
    }

    @Test fun `partial metadata uploads keep resumable offsets and completed retries drain over partial external files`() {
        val value = "timings"; val sha = ExecutionStore.digest(value)
        ResultJournal(journal, external, start = false).use { store ->
            assertEquals(3, store.put(1, sha, 7, 0, "tim".byteInputStream()))
            assertEquals(0, store.status().batches)
            assertEquals(7, store.put(1, sha, 7, 3, "ings".byteInputStream()))
            assertEquals(value, read(store, 1, value))
            store.put(2, sha, 7, 0, value.byteInputStream())
            // An ambiguous earlier external transfer can leave a partial prefix beside the journal.
            assertEquals(3, external.put(2, sha, 7, 0, "tim".byteInputStream()))
        }
        ResultJournal(journal, external, start = false).use { recovered ->
            assertTrue(recovered.drainOne()); assertEquals(value, read(external, 2, value))
        }
    }

    @Test fun `small preparation uploads respect the journal cap instead of spilling onto the acknowledgement path`() {
        ResultJournal(journal, external, maxBytes = 8192, start = false).use { store ->
            assertFailsWith<IllegalStateException> { store.put(1, ExecutionStore.digest("data"), 4, 0, "data".byteInputStream()) }
            assertEquals(0, external.offset(1, ExecutionStore.digest("data")))
            assertTrue(store.status().bytes <= 8192)
        }
    }

    @Test fun `a failed publication sync cannot acknowledge a deduplicated retry without another durability barrier`() {
        var fail = false; var barriers = 0
        ResultJournal(journal, external, start = false, syncDirectory = {
            barriers++
            if (fail) { fail = false; throw java.io.IOException("injected directory sync failure") }
            java.nio.channels.FileChannel.open(it, java.nio.file.StandardOpenOption.READ).use { channel -> channel.force(true) }
        }).use { store ->
            fail = true
            assertFailsWith<java.io.IOException> { store.putAll(1, blobs("result")) }
            val failedAt = barriers
            assertFalse(store.drainOne())
            store.putAll(1, blobs("result"))
            assertEquals(failedAt + 1, barriers)
            assertEquals("result", read(store, 1, "result")); assertTrue(store.drainOne())
        }
    }

    @Test fun `durable pending outputs survive restart deduplicate retries and drain to stable external files`() {
        val values = blobs("result") + blobs("")
        ResultJournal(journal, external, start = false).use { store ->
            store.putAll(1, values); store.putAll(1, values)
            assertEquals(1, store.status().batches)
            assertEquals("result", read(store, 1, "result")); assertEquals("", read(store, 1, ""))
            assertEquals(6, store.offset(1, ExecutionStore.digest("result")))
            assertFailsWith<IllegalArgumentException> { external.file(1, ExecutionStore.digest("result")) }
        }
        ResultJournal(journal, external, start = false).use { recovered ->
            assertEquals("result", read(recovered, 1, "result"))
            assertTrue(recovered.drainOne()); assertEquals(0, recovered.status().batches)
            assertEquals("result", read(external, 1, "result")); assertEquals("", read(external, 1, ""))
            assertEquals("result", read(recovered, 1, "result"))
        }
    }

    @Test fun `blocked external writes cannot delay reading or acknowledging another bounded batch`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val blocked = object : ArtifactStore by external {
            override fun putAll(job: Long, blobs: Map<String, ByteArray>) {
                entered.countDown(); check(release.await(10, SECONDS)); external.putAll(job, blobs)
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        ResultJournal(journal, blocked, start = false).use { store ->
            try {
                store.putAll(1, blobs("first"))
                val drain = pool.submit<Boolean> { store.drainOne() }
                assertTrue(entered.await(2, SECONDS))
                val stream = store.open(1, ExecutionStore.digest("first"))
                pool.submit { store.putAll(2, blobs("second")); assertEquals("second", read(store, 2, "second")) }.get(2, SECONDS)
                assertEquals(2, store.status().batches)
                release.countDown(); assertTrue(drain.get(3, SECONDS))
                // Already-open streams own their bytes, even after the pack is drained and reclaimed.
                stream.use { assertEquals("first", String(it.readAllBytes())) }
                assertEquals(1, store.status().batches)
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun `cap refuses new batches without removing pending accepted bytes and recovers capacity after drain`() {
        ResultJournal(journal, external, maxBytes = 32768, start = false).use { store ->
            val first = blobs("a".repeat(6000)); val second = blobs("b".repeat(6000))
            store.putAll(1, first)
            assertFailsWith<IllegalStateException> { store.putAll(2, second) }
            assertEquals("a".repeat(6000), read(store, 1, "a".repeat(6000)))
            assertTrue(store.status().bytes <= 32768)
            assertTrue(Files.list(journal).use { it.mapToLong(Files::size).sum() } <= 32768)
            assertTrue(store.drainOne()); store.putAll(2, second)
            assertEquals("b".repeat(6000), read(store, 2, "b".repeat(6000)))
        }
    }

    @Test fun `partial external publication preserves the complete journal for recovery`() {
        val partial = object : ArtifactStore by external {
            override fun putAll(job: Long, blobs: Map<String, ByteArray>) {
                val (sha, bytes) = blobs.entries.first()
                external.put(job, sha, bytes.size.toLong(), 0, bytes.inputStream())
                error("external volume disappeared")
            }
        }
        ResultJournal(journal, partial, start = false).use { store ->
            store.putAll(7, blobs("one") + blobs("two"))
            assertFailsWith<IllegalStateException> { store.drainOne() }
            assertEquals(1, store.status().batches); assertEquals("two", read(store, 7, "two"))
        }
        ResultJournal(journal, external, start = false).use { recovered ->
            assertTrue(recovered.drainOne()); assertEquals("one", read(external, 7, "one")); assertEquals("two", read(external, 7, "two"))
        }
    }

    @Test fun `job scope checksum validation and exclusive journal ownership stay enforced`() {
        ResultJournal(journal, external, start = false).use { store ->
            store.putAll(1, blobs("content"))
            assertFailsWith<IllegalArgumentException> { read(store, 2, "content") }
            assertFailsWith<IllegalArgumentException> { store.putAll(1, mapOf("a".repeat(64) to "wrong".toByteArray())) }
            assertFails { ResultJournal(journal, external, start = false) }
            assertEquals("content", read(store, 1, "content"))
            assertEquals("content", Files.readString(store.file(1, ExecutionStore.digest("content"))))
            assertEquals(0, store.status().batches)
        }
    }

    @Test fun `recovery removes unacknowledged temporary files but fails closed on corrupted pending data`() {
        ResultJournal(journal, external, start = false).use { it.putAll(1, blobs("content")) }
        Files.writeString(journal.resolve(".pending-interrupted.tmp"), "not acknowledged")
        val pack = Files.list(journal).use { it.filter { p -> p.toString().endsWith(".zip") }.findFirst().orElseThrow() }
        val entries = ZipFile(pack.toFile()).use { zip -> zip.entries().toList().associate { it.name to zip.getInputStream(it).readAllBytes() } }
        ZipOutputStream(Files.newOutputStream(pack)).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name)); zip.write(if (name == "manifest.json") bytes else "corrupt".toByteArray()); zip.closeEntry()
            }
        }
        assertFails { ResultJournal(journal, external, start = false) }
        assertFalse(Files.exists(journal.resolve(".pending-interrupted.tmp")))
        assertTrue(Files.exists(pack))
    }
}
