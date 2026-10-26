package com.eignex.lab

import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeIdleAdmissionTest {
    @Test fun `idle runner releases ownership throughout its poll sleep`() {
        val config = Config(dataDir = Files.createTempDirectory("native-idle"), minFreeBytes = 0,
            dockerCleanupDays = 0, updateCheckSec = 0, requireDocker = false)
        val runner = Runner(config, Store(config.database), startCloud = { null })
        val loop = thread(isDaemon = true, name = "native-idle-runner") { runCatching { runner.loop() } }
        try {
            val deadline = System.nanoTime() + 5_000_000_000
            while ((!runner.recovered || loop.state != Thread.State.TIMED_WAITING) && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(runner.recovered)
            assertTrue(loop.state == Thread.State.TIMED_WAITING)
            val ownership = assertNotNull(NativeExecutionLock.tryAcquire(config.nativeExecutionLock), "Nomad must acquire ownership while the runner is idle")
            ownership.use { Thread.sleep(100) }
        } finally {
            loop.interrupt(); loop.join(3000)
        }
        assertFalse(loop.isAlive)
    }
}
