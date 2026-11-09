package com.eignex.lab.cloud.aws

import com.eignex.lab.cloud.ProviderError
import com.eignex.lab.cloud.ProviderHttp
import com.eignex.lab.cloud.ProviderResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URI
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

/**
 * AWS's HTTP APIs in [region], signed with Signature Version 4 by [credentials]: the query protocol (Auto Scaling, EC2,
 * STS), the JSON protocol (DynamoDB, Secrets Manager) and Lambda's REST invocation. A provider error becomes a
 * [ProviderError] carrying the provider's error code only.
 */
class AwsApi(val region: String, private val credentials: () -> AwsCredentials, private val clock: Clock = Clock.systemUTC(),
             private val http: ProviderHttp = ProviderHttp.shared) {

    /** The host of [service]'s regional endpoint. */
    fun host(service: String) = "$service.$region.amazonaws.com"

    /** A query-protocol [action] of [service]: its response document's root element. */
    fun query(service: String, action: String, version: String, parameters: Map<String, String>): Element {
        val body = (linkedMapOf("Action" to action, "Version" to version) + parameters).entries
            .joinToString("&") { (name, value) -> encode(name) + "=" + encode(value) }.toByteArray()
        val response = send(service, "POST", "/", body, mapOf("content-type" to "application/x-www-form-urlencoded; charset=utf-8"))
        if (response.status !in 200..299) throw ProviderError(xmlErrorCode(response.text), response.status)
        return xml(response.text)
    }

    /** A JSON-protocol [target] (such as `DynamoDB_20120810.GetItem`) of [service]: its response object. */
    fun json(service: String, target: String, contentType: String, request: JsonObject): JsonObject {
        val response = send(service, "POST", "/", request.toString().toByteArray(), mapOf("content-type" to contentType, "x-amz-target" to target))
        if (response.status !in 200..299) throw ProviderError(jsonErrorCode(response), response.status)
        return if (response.body.isEmpty()) JsonObject(emptyMap()) else Json.parseToJsonElement(response.text) as JsonObject
    }

    /** Lambda's synchronous invocation of [function] with [payload]: the function's error type, if it failed, and its output. */
    fun invoke(function: String, payload: ByteArray, timeout: Duration): Pair<String?, ByteArray> {
        val response = send("lambda", "POST", "/2015-03-31/functions/${encode(function)}/invocations", payload,
            mapOf("content-type" to "application/json", "x-amz-invocation-type" to "RequestResponse"), timeout)
        if (response.status !in 200..299) throw ProviderError(response.header("x-amzn-ErrorType")?.substringBefore(':') ?: jsonErrorCode(response), response.status)
        return response.header("X-Amz-Function-Error") to response.body
    }

    private fun send(service: String, method: String, path: String, body: ByteArray, headers: Map<String, String>,
                     timeout: Duration? = null): ProviderResponse {
        val host = host(service)
        val throttled = setOf("Throttling", "ThrottlingException", "ThrottledException", "RequestThrottledException", "TooManyRequestsException",
            "ProvisionedThroughputExceededException", "TransactionInProgressException", "RequestLimitExceeded", "BandwidthLimitExceeded",
            "LimitExceededException", "RequestThrottled", "SlowDown", "PriorRequestNotComplete", "EC2ThrottledException")
        val transient = { response: ProviderResponse ->
            response.status >= 500 || response.status in setOf(400, 429) && errorCode(response) in throttled
        }
        return http.exchange(method, URI("https://$host$path"), body, timeout ?: Duration.ofSeconds(5), transient) {
            AwsSigner.sign(credentials(), region, service, method, host, path, headers, body, clock)
        }
    }

    private fun errorCode(response: ProviderResponse): String? =
        if (response.text.trimStart().startsWith("<")) xmlErrorCode(response.text) else jsonErrorCode(response)

    companion object {
        private val xmlFactory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isNamespaceAware = false
            isExpandEntityReferences = false
        }

        /** [text] parsed: its root element. */
        fun xml(text: String): Element = synchronized(xmlFactory) { xmlFactory.newDocumentBuilder() }
            .parse(InputSource(StringReader(text))).documentElement

        /** The code of a query-protocol error document (`ErrorResponse/Error/Code` or EC2's `Response/Errors/Error/Code`). */
        fun xmlErrorCode(text: String): String? = try {
            val root = xml(text)
            (root.child("Error") ?: root.child("Errors")?.child("Error"))?.text("Code")
        } catch (_: Exception) {
            null
        }

        /** The code of a JSON-protocol error: `__type` (after `#`) or the `x-amzn-ErrorType` header (before `:`). */
        fun jsonErrorCode(response: ProviderResponse): String? {
            response.header("x-amzn-ErrorType")?.let { return it.substringBefore(':') }
            val value = try { Json.parseToJsonElement(response.text) as? JsonObject } catch (_: Exception) { null }
            val type = (value?.get("__type") ?: value?.get("code") ?: value?.get("Type")) as? JsonPrimitive
            return type?.content?.substringAfterLast('#')
        }

        /** RFC 3986 percent-encoding of every byte but the unreserved characters, as SigV4 requires. */
        fun encode(value: String): String = buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val char = (byte.toInt() and 0xff).toChar()
                if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-_.~") append(char)
                else append('%').append("%02X".format(byte.toInt() and 0xff))
            }
        }
    }
}

