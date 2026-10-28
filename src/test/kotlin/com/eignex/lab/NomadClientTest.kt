package com.eignex.lab

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import kotlin.test.*

class NomadClientTest {
    @Test fun `client GC handles already removed allocations without hiding other server failures`() {
        val root = Files.createTempDirectory("nomad-client")
        val token = root.resolve("token").also { Files.writeString(it, "fixture-token") }
        var status = 500
        var body = "No such allocation on client, or allocation not eligible for GC"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { request ->
            assertEquals("GET", request.requestMethod)
            assertEquals("/v1/client/allocation/fixture/gc", request.requestURI.path)
            val bytes = body.toByteArray()
            request.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            request.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
            request.close()
        }
        server.start()
        try {
            val client = NomadClient(URI("http://127.0.0.1:${server.address.port}"), token, HttpClient.newHttpClient())
            assertFalse(client.collectAllocation("fixture"))
            body = "client RPC failed"
            assertFailsWith<IllegalStateException> { client.collectAllocation("fixture") }
            status = 200; body = ""
            assertTrue(client.collectAllocation("fixture"))
        } finally {
            server.stop(0)
        }
    }
}
