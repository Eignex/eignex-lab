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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.*

class VolumeHealthTest {
    @Test fun `hung storage probes do not block callers and cannot refresh an expired healthy sample`() {
        var now = 0L
        var block = false
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val health = VolumeHealth(reason = {
            if (block) { entered.countDown(); release.await() }
            null
        }, maxAgeMs = 100, clock = { now })
        assertNotNull(health.problem())
        health.refresh()
        assertNull(health.problem())
        block = true
        val executor = Executors.newSingleThreadExecutor()
        try {
            now = 20
            val probe = executor.submit { health.refresh() }
            assertTrue(entered.await(2, SECONDS))
            repeat(100) { assertNull(health.problem()) }
            now = 101
            assertContains(health.problem()!!, "expired")
            now = 200
            release.countDown(); probe.get(2, SECONDS)
            assertContains(health.problem()!!, "expired", message = "a slow probe must not extend its own validity")
            block = false; health.refresh()
            assertNull(health.problem())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun `failed and stale checks hold new work and recover only after a successful refresh`() {
        var now = 0L
        var problem: String? = "volume missing"
        val health = VolumeHealth(reason = { problem }, maxAgeMs = 100, clock = { now })
        val guard = DiskGuard(health::problem)
        health.refresh(); assertFalse(guard.allowsWork())
        assertEquals(problem, guard.held)
        problem = null
        assertFalse(guard.allowsWork())
        health.refresh(); assertTrue(guard.allowsWork())
        now = 101; assertFalse(guard.allowsWork())
    }
}
