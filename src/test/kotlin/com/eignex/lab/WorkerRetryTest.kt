package com.eignex.lab

import java.io.IOException
import kotlin.test.*

class WorkerRetryTest {
    private var time = 0L
    private val retry = WorkerRetry({ time }, { time += it })

    @Test fun `a brief restart tolerates more than three failures and preserves the same operation`() {
        var calls = 0
        assertEquals("accepted", retry.run({ 80_000 - time }) { if (++calls <= 7) throw WorkerUnavailable(502); "accepted" })
        assertEquals(8, calls); assertTrue(time in 5_000..15_000)
    }
    @Test fun `outage retries never extend a lease or the sixty second outage budget`() {
        var calls = 0
        assertFailsWith<IOException> { retry.run({ 1250 - time }) { calls++; throw IOException("disconnected") } }
        assertEquals(1250, time); assertTrue(calls > 1)
        time = 0
        assertFailsWith<IOException> { retry.run({ Long.MAX_VALUE }) { throw WorkerUnavailable(503) } }
        assertEquals(60_000, time)
    }
    @Test fun `fencing cancellation and whole run expiry never retry`() {
        for (failure in listOf(ExecutionFenced("lost"), ProfileRunTimeout(), InterruptedException(), java.util.concurrent.CancellationException())) {
            var calls = 0
            assertSame(failure, assertFails { retry.run({ 80_000 }) { calls++; throw failure } })
            assertEquals(1, calls); assertEquals(0, time)
        }
        var calls = 0
        assertFailsWith<IllegalArgumentException> { retry.run({ 80_000 }) { calls++; errorArgument() } }
        assertEquals(3, calls)
    }
    private fun errorArgument(): Nothing = throw IllegalArgumentException("bad manifest")
}
