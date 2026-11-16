package com.eignex.lab.worker

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/** A loopback Gradle HTTP build cache for this worker's builds, forwarding to the coordinator over the worker's own
 * authenticated session ([forward]). Gradle cannot present the worker's credentials itself; nothing else can reach it.
 * Any coordinator failure reads as a miss or a refused store, which Gradle treats as a cold, not a failed, build. */
internal class GradleCacheProxy(private val forward: (method: String, key: String, body: ByteArray?) -> Pair<Int, ByteArray>,
                                private val maxEntryBytes: Int = 128 * 1024 * 1024) : AutoCloseable {
    private val executor = Executors.newFixedThreadPool(4) { Thread(it, "worker-gradle-cache").apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 32).apply {
        createContext("/cache/") { exchange -> exchange.use { handle(it) } }
        executor = this@GradleCacheProxy.executor
        start()
    }
    val url: String get() = "http://127.0.0.1:${server.address.port}/cache/"

    private fun handle(exchange: HttpExchange) {
        val key = exchange.requestURI.path.removePrefix("/cache/")
        if (!KEY.matches(key)) return exchange.sendResponseHeaders(400, -1)
        when (exchange.requestMethod) {
            "GET" -> {
                val (status, body) = runCatching { forward("GET", key, null) }.getOrDefault(404 to ByteArray(0))
                if (status != 200) return exchange.sendResponseHeaders(404, -1)
                exchange.sendResponseHeaders(200, body.size.toLong().takeIf { it > 0 } ?: -1)
                exchange.responseBody.write(body)
            }
            "PUT" -> {
                val body = exchange.requestBody.readNBytes(maxEntryBytes + 1)
                if (body.size > maxEntryBytes) return exchange.sendResponseHeaders(413, -1)
                val status = runCatching { forward("PUT", key, body).first }.getOrDefault(503)
                exchange.sendResponseHeaders(if (status in 200..299) 201 else if (status == 413) 413 else 503, -1)
            }
            else -> exchange.sendResponseHeaders(405, -1)
        }
    }

    override fun close() { server.stop(0); executor.shutdownNow() }

    private companion object { val KEY = Regex("[0-9a-f]{32,128}") }
}
