package com.eignex.lab

import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.nomad.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import com.eignex.lab.web.*
import com.eignex.lab.worker.*

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlin.test.*

class RegionalResultBlobsTest {
    private class Blobs : BuildArchives {
        val objects = mutableMapOf<String, ByteArray>()
        val gets = mutableListOf<String>()
        override fun size(sha: String) = objects[sha]?.size?.toLong()
        override fun put(source: Path, sha: String) { objects[sha] = Files.readAllBytes(source) }
        override fun get(sha: String, destination: Path) { gets += sha; Files.write(destination, objects.getValue(sha)) }
    }

    private fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    @Test fun `the coordinator reads a regional result only when it first opens it and checks its hash`() {
        val root = Files.createTempDirectory("regional-results")
        try {
            val results = Blobs()
            val bytes = "recorded flight".toByteArray(); val sha = sha(bytes)
            results.objects[sha] = bytes
            val local = LocalArtifactStore(root.resolve("artifacts"))
            ResultJournal(root.resolve("journal"), RegionalArtifactStore(local, Blobs(), root.resolve("fetches"), results), start = false).use { store ->
                assertEquals(bytes.size.toLong(), store.regionalResult(sha))
                assertNull(store.regionalResult(sha("expired".toByteArray())))
                assertEquals(emptyList(), results.gets)
                assertContentEquals(bytes, store.open(7, sha).use { it.readBytes() })
                assertContentEquals(bytes, store.open(7, sha).use { it.readBytes() })
                assertEquals(listOf(sha), results.gets)
            }
            val corrupt = sha("expected".toByteArray())
            results.objects[corrupt] = "tampered".toByteArray()
            val store = RegionalArtifactStore(LocalArtifactStore(root.resolve("other")), Blobs(), root.resolve("fetches"), results)
            assertFails { store.open(3, corrupt).use { it.readBytes() } }
            assertFailsWith<IllegalArgumentException> { local.file(3, corrupt) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `a coordinator without a regional result store refuses regional results`() {
        val root = Files.createTempDirectory("regional-none")
        try {
            val local = LocalArtifactStore(root.resolve("artifacts"))
            assertFailsWith<RegionalResultRefused> { local.regionalResult("a".repeat(64)) }
            ResultJournal(root.resolve("journal"), RegionalArtifactStore(local, Blobs(), root.resolve("fetches")), start = false).use {
                assertFailsWith<RegionalResultRefused> { it.regionalResult("a".repeat(64)) }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `only cloud workers upload results to the regional store above a configurable size`() {
        val url = "https://bucket.s3.eu-north-1.amazonaws.com"
        assertEquals(ResultBlobs.DEFAULT_MIN_BYTES, ResultBlobs.worker(mapOf("LAB_SHARED_INPUT_URL" to url))?.minBytes)
        assertEquals(1024L, ResultBlobs.worker(mapOf("LAB_SHARED_INPUT_URL" to url, "LAB_RESULT_BLOB_MIN_BYTES" to "1024"))?.minBytes)
        assertEquals(ResultBlobs.DEFAULT_MIN_BYTES, ResultBlobs.worker(mapOf("LAB_SHARED_INPUT_URL" to url, "LAB_RESULT_BLOB_MIN_BYTES" to "-1"))?.minBytes)
        assertNull(ResultBlobs.worker(mapOf("LAB_SHARED_INPUT_URL" to url, "LAB_RESULT_BLOB_MIN_BYTES" to "off")))
        assertNull(ResultBlobs.worker(mapOf("LAB_SHARED_INPUT_URL" to url, "LAB_SHARED_INPUT_SIGNER" to "http://127.0.0.1:8093/")))
        assertNull(ResultBlobs.worker(emptyMap()))
        assertNotNull(ResultBlobs.coordinator(mapOf("LAB_SHARED_INPUT_URL" to url, "LAB_SHARED_INPUT_SIGNER" to "http://127.0.0.1:8093/")))
        assertNull(ResultBlobs.coordinator(mapOf("LAB_SHARED_INPUT_URL" to url)))
        assertEquals("result-blobs/" + "a".repeat(64), ResultBlobs.key("a".repeat(64)))
        assertFailsWith<IllegalArgumentException> { ResultBlobs.key("../x") }
    }

    @Test fun `regional outputs list with their declared size and read lazily or as unavailable once expired`() {
        val config = Config(dataDir = Files.createTempDirectory("regional-listing"))
        try {
            val store = Store(config.database)
            val job = store.create("regional", "main", emptyList(), experiment = ExperimentSpec("regional"))
            val results = Blobs()
            val kept = "a large capture".toByteArray(); val keptSha = sha(kept)
            val expired = "an expired capture".toByteArray(); val expiredSha = sha(expired)
            results.objects[keptSha] = kept
            val names = mapOf("cases/0/profile-x/cli.jfr" to keptSha, "cases/0/profile-x/hotspot-compilation.log.gz" to expiredSha)
            val token = ExecutionStore.token()
            store.execution.createSession("session", job, ExecutionClass.PREPARE, "release", ResourceBudget(1000, 1024), token, "{}", 1000)
            store.execution.bind("session", token, "allocation", "node", "release", 1001)
            val owner = store.execution.claim("session", token, "allocation", 1002)!!.owner
            store.execution.phase(owner, "uploading", 1003)
            store.execution.commit(owner, IterationResult(emptyList(), names, preparation = PreparationResult(emptyList(), emptyList()),
                regionalBlobs = mapOf(keptSha to kept.size.toLong(), expiredSha to expired.size.toLong())), 1004)
            assertEquals(mapOf(keptSha to kept.size.toLong(), expiredSha to expired.size.toLong()), store.execution.acceptedRegionalBlobs(job))

            val local = LocalArtifactStore(config.dataDir.resolve("nomad/artifacts"))
            val reader = AcceptedArtifactFiles(config, store, local,
                regional = RegionalArtifactStore(local, Blobs(), config.dataDir.resolve("nomad/build-fetches"), results))
            val listed = reader.entries(job, emptyList()).associate { it.path to it.bytes }
            assertEquals(mapOf("cases/0/profile-x/cli.jfr" to kept.size.toLong(), "cases/0/profile-x/hotspot-compilation.log.gz" to expired.size.toLong()), listed)
            assertEquals(emptyList(), results.gets, "a listing never reads the regional store")

            val read = assertNotNull(reader.resolve(job, listOf("cases", "0", "profile-x", "cli.jfr")))
            assertContentEquals(kept, Files.readAllBytes(read.file))
            assertNotNull(reader.resolve(job, listOf("cases", "0", "profile-x", "cli.jfr")))
            assertEquals(listOf(keptSha), results.gets)
            val failure = assertFailsWith<ApiError.Unavailable> { reader.resolve(job, listOf("cases", "0", "profile-x", "hotspot-compilation.log.gz")) }
            assertTrue("expired" in failure.message.orEmpty())
            // The listing stays whole after a failed read.
            assertEquals(2, reader.entries(job, emptyList()).size)
        } finally { config.dataDir.toFile().deleteRecursively() }
    }

    private class Uploads(override val minBytes: Long) : ResultBlobs.Uploader {
        val objects = mutableMapOf<String, ByteArray>()
        val signatures = mutableListOf<InputSignature>()
        override fun put(source: Path, sha: String, bytes: Long, sign: (InputSignature) -> SignedInput) {
            val signature = InputSignature("PUT", ResultBlobs.key(sha), sha, bytes)
            signatures += signature
            assertEquals("https://bucket.example/" + signature.key, sign(signature).url)
            objects[sha] = Files.readAllBytes(source)
        }
    }

    @Test fun `a cloud worker uploads large outputs directly and commits only their hash and size`() {
        val root = Files.createTempDirectory("worker-regional")
        val large = root.resolve("cli.jfr").also { Files.write(it, ByteArray(4096) { (it % 7).toByte() }) }
        val small = root.resolve("0.out").also { Files.writeString(it, "small") }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/worker/v1/sessions/session/input-signature") { exchange ->
            val signature = Json.decodeFromString<InputSignature>(String(exchange.requestBody.readAllBytes()))
            assertNotNull(exchange.requestHeaders.getFirst("X-Lab-Attempt"))
            val body = Json.encodeToString(SignedInput.serializer(), SignedInput("https://bucket.example/" + signature.key)).toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body); exchange.close()
        }
        var committed: WorkerInlineCommit? = null
        server.createContext("/worker/v1/sessions/session/commit-inline") { exchange ->
            committed = Json.decodeFromString<WorkerInlineCommit>(String(exchange.requestBody.readAllBytes()))
            exchange.sendResponseHeaders(204, -1); exchange.close()
        }
        server.start()
        try {
            val identity = root.resolve("identity").also { Files.writeString(it, "fixture") }
            val worker = WorkerSession(URI("http://127.0.0.1:${server.address.port}"), "session", "token", "allocation", identity,
                "release", root, root.resolve("unused.sock"), "unused")
            val uploads = Uploads(1024); worker.resultBlobs = uploads
            val owner = AttemptOwner("session", "allocation", "iteration", 1, "attempt-token")
            assertEquals("inline+regional", worker.commitResult(owner, IterationResult(listOf(ExecutionCaseResult(0, 0, 1, 2))),
                mapOf("cases/0/profile-x/cli.jfr" to large, "0.out" to small)))
            val body = assertNotNull(committed)
            assertEquals(mapOf(hashFile(large) to 4096L), body.result.regionalBlobs)
            assertEquals(setOf(hashFile(small)), body.blobs.keys, "the large output's bytes never reach the coordinator")
            assertContentEquals(Files.readAllBytes(large), uploads.objects.getValue(hashFile(large)))
            assertEquals(listOf(4096L), uploads.signatures.map { it.bytes })
        } finally { server.stop(0); deleteTree(root) }
    }

    @Test fun `a refused regional result or signature sends every output to the coordinator`() {
        val root = Files.createTempDirectory("worker-regional-refused")
        val large = root.resolve("cli.jfr").also { Files.write(it, ByteArray(4096) { 3 }) }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val signatures = AtomicInteger()
        var refuseSignatures = false
        server.createContext("/worker/v1/sessions/session/input-signature") { exchange ->
            exchange.requestBody.readAllBytes(); signatures.incrementAndGet()
            if (refuseSignatures) { exchange.sendResponseHeaders(422, -1); exchange.close(); return@createContext }
            val body = Json.encodeToString(SignedInput.serializer(), SignedInput("https://bucket.example/" + ResultBlobs.key(hashFile(large)))).toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.write(body); exchange.close()
        }
        val commits = mutableListOf<WorkerInlineCommit>()
        server.createContext("/worker/v1/sessions/session/commit-inline") { exchange ->
            val body = Json.decodeFromString<WorkerInlineCommit>(String(exchange.requestBody.readAllBytes()))
            commits += body
            // The coordinator cannot find the declared object: it refuses the regional route, not the row.
            exchange.sendResponseHeaders(if (body.result.regionalBlobs.isEmpty()) 204 else 422, -1); exchange.close()
        }
        server.start()
        try {
            val identity = root.resolve("identity").also { Files.writeString(it, "fixture") }
            val worker = WorkerSession(URI("http://127.0.0.1:${server.address.port}"), "session", "token", "allocation", identity,
                "release", root, root.resolve("unused.sock"), "unused")
            worker.resultBlobs = Uploads(1024)
            val owner = AttemptOwner("session", "allocation", "iteration", 1, "attempt-token")
            val files = mapOf("cases/0/cli.jfr" to large)
            val result = IterationResult(listOf(ExecutionCaseResult(0, 0, 1, 2)))
            assertEquals("inline", worker.commitResult(owner, result, files))
            assertEquals(listOf(mapOf(hashFile(large) to 4096L), emptyMap()), commits.map { it.result.regionalBlobs })
            assertEquals(setOf(hashFile(large)), commits.last().blobs.keys)
            // Later results of a session whose coordinator refused regional outputs skip the wasted upload.
            assertEquals("inline", worker.commitResult(owner, result, files))
            assertEquals(1, signatures.get())

            val fresh = WorkerSession(URI("http://127.0.0.1:${server.address.port}"), "session", "token", "allocation", identity,
                "release", root, root.resolve("unused.sock"), "unused")
            val uploads = Uploads(1024); fresh.resultBlobs = uploads
            refuseSignatures = true; commits.clear()
            assertEquals("inline", fresh.commitResult(owner, result, files))
            assertTrue(uploads.objects.isEmpty())
            assertEquals(listOf(emptyMap<String, Long>()), commits.map { it.result.regionalBlobs })
        } finally { server.stop(0); deleteTree(root) }
    }
}
