package com.eignex.lab

import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobFilesTest {
    private val host = HostReport("Test/Arm64", 4, "java")

    /** A job whose directory holds a log and a record, a secret beside it, and links into and out of the directory. */
    private class Lab {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null)
        val store = Store(config.database)
        val id = store.create("job", "main", emptyList())
        val other = store.create("other", "main", emptyList())
        val dir: Path = config.jobDir(id).createDirectories()
        val secret: Path = config.dataDir.resolve("secret.txt").apply { writeText("top secret") }

        init {
            dir.resolve("job.log").writeText("line one\nline two\n")
            dir.resolve("cases/0").createDirectories().resolve("record.json").writeText("{\"ok\":true}")
            config.jobDir(other).createDirectories().resolve("job.log").writeText("other job")
            Files.createSymbolicLink(dir.resolve("leak.txt"), secret)
            Files.createSymbolicLink(dir.resolve("shared"), config.dataDir.resolve("shared").createDirectories())
            config.dataDir.resolve("shared").resolve("cached.txt").writeText("shared cache")
            Files.createSymbolicLink(dir.resolve("log-link"), dir.resolve("job.log"))
        }
    }

    private fun withLab(block: suspend ApplicationTestBuilder.(Lab) -> Unit) {
        val lab = Lab()
        testApplication {
            application { api(lab.config, lab.store, host) }
            block(lab)
        }
    }

    private suspend fun HttpResponse.assertRefused() {
        assertTrue(status == HttpStatusCode.BadRequest || status == HttpStatusCode.NotFound, "status $status")
        val body = bodyAsText()
        assertFalse("secret" in body, body)
        assertFalse("Exception" in body || "\tat " in body, body)
        assertFalse("/" in body, body)
    }

    @Test
    fun `a job's own files are served whole and as tails`() = withLab { lab ->
        val log = client.get("/jobs/${lab.id}/files/job.log")
        val record = client.get("/jobs/${lab.id}/files/cases/0/record.json")
        val tail = client.get("/jobs/${lab.id}/files/job.log?tail=9")
        val linked = client.get("/jobs/${lab.id}/files/log-link")

        assertEquals("line one\nline two\n", log.bodyAsText())
        assertEquals("{\"ok\":true}", record.bodyAsText())
        assertEquals("line two\n", tail.bodyAsText())
        assertEquals("line one\nline two\n", linked.bodyAsText())
    }

    @Test
    fun `dot-dot paths are refused, plain or encoded`() = withLab { lab ->
        for (path in listOf(
            "../../secret.txt",
            "cases/../../../secret.txt",
            "..%2F..%2Fsecret.txt",
            "%2e%2e/%2e%2e/secret.txt",
            "%2E%2E%2F%2E%2E%2Fsecret.txt",
            "cases%2F..%2F..%2F..%2Fsecret.txt",
            "..%5C..%5Csecret.txt",
            "..%252F..%252Fsecret.txt",
            "../${lab.other}/job.log",
            "%2e%2e%2f${lab.other}%2fjob.log",
        )) {
            client.get("/jobs/${lab.id}/files/$path").assertRefused()
        }
    }

    @Test
    fun `absolute paths, backslashes and NULs are refused`() = withLab { lab ->
        for (path in listOf(
            "%2F${lab.secret.toString().removePrefix("/").replace("/", "%2F")}",
            "%2Fetc%2Fpasswd",
            "/etc/passwd",
            "cases%5C0%5Crecord.json",
            "job.log%00.txt",
            "job.log%00",
        )) {
            client.get("/jobs/${lab.id}/files/$path").assertRefused()
        }
    }

    @Test
    fun `symbolic links out of the job directory are refused and not listed`() = withLab { lab ->
        client.get("/jobs/${lab.id}/files/leak.txt").assertRefused()
        client.get("/jobs/${lab.id}/files/shared/cached.txt").assertRefused()
        client.get("/jobs/${lab.id}/files/leak.txt?tail=100").assertRefused()

        val listing = client.get("/jobs/${lab.id}/files").bodyAsText()

        assertTrue("job.log" in listing && "cases/0/record.json" in listing, listing)
        assertFalse("leak" in listing || "cached" in listing || "log-link" in listing, listing)
    }

    @Test
    fun `a malformed id is a bad request and an unknown job is not found`() = withLab { lab ->
        assertEquals(HttpStatusCode.BadRequest, client.get("/jobs/abc/files").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/jobs/abc/files/job.log").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/jobs/..%2F..%2Fsecret.txt/files").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/jobs/999/files").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/jobs/999/files/job.log").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/jobs/${lab.id}/files/missing.log").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/jobs/${lab.id}/files/cases").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/jobs/${lab.id}/files/job.log?tail=abc").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/jobs/${lab.id}/files/job.log?tail=-1").status)
    }

    @Test
    fun `a job's files are served from LAB_JOBS when it is set`() {
        val jobs = Files.createTempDirectory("jobs")
        val config = Config(dataDir = Files.createTempDirectory("lab"), jobsDir = jobs, passwordHash = null)
        val store = Store(config.database)
        val id = store.create("job", "main", emptyList())
        jobs.resolve("$id").createDirectories().resolve("job.log").writeText("from the jobs volume\n")
        testApplication {
            application { api(config, store, host) }

            assertEquals("from the jobs volume\n", client.get("/jobs/$id/files/job.log").bodyAsText())
            assertTrue("job.log" in client.get("/jobs/$id/files").bodyAsText())
        }
        assertFalse(Files.exists(config.dataDir.resolve("jobs")))
    }

    @Test
    fun `while the job files' volume is not mounted, a job's files are unavailable and say why`() {
        val root = Files.createTempDirectory("volumes")
        val config = Config(dataDir = Files.createTempDirectory("lab"), jobsDir = root.resolve("Ros/jobs"), passwordHash = null, volumesRoot = root)
        val store = Store(config.database)
        val id = store.create("job", "main", emptyList())
        testApplication {
            application { api(config, store, host) }

            for (path in listOf("/jobs/$id/files", "/jobs/$id/files/job.log", "/jobs/$id/files/job.log?tail=10")) {
                val response = client.get(path)
                assertEquals(HttpStatusCode.ServiceUnavailable, response.status, path)
                assertTrue("job files volume ${root.resolve("Ros")} is not mounted" in response.bodyAsText())
            }
            assertEquals(HttpStatusCode.NotFound, client.get("/jobs/999/files").status)
            val health = client.get("/health").bodyAsText()
            assertTrue("\"held\":\"job files volume ${root.resolve("Ros")} is not mounted\"" in health, health)
        }
        assertFalse(Files.exists(root.resolve("Ros")))
    }

    @Test
    fun `while the job files do not answer, a job's files are refused at once and say why`() {
        val gate = CountDownLatch(1)
        val data = Files.createTempDirectory("lab")
        val jobs = data.resolve("jobs")
        val java = "/Library/Java/JavaVirtualMachines/test.jdk/Contents/Home/bin/java"
        // A probe of the job files blocks, as a call on a volume macOS keeps the lab from does; kept for no time at all,
        // so every request asks again and finds the stuck probe still in flight.
        val access = VolumeAccess(timeoutMs = 300, freshMs = 0, java = java) { label, dir ->
            if (dir == jobs) gate.await()
            Volumes.probe(label, dir)
        }
        val config = Config(dataDir = data, corpusDir = data.resolve("corpus"), passwordHash = null, volumeAccess = access)
        val store = Store(config.database)
        val id = store.create("job", "main", emptyList())
        try {
            testApplication {
                application { api(config, store, host) }

                assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/jobs/$id/files").status)
                for (path in listOf("/jobs/$id/files", "/jobs/$id/files/job.log", "/jobs/$id/files/job.log?tail=10")) {
                    val started = System.nanoTime()
                    val response = client.get(path)
                    val tookMs = (System.nanoTime() - started) / 1_000_000
                    assertEquals(HttpStatusCode.ServiceUnavailable, response.status, path)
                    val body = response.bodyAsText()
                    assertTrue(body.startsWith("job files unavailable: cannot access job files directory $jobs within 300 ms"), body)
                    assertTrue("grant Full Disk Access to $java" in body, body)
                    assertTrue(tookMs < 300, "$path took $tookMs ms")
                }
                val health = client.get("/health").bodyAsText()
                assertTrue("\"held\":\"cannot access job files directory $jobs" in health, health)
                assertTrue("\"problem\":\"cannot access job files directory $jobs" in health, health)
            }
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun `jobFile refuses bad segments and links that leave the root`() {
        val lab = Lab()

        for (segments in listOf(listOf(".."), listOf("cases", "..", ".."), listOf(""), listOf("/etc/passwd"), listOf("a\\b"), listOf("a\u0000"))) {
            assertFailsWith<IllegalArgumentException> { jobFile(lab.dir, segments) }
        }
        assertNull(jobFile(lab.dir, listOf("leak.txt")))
        assertNull(jobFile(lab.dir, listOf("shared", "cached.txt")))
        assertEquals(lab.dir.toRealPath().resolve("job.log"), jobFile(lab.dir, listOf("log-link")))
    }
}
