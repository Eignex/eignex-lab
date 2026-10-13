package com.eignex.lab

import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JobFilesTest {
    private val host = HostReport("Test/Arm64", 4, "java", "engine", true, emptyList(), vendor = "Accelerate")

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
            Files.createSymbolicLink(dir.resolve("shared"), config.sharedDir.createDirectories())
            config.sharedDir.resolve("cached.txt").writeText("shared cache")
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
