package com.eignex.lab

import com.eignex.lab.domain.*
import com.eignex.lab.web.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class JobFileInventoryTest {
    private val file = FileEntry("ordinary.log", 7)
    private fun key(id: Long = 1, version: Int = 1, status: Status = Status.RUNNING, trimmed: Boolean = false) =
        JobFileInventory.Key(id, version, status, trimmed)

    @Test fun `cached readiness distinguishes queued and active work without probing readers or claiming freshness`() {
        val now = AtomicLong(100); val validates = AtomicInteger(); val reads = AtomicInteger()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val inventory = JobFileInventory({ validates.incrementAndGet(); key(it) }, { error("detailed reader required") },
            clock = now::get, parallel = 1, maxPending = 2, detailedRead = { _, phase ->
                reads.incrementAndGet(); phase(JobFileInventory.Phase.PHYSICAL_FILES)
                entered.countDown(); release.await(); listOf(file)
            })
        inventory.use {
            try {
                assertNull(it.get(1, 0)); assertTrue(entered.await(2, SECONDS))
                now.set(250)
                repeat(20) {
                    val status = inventory.readiness(1)
                    assertEquals(JobFileInventory.State.BUSY, status.state)
                    assertEquals(JobFileInventory.Phase.PHYSICAL_FILES, status.phase)
                    assertEquals(150L, status.elapsedMs); assertEquals(150L, status.phaseElapsedMs)
                    assertNull(status.snapshotAgeMs); assertNull(status.snapshotStale)
                    assertTrue("currentValidated=false" in status.detail())
                }
                assertEquals(1, validates.get()); assertEquals(1, reads.get(), "status reads cannot start more IO")
                assertNull(it.get(2, 0))
                assertEquals(JobFileInventory.State.WAITING, it.readiness(2).state)
                assertEquals(JobFileInventory.Phase.QUEUED, it.readiness(2).phase)
                assertNull(it.get(3, 0)); assertEquals(JobFileInventory.State.WAITING, it.readiness(3).state)
                release.countDown()
                assertEquals(listOf(file), assertNotNull(it.get(1)).files)
                val idle = it.readiness(1)
                assertEquals(JobFileInventory.State.IDLE, idle.state); assertEquals(false, idle.snapshotStale)
                assertTrue("currentValidated=false" in idle.detail(), "retained cache age does not prove the next request current")
            } finally { release.countDown() }
        }
        assertEquals(JobFileInventory.State.CLOSED, inventory.readiness(1).state)
    }

    @Test fun `failed readiness retains only safe categories and recovers without serving an expired snapshot`() {
        val now = AtomicLong(); val reads = AtomicInteger(); var fail = false
        JobFileInventory({ key(it) }, { error("detailed reader required") }, clock = now::get, activeAgeMs = 10,
            detailedRead = { _, phase ->
                reads.incrementAndGet(); phase(JobFileInventory.Phase.ACCEPTED_METADATA)
                if (fail) throw IOException("private/path and arbitrary payload text")
                listOf(file)
            }).use { inventory ->
            assertNotNull(inventory.get(1))
            now.set(11); fail = true
            assertNull(inventory.get(1))
            val failed = inventory.readiness(1)
            assertEquals(JobFileInventory.State.FAILED, failed.state)
            assertEquals(JobFileInventory.Phase.ACCEPTED_METADATA, failed.phase)
            assertEquals(JobFileInventory.ErrorCategory.IO, failed.lastError)
            assertEquals(JobFileInventory.Phase.ACCEPTED_METADATA, failed.lastFailurePhase)
            assertEquals(true, failed.snapshotStale); assertEquals(11L, failed.snapshotAgeMs)
            assertEquals(2_000L, failed.retryInMs)
            assertFalse("private" in failed.detail() || "payload" in failed.detail())
            repeat(20) { inventory.readiness(1) }; assertNull(inventory.get(1)); assertEquals(2, reads.get())
            fail = false; now.set(2_012)
            assertEquals(listOf(file), assertNotNull(inventory.get(1)).files)
            val recovered = inventory.readiness(1)
            assertEquals(JobFileInventory.State.IDLE, recovered.state)
            assertNull(recovered.lastError); assertNull(recovered.lastFailurePhase); assertNull(recovered.failureAgeMs)
            assertEquals(false, recovered.snapshotStale)
        }
    }

    @Test fun `generation changes report revalidation failure even while retained snapshot age is young`() {
        var version = 1; var change = false
        JobFileInventory({ key(it, version) }, {
            if (change) version++
            listOf(file)
        }, clock = { 0 }).use { inventory ->
            assertNotNull(inventory.get(1)); version++; change = true
            assertNull(inventory.get(1))
            val failed = inventory.readiness(1)
            assertEquals(JobFileInventory.State.FAILED, failed.state)
            assertEquals(JobFileInventory.Phase.REVALIDATING, failed.phase)
            assertEquals(JobFileInventory.ErrorCategory.CHANGED, failed.lastError)
            assertEquals(0L, failed.snapshotAgeMs); assertEquals(true, failed.snapshotStale)
            assertNull(inventory.failure(1), "the diagnostic category must not turn an internal refresh failure into a different API status")
        }
    }

    @Test fun `blocked inventory coalesces readers and never returns an empty partial array`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val reads = AtomicInteger()
        JobFileInventory({ key(it) }, { reads.incrementAndGet(); entered.countDown(); release.await(); listOf(file) }).use { inventory ->
            try {
                assertNull(inventory.get(1, 0))
                assertTrue(entered.await(2, SECONDS))
                repeat(20) { assertNull(inventory.get(1, 0)) }
                assertEquals(1, reads.get())
                release.countDown()
                assertEquals(listOf(file), assertNotNull(inventory.get(1)).files)
                assertEquals(listOf(file), assertNotNull(inventory.get(1)).files)
                assertEquals(1, reads.get(), "a warm validation must not repeat enumeration")
            } finally { release.countDown() }
        }
    }

    @Test fun `version done and trim transitions invalidate complete snapshots before their expiry`() {
        var version = 1; var status = Status.RUNNING; var trimmed = false
        val reads = AtomicInteger()
        JobFileInventory({ key(it, version, status, trimmed) }, { reads.incrementAndGet(); listOf(file.copy(bytes = it.version.toLong())) }).use { inventory ->
            assertEquals(1L, assertNotNull(inventory.get(1)).files.single().bytes)
            version = 2
            assertEquals(2L, assertNotNull(inventory.get(1)).files.single().bytes)
            status = Status.DONE
            assertEquals(Status.DONE, assertNotNull(inventory.get(1)).key.status)
            trimmed = true
            assertTrue(assertNotNull(inventory.get(1)).key.trimmed)
            assertEquals(4, reads.get())
        }
    }

    @Test fun `expiry and failed background refresh recover without returning stale complete data`() {
        var now = 0L; var fail = false
        val reads = AtomicInteger()
        JobFileInventory({ key(it) }, { reads.incrementAndGet(); check(!fail); listOf(file) }, clock = { now },
            activeAgeMs = 10, retryMs = 5).use { inventory ->
            assertNotNull(inventory.get(1))
            now = 11; fail = true
            assertNull(inventory.get(1))
            assertNull(inventory.get(1))
            assertEquals(2, reads.get(), "failure cooldown must not restart work on every poll")
            now = 17; fail = false
            assertEquals(listOf(file), assertNotNull(inventory.get(1)).files)
            assertEquals(3, reads.get())
        }
    }

    @Test fun `changing accepted version during enumeration retries rather than publishing an incomplete generation`() {
        var version = 1; var now = 0L
        JobFileInventory({ key(it, version) }, { version++; listOf(file) }, clock = { now }, retryMs = 1).use { inventory ->
            assertNull(inventory.get(1))
            now++
            assertNull(inventory.get(1))
        }
    }

    @Test fun `pending work and retained jobs are bounded and oversized results are served whole once`() {
        val gate = CountDownLatch(1); val entered = CountDownLatch(2)
        JobFileInventory({ key(it) }, { entered.countDown(); gate.await(); listOf(file) }, maxPending = 2).use { inventory ->
            try {
                assertNull(inventory.get(1, 0)); assertNull(inventory.get(2, 0))
                assertTrue(entered.await(2, SECONDS))
                assertNull(inventory.get(3, 0))
            } finally { gate.countDown() }
        }
        val reads = AtomicInteger()
        JobFileInventory({ key(it) }, { reads.incrementAndGet(); listOf(file) }, maxJobs = 1).use { inventory ->
            assertNotNull(inventory.get(1)); assertNotNull(inventory.get(2)); assertNotNull(inventory.get(1))
            assertEquals(3, reads.get())
        }
        JobFileInventory({ key(it) }, { listOf(file, file.copy(path = "second.log")) }, maxNames = 1).use { inventory ->
            assertEquals(2, assertNotNull(inventory.get(1)).files.size)
            assertEquals(2, assertNotNull(inventory.get(1)).files.size)
        }
    }

    @Test fun `shutdown interrupts background work and rejects new refreshes`() {
        val entered = CountDownLatch(1); val stopped = CountDownLatch(1)
        val inventory = JobFileInventory({ key(it) }, {
            entered.countDown()
            try { CountDownLatch(1).await(); emptyList() } finally { stopped.countDown() }
        })
        assertNull(inventory.get(1, 0))
        assertTrue(entered.await(2, SECONDS))
        inventory.close()
        assertTrue(stopped.await(2, SECONDS))
        assertNull(inventory.get(1, 0))
    }

    @Test fun `completed oversized handoffs revalidate trim and accepted version before the next poll`() {
        for (change in listOf("trim", "version")) {
            var version = 1; var trimmed = false
            val finished = CountDownLatch(1); val reads = AtomicInteger()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            JobFileInventory({ key(it, version, trimmed = trimmed) }, {
                entered.countDown(); release.await()
                reads.incrementAndGet(); listOf(file, file.copy(path = "second.log"))
            }, maxNames = 1, completed = { finished.countDown() }).use { inventory ->
                assertNull(inventory.get(1, 0))
                assertTrue(entered.await(2, SECONDS))
                release.countDown()
                assertTrue(finished.await(2, SECONDS), "background handoff did not complete")
                if (change == "trim") trimmed = true else version++
                val next = assertNotNull(inventory.get(1))
                assertEquals(version, next.key.version)
                assertEquals(trimmed, next.key.trimmed)
                assertEquals(2, reads.get())
            }
        }
    }

    @Test fun `terminal log metadata failures return unavailable instead of a retained complete snapshot`() {
        val root = Files.createTempDirectory("terminal-log-probe")
        Files.writeString(root.resolve("job.log"), "done")
        var fail = false; var now = 0L
        val reads = AtomicInteger()
        JobFileInventory({ id ->
            key(id, status = Status.DONE).copy(logs = jobLogStamp(root) { path ->
                if (fail) throw AccessDeniedException(path.toString())
                Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            })
        }, { reads.incrementAndGet(); listOf(file) }, clock = { now }).use { inventory ->
            assertNotNull(inventory.get(1))
            fail = true
            assertNull(inventory.get(1), "a failed metadata check cannot reuse a complete warm snapshot")
            assertEquals(503, assertNotNull(inventory.failure(1)).status.value)
            assertEquals(1, reads.get(), "failed validation must not enumerate the job or CAS")
            fail = false; now = 2_001
            assertNotNull(inventory.get(1))
            assertNull(inventory.failure(1))
        }
    }

    @Test fun `terminal log stamps observe creation replacement and removal using only four nofollow metadata reads`() {
        val root = Files.createTempDirectory("terminal-log-stamp")
        val log = root.resolve("job.log")
        var probes = 0
        fun stamp() = jobLogStamp(root) { path ->
            probes++
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        }
        val missing = stamp()
        assertEquals(4, probes)
        Files.writeString(log, "done")
        val created = stamp()
        assertNotEquals(missing, created)
        val original = Files.readAttributes(log, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val replacement = root.resolve("replacement")
        Files.writeString(replacement, "next")
        Files.setLastModifiedTime(replacement, original.lastModifiedTime())
        Files.move(replacement, log, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        val replaced = stamp()
        // A rename can preserve size and mtime; providers with file keys still expose its new identity.
        if (original.fileKey() != null) assertNotEquals(created, replaced)
        Files.delete(log)
        Files.createSymbolicLink(log, root.resolve("absent-target"))
        assertFalse(stamp().single { it.name == "job.log" }.regular)
        Files.delete(log)
        assertEquals(missing, stamp())
        assertEquals(20, probes, "validation must not enumerate a directory or inspect accepted blobs")
    }

    @Test fun `explicit wait delivers fresh inventories whose slow validations finish between short polls`() = runBlocking {
        val entered = LinkedBlockingQueue<Int>(); val finished = LinkedBlockingQueue<Unit>()
        val permits = Semaphore(0); val calls = AtomicInteger(); val reads = AtomicInteger()
        var version = 1
        JobFileInventory({ id ->
            entered.put(calls.incrementAndGet()); permits.acquire()
            key(id, version, Status.DONE)
        }, { reads.incrementAndGet(); listOf(file.copy(bytes = it.version.toLong())) },
            completed = { finished.put(Unit) }).use { inventory ->
            try {
                // Two default one-second waits keep each validation behind its latch for at least two seconds.
                // Release it in the polling gap, then repeat: an already complete warm snapshot is still missed.
                repeat(2) { attempt ->
                    assertNull(inventory.get(1))
                    assertNull(inventory.get(1))
                    assertEquals(if (attempt == 0) 1 else 3, entered.poll(2, SECONDS))
                    permits.release(if (attempt == 0) 2 else 1)
                    if (attempt == 0) assertEquals(2, entered.poll(2, SECONDS))
                    assertNotNull(finished.poll(2, SECONDS))
                }
                assertEquals(1, reads.get(), "enumeration completed; only repeated validation prevented delivery")
                version = 2
                val waiting = async(start = CoroutineStart.UNDISPATCHED) { inventory.await(1, 30_000) }
                assertEquals(4, entered.poll(2, SECONDS))
                assertFalse(waiting.isCompleted, "the request suspends while validation stays in the background")
                permits.release(2)
                val snapshot = assertNotNull(withTimeout(2_000) { waiting.await() })
                assertEquals(2, snapshot.key.version)
                assertEquals(2L, snapshot.files.single().bytes)
                assertEquals(2, reads.get())
            } finally { permits.release(16) }
        }
    }

    @Test fun `long poll timeout and cancellation leave shared inventory work available to another waiter`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val reads = AtomicInteger()
        JobFileInventory({ key(it) }, {
            reads.incrementAndGet(); entered.countDown(); release.await(); listOf(file)
        }).use { inventory ->
            try {
                val disconnected = async(start = CoroutineStart.UNDISPATCHED) { inventory.await(1, 30_000) }
                assertTrue(entered.await(2, SECONDS))
                val waiting = async(start = CoroutineStart.UNDISPATCHED) { inventory.await(1, 30_000) }
                assertNull(inventory.await(1, 1), "a bounded timeout must not cancel the shared future")
                disconnected.cancelAndJoin()
                assertFalse(waiting.isCompleted)
                release.countDown()
                assertEquals(listOf(file), assertNotNull(withTimeout(2_000) { waiting.await() }).files)
                assertEquals(1, reads.get())
            } finally { release.countDown() }
        }
    }

    @Test fun `explicit waits keep oversized handoff generation and metadata failures strict`() = runBlocking {
        var version = 1; var status = Status.RUNNING; var trimmed = false; var fail = false; var now = 0L
        val reads = AtomicInteger()
        JobFileInventory({ id ->
            if (fail) throw ApiError.Unavailable("metadata unavailable")
            key(id, version, status, trimmed)
        }, { reads.incrementAndGet(); listOf(file, file.copy(path = "second.log")) }, maxNames = 1,
            clock = { now }).use { inventory ->
            assertEquals(2, assertNotNull(inventory.await(1, 1_000)).files.size)
            version++
            assertEquals(2, assertNotNull(inventory.await(1, 1_000)).key.version)
            status = Status.DONE
            assertEquals(Status.DONE, assertNotNull(inventory.await(1, 1_000)).key.status)
            trimmed = true
            assertTrue(assertNotNull(inventory.await(1, 1_000)).key.trimmed)
            assertEquals(4, reads.get())
            fail = true
            assertNull(inventory.await(1, 1_000), "metadata failure cannot serve a completed oversized handoff")
            assertEquals(503, assertNotNull(inventory.failure(1)).status.value)
            assertEquals(4, reads.get())
            fail = false; now = 2_001
            assertEquals(2, assertNotNull(inventory.await(1, 1_000)).files.size)
            assertNull(inventory.failure(1))
        }
    }
}
