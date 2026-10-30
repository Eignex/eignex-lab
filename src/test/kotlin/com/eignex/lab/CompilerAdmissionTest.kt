package com.eignex.lab

import com.eignex.lab.util.ProcessFilePin
import com.eignex.lab.worker.CompilerAdmission
import com.eignex.lab.worker.RunDeadlineExceeded
import java.nio.file.Files
import kotlin.test.*

class CompilerAdmissionTest {
    @Test fun `later compilers cannot overtake the oldest live ticket`() {
        val root = Files.createTempDirectory("compiler-order")
        val first = CompilerAdmission(root)
        val second = CompilerAdmission(root)
        val third = CompilerAdmission(root)
        first.register().use { a -> second.register().use { b -> third.register().use { c ->
            assertNull(second.acquire(b, 2))
            first.acquire(a, 2)!!.use {
                assertNull(third.acquire(c, 2))
                second.acquire(b, 2)!!.use { assertNull(third.acquire(c, 2)) }
                third.acquire(c, 2)!!.close()
            }
        } } }
    }

    @Test fun `cancelled and dead waiters do not poison the queue`() {
        val root = Files.createTempDirectory("compiler-cancel")
        val admission = CompilerAdmission(root)
        val dead = Files.createDirectories(root.resolve("compile-waiters"))
            .resolve("00000000000000000001-00000000-0000-0000-0000-000000000000.wait")
        Files.createFile(dead)
        val cancelled = admission.register()
        admission.register().use { next ->
            assertFalse(Files.exists(dead))
            assertNull(admission.acquire(next, 1))
            cancelled.close()
            admission.acquire(next, 1)!!.close()
        }
    }

    @Test fun `legacy slot owners remain bounded and deadline exits release tickets`() {
        val root = Files.createTempDirectory("compiler-limit")
        val admission = CompilerAdmission(root)
        ProcessFilePin.tryLock(root.resolve("compile-0.lock"))!!.use {
            admission.register().use { ticket -> assertNull(admission.acquire(ticket, 1)) }
        }
        assertFailsWith<RunDeadlineExceeded> {
            admission.use(1, { throw RunDeadlineExceeded() }) { error("not admitted") }
        }
        admission.use(1, {}) { }
        assertEquals(0L, Files.list(root.resolve("compile-waiters")).use { it.count() })
    }
}
