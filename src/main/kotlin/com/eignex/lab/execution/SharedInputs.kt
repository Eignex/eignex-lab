package com.eignex.lab.execution

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.channels.FileChannel
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

internal interface InputTransport {
    fun matches(key: String, file: InputFile, md5: String): Boolean
    fun put(key: String, source: Path, sha: String)
    fun get(key: String, destination: Path)
}

/** [identity] names the remote store (its URL): a cached selection's inputs are reused only against the store that holds them. */
internal class SharedInputs(val root: Path, private val transport: InputTransport, private val existingPrefix: String = "corpus",
                           private val maxBytes: Long = 8L * 1024 * 1024 * 1024, val identity: String = "") {
    fun publish(paths: List<String>, checkActive: () -> Unit = {}): List<SharedInput> {
        val canonical = root.toRealPath()
        val files = paths.flatMap { relative ->
            val path = root.resolve(safeRelative(relative))
            require(path.toRealPath().startsWith(canonical)) { "input escapes shared root" }
            if (Files.isDirectory(path, NOFOLLOW_LINKS)) Files.walk(path).use { it.filter { p ->
                !Files.isDirectory(p, NOFOLLOW_LINKS) && root.relativize(p).first().toString() != ".lab-inputs"
            }.toList() }
            else listOf(path)
        }.distinct().sorted()
        return files.map { path ->
            checkActive()
            require(Files.isRegularFile(path, NOFOLLOW_LINKS) && path.toRealPath().startsWith(canonical))
            val file = InputFile(root.relativize(path).toString().replace('\\', '/'), hashFile(path), Files.size(path))
            val packed = path.resolveSibling(path.fileName.toString() + ".zst")
            val source = if (Files.isRegularFile(packed, NOFOLLOW_LINKS) && matchesPacked(packed, file)) packed else path
            val stored = if (source == path) file else file.copy(sha256 = hashFile(source), bytes = Files.size(source))
            val md5 = Files.newInputStream(source).use { stream ->
                val digest = MessageDigest.getInstance("MD5"); val buffer = ByteArray(64 * 1024)
                while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                digest.digest().hex()
            }
            val original = "$existingPrefix/${file.path}" + if (source == path) "" else ".zst"
            val key = if (transport.matches(original, stored, md5)) original else "input-blobs/${stored.sha256}"
            if (key != original && !transport.matches(key, stored, md5)) transport.put(key, source, stored.sha256)
            SharedInput(file, key, if (source == path) "identity" else "zstd", stored.sha256, stored.bytes)
        }
    }

    fun view(inputs: List<SharedInput>): Path {
        require(inputs.isNotEmpty() && inputs.map { it.file.path }.distinct().size == inputs.size)
        Files.createDirectories(root)
        val inventory = inputs.sortedBy { it.file.path }
        val key = ExecutionStore.digest(Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(SharedInput.serializer()), inventory))
        return Files.createDirectories(root.resolve(".lab-inputs/views/$key"))
    }

    fun acquire(inputs: List<SharedInput>, checkActive: () -> Unit = {}, required: List<String>? = null): SharedInputLease {
        val pin = SharedInputCache(root.resolve(".lab-inputs"), maxBytes).pin(view(inputs))
        try { return SharedInputLease(resolve(inputs, checkActive, required), pin) }
        catch (failure: Throwable) { pin.close(); throw failure }
    }

    fun resolve(inputs: List<SharedInput>, checkActive: () -> Unit = {}, required: List<String>? = null): Path {
        val view = view(inputs)
        val canonical = root.toRealPath()
        for (input in inputs.filter { candidate -> required == null || required.any {
            it.isEmpty() || candidate.file.path == it || candidate.file.path.startsWith(it.trimEnd('/') + "/")
        } }) {
            checkActive()
            val file = input.file; val target = view.resolve(safeRelative(file.path))
            synchronized(sharedInputMutex) {
                Files.createDirectories(target.parent)
                require(target.parent.toRealPath().startsWith(canonical) && !Files.isSymbolicLink(target))
                if (valid(target, file)) continue
                val existing = root.resolve(safeRelative(file.path))
                fun link(source: Path) {
                    if (!valid(target, file)) {
                        Files.deleteIfExists(target)
                        try { Files.createLink(target, source) }
                        catch (_: java.nio.file.FileAlreadyExistsException) { check(valid(target, file)) }
                    }
                    val localPacked = existing.resolveSibling(existing.fileName.toString() + ".zst")
                    if (input.encoding == "zstd" && !Files.exists(existing, NOFOLLOW_LINKS) &&
                        localPacked.toRealPathOrNull()?.startsWith(canonical) == true &&
                        valid(localPacked, file.copy(sha256 = input.storedSha256, bytes = input.storedBytes))) {
                        try { Files.createLink(existing, source) } catch (_: java.nio.file.FileAlreadyExistsException) { }
                    }
                }
                if (existing.toRealPathOrNull()?.startsWith(canonical) == true && valid(existing, file)) link(existing) else {
                    val blob = root.resolve(".lab-inputs/blobs/${file.sha256}")
                    Files.createDirectories(blob.parent)
                    require(blob.parent.toRealPath().startsWith(canonical))
                    FileChannel.open(blob.resolveSibling("${file.sha256}.lock"), CREATE, WRITE).use { channel -> channel.lock().use {
                        if (!valid(blob, file)) {
                            val staging = Files.createTempFile(blob.parent, ".${file.sha256}-input-", ".partial")
                            try {
                                fetch(input, staging)
                                check(valid(staging, file)) { "shared input checksum mismatch" }
                                checkActive()
                                Files.move(staging, blob, ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            } finally { Files.deleteIfExists(staging) }
                        }
                        link(blob)
                        Files.setLastModifiedTime(blob, java.nio.file.attribute.FileTime.fromMillis(now()))
                    } }
                }
            }
        }
        return view
    }

    private fun Path.toRealPathOrNull(): Path? = runCatching { toRealPath() }.getOrNull()

    private fun matchesPacked(path: Path, file: InputFile): Boolean = runCatching {
        ZstdInputStreamNoFinalizer(Files.newInputStream(path)).use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024); var bytes = 0L
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                bytes += count; require(bytes <= file.bytes); digest.update(buffer, 0, count)
            }
            bytes == file.bytes && digest.digest().hex() == file.sha256
        }
    }.getOrDefault(false)

    private fun fetch(input: SharedInput, destination: Path) {
        val packed = Files.createTempFile(destination.parent, ".${input.file.sha256}-transfer-", ".partial")
        try {
            val local = root.resolve(safeRelative(input.file.path + ".zst"))
            val source = if (input.encoding == "zstd" && local.toRealPathOrNull()?.startsWith(root.toRealPath()) == true &&
                valid(local, input.file.copy(sha256 = input.storedSha256, bytes = input.storedBytes))) local else {
                transport.get(input.key, packed)
                packed
            }
            check(Files.size(source) == input.storedBytes && hashFile(source) == input.storedSha256) { "stored input checksum mismatch" }
            if (input.encoding == "identity") Files.move(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            else ZstdInputStreamNoFinalizer(Files.newInputStream(source)).use { stream ->
                Files.newOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024); var bytes = 0L
                    while (true) {
                        val count = stream.read(buffer); if (count < 0) break
                        bytes += count; check(bytes <= input.file.bytes); output.write(buffer, 0, count)
                    }
                }
            }
        } finally { Files.deleteIfExists(packed) }
    }

    private fun valid(path: Path, file: InputFile) = Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) == file.bytes && hashFile(path) == file.sha256

    companion object {
        fun environment(environment: Map<String, String> = System.getenv(), publishSignature: ((InputSignature) -> SignedInput)? = null): SharedInputs? {
            val root = environment["LAB_SHARED_INPUT_ROOT"] ?: return null
            val base = requireNotNull(environment["LAB_SHARED_INPUT_URL"])
            return SharedInputs(Path.of(root), HttpInputTransport(URI(base), environment["LAB_SHARED_INPUT_SIGNER"]?.let(URI::create), publishSignature),
                maxBytes = environment["LAB_SHARED_INPUT_MAX_BYTES"]?.toLong() ?: 8L * 1024 * 1024 * 1024, identity = base)
        }
    }
}

