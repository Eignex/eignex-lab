package com.eignex.lab

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class WorkerFailureTest {
    @Test fun `worker process exits distinguish software from transport cancellation and fencing`() {
        for (temporary in listOf(WorkerUnavailable(503), java.io.IOException("offline"), WorkerInfrastructureFailure("dependency unavailable"),
            InterruptedException(), java.util.concurrent.CancellationException())) {
            assertEquals(WORKER_TEMPORARY_EXIT, workerProcessExit(temporary))
        }
        assertEquals(0, workerProcessExit(ExecutionFenced("completed or revoked")))
        for (software in listOf(IllegalStateException("broken invariant"), IllegalArgumentException("bad recipe"), NullPointerException(),
            WorkerBuildFailure("compile failed"))) assertEquals(WORKER_SOFTWARE_EXIT, workerProcessExit(software))
    }

    @Test fun `temporary dependency failure remains an infrastructure loss`() {
        val failure = assertFailsWith<WorkerInfrastructureFailure> { checkPreparationExit(75) { "dependency unavailable" } }
        assertEquals("dependency unavailable", failure.message)
    }

    @Test fun `ordinary preparation failures remain code failures`() {
        assertFailsWith<WorkerBuildFailure> { checkPreparationExit(1) { "compilation failed" } }
    }

    @Test fun `successful preparation does not read failure output`() {
        checkPreparationExit(0) { error("should not read logs") }
    }
}
