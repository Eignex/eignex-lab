package com.eignex.lab

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

/** Immutable input bytes live in a regional store; jobs carry only paths and checksums. */
@Serializable
data class SharedInput(val file: InputFile, val key: String) {
    init { safeRelative(key); require(!file.executable) }
}

internal interface InputTransport {
    fun matches(key: String, file: InputFile, md5: String): Boolean
    fun put(key: String, source: Path, sha: String)
    fun get(key: String, destination: Path)
}

internal class SharedInputs(val root: Path, private val transport: InputTransport, private val existingPrefix: String = "corpus") {
    fun publish(paths: List<String>, checkActive: () -> Unit = {}): List<SharedInput> {
        val canonical = root.toRealPath()
        val files = paths.flatMap { relative ->
            val path = root.resolve(safeRelative(relative))
            require(path.toRealPath().startsWith(canonical)) { "input escapes shared root" }
            if (Files.isDirectory(path, NOFOLLOW_LINKS)) Files.walk(path).use { it.filter { p -> !Files.isDirectory(p, NOFOLLOW_LINKS) }.toList() }
            else listOf(path)
        }.distinct().sorted()
        return files.map { path ->
            checkActive()
            require(Files.isRegularFile(path, NOFOLLOW_LINKS) && path.toRealPath().startsWith(canonical))
            val file = InputFile(root.relativize(path).toString().replace('\\', '/'), hashFile(path), Files.size(path))
            val md5 = Files.newInputStream(path).use { stream ->
                val digest = MessageDigest.getInstance("MD5"); val buffer = ByteArray(64 * 1024)
                while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                digest.digest().hex()
            }
            val original = "$existingPrefix/${file.path}"
            val key = if (transport.matches(original, file, md5)) original else "input-blobs/${file.sha256}"
            if (key != original && !transport.matches(key, file, md5)) transport.put(key, path, file.sha256)
            SharedInput(file, key)
        }
    }

    fun resolve(inputs: List<SharedInput>, checkActive: () -> Unit = {}): Path {
        require(inputs.isNotEmpty() && inputs.map { it.file.path }.distinct().size == inputs.size)
        Files.createDirectories(root)
        val canonical = root.toRealPath()
        val inventory = inputs.sortedBy { it.file.path }
        val key = ExecutionStore.digest(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(SharedInput.serializer()), inventory))
        val view = root.resolve(".lab-inputs/views/$key")
        Files.createDirectories(view)
        for (input in inputs) {
            checkActive()
            val file = input.file; val target = view.resolve(safeRelative(file.path))
            synchronized(fileLockFor(target)) {
                Files.createDirectories(target.parent)
                require(target.parent.toRealPath().startsWith(canonical) && !Files.isSymbolicLink(target))
                if (valid(target, file)) continue
                val existing = root.resolve(safeRelative(file.path))
                val source = if (existing.toRealPathOrNull()?.startsWith(canonical) == true && valid(existing, file)) existing else {
                    val blob = root.resolve(".lab-inputs/blobs/${file.sha256}")
                    Files.createDirectories(blob.parent)
                    synchronized(fileLockFor(blob)) {
                        if (!valid(blob, file)) {
                            val staging = Files.createTempFile(blob.parent, ".input-", ".partial")
                            try {
                                transport.get(input.key, staging)
                                check(valid(staging, file)) { "shared input checksum mismatch" }
                                checkActive()
                                Files.move(staging, blob, ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            } finally { Files.deleteIfExists(staging) }
                        }
                    }
                    blob
                }
                Files.deleteIfExists(target)
                Files.createLink(target, source)
            }
        }
        return view
    }

    private fun Path.toRealPathOrNull(): Path? = runCatching { toRealPath() }.getOrNull()

    private fun valid(path: Path, file: InputFile) = Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) == file.bytes && hashFile(path) == file.sha256

    companion object {
        fun environment(environment: Map<String, String> = System.getenv()): SharedInputs? {
            val root = environment["LAB_SHARED_INPUT_ROOT"] ?: return null
            val base = requireNotNull(environment["LAB_SHARED_INPUT_URL"])
            return SharedInputs(Path.of(root), HttpInputTransport(URI(base), environment["LAB_SHARED_INPUT_SIGNER"]?.let(URI::create)))
        }
    }
}

@Serializable private data class InputSignature(val method: String, val key: String, val sha256: String? = null)
@Serializable private data class SignedInput(val url: String, val headers: Map<String, String> = emptyMap())

internal class HttpInputTransport(private val base: URI, private val signer: URI? = null) : InputTransport {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    init {
        require(base.scheme == "https" && base.userInfo == null && base.rawQuery == null)
        require(signer == null || signer.scheme == "http" && signer.host in setOf("127.0.0.1", "localhost", "::1"))
    }
    private fun request(method: String, key: String, sha: String? = null): HttpRequest.Builder {
        safeRelative(key)
        val signed = if (signer != null) {
            val request = HttpRequest.newBuilder(signer).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.encodeToString(InputSignature.serializer(), InputSignature(method, key, sha)))).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) { "input signing unavailable" }
            Json.decodeFromString<SignedInput>(response.body())
        } else SignedInput(base.toString().trimEnd('/') + "/" + key.split('/').joinToString("/") { java.net.URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") })
        val uri = URI(signed.url); require(uri.scheme == "https" && uri.userInfo == null)
        return HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10)).also { builder -> signed.headers.forEach { (key, value) -> builder.header(key, value) } }
    }
    override fun matches(key: String, file: InputFile, md5: String): Boolean {
        val response = client.send(request("HEAD", key).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() == 404) return false
        check(response.statusCode() == 200) { "shared input metadata unavailable (${response.statusCode()})" }
        val length = response.headers().firstValue("Content-Length").orElse("").toLongOrNull()
        val checksum = response.headers().firstValue("x-amz-meta-sha256").orElse("")
        val etag = response.headers().firstValue("ETag").orElse("").trim('"')
        return length == file.bytes && (checksum == file.sha256 || etag == md5)
    }
    override fun put(key: String, source: Path, sha: String) {
        val digest = sha.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val request = request("PUT", key, sha).header("If-None-Match", "*").header("x-amz-meta-sha256", sha)
            .header("x-amz-checksum-sha256", Base64.getEncoder().encodeToString(digest)).PUT(HttpRequest.BodyPublishers.ofFile(source)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        check(response.statusCode() in setOf(200, 201, 204, 412)) { "shared input publication unavailable (${response.statusCode()})" }
    }
    override fun get(key: String, destination: Path) {
        val response = client.send(request("GET", key).GET().build(), HttpResponse.BodyHandlers.ofFile(destination))
        check(response.statusCode() == 200) { "shared input download unavailable (${response.statusCode()})" }
    }
}
