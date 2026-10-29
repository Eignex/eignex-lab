package com.eignex.lab

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/** An attempt-scoped loopback capability broker; consumer processes never receive worker credentials. */
internal class InputSigningRelay(private val sign: (InputSignature) -> SignedInput) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "input-signing").apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 4).apply {
        this.executor = this@InputSigningRelay.executor
        createContext("/") { exchange ->
            exchange.use {
                // A malformed request is the consumer's fault (400); only a failure to sign it is an outage (503).
                val signature = try {
                    require(it.requestMethod == "POST" && it.requestURI.path == "/")
                    val body = it.requestBody.readNBytes(8193); require(body.size <= 8192)
                    Json.decodeFromString<InputSignature>(String(body))
                } catch (_: IllegalArgumentException) {
                    it.sendResponseHeaders(400, -1)
                    return@use
                }
                try {
                    val result = Json.encodeToString(SignedInput.serializer(), sign(signature)).toByteArray()
                    it.responseHeaders.add("Content-Type", "application/json")
                    it.sendResponseHeaders(200, result.size.toLong()); it.responseBody.write(result)
                } catch (_: Exception) {
                    it.sendResponseHeaders(503, -1)
                }
            }
        }
        start()
    }
    val address: String get() = "http://127.0.0.1:${server.address.port}/"
    override fun close() { server.stop(0); executor.shutdownNow() }
}
