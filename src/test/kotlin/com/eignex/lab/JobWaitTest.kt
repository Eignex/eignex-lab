package com.eignex.lab

import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.store.*
import com.eignex.lab.web.*
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

class JobWaitTest {
    private val config = Config(dataDir = Files.createTempDirectory("job-wait"), auth = Auth.Disabled, minFreeBytes = 0)
    private val store = Store(config.database)
    private val host = HostReport("Test/Arm64", 4, "java")
    private fun job() = store.create("waiting", "main", listOf("full command retained" to 17L), experiment = ExperimentSpec("waiting"))

    @Test fun `terminal wait retains the complete existing job response`() = testApplication {
        val id = job()
        store.finish(id, Status.FAILED, "fixture failure")
        application { api(config, store, host) }
        val response = client.get("/jobs/$id/wait")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(store.job(id), Json.decodeFromString<Job>(response.bodyAsText()))
    }

    @Test fun `wait deadline returns the current full job with its command details`() = testApplication {
        val id = job()
        application { api(config, store, host) }
        // The existing lower timeout bound is one second, with the existing two-second polling cadence.
        val response = withTimeout(5_000) { client.get("/jobs/$id/wait?timeoutSec=0") }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<Job>(response.bodyAsText())
        assertEquals(Status.QUEUED, body.status)
        assertEquals("full command retained", body.commands.single().cmd)
        assertEquals(17L, body.commands.single().timeoutSec)
        assertEquals(store.job(id), body)
    }

    @Test fun `an active wait observes cancellation and returns the full cancelled job`() = testApplication {
        val id = job()
        application { api(config, store, host) }
        coroutineScope {
            val waiting = async { client.get("/jobs/$id/wait?timeoutSec=600") }
            delay(100)
            assertFalse(waiting.isCompleted)
            store.requestCancel(id)
            val response = withTimeout(5_000) { waiting.await() }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.decodeFromString<Job>(response.bodyAsText())
            assertEquals(Status.CANCELLED, body.status)
            assertEquals(Status.CANCELLED, body.commands.single().status)
            assertEquals(store.job(id), body)
        }
    }

    @Test fun `wait preserves missing job and malformed request errors`() = testApplication {
        application { api(config, store, host) }
        assertEquals(HttpStatusCode.NotFound, client.get("/jobs/999/wait").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/jobs/not-a-number/wait").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/jobs/999/wait?timeoutSec=invalid").status)
    }

    @Test fun `execution and hold identity checks preserve missing jobs and job bound leases`() = testApplication {
        val first = job(); val second = job()
        application { api(config, store, host) }
        assertEquals(HttpStatusCode.OK, client.get("/jobs/$first/execution").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/jobs/999/execution").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/jobs/invalid/execution").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/jobs/999/hold").status)
        val created = client.post("/jobs/$first/hold?ttlSec=60")
        assertEquals(HttpStatusCode.Created, created.status)
        val hold = Json.decodeFromString<Hold>(created.bodyAsText())
        assertEquals(first, hold.job)
        assertEquals(HttpStatusCode.NotFound, client.post("/jobs/$second/hold/${hold.id}").status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/jobs/$second/hold/${hold.id}").status)
        assertEquals(HttpStatusCode.OK, client.post("/jobs/$first/hold/${hold.id}?ttlSec=60").status)
        assertEquals(HttpStatusCode.OK, client.delete("/jobs/$first/hold/${hold.id}").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/jobs/$first/hold/${hold.id}").status)
    }
}
