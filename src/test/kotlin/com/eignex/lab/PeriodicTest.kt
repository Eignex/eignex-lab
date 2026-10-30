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
