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
    @Test fun `large packs of small members publish bounded complete chunks under one barrier and ZIP reader`() {
        val values = smallMembers(); val chunks = mutableListOf<List<ResultBlob>>()
        var barriers = 0; var opens = 0; var charged = 0L
        lateinit var journal: ResultJournal
        val destination = object : ArtifactStore by external {
            override fun offset(job: Long, sha: String): Long = error("small members must not preflight offsets")
            override fun put(job: Long, sha: String, bytes: Long, offset: Long, input: InputStream): Long = error("small members must use complete publication")
            override fun putComplete(job: Long, blobs: List<ResultBlob>, source: (ResultBlob) -> InputStream) {
                chunks += blobs.toList(); external.putComplete(job, blobs, source)
            }
            override fun publishBatch(job: Long, publish: () -> Unit) = external.publishBatch(job, {
                barriers++; assertEquals(charged, journal.status().bytes, "all chunks remain charged until the same waited barrier")
            }, publish)
        }
        ResultJournal(root.resolve("chunked"), destination, start = false, openPublicationPack = {
            opens++; java.util.zip.ZipFile(it.toFile())
        }).use {
            journal = it; packed(journal, values); charged = journal.status().bytes
            assertTrue(journal.drainGroup())
            assertEquals(listOf(64, 64), chunks.map { it.size })
            assertEquals(listOf(INLINE_BYTES.toLong(), INLINE_BYTES.toLong()), chunks.map { chunk -> chunk.sumOf { it.bytes } })
            assertEquals(values.keys.toList(), chunks.flatten().map { it.sha })
            assertEquals(1, opens); assertEquals(1, barriers); assertEquals(8192L, journal.status().bytes)
            for ((hash, value) in values) assertContentEquals(value, external.open(1, hash).use { input -> input.readAllBytes() })
        }
    }

    @Test fun `mixed packs complete small members in order while large members resume their confirmed prefix`() {
        val before = ByteArray(16384) { 1 }; val large = ByteArray(2 * INLINE_BYTES + 7) { (it % 251).toByte() }
        val after = ByteArray(16384) { 2 }; val values = listOf(before, large, after, byteArrayOf()).associateBy(::digest)
        val largeSha = digest(large); val prefix = 12345
        external.put(1, largeSha, large.size.toLong(), 0, large.copyOfRange(0, prefix).inputStream())
        val order = mutableListOf<List<String>>(); val resumed = mutableListOf<Long>(); var opens = 0; var barriers = 0
        val destination = object : ArtifactStore by external {
            override fun offset(job: Long, sha: String): Long { assertEquals(largeSha, sha); return external.offset(job, sha) }
            override fun put(job: Long, sha: String, bytes: Long, offset: Long, input: InputStream): Long {
                assertEquals(largeSha, sha); order += listOf(sha); resumed += offset
                return external.put(job, sha, bytes, offset, input)
            }
            override fun putComplete(job: Long, blobs: List<ResultBlob>, source: (ResultBlob) -> InputStream) {
                order += blobs.map { it.sha }; external.putComplete(job, blobs, source)
            }
            override fun publishBatch(job: Long, publish: () -> Unit) = external.publishBatch(job, { barriers++ }, publish)
        }
        ResultJournal(root.resolve("mixed"), destination, start = false, openPublicationPack = {
            opens++; java.util.zip.ZipFile(it.toFile())
        }).use { journal ->
            packed(journal, values); assertTrue(journal.drainOne())
            assertEquals(listOf(listOf(digest(before)), listOf(largeSha), listOf(digest(after), digest(byteArrayOf()))), order)
            assertEquals(listOf(prefix.toLong()), resumed); assertEquals(1, opens); assertEquals(1, barriers)
            for ((hash, value) in values) assertContentEquals(value, external.open(1, hash).use { it.readAllBytes() })
        }
    }

    @Test fun `all complete mixed destinations drain without opening their unavailable journal ZIP`() {
        val values = smallMembers(2) + mapOf(sha to bytes)
        for ((hash, value) in values) external.put(1, hash, value.size.toLong(), 0, value.inputStream())
        var barriers = 0
        val destination = object : ArtifactStore by external {
            override fun publishBatch(job: Long, publish: () -> Unit) = external.publishBatch(job, { barriers++ }, publish)
        }
        val path = root.resolve("unavailable")
        ResultJournal(path, destination, start = false, openPublicationPack = { error("complete destinations cannot require a redundant ZIP") }).use { journal ->
            packed(journal, values); Files.delete(pack(path))
            assertTrue(journal.drainGroup()); assertEquals(1, barriers); assertEquals(8192L, journal.status().bytes)
            for ((hash, value) in values) assertContentEquals(value, external.open(1, hash).use { it.readAllBytes() })
        }
    }

    @Test fun `corrupt later chunk retains its entire pack before the shared barrier`() = failedChunk("corrupt")
    @Test fun `truncated later chunk retains its entire pack before the shared barrier`() = failedChunk("truncated")
    @Test fun `unreadable later chunk closes its ZIP and retries without losing earlier complete members`() = failedChunk("failure")

    private fun failedChunk(mode: String) {
        val values = smallMembers(); val last = values.keys.last(); val path = root.resolve(mode)
        var clock = 1000L; var barriers = 0; var fail = true; var opens = 0; var closes = 0
        val destination = object : ArtifactStore by external {
            override fun publishBatch(job: Long, publish: () -> Unit) = external.publishBatch(job, { barriers++ }, publish)
        }
        ResultJournal(path, destination, start = false, retryClock = { clock }, openPublicationPack = { file ->
            opens++
            object : java.util.zip.ZipFile(file.toFile()) {
                override fun getInputStream(entry: java.util.zip.ZipEntry): InputStream {
                    if (mode == "failure" && fail && entry.name == last) throw java.io.IOException("injected journal entry read failure")
                    return super.getInputStream(entry)
                }
                override fun close() { closes++; super.close() }
            }
        }).use { journal ->
            packed(journal, values); val charged = journal.status().bytes; val file = pack(path); val original = Files.readAllBytes(file)
            if (mode != "failure") rewriteEntry(file, last) { value ->
                if (mode == "truncated") value.copyOf(value.size - 1) else value.copyOf().also { it[0] = (it[0] + 1).toByte() }
            }
            if (mode == "failure") assertFailsWith<java.io.IOException> { journal.drainGroup() }
            else if (mode == "truncated") assertFailsWith<IllegalArgumentException> { journal.drainGroup() }
            else assertFailsWith<IllegalStateException> { journal.drainGroup() }
            assertEquals(0, barriers); assertEquals(charged, journal.status().bytes); assertEquals(1, journal.status().batches)
            assertEquals(1, opens); assertEquals(1, closes)
            assertContentEquals(values.values.first(), external.open(1, values.keys.first()).use { it.readAllBytes() })
            fail = false; Files.write(file, original); clock += 5000
            assertTrue(journal.drainGroup()); assertEquals(1, barriers); assertEquals(8192L, journal.status().bytes)
            assertEquals(2, opens); assertEquals(2, closes)
            for ((hash, value) in values) assertContentEquals(value, external.open(1, hash).use { it.readAllBytes() })
        }
    }

    @Test fun `chunked publication retains providers that only support confirmed offset resumption`() {
        val values = smallMembers(); val first = values.entries.first(); val second = values.entries.elementAt(1)
        external.put(1, first.key, first.value.size.toLong(), 0, first.value.copyOfRange(0, 73).inputStream())
        external.put(1, second.key, second.value.size.toLong(), 0, second.value.inputStream())
        val offsets = linkedMapOf<String, Long>(); val chunks = mutableListOf<Int>(); var barriers = 0
        val destination = object : ArtifactStore {
            override fun file(job: Long, sha: String) = external.file(job, sha)
            override fun offset(job: Long, sha: String) = external.offset(job, sha)
            override fun put(job: Long, sha: String, bytes: Long, offset: Long, input: InputStream): Long {
                offsets[sha] = offset; return external.put(job, sha, bytes, offset, input)
            }
            override fun putComplete(job: Long, blobs: List<ResultBlob>, source: (ResultBlob) -> InputStream) {
                chunks += blobs.size; super<ArtifactStore>.putComplete(job, blobs, source)
            }
            override fun publishBatch(job: Long, publish: () -> Unit) = external.publishBatch(job, { barriers++ }, publish)
        }
        ResultJournal(root.resolve("fallback"), destination, start = false).use { journal ->
            packed(journal, values); assertTrue(journal.drainGroup())
            assertEquals(listOf(64, 64), chunks); assertEquals(73L, offsets[first.key]); assertEquals(second.value.size.toLong(), offsets[second.key])
            assertEquals(values.size, offsets.size); assertEquals(1, barriers)
            for ((hash, value) in values) assertContentEquals(value, external.open(1, hash).use { it.readAllBytes() })
        }
    }

    @Test fun `a short successful large publication cannot reclaim its journal pack`() {
        var short = true; var barriers = 0; var clock = 1000L
        val destination = object : ArtifactStore by external {
            override fun put(job: Long, sha: String, bytes: Long, offset: Long, input: InputStream): Long =
                if (short) bytes - 1 else external.put(job, sha, bytes, offset, input)
            override fun publishBatch(job: Long, publish: () -> Unit) = external.publishBatch(job, { barriers++ }, publish)
        }
        ResultJournal(root.resolve("short"), destination, start = false, retryClock = { clock }).use { journal ->
            journal.putPacked(1, blobs, bytes.inputStream()); val charged = journal.status().bytes
            assertFailsWith<IllegalStateException> { journal.drainGroup() }
            assertEquals(charged, journal.status().bytes); assertEquals(1, journal.status().batches); assertEquals(0, barriers)
            assertContentEquals(bytes, journal.open(1, sha).use { it.readAllBytes() })
            short = false; clock += 5000; assertTrue(journal.drainGroup()); assertEquals(1, barriers)
            assertContentEquals(bytes, external.open(1, sha).use { it.readAllBytes() })
        }
    }

    @Test fun `a corrupt retained large prefix still fails its full checksum beside complete small members`() {
        val values = smallMembers(2) + mapOf(sha to bytes); var clock = 1000L; var barriers = 0
        val corrupt = bytes.copyOfRange(0, 73).also { it[0] = (it[0] + 1).toByte() }
        external.put(1, sha, bytes.size.toLong(), 0, corrupt.inputStream())
        val destination = object : ArtifactStore by external {
            override fun publishBatch(job: Long, publish: () -> Unit) = external.publishBatch(job, { barriers++ }, publish)
        }
        ResultJournal(root.resolve("corrupt-prefix"), destination, start = false, retryClock = { clock }).use { journal ->
            packed(journal, values); val charged = journal.status().bytes
            assertFailsWith<IllegalStateException> { journal.drainGroup() }
            assertEquals(0, barriers); assertEquals(charged, journal.status().bytes); assertEquals(1, journal.status().batches)
            assertContentEquals(bytes, journal.open(1, sha).use { it.readAllBytes() })
            clock += 5000; assertTrue(journal.drainGroup()); assertEquals(1, barriers)
            assertContentEquals(bytes, external.open(1, sha).use { it.readAllBytes() })
        }
    }

    @Test fun `a truncated requested large ZIP entry cannot retire its intact journal accounting`() {
        val path = root.resolve("large-truncated"); var barriers = 0
        val destination = object : ArtifactStore by external {
            override fun publishBatch(job: Long, publish: () -> Unit) = external.publishBatch(job, { barriers++ }, publish)
        }
        ResultJournal(path, destination, start = false).use { journal ->
            journal.putPacked(1, blobs, bytes.inputStream()); val charged = journal.status().bytes
            rewriteEntry(pack(path), sha) { it.copyOf(it.size - 1) }
            assertFailsWith<IllegalArgumentException> { journal.drainGroup() }
            assertEquals(0, barriers); assertEquals(charged, journal.status().bytes); assertEquals(1, journal.status().batches)
            assertFalse(Files.exists(root.resolve("external/1/$sha")))
        }
    }

    private fun digest(value: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(value).hex()
    private fun smallMembers(count: Int = 128): Map<String, ByteArray> = (0 until count).map { n ->
        ByteArray(16384) { ((it + n) % 251).toByte() }
    }.associateBy(::digest)
    private fun packed(journal: ResultJournal, values: Map<String, ByteArray>) {
        val body = java.io.SequenceInputStream(java.util.Collections.enumeration(values.values.map { it.inputStream() }))
        body.use { journal.putPacked(1, values.map { ResultBlob(it.key, it.value.size.toLong()) }, it) }
    }
    private fun pack(path: java.nio.file.Path) = Files.list(path).use { files -> files.filter { it.fileName.toString().endsWith(".zip") }.toList().single() }
    private fun rewriteEntry(path: java.nio.file.Path, target: String, change: (ByteArray) -> ByteArray) {
        val entries = java.util.zip.ZipFile(path.toFile()).use { zip -> zip.entries().toList().map { entry ->
            entry.name to zip.getInputStream(entry).use { it.readAllBytes() }
        } }
        java.util.zip.ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            for ((name, value) in entries) {
                zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(if (name == target) change(value) else value); zip.closeEntry()
            }
        }
    }
}
