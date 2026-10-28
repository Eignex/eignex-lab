package com.eignex.lab

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class CoordinatorWorkTest {
    @Test fun `blocked work is coalesced and saturation returns immediately without queuing more work`() {
        CoordinatorWork<String, Int>("work-test", 1).use { work ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val calls = AtomicInteger()
            try {
                assertNull(work.poll("slow") { calls.incrementAndGet(); entered.countDown(); release.await(); 42 })
                assertTrue(entered.await(2, SECONDS))
                repeat(100) { assertNull(work.poll("slow") { error("duplicate work") }) }
                assertNull(work.poll("other") { error("unbounded backlog") })
                release.countDown()
                var value: Int? = null
                val deadline = System.nanoTime() + SECONDS.toNanos(2)
                while (value == null && System.nanoTime() < deadline) {
                    value = work.poll("slow") { error("duplicate work") }
                    Thread.yield()
                }
                assertEquals(42, value)
                assertEquals(1, calls.get())
            } finally { release.countDown() }
        }
    }

    @Test fun `background failures retain their type for admission and retries can succeed`() {
        CoordinatorWork<String, Int>("work-test", 1).use { work ->
            val release = CountDownLatch(1)
            work.poll("job") { release.await(); throw IllegalArgumentException("bad source") }
            release.countDown()
            val deadline = System.nanoTime() + SECONDS.toNanos(2)
            var rejected = false
            while (!rejected && System.nanoTime() < deadline) {
                try { work.poll("job") { error("duplicate") } }
                catch (failure: IllegalArgumentException) { assertEquals("bad source", failure.message); rejected = true }
                Thread.yield()
            }
            assertTrue(rejected)
            var value: Int? = null
            while (value == null && System.nanoTime() < deadline) { value = work.poll("job") { 7 }; Thread.yield() }
            assertEquals(7, value)
        }
    }
}
