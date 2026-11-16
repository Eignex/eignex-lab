package com.eignex.lab

import com.eignex.lab.store.*
import com.eignex.lab.worker.*

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import kotlin.test.*

class GradleBuildCacheTest {
    private val root = Files.createTempDirectory("gradle-cache")
    private val key = "0123456789abcdef0123456789abcdef"
    private val other = "fedcba9876543210fedcba9876543210"

    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    @Test fun `entries are stored per partition and never cross repositories or platforms`() {
        val cache = GradleBuildCache(root, 1_000_000)
        val klause = cache.partition("Eignex/klause", "linux", "amd64", "jdk25")
        assertNotEquals(klause, cache.partition("Eignex/koblas", "linux", "amd64", "jdk25"))
        assertNotEquals(klause, cache.partition("Eignex/klause", "linux", "arm64", "jdk25"))
        assertNull(cache.get(klause, key))
        assertTrue(cache.put(klause, key, "outputs".byteInputStream()))
        assertEquals("outputs", Files.readString(cache.get(klause, key)!!))
        assertNull(cache.get(cache.partition("Eignex/koblas", "linux", "amd64", "jdk25"), key))
    }

    @Test fun `oversized entries are refused and leave nothing behind`() {
        val cache = GradleBuildCache(root, 1_000_000)
        val part = cache.partition("r", "linux", "amd64", "t")
        assertFalse(cache.put(part, key, ByteArray(100).inputStream(), limit = 10))
        assertNull(cache.get(part, key))
        assertEquals(0, Files.list(root.resolve(part)).use { it.count() })
    }

    @Test fun `writes beyond the cap evict the least recently used entries`() {
        var at = 1_000L
        val cache = GradleBuildCache(root, 250, clock = { at })
        val part = cache.partition("r", "linux", "amd64", "t")
        assertTrue(cache.put(part, key, ByteArray(100).inputStream()))
        Files.setLastModifiedTime(root.resolve(part).resolve(key), java.nio.file.attribute.FileTime.fromMillis(1_000))
        assertTrue(cache.put(part, other, ByteArray(100).inputStream()))
        at = 5_000; assertNotNull(cache.get(part, other)) // other is now the more recently used entry
        assertTrue(cache.put(part, "aa".repeat(16), ByteArray(100).inputStream()))
        assertNull(cache.get(part, key), "the oldest entry made room")
        assertNotNull(cache.get(part, other))
        assertTrue(cache.usage().get() <= 250)
    }

    @Test fun `keys and partitions outside Gradle's form are rejected`() {
        val cache = GradleBuildCache(root, 1_000)
        assertFailsWith<IllegalArgumentException> { cache.get(cache.partition("r", "o", "a", "t"), "../x") }
        assertFailsWith<IllegalArgumentException> { cache.get("../../etc", key) }
    }
}

class GradleCacheProxyTest {
    private val http = HttpClient.newHttpClient()
    private val key = "0123456789abcdef0123456789abcdef"

    private fun call(proxy: GradleCacheProxy, method: String, path: String, body: ByteArray? = null): HttpResponse<ByteArray> =
        http.send(HttpRequest.newBuilder(URI.create(proxy.url + path))
            .method(method, body?.let(HttpRequest.BodyPublishers::ofByteArray) ?: HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofByteArray())

    @Test fun `hits, misses and stores forward through the worker session`() {
        val stored = mutableMapOf<String, ByteArray>()
        GradleCacheProxy({ method, key, body ->
            when (method) {
                "GET" -> stored[key]?.let { 200 to it } ?: error("worker API HTTP 404")
                else -> { stored[key] = body!!; 201 to ByteArray(0) }
            }
        }).use { proxy ->
            assertEquals(404, call(proxy, "GET", key).statusCode())
            assertEquals(201, call(proxy, "PUT", key, "outputs".toByteArray()).statusCode())
            val hit = call(proxy, "GET", key)
            assertEquals(200, hit.statusCode()); assertEquals("outputs", String(hit.body()))
        }
    }

    @Test fun `bad keys, oversized stores and coordinator failures never reach or fail the build`() {
        GradleCacheProxy({ _, _, _ -> throw java.io.IOException("coordinator unreachable") }, maxEntryBytes = 4).use { proxy ->
            assertEquals(400, call(proxy, "GET", "not-a-key").statusCode())
            assertEquals(413, call(proxy, "PUT", key, ByteArray(5)).statusCode())
            assertEquals(503, call(proxy, "PUT", key, ByteArray(3)).statusCode())
            assertEquals(404, call(proxy, "GET", key).statusCode())
        }
    }
}