/** AWS Signature Version 4 for a request whose headers carry the signature. */
object AwsSigner {
    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /**
     * The headers to send with [method] `https://[host][path]` (already percent-encoded, without a query) and [body]:
     * [headers] and `x-amz-date`, the session token if any, and the `Authorization` that signs them all with `host`.
     */
    fun sign(credentials: AwsCredentials, region: String, service: String, method: String, host: String, path: String,
             headers: Map<String, String>, body: ByteArray, clock: Clock): Map<String, String> {
        val timestamp = TIMESTAMP.format(clock.instant())
        val sent = linkedMapOf<String, String>()
        headers.forEach { (name, value) -> sent[name.lowercase()] = value }
        sent["x-amz-date"] = timestamp
        credentials.sessionToken?.let { sent["x-amz-security-token"] = it }
        val signed = (sent + ("host" to host)).mapValues { (_, value) -> value.trim().replace(Regex(" +"), " ") }.toSortedMap()
        val signedHeaders = signed.keys.joinToString(";")
        val scope = "${timestamp.take(8)}/$region/$service/aws4_request"
        // Every service but S3 signs each path segment encoded once more.
        val canonicalPath = path.split('/').joinToString("/") { AwsApi.encode(it) }
        val canonicalRequest = listOf(method, canonicalPath, "", signed.entries.joinToString("") { (name, value) -> "$name:$value\n" },
            signedHeaders, sha256(body)).joinToString("\n")
        val stringToSign = listOf(ALGORITHM, timestamp, scope, sha256(canonicalRequest.toByteArray())).joinToString("\n")
        val key = listOf(timestamp.take(8), region, service, "aws4_request")
            .fold("AWS4${credentials.secretAccessKey}".toByteArray()) { secret, part -> hmac(secret, part) }
        val signature = java.util.HexFormat.of().formatHex(hmac(key, stringToSign))
        return sent + ("authorization" to "$ALGORITHM Credential=${credentials.accessKeyId}/$scope, SignedHeaders=$signedHeaders, Signature=$signature")
    }

    private fun sha256(bytes: ByteArray) = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hmac(key: ByteArray, text: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(text.toByteArray())
}

/** The direct child elements of this element named [name]. */
fun Element.children(name: String): List<Element> {
    val nodes = childNodes
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName == name }
}

/** The first direct child element named [name]. */
fun Element.child(name: String): Element? = children(name).firstOrNull()

/** The text of the direct child element named [name], or null when there is none. */
fun Element.text(name: String): String? = child(name)?.textContent

/** The `member` (query protocol) or `item` (EC2) elements of the list element named [name]. */
fun Element.members(name: String): List<Element> = child(name)?.let { it.children("member") + it.children("item") }.orEmpty()

internal fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
