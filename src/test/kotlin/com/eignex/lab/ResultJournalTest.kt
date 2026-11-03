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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.*

class ResultJournalTest {
    private val root = Files.createTempDirectory("result-journal")
    private val external = LocalArtifactStore(root.resolve("external"))
    private val journal = root.resolve("internal")
    private fun blobs(text: String) = mapOf(ExecutionStore.digest(text) to text.toByteArray())
    private fun read(store: ArtifactStore, job: Long, text: String) = store.open(job, ExecutionStore.digest(text)).use { String(it.readAllBytes()) }

    @Test fun `group reclamation shares one internal barrier without releasing undrained capacity`() {
        var syncs = 0
        ResultJournal(journal, external, start = false, syncDirectory = { syncs++ }).use { store ->
            repeat(130) { store.putAll(1, blobs("result-$it")) }
            val charged = store.status().bytes; val before = syncs
            assertTrue(store.drainGroup())
            assertEquals(before + 1, syncs); assertEquals(2, store.status().batches)
            assertTrue(store.status().bytes in 8193 until charged)
            assertEquals("result-0", read(external, 1, "result-0"))
            assertEquals("result-129", read(store, 1, "result-129"))
            assertTrue(store.drainGroup()); assertEquals(before + 2, syncs)
            assertEquals(8192L, store.status().bytes)
        }
    }

    @Test fun `failed shared reclamation retains every charge and retries missing paths without republication`() {
        var syncs = 0; var fail = false; var at = 1000L; var publications = 0
        val destination = object : ArtifactStore by external {
            override fun putAll(job: Long, blobs: Map<String, ByteArray>) { publications++; external.putAll(job, blobs) }
        }
        ResultJournal(journal, destination, maxBytes = 2L * 1024 * 1024, start = false, retryClock = { at }, syncDirectory = {
            syncs++; if (fail) { fail = false; throw java.io.IOException("injected shared reclamation failure") }
        }).use { store ->
            repeat(128) { store.putAll(1, blobs("result-$it")) }
            val charged = store.status().bytes; val before = syncs
            fail = true
            assertFailsWith<java.io.IOException> { store.drainGroup() }
            assertEquals(before + 1, syncs); assertEquals(charged, store.status().bytes)
            assertEquals(128, store.status().batches)
            assertFailsWith<IllegalStateException> { store.putAll(2, blobs("x".repeat(1024 * 1024))) }
            assertEquals(charged, store.status().bytes)
            assertFalse(store.drainGroup()); assertEquals(before + 1, syncs)
            assertEquals(0L, Files.list(journal).use { paths -> paths.filter { it.toString().endsWith(".zip") }.count() })
            at += 5000
            assertFalse(store.drainGroup()); assertEquals(before + 2, syncs)
            assertEquals(8192L, store.status().bytes); assertEquals(0, store.status().batches)
            assertEquals(128, publications)
        }
        ResultJournal(journal, destination, start = false).use { recovered ->
            assertEquals(0, recovered.status().batches); assertEquals(8192L, recovered.status().bytes)
            assertEquals("result-127", read(recovered, 1, "result-127"))
            assertEquals(128, publications)
        }
    }

    @Test fun `restart after a failed grouped deletion barrier retains externally durable results`() {
        var fail = false
        ResultJournal(journal, external, start = false, syncDirectory = {
            if (fail) throw java.io.IOException("injected grouped deletion barrier failure")
        }).use { store ->
            repeat(3) { store.putAll(1, blobs("result-$it")) }
            val charged = store.status().bytes
            fail = true
            assertFailsWith<java.io.IOException> { store.drainGroup() }
            assertEquals(charged, store.status().bytes); assertEquals(3, store.status().batches)
        }
        ResultJournal(journal, external, start = false).use { recovered ->
            assertEquals(0, recovered.status().batches); assertEquals(8192L, recovered.status().bytes)
            repeat(3) { assertEquals("result-$it", read(recovered, 1, "result-$it")) }
        }
    }

