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

    @Test fun `an upstream download outage is an infrastructure loss, not a code failure`() {
        // Verbatim from jobs 11545-11560, which failed on a GitHub outage before running any case.
        val outages = listOf(
            "> Could not GET 'https://github.com/Eignex/eignex-lab/releases/download/lab-bench-v0.1.0/lab-bench-0.1.0.module'. Received status code 504 from server: Gateway Time-out",
            "> Could not HEAD 'https://github.com/MiniZinc/MiniZincIDE/releases/download/2.10.1/MiniZincIDE-2.10.1-x86_64-linux-gnu.tgz'. Received status code 504 from server: Gateway Time-out",
            "Exception in thread \"main\" java.io.IOException: Server returned HTTP response code: 504 for URL: https://services.gradle.org/distributions/gradle-9.7.1-bin.zip",
            "> Could not GET 'https://repo.maven.apache.org/maven2/a/b/1/b-1.pom'. Received status code 429 from server: Too Many Requests",
            "> Could not GET 'https://repo.maven.apache.org/maven2/a/b/1/b-1.pom'. Connect to repo.maven.apache.org:443 failed: Connect timed out",
            "Exception in thread \"main\" java.net.UnknownHostException: services.gradle.org\n\tat org.gradle.wrapper.Download.downloadInternal(Download.java:58)",
        )
        for (output in outages) {
            assertFailsWith<WorkerInfrastructureFailure>(output) { checkPreparationExit(1) { "exit 1\nFAILURE: Build failed with an exception.\n$output" } }
        }
    }

    @Test fun `a missing or forbidden dependency stays a build failure`() {
        for (output in listOf(
            "> Could not GET 'https://github.com/x/y/releases/download/v1/y.module'. Received status code 404 from server: Not Found",
            "> Could not GET 'https://maven.pkg.github.com/x/y.pom'. Received status code 401 from server: Unauthorized",
            "e: file:///src/Main.kt:3:5 Unresolved reference 'connect'.",
        )) assertFailsWith<WorkerBuildFailure>(output) { checkPreparationExit(1) { output } }
    }

    @Test fun `successful preparation does not read failure output`() {
        checkPreparationExit(0) { error("should not read logs") }
    }
}
