package com.eignex.lab.cloud.gcp

import com.eignex.lab.cloud.ProviderError
import com.eignex.lab.cloud.ProviderHttp
import com.eignex.lab.cloud.ProviderResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** The OAuth scope every capacity call uses. */
const val CLOUD_PLATFORM = "https://www.googleapis.com/auth/cloud-platform"

/** An access token and when it expires. */
data class GoogleToken(val value: String, val expires: Instant) {
    override fun toString() = "GoogleToken(expires=$expires)"
}

/** A source of access tokens, each reused until a minute before it expires. */
abstract class GoogleTokens(protected val http: ProviderHttp = ProviderHttp.shared, private val now: () -> Instant = Instant::now) : () -> String {
    private var cached: GoogleToken? = null

    @Synchronized override fun invoke(): String {
        cached?.let { if (it.expires > now().plusSeconds(60)) return it.value }
        return fetch().also { cached = it }.value
    }

    protected abstract fun fetch(): GoogleToken

    protected fun json(response: ProviderResponse): JsonObject {
        if (response.status !in 200..299) throw ProviderError(null, response.status)
        return Json.parseToJsonElement(response.text) as JsonObject
    }

    protected fun JsonObject.text(name: String) = (this[name] as? JsonPrimitive)?.content
    protected fun expiresIn(value: JsonObject) = now().plusSeconds(value.text("expires_in")?.toLongOrNull() ?: 300)

    companion object {
        /**
         * Application Default Credentials as the Google client libraries find them: the file `GOOGLE_APPLICATION_CREDENTIALS`
         * names, else gcloud's `~/.config/gcloud/application_default_credentials.json` (an `authorized_user` or an
         * `impersonated_service_account` over one), else the metadata server of the runtime the code runs on.
         */
        fun application(environment: Map<String, String> = System.getenv(), http: ProviderHttp = ProviderHttp.shared): GoogleTokens {
            val home = environment["HOME"] ?: System.getProperty("user.home")
            val file = environment["GOOGLE_APPLICATION_CREDENTIALS"]?.let { Path.of(it) }
                ?: Path.of(environment["CLOUDSDK_CONFIG"] ?: "$home/.config/gcloud", "application_default_credentials.json").takeIf { Files.isRegularFile(it) }
                ?: return Metadata(http)
            val value = Json.parseToJsonElement(Files.readString(file)) as JsonObject
            return credentials(value, http)
        }

        private fun credentials(value: JsonObject, http: ProviderHttp): GoogleTokens = when ((value["type"] as? JsonPrimitive)?.content) {
            "authorized_user" -> AuthorizedUser(value, http)
            "impersonated_service_account" -> {
                val url = (value["service_account_impersonation_url"] as JsonPrimitive).content
                val source = credentials(value["source_credentials"] as JsonObject, http)
                Impersonated(source, url.substringAfter("/serviceAccounts/").substringBefore(':'), 3600, http)
            }
            else -> throw IllegalStateException("unsupported application default credentials; use gcloud auth application-default login")
        }
    }

    /** The service account of the GCP runtime this code runs on, from its metadata server. */
    class Metadata(http: ProviderHttp = ProviderHttp.shared) : GoogleTokens(http) {
        override fun fetch(): GoogleToken {
            val value = json(http.exchange("GET", URI("http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token")) {
                mapOf("Metadata-Flavor" to "Google")
            })
            return GoogleToken(value.text("access_token")!!, expiresIn(value))
        }
    }

    /** A user's refresh token, as `gcloud auth application-default login` stores it. */
    class AuthorizedUser(private val value: JsonObject, http: ProviderHttp = ProviderHttp.shared) : GoogleTokens(http) {
        override fun fetch(): GoogleToken {
            val form = listOf("client_id", "client_secret", "refresh_token").joinToString("&") { "$it=" + encode(value.text(it)!!) } + "&grant_type=refresh_token"
            val answer = json(http.exchange("POST", URI("https://oauth2.googleapis.com/token"), form.toByteArray()) {
                mapOf("Content-Type" to "application/x-www-form-urlencoded")
            })
            return GoogleToken(answer.text("access_token")!!, expiresIn(answer))
        }
    }

    /** Service account [account] impersonated with [source]'s tokens through the IAM Credentials API. */
    class Impersonated(private val source: () -> String, private val account: String, private val lifetimeSeconds: Int = 600,
                       http: ProviderHttp = ProviderHttp.shared) : GoogleTokens(http) {
        private val base = "https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/${encode(account)}"

        override fun fetch(): GoogleToken {
            val request = buildJsonObject { putJsonArray("scope") { add(CLOUD_PLATFORM) }; put("lifetime", "${lifetimeSeconds}s") }
            val value = json(post("$base:generateAccessToken", request))
            return GoogleToken(value.text("accessToken")!!, Instant.parse(value.text("expireTime")!!))
        }

        /** A fresh OpenID Connect token for [audience] that names the account's email, as Cloud Run invokers need. */
        fun idToken(audience: String): String =
            json(post("$base:generateIdToken", buildJsonObject { put("audience", audience); put("includeEmail", true) })).text("token")!!

        private fun post(url: String, body: JsonObject) = http.exchange("POST", URI(url), body.toString().toByteArray()) {
            mapOf("Authorization" to "Bearer ${source()}", "Content-Type" to "application/json")
        }
    }
}

private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
