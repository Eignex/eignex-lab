package com.eignex.lab.execution

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `input-signer --config FILE`: the Mac's loopback signing service for the regional input store, which the coordinator
 * and native workers reach at `LAB_SHARED_INPUT_SIGNER`. It answers a POST of `{"method", "key", "sha256"?}` with
 * `{"url", "headers"}`: a five-minute S3 URL and the headers its request must send. No stored bytes pass through it and
 * it never hands out the credentials it signs with.
 *
 * It signs reads (HEAD and GET) of `corpus/…`, `corpus-packs/<name>.tar` and `<prefix>/<sha256>` keys, and conditional,
 * checksum-bound writes (PUT with `If-None-Match: *`) of corpus packs and of `<prefix>/<sha256>` keys whose hash is the
 * body's `sha256`, for the prefixes `input-blobs`, `build-blobs` ([RegionalBuildArchives]) and `result-blobs`
 * ([ResultBlobs]). The store's policy (`deploy/infra/aws/input-store.sh`) grants the same.
 *
 * The configuration file names `bucket`, `region`, the AWS `profile` whose credentials sign, and an optional `port`
 * (8093). A malformed request is answered 400, a failure to sign 503.
 */
internal object InputSigner {
    const val DEFAULT_PORT = 8093
    const val EXPIRES_SECONDS = 300
    /** A worker checks eight inputs concurrently and several may share this listener. */
    private const val BACKLOG = 64
    private const val MAX_REQUEST_BYTES = 8192
    private val BLOB = Regex("(?:input-blobs|build-blobs|result-blobs)/([a-f0-9]{64})")
    private val PACK = Regex("corpus-packs/[A-Za-z0-9][A-Za-z0-9._-]*\\.tar")

    /** A request the signer will sign: [headers] are the ones the signed request must send, and are signed too. */
    data class Operation(val method: String, val key: String, val headers: Map<String, String> = emptyMap())

