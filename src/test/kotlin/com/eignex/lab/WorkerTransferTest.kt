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

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlin.test.*

class WorkerTransferTest {
    @Test fun `cold input streams retry only the failed range and retain no download file`() {
        val root = Files.createTempDirectory("worker-input-stream")
        val bytes = ByteArray(128 * 1024) { (it % 251).toByte() }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        val offsets = mutableListOf<Int>(); var failed = false
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/worker/v1/sessions/session/inputs/$hash") { exchange ->
            try {
                val offset = exchange.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                offsets += offset
                if (offset > 0 && !failed) { failed = true; exchange.sendResponseHeaders(503, -1) }
                else {
                    val end = (offset + 32768).coerceAtMost(bytes.size)
                    exchange.responseHeaders.add("Content-Range", "bytes $offset-${end-1}/${bytes.size}")
                    exchange.sendResponseHeaders(206, (end-offset).toLong()); exchange.responseBody.write(bytes, offset, end-offset)
                }
            } finally { exchange.close() }
        }
        server.start()
        try {
            val identity = root.resolve("identity").also { Files.writeString(it, "fixture") }
            val worker = WorkerSession(URI.create("http://127.0.0.1:${server.address.port}"), "session", "token", "allocation", identity,
                "release", root, root.resolve("unused.sock"), "unused")
            val bundle = WorkerBundle(hash, listOf(InputFile("code", hash, bytes.size.toLong())), "Eignex/example", "a".repeat(40), "linux", "amd64", "fixture")
            worker.download(bundle).use { input -> assertContentEquals(bytes, input.readAllBytes()); assertEquals(-1, input.read()) }
            assertEquals(listOf(0, 32768, 32768, 65536, 98304), offsets)
            assertFalse(Files.exists(root.resolve("downloads")))
        } finally { server.stop(0); deleteTree(root) }
    }

    @Test fun `large results use one raw streaming request and survive a brief coordinator outage`() {
        val root = Files.createTempDirectory("worker-packed")
        val bytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val file = root.resolve("capture").also { Files.write(it, bytes) }
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/worker/v1/sessions/session/commit-packed") { exchange ->
            try {
                val packed = WorkerPackedCommit.read(exchange.requestBody)
                assertEquals(listOf(ResultBlob(hashFile(file), bytes.size.toLong())), packed.blobs)
                assertContentEquals(bytes, exchange.requestBody.readAllBytes())
                exchange.sendResponseHeaders(if (calls.incrementAndGet() <= 4) 503 else 204, -1)
            } finally { exchange.close() }
        }
        server.start()
        try {
            val identity = root.resolve("identity").also { Files.writeString(it, "fixture") }
            val worker = WorkerSession(URI.create("http://127.0.0.1:${server.address.port}"), "session", "token", "allocation", identity,
                "release", root, root.resolve("unused.sock"), "unused")
            val owner = AttemptOwner("session", "allocation", "iteration", 1, "attempt-token")
            assertEquals("packed", worker.commitResult(owner, IterationResult(listOf(ExecutionCaseResult(0, 0, 1, 2))), mapOf("0.out" to file, "0.err" to file)))
            assertEquals(5, calls.get())
        } finally { server.stop(0) }
    }

    @Test fun `small result files use one acceptance request including empty and duplicate blobs`() {
        val root = Files.createTempDirectory("worker-inline")
        val one = root.resolve("one").also { Files.writeString(it, "capture") }
        val empty = root.resolve("empty").also { Files.write(it, byteArrayOf()) }
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/worker/v1/sessions/session/commit-inline") { exchange ->
            try {
                requests.incrementAndGet()
                val body = Json.decodeFromString<WorkerInlineCommit>(String(exchange.requestBody.readAllBytes()))
                assertEquals(2, body.blobs.size)
                assertEquals(setOf("0.out", "0.err", "0.exit"), body.result.artifacts.keys)
                for ((hash, bytes) in body.decoded()) assertEquals(hash, java.security.MessageDigest.getInstance("SHA-256").digest(bytes).hex())
                exchange.sendResponseHeaders(204, -1)
            } finally { exchange.close() }
        }
        server.start()
        try {
            val identity = root.resolve("identity").also { Files.writeString(it, "fixture") }
            val worker = WorkerSession(URI.create("http://127.0.0.1:${server.address.port}"), "session", "token", "allocation", identity,
                "release", root, root.resolve("unused.sock"), "unused")
            val owner = AttemptOwner("session", "allocation", "iteration", 1, "attempt-token")
            assertEquals("inline", worker.commitResult(owner, IterationResult(listOf(ExecutionCaseResult(0, 0, 1, 2))), mapOf("0.out" to one, "0.err" to one, "0.exit" to empty)))
            assertEquals(1, requests.get())
        } finally { server.stop(0) }
    }

    @Test fun `successful chunk acknowledgements eliminate redundant progress requests`() {
        val root = Files.createTempDirectory("worker-ack")
        val file = root.resolve("capture").also { Files.write(it, ByteArray(9 * 1024 * 1024) { (it % 251).toByte() }) }
        val hash = hashFile(file); val artifacts = LocalArtifactStore(root.resolve("artifacts"))
        val gets = AtomicInteger(); val puts = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/worker/v1/sessions/session/uploads/$hash") { exchange ->
            try {
                val offset = if (exchange.requestMethod == "GET") { gets.incrementAndGet(); artifacts.offset(1, hash) } else {
                    puts.incrementAndGet()
                    val query = exchange.requestURI.rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
                    artifacts.put(1, hash, query.getValue("bytes").toLong(), query.getValue("offset").toLong(), exchange.requestBody)
                }
                val bytes = """{"offset":$offset}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
            } finally { exchange.close() }
        }
        server.start()
        try {
            val identity = root.resolve("identity").also { Files.writeString(it, "fixture") }
            val worker = WorkerSession(URI.create("http://127.0.0.1:${server.address.port}"), "session", "token", "allocation", identity,
                "release", root, root.resolve("unused.sock"), "unused")
            val trace = PreparationTrace()
            assertEquals(hash, worker.upload(file, AttemptOwner("session", "allocation", "iteration", 1, "attempt-token"), reusable = true, trace = trace))
            assertEquals(1, gets.get()); assertEquals(2, puts.get())
            assertEquals("miss", trace.snapshot().caches["upload"]?.outcome)
            assertEquals("absent", trace.snapshot().caches["upload"]?.reason)
            val warm = PreparationTrace()
            assertEquals(hash, worker.upload(file, AttemptOwner("session", "allocation", "iteration", 1, "attempt-token"), reusable = true, trace = warm))
            assertEquals(2, gets.get()); assertEquals(2, puts.get())
            assertEquals("coordinator-present", warm.snapshot().caches["upload"]?.reason)
            assertEquals(1, warm.snapshot().caches["upload"]?.hashedFiles)
            assertEquals(Files.size(file), warm.snapshot().caches["upload"]?.hashedBytes)
        } finally { server.stop(0) }
    }

    @Test fun `preparation upload evidence retains rejected offsets and retry responses without credentials`() {
        val root = Files.createTempDirectory("upload-evidence")
        val file = root.resolve("capture").also { Files.writeString(it, "prepared artifact") }
        val hash = hashFile(file)
        val puts = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/worker/v1/sessions/session/uploads/$hash") { exchange ->
            try {
                val isPut = exchange.requestMethod == "PUT"
                exchange.requestBody.readAllBytes()
                if (isPut && puts.incrementAndGet() == 1) exchange.sendResponseHeaders(503, -1)
                else {
                    val bytes = "{\"offset\":${if (isPut) Files.size(file) else 0}}".toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
                }
            } finally { exchange.close() }
        }
        server.start()
        try {
            val identity = root.resolve("identity").also { Files.writeString(it, "fixture-identity-secret") }
            val worker = WorkerSession(URI.create("http://127.0.0.1:${server.address.port}"), "session", "fixture-bearer-secret", "allocation", identity,
                "release", root, root.resolve("unused.sock"), "unused")
            val budget = ResourceBudget(1000, 512)
            val recorder = PreparationRecorder(ResourceObservation("advisory-native", budget, null, null, "test", "test", "test", emptyMap()), PreparationTrace())
            worker.preparationRecorder = recorder
            assertEquals(hash, worker.upload(file, AttemptOwner("session", "allocation", "preparation", 1, "fixture-attempt-secret")))
            val diagnostic = recorder.snapshot(1000, 2000)
            assertEquals(listOf(200, 503, 200, 200), diagnostic.uploads.map { it.status })
            assertEquals(listOf(null, 0L, null, 0L), diagnostic.uploads.map { it.offset })
            assertEquals(Files.size(file), diagnostic.uploads.last().acceptedOffset)
            val json = Json.encodeToString(PreparationDiagnostics.serializer(), diagnostic)
            assertFalse(json.contains("secret")); assertFalse(json.contains("Authorization")); assertFalse(json.contains("http://"))
            repeat(20) { recorder.upload(diagnostic.uploads.last().copy(offset = it.toLong())) }
            assertEquals((4L..19L).toList(), recorder.snapshot(1000, 2000).uploads.map { it.offset })
            assertFailsWith<IllegalArgumentException> { diagnostic.uploads.last().copy(method = "DELETE") }
            assertFailsWith<IllegalArgumentException> { diagnostic.uploads.last().copy(sha256 = "not-a-hash") }
        } finally { server.stop(0); deleteTree(root) }
    }

    @Test fun `upload resumes stored bytes after a nonfinal chunk acknowledgement is lost`() {
        val root = Files.createTempDirectory("worker-transfer")
        val file = root.resolve("capture")
        Files.write(file, ByteArray(9 * 1024 * 1024) { (it % 251).toByte() })
        val hash = hashFile(file)
        val artifacts = LocalArtifactStore(root.resolve("artifacts"))
        val dropped = AtomicBoolean()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/worker/v1/sessions/session/uploads/$hash") { exchange ->
            try {
                val offset = if (exchange.requestMethod == "GET") artifacts.offset(1, hash) else {
                    val query = exchange.requestURI.rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
                    val stored = artifacts.put(1, hash, query.getValue("bytes").toLong(), query.getValue("offset").toLong(), exchange.requestBody)
                    if (stored < Files.size(file) && dropped.compareAndSet(false, true)) return@createContext
                    stored
                }
                val bytes = """{"offset":$offset}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.write(bytes)
            } catch (_: IllegalArgumentException) { exchange.sendResponseHeaders(400, -1) }
            finally { exchange.close() }
        }
        server.start()
        try {
            val identity = root.resolve("identity"); Files.writeString(identity, "fixture-identity")
            val worker = WorkerSession(URI.create("http://127.0.0.1:${server.address.port}"), "session", "token", "allocation", identity,
                "release", root, root.resolve("unused.sock"), "unused")
            assertEquals(hash, worker.upload(file, AttemptOwner("session", "allocation", "iteration", 1, "attempt-token")))
            assertTrue(dropped.get())
            assertEquals(hash, hashFile(artifacts.file(1, hash)))
        } finally { server.stop(0) }
    }
    @Test fun `repeated preparation bundles skip all upload bytes across jobs and coordinator restarts`() {
        val root = Files.createTempDirectory("worker-reuse")
        val file = root.resolve("prepared.zip").also { Files.writeString(it, "prepared distribution") }
        val hash = hashFile(file); var job = 1L
        val puts = AtomicInteger(); val gets = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/worker/v1/sessions/session/uploads/$hash") { exchange ->
            try {
                val store = LocalArtifactStore(root.resolve("artifacts")) // Fresh process state for every request.
                val query = exchange.requestURI.rawQuery.orEmpty().split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
                assertEquals("true", query["reuse"])
                val offset = if (exchange.requestMethod == "GET") {
                    gets.incrementAndGet(); store.reuse(job, hash) ?: store.offset(job, hash)
                } else {
                    puts.incrementAndGet()
                    store.put(job, hash, query.getValue("bytes").toLong(), query.getValue("offset").toLong(), exchange.requestBody)
                        .also { store.remember(job, hash) }
                }
                val reply = """{"offset":$offset}""".toByteArray()
                exchange.sendResponseHeaders(200, reply.size.toLong()); exchange.responseBody.write(reply)
            } finally { exchange.close() }
        }
        server.start()
        try {
            val identity = root.resolve("identity").also { Files.writeString(it, "fixture") }
            val worker = WorkerSession(URI.create("http://127.0.0.1:${server.address.port}"), "session", "token", "allocation", identity,
                "release", root, root.resolve("unused.sock"), "unused")
            val owner = AttemptOwner("session", "allocation", "iteration", 1, "attempt-token")
            assertEquals(hash, worker.upload(file, owner, reusable = true))
            job = 2
            assertEquals(hash, worker.upload(file, owner, reusable = true))
            assertEquals(2, gets.get()); assertEquals(1, puts.get())
            assertEquals(hash, hashFile(LocalArtifactStore(root.resolve("artifacts")).file(2, hash)))
        } finally { server.stop(0); deleteTree(root) }
    }

}
