package com.eignex.lab

import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import com.eignex.lab.web.*
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.*

class AcceptedArtifactFilesTest {
    private class Lab(initialCommit: Boolean = true, auth: Auth = Auth.Disabled,
                      val contents: List<String> = listOf("first retained bytes", "second retained bytes", "{\"ok\":true}")) {
        val config = Config(dataDir = Files.createTempDirectory("logical-artifacts"), auth = auth)
        val store = Store(config.database)
        val blobs = LocalArtifactStore(config.dataDir.resolve("nomad/artifacts"))
        val job = store.create("accepted", "main", emptyList(), experiment = ExperimentSpec("accepted"))
        val other = store.create("other", "main", emptyList(), experiment = ExperimentSpec("other"))
        val names = listOf("cases/0/out/DIR/Report.gz", "cases/0/out/dir/report.gz", "cases/0/record.json")
        val hashes = contents.map(ExecutionStore::digest)
        val accepted = names.zip(hashes).toMap()
        val services = ApiServices(config, store, HostReport("Test/Arm64", 4, "java"), blobs)
        val root = Files.createDirectories(config.jobDir(job))
        val physical = root.resolve("cases/0/out/dir/Report.gz")

        init {
            Files.createDirectories(physical.parent)
            Files.writeString(physical, "colliding projection contains neither original")
            // Model a folded projection on a case-sensitive CI volume using only links inside the job.
            val directory = physical.parent.resolveSibling("DIR")
            if (!Files.exists(directory)) Files.createSymbolicLink(directory, physical.parent)
            val file = physical.resolveSibling("report.gz")
            if (!Files.exists(file)) Files.createSymbolicLink(file, physical)
            Files.writeString(root.resolve("job.log"), "live log\n")
            contents.zip(hashes).forEach { (text, sha) -> blobs.put(job, sha, text.length.toLong(), 0, text.byteInputStream()) }
            if (initialCommit) commitAccepted()
        }
        fun commitAccepted() {
            val token = ExecutionStore.token()
            store.execution.createSession("preparation", job, ExecutionClass.PREPARE, "release", ResourceBudget(1000, 1024), token, "{}", 1000)
            store.execution.bind("preparation", token, "allocation", "node", "release", 1001)
            val owner = store.execution.claim("preparation", token, "allocation", 1002)!!.owner
            store.execution.phase(owner, "uploading", 1003)
            store.execution.commit(owner, IterationResult(emptyList(), accepted,
                preparation = com.eignex.lab.worker.PreparationResult(emptyList(), emptyList(), artifacts = accepted)), 1004)
        }
    }

    @Test fun `listing sizes are requested once per distinct hash without opening payloads`() {
        val lab = Lab(contents = listOf("same", "same", "{}"))
        var calls = 0
        val metadata = object : ArtifactStore by lab.blobs {
            override fun sizes(job: Long, hashes: Set<String>): Map<String, Long> {
                calls++
                assertEquals(lab.hashes.toSet(), hashes)
                return lab.blobs.sizes(job, hashes)
            }
            override fun file(job: Long, sha: String): java.nio.file.Path = error("listing opened a payload")
        }
        val reader = AcceptedArtifactFiles(lab.config, lab.store, metadata)
        repeat(2) {
            val entries = reader.entries(lab.job, jobFiles(lab.root))
            assertEquals(lab.names.toSet(), entries.filter { it.sha256 != null }.map { it.path }.toSet())
        }
        assertEquals(1, calls)
    }

