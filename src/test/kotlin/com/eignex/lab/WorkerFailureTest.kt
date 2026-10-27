package com.eignex.lab

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class WorkerFailureTest {
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
