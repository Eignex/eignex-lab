package com.eignex.lab

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

class WorkerTransferTest {
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
}
