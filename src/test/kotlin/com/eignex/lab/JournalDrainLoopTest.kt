package com.eignex.lab

import com.eignex.lab.store.JournalDrainLoop
import java.util.concurrent.RejectedExecutionException
import kotlin.test.*

class JournalDrainLoopTest {
    @Test fun `a full backlog continues immediately in bounded quanta without per pack idle`() {
        val tasks = ArrayDeque<Pair<Runnable, Long>>()
        var pending = 100
        val loop = JournalDrainLoop(
            drainOne = { if (pending == 0) false else { pending--; true } },
            hasWork = { pending > 0 }, closed = { false },
            schedule = { action, delay -> tasks.addLast(action to delay) },
            onFailure = { throw AssertionError(it) }, nanoTime = { 0L })
        loop.start()
        assertEquals(2, tasks.size)
        var quanta = 0
        while (pending > 0) {
            val (action, delay) = tasks.removeFirst()
            assertEquals(0L, delay, "eligible packs must not wait for the idle interval")
            val before = pending
            action.run(); quanta++
            assertTrue(before - pending in 1..32)
        }
        assertEquals(4, quanta)
        assertTrue(tasks.any { it.second == 250L }, "empty lanes return to bounded idle polling")
    }

    @Test fun `a slow publication yields after its barrier without interrupting it`() {
        val tasks = ArrayDeque<Pair<Runnable, Long>>()
        var clock = 0L; var published = 0
        JournalDrainLoop({ published++; clock += 26_000_000L; true }, { true }, { false },
            { action, delay -> tasks.addLast(action to delay) }, { throw AssertionError(it) }, { clock }).start()
        tasks.removeFirst().first.run()
        assertEquals(1, published)
        assertEquals(0L, tasks.last().second)
    }

    @Test fun `a failed pack does not impose an idle delay on other eligible packs`() {
        val tasks = ArrayDeque<Pair<Runnable, Long>>()
        var attempts = 0; var failures = 0; var published = false
        JournalDrainLoop({
            when (attempts++) {
                0 -> throw java.io.IOException("failed pack has entered retry backoff")
                1 -> { published = true; true }
                else -> false
            }
        }, { !published }, { false }, { action, delay -> tasks.addLast(action to delay) },
            { failures++ }, { 0L }).start()
        tasks.removeFirst().first.run()
        assertEquals(1, failures); assertTrue(published)
        assertEquals(250L, tasks.last().second)
    }

    @Test fun `shutdown skips queued work and never resubmits`() {
        val tasks = ArrayDeque<Pair<Runnable, Long>>()
        var closed = false; var attempts = 0
        JournalDrainLoop({ attempts++; true }, { true }, { closed },
            { action, delay -> tasks.addLast(action to delay) }, { throw AssertionError(it) }).start()
        closed = true
        repeat(2) { tasks.removeFirst().first.run() }
        assertEquals(0, attempts); assertTrue(tasks.isEmpty())
    }

    @Test fun `executor shutdown racing a resubmission is harmless only after close`() {
        var closed = false
        JournalDrainLoop({ false }, { false }, { closed }, { _, _ ->
            closed = true; throw RejectedExecutionException("executor stopped")
        }, { throw AssertionError(it) }).start()
        assertTrue(closed)
        assertFailsWith<RejectedExecutionException> {
            JournalDrainLoop({ false }, { false }, { false }, { _, _ ->
                throw RejectedExecutionException("unexpected rejection")
            }, { throw AssertionError(it) }).start()
        }
    }
}
