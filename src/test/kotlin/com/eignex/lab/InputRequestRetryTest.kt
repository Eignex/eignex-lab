package com.eignex.lab

import com.eignex.lab.execution.InputRequestRetry
import com.eignex.lab.domain.ExecutionFenced
import java.io.EOFException
import kotlin.test.*

class InputRequestRetryTest {
    @Test fun `transient input requests recover within three attempts`() {
        var attempts = 0
        val sleeps = mutableListOf<Long>()
        val result = InputRequestRetry { sleeps.add(it) }.run("presence", {}) {
            if (++attempts < 3) throw EOFException("connection reset")
            7
        }
        assertEquals(7, result)
        assertEquals(3, attempts)
        assertEquals(listOf(100L, 200L), sleeps)
    }

    @Test fun `persistent transport failure exhausts a bounded request rather than looping`() {
        var attempts = 0
        assertFailsWith<EOFException> {
            InputRequestRetry {}.run("presence", {}) { attempts++; throw EOFException() }
        }
        assertEquals(3, attempts)
    }

    @Test fun `ownership lost during backoff prevents another request`() {
        var attempts = 0
        var active = true
        assertFailsWith<ExecutionFenced> {
            InputRequestRetry { active = false }.run("presence", {
                if (!active) throw ExecutionFenced("lease expired")
            }) { attempts++; throw EOFException() }
        }
        assertEquals(1, attempts)
    }

    @Test fun `validation failures are not retried`() {
        var attempts = 0
        assertFailsWith<IllegalStateException> {
            InputRequestRetry {}.run("presence", {}) { attempts++; error("invalid metadata") }
        }
        assertEquals(1, attempts)
    }

    @Test fun `interrupted backoff does not issue another request`() {
        var attempts = 0
        assertFailsWith<InterruptedException> {
            InputRequestRetry { throw InterruptedException() }.run("presence", {}) { attempts++; throw EOFException() }
        }
        assertEquals(1, attempts)
    }
}
