package com.eignex.lab.worker

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.nomad.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * A worker's calls to its coordinator session: authenticated as the session, its allocation and its identity, fenced on
 * 401/403/409, retried on the transient statuses by the caller, and given no more time than the worker's lease and
 * run deadline leave.
 */
internal class CoordinatorClient(
    private val api: URI,
    private val id: String,
    private val token: String,
    private val allocation: String,
    private val identityFile: Path,
    private val client: HttpClient,
    private val leaseRemainingMs: () -> Long,
    private val runRemainingMs: () -> Long?,
    private val checkRun: () -> Unit,
    private val recorder: () -> PreparationRecorder?,
) {
    fun request(path: String, method: String = "GET", body: HttpRequest.BodyPublisher = HttpRequest.BodyPublishers.noBody(), owner: AttemptOwner? = null, range: Long = 0): HttpResponse<ByteArray> {
        val started = System.nanoTime()
        val timeout = if (path == "resource-checkpoint") 3_000L else if (path == "run-timeout") 30_000L else if (path == "preparation-diagnostics") {
            // Evidence only: do not extend work, retry, or accept writes after the owning lease expires.
            val lease = leaseRemainingMs()
            if (lease <= 0) throw ExecutionFenced("worker lease expired")
            minOf(5_000, lease)
        } else {
            checkRun(); val lease = leaseRemainingMs()
            if (lease <= 0) throw ExecutionFenced("worker lease expired")
            minOf(30_000, runRemainingMs() ?: 30_000, lease).coerceAtLeast(1)
        }
        val builder = HttpRequest.newBuilder(URI.create(api.toString().trimEnd('/') + "/worker/v1/sessions/${NomadClient.encode(id)}/$path"))
            .timeout(Duration.ofMillis(timeout)).header("Authorization", "Bearer $token").header("X-Lab-Allocation", allocation)
            .header("X-Lab-Identity", Files.readString(identityFile).trim())
            .header("Content-Type", if (path.startsWith("commit-packed") || path == "uploads-packed") "application/octet-stream" else "application/json")
        if (owner != null) builder.header("X-Lab-Attempt", Json.encodeToString(owner))
        if (range > 0) builder.header("Range", "bytes=$range-")
        val upload = Regex("uploads/([0-9a-f]{64})(?:\\?(.*))?").matchEntire(path)
        fun record(status: Int?, responseOffset: Long? = null, failure: String? = null) {
            if (upload == null) return
            val offset = Regex("(?:^|&)offset=([0-9]+)(?:&|$)").find(upload.groupValues[2])?.groupValues?.get(1)?.toLongOrNull()
            recorder()?.upload(PreparationUploadDiagnostic(upload.groupValues[1], method, status, offset,
                responseOffset, body.contentLength().coerceAtLeast(0), (System.nanoTime() - started) / 1_000_000, failure))
        }
        val response = try { client.send(builder.method(method, body).build(), HttpResponse.BodyHandlers.ofByteArray()) }
        catch (failure: java.io.IOException) {
            record(null, failure = if (failure is java.net.http.HttpTimeoutException) "timeout" else "io"); throw failure
        } catch (failure: InterruptedException) { record(null, failure = "interrupted"); throw failure }
        record(response.statusCode(), if (upload == null) null else runCatching { Json.decodeFromString<UploadReply>(String(response.body())).offset }.getOrNull())
        val elapsed = (System.nanoTime()-started)/1_000_000
        if (elapsed > 250) println("worker request timing: path=$path status=${response.statusCode()} elapsedMs=$elapsed bytes=${body.contentLength()}")
        if (response.statusCode() in setOf(401, 403, 409)) throw ExecutionFenced("worker allocation fenced")
        if (response.statusCode() == 422) throw RegionalResultRefused("coordinator refused regional results on $path")
        if (path in setOf("commit-inline", "commit-packed", "commit-packed-large", "uploads-packed") && response.statusCode() in setOf(404, 405)) return response
        if (response.statusCode() in setOf(408, 429, 500, 502, 503, 504)) throw WorkerUnavailable(response.statusCode())
        check(response.statusCode() in 200..299) { "worker API HTTP ${response.statusCode()}" }
        return response
    }
    fun json(path: String, body: String, owner: AttemptOwner? = null) = request(path, "POST", HttpRequest.BodyPublishers.ofString(body), owner)
}