    @Test fun `cold blocked listing returns retryable unavailable then a complete ordinary and logical inventory`() = testApplication {
        val lab = Lab()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val metadata = object : ArtifactStore by lab.blobs {
            override fun sizes(job: Long, hashes: Set<String>): Map<String, Long> {
                entered.countDown(); release.await()
                return lab.blobs.sizes(job, hashes)
            }
        }
        val services = ApiServices(lab.config, lab.store, HostReport("Test/Arm64", 4, "java"), metadata)
        application { api(services) }
        try {
            val pending = client.get("/jobs/${lab.job}/files")
            assertTrue(entered.await(2, SECONDS))
            assertEquals(HttpStatusCode.ServiceUnavailable, pending.status)
            assertEquals("2", pending.headers[HttpHeaders.RetryAfter])
            assertTrue(pending.bodyAsText().startsWith("{"), "a pending inventory must not look like a complete array")
            val unavailable = Json.decodeFromString<ErrorBody>(pending.bodyAsText())
            val detail = assertNotNull(unavailable.detail)
            assertTrue("state=busy" in detail && "phase=accepted_metadata" in detail, detail)
            assertTrue("currentValidated=false" in detail, detail)
            assertFalse(lab.root.toString() in detail || lab.names.any { it in detail }, "diagnostics must not reveal paths or artifact names")
            release.countDown()
            var ready = client.get("/jobs/${lab.job}/files")
            withTimeout(5_000) { while (ready.status == HttpStatusCode.ServiceUnavailable) { delay(10); ready = client.get("/jobs/${lab.job}/files") } }
            assertEquals(HttpStatusCode.OK, ready.status)
            val files = Json.decodeFromString<List<FileEntry>>(ready.bodyAsText())
            assertTrue(files.any { it.path == "job.log" })
            assertEquals(lab.names.toSet(), files.filter { it.path.startsWith("cases/") }.map { it.path }.toSet())
        } finally { release.countDown() }
    }

    @Test fun `accepted case variants are listed and fetched independently of a folded projection`() = testApplication {
        val lab = Lab(); val before = lab.store.execution.accepted(lab.job)
        application { api(lab.services) }
        val files = Json.decodeFromString<List<FileEntry>>(client.get("/jobs/${lab.job}/files").bodyAsText())
        assertEquals(lab.names.toSet(), files.filter { it.path.startsWith("cases/") }.map { it.path }.toSet())
        for (index in lab.names.indices) {
            val entry = files.single { it.path == lab.names[index] }
            assertEquals(lab.hashes[index], entry.sha256)
            assertEquals(lab.contents[index].length.toLong(), entry.bytes)
            val response = client.get("/jobs/${lab.job}/files/${lab.names[index]}")
            assertEquals(lab.contents[index], response.bodyAsText())
            assertEquals("\"${lab.hashes[index]}\"", response.headers[HttpHeaders.ETag])
        }
        val tail = client.get("/jobs/${lab.job}/files/${lab.names[0]}?tail=5")
        assertEquals("bytes", tail.bodyAsText()); assertNull(tail.headers[HttpHeaders.ETag])
        assertEquals("live log\n", client.get("/jobs/${lab.job}/files/job.log").bodyAsText())
        assertEquals(before, lab.store.execution.accepted(lab.job), "reads cannot rewrite accepted results")
    }

