package com.eignex.lab.nomad

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import com.eignex.lab.worker.*

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

interface NomadControl : ClusterNodes {
    val namespace: String get() = "default"
    fun job(id: String): JsonObject?
    fun submit(job: JsonObject)
    fun stop(id: String)
    fun allocations(job: String): List<NomadAllocation>
    fun allocation(id: String): NomadAllocation?
    fun jwks(): JsonObject
    override fun nodes(): List<JsonObject> = emptyList()
    override fun node(id: String): JsonObject? = null
    override fun nodeAllocations(id: String): List<JsonObject> = emptyList()
    override fun hasActiveAllocation(job: String): Boolean = allocations(job).any { it.active }
    fun collectAllocation(id: String): Boolean { error("allocation garbage collection is unavailable") }
    fun hostVolumes(): List<JsonObject> = emptyList()
    fun createHostVolume(volume: JsonObject) { error("dynamic host volumes are unavailable") }
}

data class NomadWorkerExit(val code: Int)

/** Safe controller-side evidence, independent of the strict software-crash classifier. Unknown is null. */
@kotlinx.serialization.Serializable
data class NomadTaskTermination(val client: String?, val desired: String?, val task: String?,
    val events: List<String>, val exitCode: Int?, val signal: Int?, val oomKilled: Boolean?,
    val killReported: Boolean, val driverErrorReported: Boolean, val setupErrorReported: Boolean)

data class NomadAllocation(val id: String, val job: String, val node: String, val client: String, val desired: String, val createdAt: Long = 0,
                           val workerExit: NomadWorkerExit? = null, val termination: NomadTaskTermination? = null) {
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
    override fun collectAllocation(id: String): Boolean = try {
        request("GET", "/v1/client/allocation/${encode(id)}/gc")
        true
    } catch (failure: NomadHttpFailure) {
        // Server records outlive client workspaces. Nomad also uses this response for a GC eligibility race.
        if (failure.status == 500 && failure.response.trim() == "No such allocation on client, or allocation not eligible for GC") false
        else throw failure
    }
    override fun hostVolumes() = request("GET", "/v1/volumes", query = "type=host")!!.jsonArray.map { it.jsonObject }
    override fun createHostVolume(volume: JsonObject) { request("PUT", "/v1/volume/host/create", buildJsonObject { put("Volume", volume) }) }
    override fun jwks() = request("GET", "/.well-known/jwks.json")!!.jsonObject
    private fun request(method: String, path: String, body: JsonElement? = null, missing: Boolean = false, query: String? = null): JsonElement? {
        val uri = URI.create(address.toString().trimEnd('/') + path + "?namespace=${encode(namespace)}" + (query?.let { "&$it" } ?: ""))
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("X-Nomad-Token", Files.readString(tokenFile).trim())
            .header("Content-Type", "application/json").method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody()).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (missing && response.statusCode() == 404) return null
        if (response.statusCode() !in 200..299) throw NomadHttpFailure(response.statusCode(), response.body(), method)
        return response.body().takeIf { it.isNotBlank() }?.let(Json::parseToJsonElement)
    }
    private class NomadHttpFailure(val status: Int, val response: String, method: String) :
        IllegalStateException("Nomad $method failed with HTTP $status")
    companion object {
        internal fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
        internal fun allocationRow(j: JsonObject) = NomadAllocation(j.getValue("ID").jsonPrimitive.content, j.getValue("JobID").jsonPrimitive.content,
            j.getValue("NodeID").jsonPrimitive.content, j.getValue("ClientStatus").jsonPrimitive.content, j.getValue("DesiredStatus").jsonPrimitive.content, j["CreateTime"]?.jsonPrimitive?.longOrNull ?: 0,
            workerExit(j), termination(j))

        private fun termination(j: JsonObject): NomadTaskTermination? {
            val worker = (j["TaskStates"] as? JsonObject)?.get("worker") as? JsonObject ?: return null
            val events = (worker["Events"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
            val ended = events.lastOrNull { it["Type"]?.jsonPrimitive?.contentOrNull == "Terminated" }
            val details = ended?.get("Details") as? JsonObject
            fun value(key: String, allowed: Set<String>) = j[key]?.jsonPrimitive?.contentOrNull?.takeIf { it in allowed }
            val knownEvents = setOf("Received", "Task Setup", "Started", "Terminated", "Not Restarting", "Killing", "Killed",
                "Driver Failure", "Failed Validation", "Setup Failure", "Restarting", "Client Reconnected")
            fun reported(key: String) = events.any { !it[key]?.jsonPrimitive?.contentOrNull.isNullOrEmpty() }
            return NomadTaskTermination(value("ClientStatus", setOf("pending", "running", "complete", "failed", "lost", "unknown")),
                value("DesiredStatus", setOf("run", "stop", "evict")),
                worker["State"]?.jsonPrimitive?.contentOrNull?.takeIf { it in setOf("pending", "running", "dead") },
                events.takeLast(32).map { it["Type"]?.jsonPrimitive?.contentOrNull?.takeIf { type -> type in knownEvents } ?: "other" },
                ended?.get("ExitCode")?.jsonPrimitive?.intOrNull ?: details?.get("exit_code")?.jsonPrimitive?.intOrNull,
                ended?.get("Signal")?.jsonPrimitive?.intOrNull ?: details?.get("signal")?.jsonPrimitive?.intOrNull,
                details?.get("oom_killed")?.jsonPrimitive?.booleanOrNull,
                reported("KillReason"), reported("DriverError"), reported("SetupError"))
        }

        private fun workerExit(j: JsonObject): NomadWorkerExit? {
            if (j["ClientStatus"]?.jsonPrimitive?.content != "failed" || j["DesiredStatus"]?.jsonPrimitive?.content != "run") return null
            val worker = (j["TaskStates"] as? JsonObject)?.get("worker") as? JsonObject ?: return null
            if (worker["State"]?.jsonPrimitive?.content != "dead" || worker["Failed"]?.jsonPrimitive?.booleanOrNull != true) return null
            val events = (worker["Events"] as? JsonArray)?.map { it.jsonObject } ?: return null
            // Positive process-exit evidence only. Kill/eviction, signals, driver/setup errors, OOM and unknown
            // event sequences remain infrastructure recovery, rather than spending a deterministic-error budget.
            val ordinary = setOf("Received", "Task Setup", "Started", "Terminated", "Not Restarting")
            if (events.any { it["Type"]?.jsonPrimitive?.content !in ordinary } || events.none { it["Type"]?.jsonPrimitive?.content == "Started" }) return null
            val ended = events.lastOrNull { it["Type"]?.jsonPrimitive?.content == "Terminated" } ?: return null
            val details = ended["Details"] as? JsonObject
            val code = ended["ExitCode"]?.jsonPrimitive?.intOrNull ?: details?.get("exit_code")?.jsonPrimitive?.intOrNull ?: return null
            val signal = ended["Signal"]?.jsonPrimitive?.intOrNull ?: details?.get("signal")?.jsonPrimitive?.intOrNull ?: return null
            if (code == 0 || signal != 0 || details?.get("oom_killed")?.jsonPrimitive?.booleanOrNull != false ||
                listOf("KillReason", "DriverError", "SetupError").any { !ended[it]?.jsonPrimitive?.contentOrNull.isNullOrEmpty() }) return null
            return NomadWorkerExit(code)
        }
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
