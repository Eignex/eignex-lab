package com.eignex.lab

import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.time.Duration
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

interface NomadControl {
    val namespace: String get() = "default"
    fun job(id: String): JsonObject?
    fun submit(job: JsonObject)
    fun stop(id: String)
    fun allocations(job: String): List<NomadAllocation>
    fun allocation(id: String): NomadAllocation?
    fun jwks(): JsonObject
    fun nodes(): List<JsonObject> = emptyList()
    fun node(id: String): JsonObject? = null
    fun nodeAllocations(id: String): List<JsonObject> = emptyList()
    fun hostVolumes(): List<JsonObject> = emptyList()
    fun createHostVolume(volume: JsonObject) { error("dynamic host volumes are unavailable") }
}

data class NomadAllocation(val id: String, val job: String, val node: String, val client: String, val desired: String, val createdAt: Long = 0) {
    val active get() = client in setOf("pending", "running") && desired == "run"
}

/** Control credentials stay in this process. No redirects, unverified TLS, provider credentials or worker token reuse. */
class NomadClient(private val address: URI, private val tokenFile: Path, private val client: HttpClient,
                  override val namespace: String = "default") : NomadControl {
    init { require(address.scheme == "https" || (address.scheme == "http" && address.host in setOf("127.0.0.1", "localhost", "::1"))); require(address.rawQuery == null && address.userInfo == null) }
    override fun job(id: String) = request("GET", "/v1/job/${encode(id)}", missing = true)?.jsonObject
    override fun submit(job: JsonObject) { request("POST", "/v1/jobs", buildJsonObject { put("Job", job); put("EnforceIndex", true); put("JobModifyIndex", 0) }) }
    override fun stop(id: String) { request("DELETE", "/v1/job/${encode(id)}", missing = true) }
    override fun allocations(job: String): List<NomadAllocation> = request("GET", "/v1/job/${encode(job)}/allocations", missing = true)?.jsonArray?.map { allocationRow(it.jsonObject) } ?: emptyList()
    override fun allocation(id: String) = request("GET", "/v1/allocation/${encode(id)}", missing = true)?.jsonObject?.let(::allocationRow)
    override fun nodes() = request("GET", "/v1/nodes")!!.jsonArray.map { it.jsonObject }
    override fun node(id: String) = request("GET", "/v1/node/${encode(id)}", missing = true)?.jsonObject
    override fun nodeAllocations(id: String) = request("GET", "/v1/node/${encode(id)}/allocations")!!.jsonArray.map { it.jsonObject }
    override fun hostVolumes() = request("GET", "/v1/volumes", query = "type=host")!!.jsonArray.map { it.jsonObject }
    override fun createHostVolume(volume: JsonObject) { request("PUT", "/v1/volume/host/create", buildJsonObject { put("Volume", volume) }) }
    override fun jwks() = request("GET", "/.well-known/jwks.json")!!.jsonObject
    private fun request(method: String, path: String, body: JsonElement? = null, missing: Boolean = false, query: String? = null): JsonElement? {
        val uri = URI.create(address.toString().trimEnd('/') + path + "?namespace=${encode(namespace)}" + (query?.let { "&$it" } ?: ""))
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("X-Nomad-Token", Files.readString(tokenFile).trim())
            .header("Content-Type", "application/json").method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody()).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (missing && response.statusCode() == 404) return null
        check(response.statusCode() in 200..299) { "Nomad $method failed with HTTP ${response.statusCode()}" }
        return response.body().takeIf { it.isNotBlank() }?.let(Json::parseToJsonElement)
    }
    companion object {
        internal fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
        private fun allocationRow(j: JsonObject) = NomadAllocation(j.getValue("ID").jsonPrimitive.content, j.getValue("JobID").jsonPrimitive.content,
            j.getValue("NodeID").jsonPrimitive.content, j.getValue("ClientStatus").jsonPrimitive.content, j.getValue("DesiredStatus").jsonPrimitive.content, j["CreateTime"]?.jsonPrimitive?.longOrNull ?: 0)
        fun tlsClient(keys: Path, trust: Path, passwordFile: Path): HttpClient {
            val password = Files.readString(passwordFile).trimEnd('\r', '\n').toCharArray()
            try {
                fun load(path: Path) = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(path).use { load(it, password) } }
                val key = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(load(keys), password) }
                val ca = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(load(trust)) }
                val tls = SSLContext.getInstance("TLS").apply { init(key.keyManagers, ca.trustManagers, null) }
                return HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
            } finally { password.fill('\u0000') }
        }
    }
}