    @Test fun `a post DONE coordinator log append refreshes a warm inventory without losing accepted names or hashes`() = testApplication {
        val lab = Lab()
        lab.store.finish(lab.job, Status.DONE)
        application { api(lab.services) }
        suspend fun listing(): List<FileEntry> = withTimeout(5_000) {
            var response = client.get("/jobs/${lab.job}/files")
            while (response.status == HttpStatusCode.ServiceUnavailable) {
                delay(10); response = client.get("/jobs/${lab.job}/files")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            Json.decodeFromString<List<FileEntry>>(response.bodyAsText())
        }
        val version = lab.store.execution.acceptedArtifactVersion(lab.job)
        val before = listing()
        val log = lab.root.resolve("job.log")
        Files.writeString(log, "published after DONE\n", java.nio.file.StandardOpenOption.APPEND)
        val after = listing()
        assertEquals(Status.DONE, lab.store.jobStatus(lab.job))
        assertEquals(version, lab.store.execution.acceptedArtifactVersion(lab.job))
        assertTrue(after.single { it.path == "job.log" }.bytes > before.single { it.path == "job.log" }.bytes)
        assertEquals(Files.size(log), after.single { it.path == "job.log" }.bytes)
        assertEquals(before.map { it.path }.toSet(), after.map { it.path }.toSet())
        assertEquals(before.filter { it.sha256 != null }, after.filter { it.sha256 != null })
        assertEquals(Files.readString(log), client.get("/jobs/${lab.job}/files/job.log").bodyAsText())
    }

    @Test fun `accepted names refresh after a commit and oversized maps retain correct window boundaries`() = testApplication {
        val lab = Lab(initialCommit = false)
        application { api(lab.services) }
        val reader = AcceptedArtifactFiles(lab.config, lab.store, lab.blobs, maxNames = 1)
        assertNull(reader.resolve(lab.job, lab.names[0].split('/')))
        lab.commitAccepted()
        assertEquals(lab.accepted, lab.store.execution.acceptedArtifacts(lab.job))
        for (name in lab.names + lab.names.reversed()) {
            val artifact = assertNotNull(reader.resolve(lab.job, name.split('/')))
            assertEquals(lab.accepted[name], artifact.sha256)
            assertEquals(artifact.sha256, hashFile(artifact.file))
        }
        assertNull(reader.resolve(lab.job, listOf("zzzz")))
        assertEquals(lab.names.toSet(), reader.entries(lab.job, jobFiles(lab.root)).filter { it.sha256 != null }.map { it.path }.toSet())
    }

    @Test fun `trimmed jobs retain records without resurrecting accepted raw artifacts or their aliases`() = testApplication {
        val lab = Lab()
        application { api(lab.services) }
        Files.writeString(lab.root.resolve(JobFileNames.TRIMMED), "trimmed")
        val files = Json.decodeFromString<List<FileEntry>>(client.get("/jobs/${lab.job}/files").bodyAsText())
        assertEquals(listOf("cases/0/record.json"), files.filter { it.path.startsWith("cases/") }.map { it.path })
        for (name in lab.names.take(2) + "cases/0/out/dir/Report.gz")
            assertEquals(HttpStatusCode.NotFound, client.get("/jobs/${lab.job}/files/$name").status)
        assertEquals(lab.contents[2], client.get("/jobs/${lab.job}/files/${lab.names[2]}").bodyAsText())
    }

    @Test fun `missing corrupt blobs cannot silently serve a folded sibling and remain diagnosable in listings`() = testApplication {
        val lab = Lab()
        application { api(lab.services) }
        Files.delete(lab.blobs.readFile(lab.job, lab.hashes[0]))
        Files.writeString(lab.blobs.readFile(lab.job, lab.hashes[1]), "corrupt")
        val files = Json.decodeFromString<List<FileEntry>>(client.get("/jobs/${lab.job}/files").bodyAsText())
        assertEquals(FileEntry.UNKNOWN_BYTES, files.single { it.path == lab.names[0] }.bytes)
        for (name in lab.names.take(2)) {
            val response = client.get("/jobs/${lab.job}/files/$name")
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            val error = Json.decodeFromString<ErrorBody>(response.bodyAsText())
            assertEquals("accepted artifact unavailable", error.error); assertNull(error.detail)
        }
        // A journal-pending blob can still use a byte-identical safe projection.
        Files.writeString(lab.physical, lab.contents[0])
        assertEquals(lab.contents[0], client.get("/jobs/${lab.job}/files/${lab.names[0]}").bodyAsText())
    }

    @Test fun `logical artifacts obey authentication job ownership and existing path guards`() = testApplication {
        val lab = Lab(auth = Auth.Required(Password.hash("fixture-password")))
        application { api(lab.services) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/jobs/${lab.job}/files").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/jobs/${lab.job}/files/${lab.names[0]}").status)
        val reader = AcceptedArtifactFiles(lab.config, lab.store, lab.blobs)
        assertNull(reader.resolve(lab.other, lab.names[0].split('/')))
        assertFailsWith<IllegalArgumentException> { reader.resolve(lab.job, listOf("..", "secret")) }
        assertFailsWith<IllegalArgumentException> { reader.resolve(lab.job, listOf("cases\\secret")) }
        assertNull(reader.resolve(lab.job, listOf("nomad", "prepared-trees", "private.json")))
    }
}