@Serializable internal data class InputSignature(val method: String, val key: String, val sha256: String? = null)
@Serializable internal data class SignedInput(val url: String, val headers: Map<String, String> = emptyMap())

private val inputSigningClient: HttpClient by lazy {
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
}
private val sharedInputMutex = Any()

internal fun signInput(signer: URI, signature: InputSignature): SignedInput {
    require(signer.scheme == "http" && signer.host in setOf("127.0.0.1", "localhost", "::1") && signer.userInfo == null)
    val request = HttpRequest.newBuilder(signer).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(Json.encodeToString(InputSignature.serializer(), signature))).build()
    val response = inputSigningClient.send(request, HttpResponse.BodyHandlers.ofString())
    check(response.statusCode() == 200) { "input signing unavailable" }
    return Json.decodeFromString<SignedInput>(response.body())
}

internal class HttpInputTransport(private val base: URI, private val signer: URI? = null,
                                 private val publishSignature: ((InputSignature) -> SignedInput)? = null) : InputTransport {
    private val client: HttpClient get() = inputSigningClient
    init {
        require(base.scheme == "https" && base.userInfo == null && base.rawQuery == null)
        require(signer == null || signer.scheme == "http" && signer.host in setOf("127.0.0.1", "localhost", "::1"))
    }
    private fun request(method: String, key: String, sha: String? = null): HttpRequest.Builder {
        safeRelative(key)
        val signature = InputSignature(method, key, sha)
        val signed = if (method == "PUT" && publishSignature != null) publishSignature.invoke(signature) else if (signer != null) {
            signInput(signer, signature)
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
    /** The size of the object at [key] when it was published with content hash [sha], else null. */
    fun stored(key: String, sha: String): Long? {
        val response = client.send(request("HEAD", key).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() == 404) return null
        check(response.statusCode() == 200) { "shared input metadata unavailable (${response.statusCode()})" }
        if (response.headers().firstValue("x-amz-meta-sha256").orElse("") != sha) return null
        return response.headers().firstValue("Content-Length").orElse("").toLongOrNull()
    }
    override fun put(key: String, source: Path, sha: String) {
        val digest = sha.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val request = request("PUT", key, sha).setHeader("If-None-Match", "*").setHeader("x-amz-meta-sha256", sha)
            .setHeader("x-amz-checksum-sha256", Base64.getEncoder().encodeToString(digest)).PUT(HttpRequest.BodyPublishers.ofFile(source)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        check(response.statusCode() in setOf(200, 201, 204, 412)) { "shared input publication unavailable (${response.statusCode()})" }
    }
    override fun get(key: String, destination: Path) {
        val response = client.send(request("GET", key).GET().build(), HttpResponse.BodyHandlers.ofFile(destination))
        check(response.statusCode() == 200) { "shared input download unavailable (${response.statusCode()})" }
    }
}

/**
 * Build archives of cloud preparations, kept by content hash in the regional store beside the shared inputs. Cloud
 * workers write and read them over the free regional route, so the archives never cross the internet; the store's
 * lifecycle expires them.
 */
internal interface BuildArchives {
    /** The size of archive [sha] when the store holds it, else null. */
    fun size(sha: String): Long?
    fun put(source: Path, sha: String)
    fun get(sha: String, destination: Path)

    companion object {
        const val PREFIX = "build-blobs"

        /** A cloud worker's view: unsigned regional reads, writes signed through its session. Null on the local machine. */
        fun worker(environment: Map<String, String> = System.getenv(), publishSignature: ((InputSignature) -> SignedInput)? = null): BuildArchives? {
            if (environment["LAB_SHARED_INPUT_SIGNER"] != null) return null
            val base = environment["LAB_SHARED_INPUT_URL"] ?: return null
            return RegionalBuildArchives(HttpInputTransport(URI(base), publishSignature = publishSignature))
        }

        /** The coordinator's view: reads signed by its loopback signer. Null when no regional store is configured. */
        fun coordinator(environment: Map<String, String> = System.getenv()): BuildArchives? {
            val base = environment["LAB_SHARED_INPUT_URL"] ?: return null
            val signer = environment["LAB_SHARED_INPUT_SIGNER"] ?: return null
            return RegionalBuildArchives(HttpInputTransport(URI(base), URI(signer)))
        }
    }
}

internal class RegionalBuildArchives(private val transport: HttpInputTransport) : BuildArchives {
    private fun key(sha: String): String { require(sha.matches(SHA256)); return "${BuildArchives.PREFIX}/$sha" }
    override fun size(sha: String): Long? = transport.stored(key(sha), sha)
    override fun put(source: Path, sha: String) = transport.put(key(sha), source, sha)
    override fun get(sha: String, destination: Path) = transport.get(key(sha), destination)
}

internal class SharedInputLease(val directory: Path, private val pin: AutoCloseable) : AutoCloseable {
    override fun close() = pin.close()
}

internal class SharedCheckInputUnavailable(cause: Throwable) :
    IllegalStateException("shared checking input temporarily unavailable: ${cause.message ?: cause.javaClass.simpleName}", cause)
