package com.eignex.lab

import com.eignex.lab.store.*
import com.eignex.lab.util.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermission.GROUP_WRITE
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class ArtifactFileVerificationTest {
    private val root = Files.createTempDirectory("artifact-file-verification")
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun bytes() = ByteArray(70_000) { (it % 251).toByte() }
    private fun file(name: String, content: String = "hello") = root.resolve(name).also { Files.writeString(it, content) }
    @AfterTest fun close() { deleteTree(root) }

    @Test fun `cold reads verify fully and unchanged reads reuse only metadata without retaining payloads`() {
        val path = file("blob"); val expected = ExecutionStore.digest("hello"); var reads = 0
        val proof = ArtifactFileVerification(hash = { reads++; hashFile(it) })
        proof.verify(path, expected); proof.verify(path, expected)
        assertEquals(1, reads); assertEquals(1, proof.entryCount)
        assertEquals(listOf(path), Files.list(root).use { it.toList() })
        // Proof is RAM-only: a fresh process/store has to verify old content again.
        ArtifactFileVerification(hash = { reads++; hashFile(it) }).verify(path, expected)
        assertEquals(2, reads)
    }

    @Test fun `concurrent cold readers coalesce while another key can verify independently`() {
        val path = file("one"); val other = file("two", "other")
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val followerObserved = CountDownLatch(1)
        val followerThread = AtomicReference<Thread>(); val reads = AtomicInteger()
        val proof = ArtifactFileVerification(hash = {
            if (it == path) { reads.incrementAndGet(); entered.countDown(); check(release.await(5, SECONDS)) }
            hashFile(it)
        }, observe = {
            if (Thread.currentThread() === followerThread.get()) followerObserved.countDown()
            TreeVerification.fileMetadata(it)
        })
        val pool = Executors.newFixedThreadPool(3)
        try {
            val first = pool.submit { proof.verify(path, ExecutionStore.digest("hello")) }
            assertTrue(entered.await(5, SECONDS))
            val follower = pool.submit { followerThread.set(Thread.currentThread()); proof.verify(path, ExecutionStore.digest("hello")) }
            assertTrue(followerObserved.await(5, SECONDS))
            pool.submit { proof.verify(other, ExecutionStore.digest("other")) }.get(5, SECONDS)
            assertFalse(follower.isDone)
            release.countDown(); first.get(5, SECONDS); follower.get(5, SECONDS)
            assertEquals(1, reads.get())
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, SECONDS)) }
    }

    @Test fun `distinct flight bound survives a failed verifier and permits retry of its key`() {
        val first = file("one"); val second = file("two", "other")
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val secondObserved = CountDownLatch(1)
        val failFirst = AtomicBoolean(true); val active = AtomicInteger(); val peak = AtomicInteger(); val calls = AtomicInteger()
        val proof = ArtifactFileVerification(maxFlights = 1, hash = { path ->
            calls.incrementAndGet(); val count = active.incrementAndGet(); peak.updateAndGet { maxOf(it, count) }
            try {
                if (path == first && failFirst.compareAndSet(true, false)) {
                    entered.countDown(); check(release.await(5, SECONDS)); throw IOException("verification read failed")
                }
                hashFile(path)
            } finally { active.decrementAndGet() }
        }, observe = { if (it == second) secondObserved.countDown(); TreeVerification.fileMetadata(it) })
        val pool = Executors.newFixedThreadPool(2)
        try {
            val failed = pool.submit { proof.verify(first, ExecutionStore.digest("hello")) }
            assertTrue(entered.await(5, SECONDS))
            val waiting = pool.submit { proof.verify(second, ExecutionStore.digest("other")) }
            assertTrue(secondObserved.await(5, SECONDS)); assertFalse(waiting.isDone)
            release.countDown()
            val error = assertFailsWith<java.util.concurrent.ExecutionException> { failed.get(5, SECONDS) }
            assertIs<IOException>(error.cause)
            waiting.get(5, SECONDS)
            pool.submit { proof.verify(first, ExecutionStore.digest("hello")) }.get(5, SECONDS)
            assertEquals(1, peak.get()); assertEquals(3, calls.get()); assertEquals(2, proof.entryCount)
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, SECONDS)) }
    }

    @Test fun `interrupting same key and capacity waiters neither cancels their owner nor leaks a slot`() {
        val first = file("one"); val second = file("two", "other")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val followerObserved = CountDownLatch(1); val capacityObserved = CountDownLatch(1); val waitersEnded = CountDownLatch(2)
        val followerThread = AtomicReference<Thread>(); val capacityThread = AtomicReference<Thread>(); val calls = AtomicInteger()
        val proof = ArtifactFileVerification(maxFlights = 1, hash = {
            calls.incrementAndGet()
            if (it == first) { entered.countDown(); check(release.await(5, SECONDS)) }
            hashFile(it)
        }, observe = {
            if (Thread.currentThread() === followerThread.get()) followerObserved.countDown()
            if (Thread.currentThread() === capacityThread.get()) capacityObserved.countDown()
            TreeVerification.fileMetadata(it)
        })
        val pool = Executors.newFixedThreadPool(3)
        try {
            val owner = pool.submit { proof.verify(first, ExecutionStore.digest("hello")) }
            assertTrue(entered.await(5, SECONDS))
            val follower = pool.submit {
                followerThread.set(Thread.currentThread())
                try { proof.verify(first, ExecutionStore.digest("hello")) } finally { waitersEnded.countDown() }
            }
            val capacity = pool.submit {
                capacityThread.set(Thread.currentThread())
                try { proof.verify(second, ExecutionStore.digest("other")) } finally { waitersEnded.countDown() }
            }
            assertTrue(followerObserved.await(5, SECONDS)); assertTrue(capacityObserved.await(5, SECONDS))
            assertTrue(follower.cancel(true)); assertTrue(capacity.cancel(true)); assertTrue(waitersEnded.await(5, SECONDS))
            assertFalse(owner.isDone); assertEquals(1, calls.get())
            release.countDown(); owner.get(5, SECONDS)
            pool.submit { proof.verify(second, ExecutionStore.digest("other")) }.get(5, SECONDS)
            assertEquals(2, calls.get())
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, SECONDS)) }
    }

    @Test fun `interrupted verifier releases its flight and capacity so later reads can recover`() {
        val first = file("one"); val second = file("two", "other")
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val interruptOnce = AtomicBoolean(true)
        val proof = ArtifactFileVerification(maxFlights = 1, hash = {
            if (it == first && interruptOnce.compareAndSet(true, false)) {
                entered.countDown(); check(release.await(5, SECONDS))
            }
            hashFile(it)
        })
        val pool = Executors.newFixedThreadPool(2)
        try {
            val owner = pool.submit { proof.verify(first, ExecutionStore.digest("hello")) }
            assertTrue(entered.await(5, SECONDS)); assertTrue(owner.cancel(true))
            pool.submit { proof.verify(second, ExecutionStore.digest("other")) }.get(5, SECONDS)
            pool.submit { proof.verify(first, ExecutionStore.digest("hello")) }.get(5, SECONDS)
            assertEquals(2, proof.entryCount)
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, SECONDS)) }
    }

    @Test fun `same size tampering with restored mtime rejects cached proof and repair verifies again`() {
        val path = file("blob"); val expected = ExecutionStore.digest("hello"); var reads = 0
        val proof = ArtifactFileVerification(hash = { reads++; hashFile(it) })
        proof.verify(path, expected)
        val modified = Files.getLastModifiedTime(path)
        Files.writeString(path, "wrong"); Files.setLastModifiedTime(path, modified)
        assertFailsWith<IllegalArgumentException> { proof.verify(path, expected) }
        assertEquals(2, reads); assertEquals(0, proof.entryCount)
        Files.writeString(path, "hello"); Files.setLastModifiedTime(path, modified)
        proof.verify(path, expected); proof.verify(path, expected)
        assertEquals(3, reads)
    }

    @Test fun `replacement inodes reverify and symbolic links never inherit a proof`() {
        val path = file("blob"); val expected = ExecutionStore.digest("hello"); var reads = 0
        val proof = ArtifactFileVerification(hash = { reads++; hashFile(it) })
        proof.verify(path, expected)
        val modified = Files.getLastModifiedTime(path); val replacement = file("replacement")
        Files.setLastModifiedTime(replacement, modified); Files.move(replacement, path, REPLACE_EXISTING)
        proof.verify(path, expected); assertEquals(2, reads)
        Files.delete(path); val outside = file("outside"); Files.createSymbolicLink(path, outside)
        assertFailsWith<IllegalArgumentException> { proof.verify(path, expected) }
        assertEquals(2, reads)
    }

    @Test fun `unavailable or incomplete metadata always hashes and cannot conceal corruption`() {
        val path = file("blob"); val expected = ExecutionStore.digest("hello")
        for (observation in listOf<(Path) -> TreeVerification.FileMetadata?>(
            { null }, { throw IOException("metadata unavailable") }, { throw SecurityException("metadata denied") },
            { TreeVerification.fileMetadata(it)?.let { value -> value.copy(stamp = value.stamp.copy(changed = null)) } },
        )) {
            Files.writeString(path, "hello"); var reads = 0
            val proof = ArtifactFileVerification(hash = { reads++; hashFile(it) }, observe = observation)
            proof.verify(path, expected); proof.verify(path, expected)
            assertEquals(2, reads); assertEquals(0, proof.entryCount)
            Files.writeString(path, "wrong")
            assertFailsWith<IllegalArgumentException> { proof.verify(path, expected) }
            assertEquals(3, reads)
        }
    }

    @Test fun `metadata change during hashing cannot establish a proof or a verified response`() {
        val path = file("blob"); val proof = ArtifactFileVerification(hash = { candidate ->
            hashFile(candidate).also { Files.writeString(candidate, "wrong") }
        })
        assertFailsWith<IllegalArgumentException> { proof.verify(path, ExecutionStore.digest("hello")) }
        assertEquals(0, proof.entryCount)
    }

    @Test fun `metadata eviction bounds entries and changes no files`() {
        var reads = 0; val proof = ArtifactFileVerification(maxEntries = 2, hash = { reads++; hashFile(it) })
        val paths = (1..3).map { file("blob-$it") }
        paths.forEach { proof.verify(it, ExecutionStore.digest("hello")) }
        assertEquals(2, proof.entryCount)
        proof.verify(paths.first(), ExecutionStore.digest("hello"))
        assertEquals(4, reads); assertEquals(2, proof.entryCount)
        assertEquals(3L, Files.list(root).use { it.count() })
    }

    @Test fun `fresh and resumed verified publications seed only the publishing store after barriers`() {
        val data = bytes(); val expected = sha(data); var reads = 0
        val proof = ArtifactFileVerification(hash = { reads++; hashFile(it) }); val store = LocalArtifactStore(root, proof)
        store.put(1, expected, data.size.toLong(), 0, data.inputStream())
        store.file(1, expected); store.open(1, expected).use { assertEquals(data[0].toInt() and 255, it.read()) }
        assertEquals(0, reads)
        store.put(2, expected, data.size.toLong(), 0, data.copyOfRange(0, 10).inputStream())
        store.put(2, expected, data.size.toLong(), 10, data.copyOfRange(10, data.size).inputStream())
        store.file(2, expected); assertEquals(0, reads)
        // Another API process has no shared RAM proof and must perform its own first complete checksum.
        LocalArtifactStore(root, ArtifactFileVerification(hash = { reads++; hashFile(it) })).file(2, expected)
        assertEquals(1, reads)
    }

    @Test fun `failed shared barrier retains bytes without publishing a verification hint`() {
        val data = bytes(); val expected = sha(data); var reads = 0
        val proof = ArtifactFileVerification(hash = { reads++; hashFile(it) }); val store = LocalArtifactStore(root, proof)
        assertFailsWith<IOException> {
            store.publishBatch(1, { throw IOException("barrier failed") }) {
                store.put(1, expected, data.size.toLong(), 0, data.inputStream()); assertEquals(0, proof.entryCount)
            }
        }
        assertEquals(0, proof.entryCount); assertTrue(Files.exists(root.resolve("1/$expected")))
        store.file(1, expected); assertEquals(1, reads)
        store.publishBatch(2, { assertEquals(1, proof.entryCount) }) {
            store.put(2, expected, data.size.toLong(), 0, data.inputStream()); assertEquals(1, proof.entryCount)
        }
        assertEquals(2, proof.entryCount); store.file(2, expected); assertEquals(1, reads)
    }

    @Test fun `changes between rename and the shared barrier cannot seed a verification hint`() {
        val data = bytes(); val expected = sha(data); val proof = ArtifactFileVerification()
        val store = LocalArtifactStore(root, proof)
        store.publishBatch(1, {
            val path = root.resolve("1/$expected"); val modified = Files.getLastModifiedTime(path)
            Files.write(path, data.copyOf().also { it[0] = 127 }); Files.setLastModifiedTime(path, modified)
        }) { store.put(1, expected, data.size.toLong(), 0, data.inputStream()) }
        assertEquals(0, proof.entryCount)
        assertFailsWith<IllegalArgumentException> { store.file(1, expected) }
    }

    @Test fun `publication proof cannot bless a replacement inode with the same size and mtime`() {
        val partial = file("partial"); val proof = ArtifactFileVerification()
        val verified = proof.capture(partial); assertNotNull(verified)
        val full = root.resolve("full"); Files.move(partial, full)
        val replacement = file("replacement", "wrong")
        Files.setLastModifiedTime(replacement, Files.getLastModifiedTime(full)); Files.move(replacement, full, REPLACE_EXISTING)
        val renamed = proof.renamed(full, verified); assertNull(renamed)
        proof.published(full, ExecutionStore.digest("hello"), renamed); assertEquals(0, proof.entryCount)
        assertFailsWith<IllegalArgumentException> { proof.verify(full, ExecutionStore.digest("hello")) }
    }

    @Test fun `publication proof retains full filemode even when executable status and mtime match`() {
        val partial = file("partial"); val proof = ArtifactFileVerification()
        val verified = proof.capture(partial); assertNotNull(verified)
        val full = root.resolve("full"); Files.move(partial, full)
        val permissions = Files.getPosixFilePermissions(full).toMutableSet()
        if (!permissions.add(GROUP_WRITE)) permissions.remove(GROUP_WRITE)
        Files.setPosixFilePermissions(full, permissions)
        val changed = proof.capture(full); assertNotNull(changed)
        assertEquals(verified.stamp.executable, changed.stamp.executable)
        assertEquals(verified.stamp.modified, changed.stamp.modified)
        assertNotEquals(verified.mode, changed.mode); assertNull(proof.renamed(full, verified))
    }

    @Test fun `tiny publications avoid added proof metadata and corrupt resumed prefixes are still rejected`() {
        var observations = 0; val proof = ArtifactFileVerification(observe = { observations++; TreeVerification.fileMetadata(it) })
        val store = LocalArtifactStore(root, proof); val data = "small".toByteArray(); val expected = sha(data)
        store.put(1, expected, data.size.toLong(), 0, data.inputStream())
        assertEquals(0, observations); assertEquals(0, proof.entryCount)
        val large = bytes(); val largeSha = sha(large)
        store.put(2, largeSha, large.size.toLong(), 0, byteArrayOf(127).inputStream())
        assertFailsWith<IllegalStateException> {
            store.put(2, largeSha, large.size.toLong(), 1, large.copyOfRange(1, large.size).inputStream())
        }
        assertEquals(0, proof.entryCount); assertFalse(Files.exists(root.resolve("2/$largeSha.partial")))
    }
}
