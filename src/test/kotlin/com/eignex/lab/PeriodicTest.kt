package com.eignex.lab

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeriodicTest {
    @Test
    fun `a failing round is logged and the loop goes on until it is interrupted`() {
        val rounds = CountDownLatch(3)
        val loop = periodic("test-loop", 1) {
            rounds.countDown()
            error("round failed")
        }
        assertTrue(rounds.await(5, TimeUnit.SECONDS))

        loop.interrupt()
        loop.join(5_000)
        assertFalse(loop.isAlive)
    }
}
