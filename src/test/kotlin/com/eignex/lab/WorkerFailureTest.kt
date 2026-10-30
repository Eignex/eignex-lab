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

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class WorkerFailureTest {
    @Test fun `worker process exits distinguish software from transport cancellation and fencing`() {
        for (temporary in listOf(WorkerUnavailable(503), java.io.IOException("offline"), WorkerInfrastructureFailure("dependency unavailable"),
            InterruptedException(), java.util.concurrent.CancellationException(), LeaseLost("lease ended"))) {
            assertEquals(WORKER_TEMPORARY_EXIT, workerProcessExit(temporary))
        }
        assertEquals(0, workerProcessExit(ExecutionFenced("completed or revoked")))
        for (software in listOf(IllegalStateException("broken invariant"), IllegalArgumentException("bad recipe"), NullPointerException(),
            WorkerBuildFailure("compile failed"))) assertEquals(WORKER_SOFTWARE_EXIT, workerProcessExit(software))
    }

    @Test fun `waiting past a lost build lease is a temporary loss, never a crash`() {
        val root = kotlin.io.path.createTempDirectory("build-cache")
        val cache = WorkerBuildCache(root, ProcessSupervisor(root.resolve("unused.sock")))
        val failure = assertFailsWith<LeaseLost> { cache.compilationSlot(run = { null }, lease = { 0 }) { error("must not build") } }
        assertEquals(WORKER_TEMPORARY_EXIT, workerProcessExit(failure))
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
