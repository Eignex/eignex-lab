package com.eignex.lab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.util.concurrent.CancellationException

class RetryTest {
    @Test
    fun `an exhausted profile deadline is not retried`() {
        var tries = 0

        assertFailsWith<ProfileRunTimeout> {
            retrying(Backoff(3, 1, 1), sleep = {}) { tries++; throw ProfileRunTimeout() }
        }

        assertEquals(1, tries)
    }

    @Test
    fun `a cancelled operation is not retried`() {
        var tries = 0

        assertFailsWith<CancellationException> {
            retrying(Backoff(3, 1, 1), sleep = {}) { tries++; throw CancellationException() }
        }

        assertEquals(1, tries)
    }

    @Test
    fun `waits double from the base and stop at the cap`() {
        val backoff = Backoff(attempts = 6, baseMs = 15_000, maxMs = 300_000)

        assertEquals(listOf(15_000L, 30_000, 60_000, 120_000, 240_000, 300_000), (1..6).map(backoff::delayAfter))
    }

    @Test
    fun `a step that recovers returns after its waits`() {
        val waits = ArrayList<Long>()
        var tries = 0

        val result = retrying(Backoff(5, 10, 100), sleep = { waits += it }) {
            if (++tries < 3) error("down") else "up"
        }

        assertEquals(listOf("up", "3", "[10, 20]"), listOf(result, tries.toString(), waits.toString()))
    }

    @Test
    fun `a step that keeps failing fails after its last try`() {
        var tries = 0

        val failure = assertFailsWith<IllegalStateException> { retrying(Backoff(3, 1, 1), sleep = {}) { error("down ${++tries}") } }

        assertEquals("down 3", failure.message)
    }
}