    /** The operation a signing request's JSON body asks for; [IllegalArgumentException] when it may not be signed. */
    fun operation(body: JsonElement): Operation {
        val request = body as? JsonObject ?: throw IllegalArgumentException("invalid signing request")
        val method = (request["method"] as? JsonPrimitive)?.takeIf { it.isString && it.content in setOf("HEAD", "GET", "PUT") }?.content
        val key = (request["key"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(method != null && key != null) { "invalid operation" }
        require(key.split('/').none { it in setOf("", ".", "..") } && '\\' !in key && '\u0000' !in key) { "invalid key" }
        // Content-addressed shared inputs, cloud build archives and cloud solve outputs (which the bucket lifecycle expires).
        val blob = BLOB.matchEntire(key)
        val pack = PACK.matches(key)
        require(blob != null || pack || key.startsWith("corpus/")) { "key outside input cache" }
        if (method != "PUT") return Operation(method, key)
        val sha = request["sha256"].let { if (it == null) "" else (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }
        require(sha != null && SHA256.matches(sha) && (pack || blob != null && sha == blob.groupValues[1])) {
            "writes require a content hash"
        }
        val checksum = Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(sha))
        return Operation(method, key, linkedMapOf("If-None-Match" to "*", "x-amz-meta-sha256" to sha, "x-amz-checksum-sha256" to checksum))
    }

    /** The response body for [operation]: its signed URL and the headers to send with it. */
    fun response(operation: Operation, presigner: S3Presigner): String = buildJsonObject {
        put("url", presigner.presign(operation.method, operation.key, operation.headers, EXPIRES_SECONDS))
        putJsonObject("headers") { operation.headers.forEach { (name, value) -> put(name, value) } }
    }.toString()

    /**
     * The loopback listener on [port] (0 for any), until [HttpServer.stop]: it answers each request [operation] accepts
     * with [sign]'s response for it.
     */
    fun serve(port: Int, sign: (Operation) -> String): HttpServer {
        val executor = Executors.newFixedThreadPool(16) { task -> Thread(task, "input-signer").apply { isDaemon = true } }
        return HttpServer.create(InetSocketAddress("127.0.0.1", port), BACKLOG).apply {
            this.executor = executor
            createContext("/") { exchange -> exchange.use { handle(it, sign) } }
            start()
        }
    }

    private fun handle(exchange: HttpExchange, sign: (Operation) -> String) {
        if (exchange.requestMethod != "POST") return exchange.sendResponseHeaders(501, -1)
        val operation = try {
            val length = exchange.requestHeaders.getFirst("Content-Length")?.trim()?.toIntOrNull() ?: 0
            require(exchange.requestURI.rawPath == "/" && exchange.requestURI.rawQuery == null && length in 1..MAX_REQUEST_BYTES)
            operation(Json.parseToJsonElement(String(exchange.requestBody.readNBytes(length), Charsets.UTF_8)))
        } catch (_: IllegalArgumentException) {
            return text(exchange, 400, "invalid signing request")
        }
        val result = try {
            sign(operation).toByteArray()
        } catch (_: Exception) {
            return text(exchange, 503, "input signing unavailable")
        }
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, result.size.toLong())
        exchange.responseBody.write(result)
    }

    private fun text(exchange: HttpExchange, status: Int, message: String) {
        val bytes = message.toByteArray()
        exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    fun main(args: List<String>): Int {
        if (args.size != 2 || args[0] != "--config") {
            System.err.println("usage: eignex-lab input-signer --config FILE")
            return 2
        }
        val presigner: S3Presigner
        val port: Int
        try {
            val config = Json.parseToJsonElement(Files.readString(Path.of(args[1]))).jsonObject
            fun text(name: String) = requireNotNull((config[name] as? JsonPrimitive)?.contentOrNull) { "configuration lacks $name" }
            port = config["port"]?.let { requireNotNull((it as? JsonPrimitive)?.intOrNull) { "port must be a number" } } ?: DEFAULT_PORT
            val credentials = AwsProfileCredentials(text("profile"))
            credentials.invoke()  // an unusable profile stops here, in the service's log, rather than at each request
            presigner = S3Presigner(text("bucket"), text("region"), credentials)
        } catch (e: Exception) {
            System.err.println("input-signer: ${e.message}")
            return 78  // sysexits' configuration error
        }
        serve(port) { response(it, presigner) }
        println("input-signer: listening on 127.0.0.1:$port")
        while (true) Thread.sleep(Long.MAX_VALUE)
    }
}

/** Credentials that sign AWS requests; [expiration] is null for an access key that does not expire. */
internal data class AwsCredentials(val accessKeyId: String, val secretAccessKey: String, val sessionToken: String? = null,
                                   val expiration: Instant? = null) {
    override fun toString() = "AwsCredentials($accessKeyId)"
}

/**
 * The credentials of a named profile, resolved as the AWS SDKs do for an explicit profile: an access key in the shared
 * credentials file (`AWS_SHARED_CREDENTIALS_FILE`, `~/.aws/credentials`), else the profile's `credential_process`, else
 * an access key in the config file (`AWS_CONFIG_FILE`, `~/.aws/config`). An access key is read once; a process's
 * credentials are reused until ten minutes before they expire. Roles, SSO and web identities are not supported.
 */
internal class AwsProfileCredentials(private val profile: String, private val environment: Map<String, String> = System.getenv(),
                                     private val clock: Clock = Clock.systemUTC()) : () -> AwsCredentials {
    private var cached: AwsCredentials? = null

    @Synchronized override fun invoke(): AwsCredentials {
        cached?.let { current -> if (current.expiration == null || current.expiration > clock.instant().plus(REFRESH)) return current }
        return resolve().also { cached = it }
    }

    private fun resolve(): AwsCredentials {
        val home = environment["HOME"] ?: System.getProperty("user.home")
        fun file(variable: String, default: String) = Path.of((environment[variable] ?: default).replaceFirst(Regex("^~(?=/|$)"), Regex.escapeReplacement(home)))
        val credentials = ini(file("AWS_SHARED_CREDENTIALS_FILE", "$home/.aws/credentials"))[profile]
        val config = ini(file("AWS_CONFIG_FILE", "$home/.aws/config")).let { sections ->
            if (profile == "default") sections["default"] ?: sections["profile default"] else sections["profile $profile"]
        }
        check(credentials != null || config != null) { "no AWS profile $profile" }
        staticKey(credentials)?.let { return it }
        (credentials?.get("credential_process") ?: config?.get("credential_process"))?.let { return process(it) }
        return staticKey(config) ?: throw IllegalStateException("AWS profile $profile has no access key or credential_process")
    }

    private fun staticKey(section: Map<String, String>?): AwsCredentials? {
        val id = section?.get("aws_access_key_id")?.takeIf { it.isNotEmpty() } ?: return null
        val secret = section["aws_secret_access_key"]?.takeIf { it.isNotEmpty() } ?: return null
        return AwsCredentials(id, secret, section["aws_session_token"]?.takeIf { it.isNotEmpty() })
    }

    private fun process(command: String): AwsCredentials {
        val process = ProcessBuilder("/bin/sh", "-c", command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        process.outputStream.close()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(process.waitFor(1, TimeUnit.MINUTES) && process.exitValue() == 0) { "credential_process of $profile failed" }
        val result = Json.parseToJsonElement(output).jsonObject
        fun field(name: String) = (result[name] as? JsonPrimitive)?.contentOrNull
        check(field("Version") == "1") { "credential_process of $profile printed an unsupported version" }
        return AwsCredentials(checkNotNull(field("AccessKeyId")), checkNotNull(field("SecretAccessKey")), field("SessionToken"),
            field("Expiration")?.let { Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(it)) })
    }

    companion object {
        private val REFRESH: Duration = Duration.ofMinutes(10)

        /** The sections of an INI file as the AWS SDKs read it, option names lowercased; none when it is missing. */
        fun ini(path: Path): Map<String, Map<String, String>> {
            if (!Files.isRegularFile(path)) return emptyMap()
            val sections = linkedMapOf<String, MutableMap<String, String>>()
            var section: MutableMap<String, String>? = null
            for (raw in Files.readAllLines(path)) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
                if (raw[0].isWhitespace()) continue  // a continuation line, such as a nested s3 setting
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = sections.getOrPut(line.substring(1, line.length - 1).trim()) { linkedMapOf() }
                    continue
                }
                val split = line.indexOfFirst { it == '=' || it == ':' }
                if (split <= 0) continue
                section?.put(line.substring(0, split).trim().lowercase(), line.substring(split + 1).trim())
            }
            return sections
        }
    }
}

