package com.eignex.lab

import com.eignex.lab.domain.*
import com.eignex.lab.util.*
import com.eignex.lab.worker.*
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import java.util.Base64
import kotlin.test.*

class WorkerDiagnosticsTest {
    @Test fun `diagnostic snapshots bound bytes and time while excluding worktrees other cases and links`() {
        val root = Files.createTempDirectory("deadline-diagnostics")
        try {
            Files.createDirectories(root.resolve("cases/0/out"))
            Files.writeString(root.resolve("cases/0/out/result.json"), "{\"status\":\"partial\"}")
            Files.writeString(root.resolve("0.termination"), "whole-run deadline")
            Files.write(root.resolve("cases/0/out/witness"), ByteArray(WorkerDiagnostics.FILE_BYTES + 17) { 42 })
            Files.createDirectories(root.resolve("worktrees"))
            Files.writeString(root.resolve("worktrees/credentials"), "private")
            Files.createSymbolicLink(root.resolve("cases/0/link"), root.resolve("worktrees/credentials"))
            Files.createDirectories(root.resolve("cases/1"))
            Files.writeString(root.resolve("cases/1/result.json"), "another row")
            val body = WorkerDiagnostics.collect(root, listOf(0)) { 5000 }
            assertEquals(setOf("0.termination", "cases/0/out/result.json", "cases/0/out/witness"), body.files.keys)
            val witness = body.files.getValue("cases/0/out/witness")
            assertEquals(WorkerDiagnostics.FILE_BYTES + 17L, witness.originalBytes)
            assertEquals(WorkerDiagnostics.FILE_BYTES, Base64.getDecoder().decode(witness.base64).size)
            assertTrue(body.limited)
            val expired = WorkerDiagnostics.collect(root, listOf(0)) { 0 }
            assertTrue(expired.files.isEmpty()); assertTrue(expired.limited)
        } finally { deleteTree(root) }
    }

    @Test fun `the aggregate snapshot stops at its byte and file ceilings`() {
        val root = Files.createTempDirectory("deadline-limits")
        try {
            val output = Files.createDirectories(root.resolve("cases/0"))
            repeat(17) { Files.write(output.resolve("large-$it"), ByteArray(WorkerDiagnostics.FILE_BYTES)) }
            val bytes = WorkerDiagnostics.collect(root, listOf(0)) { 5000 }
            assertEquals(WorkerDiagnostics.TOTAL_BYTES, bytes.files.values.sumOf { Base64.getDecoder().decode(it.base64).size })
            assertTrue(bytes.limited)
            deleteTree(output); Files.createDirectory(output)
            repeat(WorkerDiagnostics.MAX_FILES + 1) { Files.writeString(output.resolve("small-$it"), "x") }
            val files = WorkerDiagnostics.collect(root, listOf(0)) { 5000 }
            assertEquals(WorkerDiagnostics.MAX_FILES, files.files.size); assertTrue(files.limited)
        } finally { deleteTree(root) }
    }

    @Test fun `only diagnostic requests bypass expired worker allowances`() {
        val root = Files.createTempDirectory("deadline-client")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var received = false
        val owner = AttemptOwner("session", "allocation", "0:none:0", 1, "attempt-secret")
        server.createContext("/worker/v1/sessions/session/deadline-diagnostics") { exchange ->
            val body = Json.decodeFromString<WorkerDiagnosticFiles>(String(exchange.requestBody.readAllBytes()))
            received = body.files.isEmpty() && exchange.requestHeaders.getFirst("X-Lab-Attempt") == Json.encodeToString(AttemptOwner.serializer(), owner)
            exchange.sendResponseHeaders(204, -1); exchange.close()
        }
        server.start()
        try {
            val identity = root.resolve("identity").also { Files.writeString(it, "identity") }
            val coordinator = CoordinatorClient(URI("http://127.0.0.1:${server.address.port}"), "session", "token", "allocation",
                identity, HttpClient.newHttpClient(), { -1 }, { -1 }, { throw RunDeadlineExceeded() }, { null })
            assertFailsWith<RunDeadlineExceeded> { coordinator.json("commit", "{}") }
            assertEquals(204, coordinator.json("deadline-diagnostics", """{"files":{},"limited":false}""", owner).statusCode())
            assertTrue(received)
        } finally { server.stop(0); deleteTree(root) }
    }
}
