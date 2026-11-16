package com.eignex.lab

import com.eignex.lab.store.*
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.FileTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class JournalCompactionTest {
    private val root = Files.createTempDirectory("journal-compaction")
    private val path = root.resolve("journal")
    private val external = LocalArtifactStore(root.resolve("external"))
    private fun add(store: ResultJournal, value: String, job: Long = 1) = store.putAll(job, mapOf(ExecutionStore.digest(value) to value.toByteArray()))
    private fun read(store: ArtifactStore, value: String, job: Long = 1) = store.open(job, ExecutionStore.digest(value)).use { String(it.readAllBytes()) }
    private fun packs() = Files.list(path).use { paths -> paths.filter { it.fileName.toString().endsWith(".zip") }.toList() }
    private fun recoveredPacks(groups: List<List<String>>) {
        Files.createDirectory(path)
        groups.forEachIndexed { index, values ->
            val fixture = root.resolve("fixture-$index")
            ResultJournal(fixture, external, start = false).use { store ->
                store.putAll(1, values.associate { ExecutionStore.digest(it) to it.toByteArray() })
            }
            val pack = Files.list(fixture).use { it.filter { file -> file.fileName.toString().endsWith(".zip") }.toList().single() }
            Files.move(pack, path.resolve("1-${UUID.randomUUID()}.zip"))
        }
    }

    @Test fun `small pairs progressively recover real charged bytes without borrowing transient cap space`() {
        lateinit var store: ResultJournal
        val limit = 106L * 1024
        ResultJournal(path, external, maxBytes = limit, start = false, forceCompactedFile = {
            assertTrue(store.status().bytes <= limit)
            FileChannel.open(it, WRITE).use { channel -> channel.force(true) }
        }).use {
            store = it
            repeat(10) { add(store, "value-$it") }
            val before = store.status().bytes
            assertEquals(13312, limit - before, "initially only a small replacement reservation fits")
            assertTrue(store.compactOne()); assertTrue(store.status().batches > 1)
            repeat(10) { if (store.status().batches > 1) assertTrue(store.compactOne()) }
            assertEquals(1, store.status().batches); assertTrue(store.status().bytes < before)
            assertEquals(before - store.status().bytes, store.status().compactionReclaimedBytes)
            assertTrue(store.status().compactions > 1)
            assertEquals(0L, store.status().drainErrors)
            repeat(10) { assertEquals("value-$it", read(store, "value-$it")) }
        }
    }

    @Test fun `full charge waits for ordinary drain progress instead of overwriting or exempting a replacement`() {
        var forced = false
        ResultJournal(path, external, maxBytes = 30L * 1024, start = false, forceCompactedFile = { forced = true }).use { store ->
            add(store, "first"); add(store, "second")
            val before = store.status().bytes; val original = packs().toSet()
            assertFalse(store.compactOne()); assertFalse(forced)
            assertEquals(before, store.status().bytes); assertEquals(original, packs().toSet())
            assertEquals("first", read(store, "first")); assertEquals("second", read(store, "second"))
        }
    }

    @Test fun `failed new file force leaves every accepted original and returns the transient reservation`() {
        ResultJournal(path, external, start = false, forceCompactedFile = { throw IOException("force failed") }).use { store ->
            add(store, "first"); add(store, "second")
            val before = store.status().bytes; val original = packs().toSet()
            assertFailsWith<IOException> { store.compactOne() }
            assertEquals(before, store.status().bytes); assertEquals(original, packs().toSet())
            assertEquals("first", read(store, "first")); assertEquals("second", read(store, "second"))
        }
    }

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun `lost new publication acknowledgement retries or restarts without another replacement`(restart: Boolean) {
        var fail = false; var forces = 0; var originalCharge = 0L
        ResultJournal(path, external, start = false, syncDirectory = { if (fail) throw IOException("root failed") },
            forceCompactedFile = { forces++; FileChannel.open(it, WRITE).use { channel -> channel.force(true) } }).use { store ->
            add(store, "first"); add(store, "second")
            val before = store.status().bytes; originalCharge = before
            fail = true
            assertFailsWith<IOException> { store.compactOne() }
            assertEquals(3, store.status().batches); assertTrue(store.status().bytes > before)
            assertEquals(1, forces); assertEquals(0L, store.status().compactionReclaimedBytes)
            assertEquals("first", read(store, "first")); assertEquals("second", read(store, "second"))
            fail = false
            if (!restart) {
                assertTrue(store.compactOne()); assertEquals(1, forces)
                assertEquals(1, store.status().batches)
            }
        }
        ResultJournal(path, external, start = false).use { recovered ->
            // Startup validates both formats' ordinary immutable packs and chooses strict covering copies.
            recovered.compactOne(); assertEquals(1, recovered.status().batches)
            if (restart) assertEquals(originalCharge - recovered.status().bytes, recovered.status().compactionReclaimedBytes,
                "restart and uninterrupted compaction use the same conservative net old-minus-replacement metric")
            assertEquals("first", read(recovered, "first")); assertEquals("second", read(recovered, "second"))
            assertTrue(recovered.drainOne()); assertEquals(8192, recovered.status().bytes)
        }
    }

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun `lost old deletion acknowledgement keeps charges until a shared retry barrier or restart`(restart: Boolean) {
        var armed = false; var syncs = 0; var clock = 1000L
        ResultJournal(path, external, start = false, retryClock = { clock }, syncDirectory = {
            if (armed && ++syncs == 2) throw IOException("old deletion barrier failed")
        }).use { store ->
            add(store, "first"); add(store, "second")
            val before = store.status().bytes
            armed = true
            assertFailsWith<IOException> { store.compactOne() }
            assertEquals(3, store.status().batches); assertEquals(1, packs().size)
            assertTrue(store.status().bytes > before); assertEquals(0L, store.status().compactionReclaimedBytes)
            assertEquals("first", read(store, "first")); assertEquals("second", read(store, "second"))
            if (!restart) {
                assertFalse(store.compactOne()); assertEquals(3, store.status().batches)
                clock += 5000
                assertFalse(store.compactOne()); assertEquals(1, store.status().batches)
                assertEquals(before - store.status().bytes, store.status().compactionReclaimedBytes)
                assertEquals(0L, store.status().drainErrors)
            }
        }
        ResultJournal(path, external, start = false).use { recovered ->
            assertEquals(1, recovered.status().batches)
            assertEquals("first", read(recovered, "first")); assertEquals("second", read(recovered, "second"))
        }
    }

    @Test fun `partial old deletion failures reclaim only deletions covered by the successful root barrier`() {
        var blocked: Path? = null; var clock = 1000L
        ResultJournal(path, external, start = false, retryClock = { clock }, deletePack = {
            if (it == blocked) throw IOException("delete failed") else Files.deleteIfExists(it)
        }).use { store ->
            add(store, "first"); add(store, "second"); blocked = packs().first()
            val before = store.status().bytes
            assertFailsWith<IOException> { store.compactOne() }
            assertEquals(2, store.status().batches); assertEquals(2, packs().size)
            assertEquals(0L, store.status().compactionReclaimedBytes)
            assertEquals("first", read(store, "first")); assertEquals("second", read(store, "second"))
            blocked = null; clock += 5000
            assertFalse(store.compactOne()); assertEquals(1, store.status().batches)
            assertEquals(before - store.status().bytes, store.status().compactionReclaimedBytes)
        }
    }

    @Test fun `oldest pack age survives coalescing and the unchanged ZIP format survives another startup`() {
        ResultJournal(path, external, start = false).use { store -> repeat(4) { add(store, "value-$it") } }
        val oldest = System.currentTimeMillis() - 3_600_000
        packs().forEachIndexed { index, pack -> Files.setLastModifiedTime(pack, FileTime.fromMillis(oldest + index * 1000)) }
        ResultJournal(path, external, start = false).use { store ->
            assertTrue(store.compactOne()); assertEquals(1, store.status().batches)
            assertEquals(oldest, Files.getLastModifiedTime(packs().single()).toMillis())
            assertTrue(store.status().oldestAgeMs >= 3_600_000)
            ZipFile(packs().single().toFile()).use { zip ->
                val manifest = zip.getInputStream(zip.getEntry("manifest.json")).reader().readText()
                assertFalse(manifest.contains("superseded")); assertFalse(manifest.contains("retirement"))
            }
        }
        ResultJournal(path, external, start = false).use { recovered ->
            assertEquals(1, recovered.status().batches); assertTrue(recovered.status().oldestAgeMs >= 3_600_000)
            repeat(4) { assertEquals("value-$it", read(recovered, "value-$it")) }
        }
    }

    @Test fun `restart coverage uses one strict preferred full copy instead of cyclic overlap deletions`() {
        recoveredPacks(listOf(listOf("first", "second"), listOf("second", "third"), listOf("first", "third"),
            listOf("first", "second", "third"), listOf("first", "second", "third")))
        ResultJournal(path, external, start = false).use { recovered ->
            assertEquals(5, recovered.status().batches)
            assertFalse(recovered.compactOne(), "verified redundant originals need no new transient replacement")
            assertEquals(1, recovered.status().batches); assertEquals(1, packs().size)
            for (value in listOf("first", "second", "third")) assertEquals(value, read(recovered, value))
        }
        ResultJournal(path, external, start = false).use { recovered ->
            assertEquals(1, recovered.status().batches); assertTrue(recovered.drainOne())
            for (value in listOf("first", "second", "third")) assertEquals(value, read(recovered, value))
        }
    }

    @Test fun `partial overlap alone cannot retire originals before a verified union is durable`() {
        recoveredPacks(listOf(listOf("first", "second"), listOf("second", "third"), listOf("first", "third")))
        ResultJournal(path, external, start = false, forceCompactedFile = { throw IOException("copy not durable") }).use { recovered ->
            assertEquals(3, recovered.status().batches)
            assertFailsWith<IOException> { recovered.compactOne() }
            assertEquals(3, recovered.status().batches); assertEquals(3, packs().size)
            for (value in listOf("first", "second", "third")) assertEquals(value, read(recovered, value))
        }
    }

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun `corrupt or truncated inputs cannot publish a replacement or remove other accepted packs`(truncated: Boolean) {
        ResultJournal(path, external, start = false).use { store ->
            add(store, "first"); add(store, "second")
            val original = packs().associateWith { Files.readAllBytes(it) }
            val target = original.keys.first { file -> ZipFile(file.toFile()).use { it.getEntry(ExecutionStore.digest("first")) != null } }
            if (truncated) Files.write(target, original.getValue(target).copyOf(original.getValue(target).size / 2))
            else {
                val entries = ZipFile(target.toFile()).use { zip -> zip.entries().toList().associate { it.name to zip.getInputStream(it).readAllBytes() } }
                ZipOutputStream(Files.newOutputStream(target)).use { zip ->
                    zip.setLevel(0)
                    for ((name, value) in entries) {
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(if (name == ExecutionStore.digest("first")) "wrong".toByteArray() else value); zip.closeEntry()
                    }
                }
            }
            val before = store.status().bytes
            assertFails { store.compactOne() }
            assertEquals(original.keys, packs().toSet()); assertEquals(before, store.status().bytes)
            assertEquals("second", read(store, "second"))
            Files.write(target, original.getValue(target))
        }
        ResultJournal(path, external, start = false).use { recovered ->
            assertEquals("first", read(recovered, "first")); assertEquals("second", read(recovered, "second"))
        }
    }

    @Test fun `a pending replacement already flushed and removed externally can finish acknowledgement recovery`() {
        var fail = false; var forces = 0
        ResultJournal(path, external, start = false, syncDirectory = { if (fail) throw IOException("root failed") },
            forceCompactedFile = { forces++; FileChannel.open(it, WRITE).use { channel -> channel.force(true) } }).use { store ->
            add(store, "first"); add(store, "second")
            fail = true; assertFailsWith<IOException> { store.compactOne() }; fail = false
            val other = "other".toByteArray(); val hash = ExecutionStore.digest("other")
            store.putPacked(2, listOf(ResultBlob(hash, other.size.toLong())), other.inputStream())
            // The independent publication made every existing rename durable. Normal drains can win this race.
            assertTrue(store.drainGroup()); assertEquals(1, store.status().batches)
            assertEquals("first", read(external, "first")); assertEquals("second", read(external, "second"))
            assertTrue(store.compactOne()); assertEquals(1, forces); assertEquals(1, store.status().batches)
            assertEquals("first", read(store, "first")); assertEquals("second", read(store, "second"))
            assertEquals("other", read(store, "other", 2))
        }
    }

    @Test fun `a compaction quantum keeps jobs separate and never exceeds its blob bound`() {
        ResultJournal(path, external, start = false).use { store ->
            repeat(140) { add(store, "value-$it") }; repeat(2) { add(store, "other-$it", 2) }
            assertTrue(store.compactOne()); assertEquals(15, store.status().batches)
            assertTrue(store.compactOne()); assertTrue(store.compactOne())
            assertEquals(3, store.status().batches, "two bounded packs for one job and one pack for the other")
            assertFalse(store.compactOne())
            repeat(140) { assertEquals("value-$it", read(store, "value-$it")) }
            repeat(2) { assertEquals("other-$it", read(store, "other-$it", 2)) }
        }
    }

    @Test fun `a compaction quantum cannot combine payloads beyond four MiB`() {
        ResultJournal(path, external, start = false).use { store ->
            val values = (0..8).map { "header-$it\n" + "x".repeat(600 * 1024) }
            for (value in values) add(store, value)
            assertTrue(store.compactOne()); assertEquals(4, store.status().batches)
            assertTrue(store.compactOne()); assertEquals(2, store.status().batches)
            assertFalse(store.compactOne())
            for (value in values) assertEquals(value, read(store, value))
        }
    }

    @Test fun `pinned old readers and stable file callers survive local redirection and replacement drain`() {
        val readerEntered = CountDownLatch(1); val readerRelease = CountDownLatch(1)
        val copyEntered = CountDownLatch(1); val copyRelease = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(3)
        ResultJournal(path, external, start = false, forceCompactedFile = {
            FileChannel.open(it, WRITE).use { channel -> channel.force(true) }
            copyEntered.countDown(); check(copyRelease.await(10, SECONDS))
        }).use { store ->
            try {
                add(store, "first"); add(store, "second")
                val old = pool.submit<String> { store.withPendingBlob(1, ExecutionStore.digest("first")) { oldPath, _ ->
                    readerEntered.countDown(); check(readerRelease.await(10, SECONDS))
                    assertTrue(Files.isRegularFile(oldPath), "old reader must outlive both local replacement and its external drain")
                    ZipFile(oldPath.toFile()).use { zip -> zip.getInputStream(zip.getEntry(ExecutionStore.digest("first"))).reader().readText() }
                }!! }
                assertTrue(readerEntered.await(3, SECONDS))
                val copying = pool.submit<Boolean> { store.compactOne() }
                assertTrue(copyEntered.await(3, SECONDS))
                val copyingStatus = store.status()
                assertEquals(2, copyingStatus.phases.getValue(ResultJournal.Phase.compacting).batches)
                assertEquals(3, copyingStatus.phases.getValue(ResultJournal.Phase.compacting).readers)
                assertEquals(1, copyingStatus.receiving); assertTrue(copyingStatus.receivingReservedBytes > 0)
                assertEquals(copyingStatus.bytes, 8192L + copyingStatus.receivingReservedBytes + copyingStatus.phases.values.sumOf { it.bytes })
                // This caller initially sees the busy original and must reread the redirected index.
                val stable = pool.submit<Path> { store.file(1, ExecutionStore.digest("first")) }
                add(store, "other-job", 2)
                copyRelease.countDown(); assertTrue(copying.get(5, SECONDS))
                assertEquals("first", Files.readString(stable.get(5, SECONDS)))
                assertEquals(0L, store.status().compactionReclaimedBytes, "pinned input still holds its charge")
                assertEquals(1, store.status().phases.getValue(ResultJournal.Phase.superseded).batches)
                assertEquals(0L, store.status().receivingReservedBytes)
                assertEquals("first", read(store, "first"))
                readerRelease.countDown(); assertEquals("first", old.get(5, SECONDS))
                assertTrue(store.status().compactionReclaimedBytes > 0)
                assertEquals("other-job", read(store, "other-job", 2))
            } finally { readerRelease.countDown(); copyRelease.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun `mixed packed and inline commits survive compaction redirection while both external lanes wait`() {
        val entered = CountDownLatch(2); val release = CountDownLatch(1)
        val copyEntered = CountDownLatch(1); val copyRelease = CountDownLatch(1); val pool = Executors.newFixedThreadPool(3)
        val blocked = object : ArtifactStore by external {
            override fun publishBatch(job: Long, publish: () -> Unit) = external.publishBatch(job, {
                entered.countDown(); check(release.await(10, SECONDS))
            }, publish)
        }
        ResultJournal(path, blocked, start = false, forceCompactedFile = {
            FileChannel.open(it, WRITE).use { channel -> channel.force(true) }
            copyEntered.countDown(); check(copyRelease.await(10, SECONDS))
        }).use { store ->
            try {
                repeat(2) { add(store, "busy-$it") }; repeat(4) { add(store, "local-$it", 2) }
                val drains = (1..2).map { pool.submit<Boolean> { store.drainOne() } }
                assertTrue(entered.await(5, SECONDS))
                val compacting = pool.submit<Boolean> { store.compactOne() }
                assertTrue(copyEntered.await(5, SECONDS))
                val duplicate = "local-0".toByteArray(); val fresh = "new-packed".toByteArray()
                store.putPacked(2, listOf(ResultBlob(ExecutionStore.digest("local-0"), duplicate.size.toLong()),
                    ResultBlob(ExecutionStore.digest("new-packed"), fresh.size.toLong())), (duplicate + fresh).inputStream())
                add(store, "new-inline", 3)
                assertEquals("local-0", read(store, "local-0", 2)); assertEquals("new-packed", read(store, "new-packed", 2))
                assertEquals("new-inline", read(store, "new-inline", 3)); assertTrue(store.status().bytes <= store.status().limit)
                copyRelease.countDown(); assertTrue(compacting.get(5, SECONDS)); assertEquals(5, store.status().batches)
                assertTrue(store.status().compactionReclaimedBytes > 0)
                repeat(4) { assertEquals("local-$it", read(store, "local-$it", 2)) }
                release.countDown(); drains.forEach { assertTrue(it.get(5, SECONDS)) }
            } finally { release.countDown(); copyRelease.countDown(); pool.shutdownNow() }
        }
        ResultJournal(path, external, start = false).use { recovered ->
            assertTrue(recovered.status().bytes <= recovered.status().limit)
            repeat(2) { assertEquals("busy-$it", read(recovered, "busy-$it")) }
            repeat(4) { assertEquals("local-$it", read(recovered, "local-$it", 2)) }
            assertEquals("new-packed", read(recovered, "new-packed", 2)); assertEquals("new-inline", read(recovered, "new-inline", 3))
            while (recovered.drainGroup()) { assertTrue(recovered.status().bytes <= recovered.status().limit) }
            assertEquals(8192, recovered.status().bytes)
        }
    }
}