/**
 * Query-string-authenticated (presigned) S3 URLs, signed with AWS Signature Version 4 as the AWS SDKs sign them for a
 * virtual-hosted bucket: the payload unsigned, and every header the request must send signed beside `host`.
 */
internal class S3Presigner(private val bucket: String, private val region: String, private val credentials: () -> AwsCredentials,
                           private val clock: Clock = Clock.systemUTC(), private val host: String = host(bucket, region)) {
    init { require(bucket.isNotEmpty() && region.isNotEmpty()) }

    fun presign(method: String, key: String, headers: Map<String, String>, expires: Int): String {
        val signing = credentials()
        val timestamp = TIMESTAMP.format(clock.instant())
        val scope = "${timestamp.take(8)}/$region/s3/aws4_request"
        val signed = (headers.entries.associate { (name, value) -> name.lowercase() to value.trim().replace(Regex(" +"), " ") } +
            ("host" to host)).toSortedMap()
        val signedHeaders = signed.keys.joinToString(";")
        val query = sortedMapOf(
            "X-Amz-Algorithm" to ALGORITHM,
            "X-Amz-Credential" to "${signing.accessKeyId}/$scope",
            "X-Amz-Date" to timestamp,
            "X-Amz-Expires" to expires.toString(),
            "X-Amz-SignedHeaders" to signedHeaders,
        )
        signing.sessionToken?.let { query["X-Amz-Security-Token"] = it }
        val canonicalQuery = query.entries.joinToString("&") { (name, value) -> encode(name) + "=" + encode(value) }
        val path = "/" + encode(key, slash = true)
        val canonicalRequest = listOf(method, path, canonicalQuery, signed.entries.joinToString("") { (name, value) -> "$name:$value\n" },
            signedHeaders, "UNSIGNED-PAYLOAD").joinToString("\n")
        val stringToSign = listOf(ALGORITHM, timestamp, scope, sha256(canonicalRequest)).joinToString("\n")
        val key = listOf(timestamp.take(8), region, "s3", "aws4_request")
            .fold("AWS4${signing.secretAccessKey}".toByteArray()) { secret, part -> hmac(secret, part) }
        return "https://$host$path?$canonicalQuery&X-Amz-Signature=${hmac(key, stringToSign).hex()}"
    }

    companion object {
        private const val ALGORITHM = "AWS4-HMAC-SHA256"
        private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        /** The bucket's virtual-hosted endpoint; us-east-1 keeps the global one, as the SDKs do. */
        fun host(bucket: String, region: String) = if (region == "us-east-1") "$bucket.s3.amazonaws.com" else "$bucket.s3.$region.amazonaws.com"

        /** RFC 3986 percent-encoding of every byte but the unreserved characters (and `/` when [slash]), as SigV4 requires. */
        fun encode(value: String, slash: Boolean = false): String = buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val char = (byte.toInt() and 0xff).toChar()
                if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-_.~" || slash && char == '/') append(char)
                else append('%').append("%02X".format(byte.toInt() and 0xff))
            }
        }

        private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).hex()
        private fun hmac(key: ByteArray, text: String): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(text.toByteArray())
    }
}
