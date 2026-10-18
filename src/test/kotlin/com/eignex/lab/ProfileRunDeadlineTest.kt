package com.eignex.lab

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProfileRunDeadlineTest {
    @Test
    fun `a blocked cancellation read cannot extend the deadline`() {
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            ProfileRunDeadline(2_000, cancelled = {
                blocked.countDown()
                release.await()
                false
            }).use { deadline ->
                assertFailsWith<ProfileRunTimeout> {
                    deadline.work { blocked.await(); CountDownLatch(1).await() }
                }
                assertEquals("cleaned", deadline.cleanup { "cleaned" })
            }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `work stages share their allowance and preserve cleanup time`() {
        var nanos = 0L
        ProfileRunDeadline(10_000, nanoTime = { nanos }).use { deadline ->
            deadline.work { nanos = 4_000_000_000L }

            assertEquals(5_000L, deadline.remainingMillis())
            assertFailsWith<ProfileRunTimeout> { deadline.work { nanos = 9_000_000_000L } }
            deadline.cleanup { nanos = 9_500_000_000L }

            assertEquals(500L, deadline.remainingMillis(cleanup = true))
        }
    }

    @Test
    fun `an exhausted allowance refuses additional cleanup`() {
        var nanos = 0L
        ProfileRunDeadline(10_000, nanoTime = { nanos }).use { deadline ->
            var called = false
            nanos = 10_000_000_000L

            assertFailsWith<ProfileRunTimeout> { deadline.cleanup { called = true } }

            assertFalse(called)
        }
    }

    @Test
    fun `a failed stage preserves its original exception`() {
        val failure = IllegalArgumentException("setup failed")

        val actual = ProfileRunDeadline(10_000).use { deadline ->
            assertFailsWith<IllegalArgumentException> { deadline.work { throw failure } }
        }

        assertSame(failure, actual)
    }

    @Test
    fun `a timed out setup kills its child process`() {
        val child = AtomicLong()
        var process: Process? = null
        val started = System.nanoTime()
        try {
            ProfileRunDeadline(2_000).use { deadline ->
                assertFailsWith<ProfileRunTimeout> {
                    deadline.work {
                        val running = ProfileRunDeadline.start(ProcessBuilder("bash", "-c", "sleep 30 & echo \$!; wait"))
                        process = running
                        child.set(running.inputStream.bufferedReader().readLine().trim().toLong())
                        running.waitFor()
                    }
                }
            }

            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 10)
            assertTrue(child.get() > 0)
            ProcessHandle.of(child.get()).ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
            assertFalse(process!!.isAlive)
            assertFalse(ProcessHandle.of(child.get()).map { it.isAlive }.orElse(false))
        } finally {
            process?.let { killGroup(it.pid()); it.destroyForcibly() }
        }
    }

    @Test
    fun `closing a stage reaches an orphan while leaving an unrelated group running`() {
        val unrelated = ProcessBuilder(inSession("sleep", "30")).start()
        var orphan = 0L
        try {
            ProfileRunDeadline(10_000).use { deadline ->
                deadline.work {
                    val parent = ProfileRunDeadline.start(ProcessBuilder("bash", "-c", "sleep 30 & echo \$!; exit 0"))
                    orphan = parent.inputStream.bufferedReader().readLine().trim().toLong()
                    parent.waitFor()
                }
            }

            ProcessHandle.of(orphan).ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
            assertFalse(ProcessHandle.of(orphan).map { it.isAlive }.orElse(false))
            assertTrue(unrelated.isAlive)
        } finally {
            unrelated.destroyForcibly().waitFor()
            if (orphan > 0) ProcessHandle.of(orphan).ifPresent { it.destroyForcibly() }
        }
    }

    @Test
    fun `cancellation stops a waiting stage and still allows cleanup`() {
        val cancelled = AtomicBoolean()
        var process: Process? = null
        try {
            ProfileRunDeadline(30_000, cancelled = cancelled::get).use { deadline ->
                assertFailsWith<CancellationException> {
                    deadline.work {
                        val running = ProfileRunDeadline.start(ProcessBuilder("sleep", "30"))
                        process = running
                        cancelled.set(true)
                        running.waitFor()
                    }
                }

                assertEquals("cleaned", deadline.cleanup { "cleaned" })
            }
            process!!.waitFor(5, TimeUnit.SECONDS)
            assertFalse(process!!.isAlive)
        } finally {
            process?.destroyForcibly()
        }
    }

    @Test
    fun `a captured stage cannot launch processes after it closes`() {
        ProfileRunDeadline(10_000).use { deadline ->
            val late = deadline.work { ProfileRunDeadline.task { ProfileRunDeadline.start(ProcessBuilder("true")) } }

            assertFailsWith<ProfileRunTimeout> { late.call() }
        }
    }
}
