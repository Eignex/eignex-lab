package com.eignex.lab.web

import com.eignex.lab.execution.*
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Flow

/** Stored-byte metadata, shared by every collection; paths and hashes retain compression suffixes. */
@Serializable
internal data class CorpusIndex(val collection: String, val files: List<Entry>, val version: Int = 1) {
    @Serializable
    data class Entry(val path: String, val sha256: String, val bytes: Long)

    fun validate(expected: String) {
        require(version == 1 && collection == expected) { "unsupported or mismatched corpus index" }
        require(files.map { it.path }.toSet().size == files.size) { "duplicate corpus index path" }
        for (file in files) {
            require(file.sha256.matches(INDEX_SHA256) && file.bytes >= 0) { "invalid corpus index checksum or size" }
            require(file.path.isNotEmpty() && file.path.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
                file.path.none { it == '\\' || it.isISOControl() }) { "unsafe corpus index path" }
        }
    }
}

/** Reads only an existing index. Never walks, downloads, decompresses or generates a corpus. */
internal class CorpusIndexes(
    private val root: Path,
    private val environment: Map<String, String> = System.getenv(),
    private val sign: (URI, InputSignature) -> SignedInput = ::signInput,
    private val download: (SignedInput) -> ByteArray? = ::downloadIndex,
) {
    fun get(collection: String): CorpusIndex? {
        require(collection.length <= 200 && collection.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) { "invalid collection id" }
        try {
            val shared = environment["LAB_SHARED_INPUT_URL"]?.takeIf { it.isNotBlank() }
            val mirror = environment["LAB_CORPUS_MIRROR"]?.takeIf { it.isNotBlank() }
                ?: shared?.let { it.trimEnd('/') + "/corpus-packs" }
            val bytes = if (mirror != null) {
                val base = URI(mirror)
                check(base.scheme == "https" && base.userInfo == null && base.rawQuery == null && base.rawFragment == null)
                val key = "corpus-packs/$collection.index.tar"
                val address = environment["LAB_SHARED_INPUT_SIGNER"]?.takeIf { it.isNotBlank() }?.let {
                    sign(URI(it), InputSignature("GET", key))
                } ?: SignedInput(mirror.trimEnd('/') + "/$collection.index.tar")
                val archive = download(address) ?: return null
                TarArchiveInputStream(ByteArrayInputStream(archive)).use { tar ->
                    val entry = checkNotNull(tar.nextEntry) { "empty corpus index archive" }
                    check(entry.isFile && entry.name == "index.json" && entry.size in 0..MAX_INDEX_BYTES.toLong())
                    val content = tar.readNBytes(entry.size.toInt())
                    check(content.size.toLong() == entry.size && tar.nextEntry == null) { "invalid corpus index archive" }
                    content
                }
            } else {
                // Require a readable root before treating a missing sidecar as an unpublished index.
                val local = environment["LAB_SHARED_INPUT_ROOT"]?.let(Path::of) ?: root
                Files.newDirectoryStream(local).use { }
                val path = local.resolve(".index/$collection.json")
                try {
                    check(path.toRealPath().startsWith(local.toRealPath())) { "corpus index escapes storage root" }
                    Files.newInputStream(path).use { it.readNBytes(MAX_INDEX_BYTES + 1) }
                } catch (_: NoSuchFileException) { return null }
            }
            check(bytes.size <= MAX_INDEX_BYTES) { "corpus index exceeds metadata limit" }
            return indexJson.decodeFromString<CorpusIndex>(bytes.decodeToString(throwOnInvalidSequence = true)).also { it.validate(collection) }
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ApiError.Unavailable("corpus index backend unavailable; retry later")
        } catch (_: Exception) {
            // Signed URLs, signer addresses and local paths must not leak through backend diagnostics.
            throw ApiError.Unavailable("corpus index backend unavailable or invalid; check storage and signing configuration")
        }
    }
}

internal fun Route.corpusRoutes(indexes: CorpusIndexes) {
    get("/corpus/indexes/{collection}") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        val collection = checkNotNull(call.parameters["collection"])
        val text = io {
            val index = indexes.get(collection) ?: throw ApiError.NotFound("no published corpus index for $collection")
            indexJson.encodeToString(CorpusIndex.serializer(), index)
        }
        call.respondText(text, ContentType.Application.Json)
    }
}

private val indexJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val INDEX_SHA256 = Regex("[a-f0-9]{64}")
private const val MAX_INDEX_BYTES = 256 * 1024 * 1024
private val indexClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build() }

/** A complete, bounded metadata response: the request deadline covers the body as well as headers. */
private fun downloadIndex(address: SignedInput): ByteArray? {
    val uri = URI(address.url)
    check(uri.scheme == "https" && uri.userInfo == null)
    val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().also { builder ->
        address.headers.forEach { (name, value) -> builder.header(name, value) }
    }.build()
    val response = indexClient.send(request, HttpResponse.BodyHandler { info ->
        val body = HttpResponse.BodySubscribers.ofByteArray()
        object : HttpResponse.BodySubscriber<ByteArray> {
            private lateinit var subscription: Flow.Subscription
            private var received = 0L
            override fun getBody() = body.body
            override fun onSubscribe(value: Flow.Subscription) { subscription = value; body.onSubscribe(value) }
            override fun onNext(items: List<ByteBuffer>) {
                received += items.sumOf { it.remaining().toLong() }
                if (received > MAX_INDEX_BYTES + 4096L || info.statusCode() !in setOf(200, 404)) {
                    subscription.cancel()
                    body.onError(IOException("corpus index download refused"))
                } else body.onNext(items)
            }
            override fun onError(error: Throwable) = body.onError(error)
            override fun onComplete() = body.onComplete()
        }
    })
    if (response.statusCode() == 404) return null
    check(response.statusCode() == 200) { "corpus index download unavailable" }
    return response.body()
}
