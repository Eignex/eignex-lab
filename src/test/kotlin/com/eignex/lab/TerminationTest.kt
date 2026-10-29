package com.eignex.lab

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminationTest {
    @Test
    fun `reasons append with when, oldest first, and a missing file reads as none`() {
        val dir = Files.createTempDirectory("job")
        assertTrue(Termination.read(dir, 4).isEmpty())

        Termination.record(Termination.file(dir, 4), "shutdown: the runner stopped, so the lab killed the case; it reruns when the runner is back", at = 1)
        Termination.record(Termination.file(dir, 4), Termination.external(137)!!, at = 2)

        assertEquals(listOf("1 shutdown: the runner stopped, so the lab killed the case; it reruns when the runner is back", "2 ${Termination.external(137)}"), Termination.read(dir, 4))
    }

    @Test
    fun `only a kill's exits are signals, and an external one names no cause it has no evidence for`() {
        assertNull(Termination.external(1))
        assertNull(Termination.external(124))
        assertEquals("external: exit 143 is a SIGTERM the lab did not send; nothing the lab saw says what sent it",
            Termination.external(143))
    }

    @Test
    fun `an AWS kill before the limit is external with the kernel's lines, and one after it is the timeout's`() {
        val oom = "[Sat Oct 17 10:00:00 2026] Out of memory: Killed process 4242 (java)"

        assertEquals("external: exit 137 is a SIGKILL the lab did not send; kernel: $oom",
            Termination.remote(137, elapsedMs = 5_000, timeoutSec = 240, kernel = listOf(oom)))
        assertEquals("external: exit 137 is a SIGKILL the lab did not send; the instance's kernel log names no killed process",
            Termination.remote(137, elapsedMs = 5_000, timeoutSec = 240, kernel = emptyList()))
        assertTrue(Termination.remote(137, elapsedMs = 270_000, timeoutSec = 240, kernel = emptyList())!!.startsWith("timeout:"))
        assertNull(Termination.remote(1, elapsedMs = 5_000, timeoutSec = 240, kernel = emptyList()))
    }
}