    @Test fun `partial deletion failure reclaims only shared-synced peers and retains the failed charge`() {
        var held: Path? = null; var at = 1000L; var syncs = 0; var publications = 0
        val destination = object : ArtifactStore by external {
            override fun publishBatch(job: Long, publish: () -> Unit) {
                publications++; publish()
                if (held == null) {
                    val path = Files.list(journal).use { paths -> paths.filter { it.toString().endsWith(".zip") }.findFirst().orElseThrow() }
                    Files.delete(path); Files.createDirectory(path); Files.writeString(path.resolve("busy"), "retained")
                    held = path
                }
            }
        }
        ResultJournal(journal, destination, start = false, retryClock = { at }, syncDirectory = { syncs++ }).use { store ->
            repeat(3) { store.putAll(1, blobs("result-$it")) }
            val charged = store.status().bytes; val before = syncs
            assertFailsWith<java.nio.file.DirectoryNotEmptyException> { store.drainGroup() }
            assertEquals(before + 1, syncs); assertEquals(1, store.status().batches)
            assertTrue(store.status().bytes in 8193 until charged)
            repeat(3) { assertEquals("result-$it", read(store, 1, "result-$it")) }
            assertFalse(store.drainGroup()); assertEquals(before + 1, syncs)
            val path = requireNotNull(held); Files.delete(path.resolve("busy")); Files.delete(path)
            at += 5000
            assertFalse(store.drainGroup()); assertEquals(before + 2, syncs)
            assertEquals(8192L, store.status().bytes); assertEquals(1, publications)
        }
    }

    @Test fun `another lane cannot reclaim packs reserved by a blocked group`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val destination = object : ArtifactStore by external {
            override fun publishBatch(job: Long, publish: () -> Unit) {
                if (job == 1L) { entered.countDown(); check(release.await(10, SECONDS)) }
                publish()
            }
        }
        val pool = Executors.newSingleThreadExecutor()
        ResultJournal(journal, destination, start = false).use { store ->
            try {
                repeat(128) { store.putAll(1, blobs("reserved-$it")) }
                store.putAll(2, blobs("other"))
                val drain = pool.submit<Boolean> { store.drainGroup() }
                assertTrue(entered.await(3, SECONDS))
                assertTrue(store.drainGroup()); assertEquals(128, store.status().batches)
                assertEquals(128L, Files.list(journal).use { paths -> paths.filter { it.toString().endsWith(".zip") }.count() })
                assertEquals("reserved-0", read(store, 1, "reserved-0"))
                assertEquals("other", read(external, 2, "other"))
                release.countDown(); assertTrue(drain.get(5, SECONDS))
                assertEquals(0, store.status().batches); assertEquals(8192L, store.status().bytes)
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun `external batch barriers precede reclamation and repeat after restart with complete files`(large: Boolean) {
        val payloads = (0..1).map { value -> ByteArray(if (large) 600 * 1024 else 16 * 1024) { ((it + value) % 251).toByte() } }
            .associateBy { java.security.MessageDigest.getInstance("SHA-256").digest(it).hex() }
        var barriers = 0
        var fail = true
        fun destination(): ArtifactStore {
            val local = LocalArtifactStore(root.resolve("external"))
            return object : ArtifactStore by local {
                override fun publishBatch(job: Long, publish: () -> Unit) = local.publishBatch(job, { directory ->
                    assertEquals(root.resolve("external/$job"), directory)
                    for ((sha, bytes) in payloads) assertContentEquals(bytes, Files.readAllBytes(local.file(job, sha)))
                    barriers++
                    if (fail) throw java.io.IOException("injected volume barrier failure")
                }, publish)
            }
        }
        ResultJournal(journal, destination(), start = false).use { store ->
            val body = java.io.ByteArrayOutputStream().apply { payloads.values.forEach { write(it) } }.toByteArray()
            store.putPacked(1, payloads.map { ResultBlob(it.key, it.value.size.toLong()) }, body.inputStream())
            assertFailsWith<java.io.IOException> { store.drainOne() }
            assertEquals(1, barriers); assertEquals(1, store.status().batches)
            for ((sha, bytes) in payloads) assertContentEquals(bytes, store.open(1, sha).use { it.readAllBytes() })
        }
        fail = false
        ResultJournal(journal, destination(), start = false).use { recovered ->
            assertTrue(recovered.drainOne())
            assertEquals(2, barriers, "already complete external files still need the failed barrier retried")
            assertEquals(0, recovered.status().batches)
            for ((sha, bytes) in payloads) assertContentEquals(bytes, external.open(1, sha).use { it.readAllBytes() })
        }
    }

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
            assertEquals(1, store.status().phases.getValue(ResultJournal.Phase.unconfirmedInternal).batches)
            assertEquals(ResultJournal.Phase.unconfirmedInternal, store.status().oldestPhase)
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

    @Test fun `two drainers reserve distinct packs while both external publications are blocked`() {
        val entered = CountDownLatch(2); val release = CountDownLatch(1)
        val jobs = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        val blocked = object : ArtifactStore by external {
            override fun putAll(job: Long, blobs: Map<String, ByteArray>) {
                assertTrue(jobs.add(job), "a pack must have exactly one publishing owner")
                entered.countDown(); check(release.await(10, SECONDS)); external.putAll(job, blobs)
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        ResultJournal(journal, blocked, start = false).use { store ->
            try {
                store.putAll(1, blobs("first")); store.putAll(2, blobs("second"))
                val drains = (1..2).map { pool.submit<Boolean> { store.drainOne() } }
                assertTrue(entered.await(3, SECONDS), "both lanes must claim work before either publication finishes")
                assertEquals(setOf(1L, 2L), jobs)
                assertEquals("first", read(store, 1, "first")); assertEquals("second", read(store, 2, "second"))
                release.countDown(); drains.forEach { assertTrue(it.get(3, SECONDS)) }
                assertEquals(0, store.status().batches)
            } finally { release.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun `a failed publication backs off its pack while another job can drain`() {
        val destination = object : ArtifactStore by external {
            override fun putAll(job: Long, blobs: Map<String, ByteArray>) {
                if (job == 1L) throw java.io.IOException("injected publication failure")
                external.putAll(job, blobs)
            }
        }
        ResultJournal(journal, destination, start = false).use { store ->
            store.putAll(1, blobs("retry")); store.putAll(2, blobs("ready"))
            assertFailsWith<java.io.IOException> { store.drainOne() }
            assertTrue(store.drainOne()); assertFalse(store.drainOne())
            assertEquals(1, store.status().batches); assertEquals(1L, store.status().drainErrors)
            assertEquals("retry", read(store, 1, "retry")); assertEquals("ready", read(external, 2, "ready"))
        }
    }

    @Test fun `a failed reclamation barrier retains its charge and backs off without starving other packs`() {
        var failCleanup = false; var syncs = 0; var publications = 0
        val destination = object : ArtifactStore by external {
            override fun putAll(job: Long, blobs: Map<String, ByteArray>) {
                publications++; external.putAll(job, blobs)
            }
        }
        ResultJournal(journal, destination, start = false, syncDirectory = {
            syncs++
            if (failCleanup) { failCleanup = false; throw java.io.IOException("injected cleanup barrier failure") }
        }).use { store ->
            store.putAll(1, blobs("published")); store.putAll(2, blobs("ready"))
            val charged = store.status().bytes; val initialSyncs = syncs
            failCleanup = true
            assertFailsWith<java.io.IOException> { store.drainOne() }
            assertEquals(charged, store.status().bytes, "failed reclamation must not release journal capacity")
            assertEquals(initialSyncs + 1, syncs)
            assertEquals("published", read(store, 1, "published"))
            assertTrue(store.drainOne()); assertFalse(store.drainOne())
            assertEquals(initialSyncs + 2, syncs, "the failed cleanup must not be retried before its backoff")
            assertEquals(1, store.status().batches)
            assertEquals("ready", read(external, 2, "ready"))
        }
        ResultJournal(journal, destination, start = false).use { recovered ->
            assertEquals(0, recovered.status().batches); assertEquals(8192L, recovered.status().bytes)
            assertFalse(recovered.drainOne())
            assertEquals("published", read(recovered, 1, "published")); assertEquals("ready", read(recovered, 2, "ready"))
            assertEquals(2, publications, "recovery must not republish already durable external bytes")
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
