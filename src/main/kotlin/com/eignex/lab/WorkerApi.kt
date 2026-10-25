package com.eignex.lab

import io.ktor.http.*
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.io.readByteArray
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.nio.file.Files

@Serializable data class WorkerRegistration(val release: String)
@Serializable data class WorkerSelection(val owner: AttemptOwner, val selections: List<List<Problem>>)
@Serializable data class WorkerCommit(val owner: AttemptOwner, val result: IterationResult)
@Serializable data class WorkerPhase(val owner: AttemptOwner, val phase: String)
@Serializable data class WorkerBuildError(val owner: AttemptOwner, val detail: String)
@Serializable data class WorkerProgress(val owner: AttemptOwner, val index: Int)
@Serializable data class LeaseReply(val leaseMs: Long, val runRemainingMs: Long? = null)
@Serializable data class UploadReply(val offset: Long)

/** Separate listener from the password-authenticated operator API. No operator routes or provider control methods. */
fun Application.workerApi(store: Store, backend: NomadBackend, artifacts: ArtifactStore, workAllowed: () -> Boolean) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<ExecutionFenced> { call, _ -> call.respond(HttpStatusCode.Conflict, "execution fenced") }
        exception<IllegalArgumentException> { call, cause ->
            call.application.log.warn("worker request rejected: ${cause.javaClass.simpleName} at ${cause.stackTrace.firstOrNull { it.className.startsWith("com.eignex.lab.") }}")
            call.respond(HttpStatusCode.BadRequest, "invalid worker request") }
        exception<IllegalStateException> { call, _ -> call.respond(HttpStatusCode.ServiceUnavailable, "worker service unavailable") }
    }
    suspend fun ApplicationCall.bodyText(limit: Int = 4 * 1024 * 1024): String = receiveChannel().let { channel ->
        val bytes = channel.readRemaining(limit.toLong() + 1).readByteArray()
        require(bytes.size <= limit); String(bytes)
    }
    fun ApplicationCall.credentials(): List<String> = listOf(parameters["id"]!!, request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ") ?: throw ExecutionFenced("credential required"),
        request.headers["X-Lab-Allocation"] ?: throw ExecutionFenced("allocation required"), request.headers["X-Lab-Identity"] ?: throw ExecutionFenced("workload identity required"))
    fun ApplicationCall.auth(): ExecutionSession { val c = credentials(); return backend.authenticate(c[0], c[1], c[2], c[3]) }
    routing {
        route("/worker/v1/sessions/{id}") {
            post("/register") {
                val c = call.credentials(); val body = Json.decodeFromString<WorkerRegistration>(call.bodyText())
                call.respond(backend.register(c[0], c[1], c[2], c[3], body.release))
            }
            post("/claim") {
                require(workAllowed()); call.auth()
                val c = call.credentials(); val claim = backend.claim(c[0], c[1], c[2])
                if (claim == null) call.respond(HttpStatusCode.NoContent) else call.respond(claim)
            }
            post("/renew") {
                require(workAllowed()); val s = call.auth(); val owner = Json.decodeFromString<AttemptOwner>(call.bodyText()); require(owner.session == s.id && owner.allocation == s.allocation)
                store.execution.renew(owner); call.respond(LeaseReply(ExecutionStore.LEASE_MS, store.execution.remainingRunMs(s.id)))
            }
            post("/phase") {
                require(workAllowed()); val s = call.auth(); val body = Json.decodeFromString<WorkerPhase>(call.bodyText()); require(body.owner.session == s.id && body.owner.allocation == s.allocation)
                store.execution.phase(body.owner, body.phase); call.respond(HttpStatusCode.NoContent)
            }
            post("/progress") {
                require(workAllowed()); val s = call.auth(); val body = Json.decodeFromString<WorkerProgress>(call.bodyText())
                require(body.owner.session == s.id && body.owner.allocation == s.allocation)
                store.execution.caseStarted(body.owner, body.index); call.respond(HttpStatusCode.NoContent)
            }
            put("/logs/{index}/{stream}") {
                require(workAllowed()); val s = call.auth()
                val owner = Json.decodeFromString<AttemptOwner>(call.request.headers["X-Lab-Attempt"] ?: throw ExecutionFenced("attempt required"))
                require(owner.session == s.id && owner.allocation == s.allocation)
                val bytes = call.receiveChannel().readRemaining((WorkerLogs.CHUNK_BYTES + 1).toLong()).readByteArray()
                require(bytes.size <= WorkerLogs.CHUNK_BYTES)
                val offset = backend.logs.append(owner, call.parameters["index"]!!.toInt(), call.parameters["stream"]!!,
                    call.request.queryParameters["offset"]!!.toLong(), bytes)
                call.respond(UploadReply(offset))
            }
            post("/build-failed") {
                require(workAllowed()); val s = call.auth(); val body = Json.decodeFromString<WorkerBuildError>(call.bodyText())
                require(body.owner.session == s.id && body.owner.allocation == s.allocation)
                store.execution.rejectBuild(body.owner, body.detail); call.respond(HttpStatusCode.NoContent)
            }
            post("/run-timeout") {
                require(workAllowed()); val s = call.auth(); val owner = Json.decodeFromString<AttemptOwner>(call.bodyText())
                require(owner.session == s.id && owner.allocation == s.allocation)
                store.execution.runTimedOut(owner); call.respond(HttpStatusCode.NoContent)
            }
            post("/selection") {
                require(workAllowed()); val session = call.auth()
                val body = Json.decodeFromString<WorkerSelection>(call.bodyText(64 * 1024 * 1024))
                require(body.owner.session == session.id && body.owner.allocation == session.allocation)
                call.respond(backend.selectPreparation(body.owner, body.selections))
            }
            post("/commit") {
                val s = call.auth(); val body = Json.decodeFromString<WorkerCommit>(call.bodyText(if (s.executionClass == "prepare") 64 * 1024 * 1024 else 4 * 1024 * 1024)); require(body.owner.session == s.id && body.owner.allocation == s.allocation)
                backend.commit(body.owner, body.result); call.respond(HttpStatusCode.NoContent)
            }
            get("/inputs/{sha}") {
                require(workAllowed()); val s = call.auth(); val sha = call.parameters["sha"]!!
                val manifest = store.execution.manifest(s.id)
                require((manifest.bundles + listOfNotNull(manifest.inputCorpus)).any { it.sha256 == sha })
                val file = artifacts.readFile(s.jobId, sha)
                val range = call.request.headers[HttpHeaders.Range] ?: "bytes=0-"
                val match = requireNotNull(Regex("bytes=([0-9]+)-([0-9]*)").matchEntire(range))
                val from = match.groupValues[1].toLong()
                val total = Files.size(file); require(from in 0 until total)
                val requestedEnd = match.groupValues[2].toLongOrNull() ?: total - 1
                val end = minOf(requestedEnd, total - 1, from + 8L * 1024 * 1024 - 1); require(end >= from)
                call.response.status(HttpStatusCode.PartialContent)
                call.response.header(HttpHeaders.ContentRange, "bytes $from-$end/$total")
                call.response.header(HttpHeaders.AcceptRanges, "bytes")
                call.respondOutputStream(ContentType.Application.OctetStream) {
                    Files.newInputStream(file).use { input ->
                        input.skipNBytes(from); var left = end - from + 1; val buffer = ByteArray(64 * 1024)
                        while (left > 0) { val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt()); check(n > 0); write(buffer, 0, n); left -= n }
                    }
                }
            }
            get("/uploads/{sha}") {
                require(workAllowed()); val s = call.auth(); val owner = call.request.headers["X-Lab-Attempt"]?.let { Json.decodeFromString<AttemptOwner>(it) } ?: throw ExecutionFenced("attempt required")
                require(owner.session == s.id && owner.allocation == s.allocation); store.execution.authorize(owner)
                call.respond(UploadReply(artifacts.offset(s.jobId, call.parameters["sha"]!!)))
            }
            put("/uploads/{sha}") {
                require(workAllowed()); val s = call.auth(); val owner = Json.decodeFromString<AttemptOwner>(call.request.headers["X-Lab-Attempt"] ?: throw ExecutionFenced("attempt required"))
                require(owner.session == s.id && owner.allocation == s.allocation); store.execution.authorize(owner)
                val bytes = call.request.queryParameters["bytes"]!!.toLong(); val offset = call.request.queryParameters["offset"]!!.toLong()
                val result = call.receiveChannel().toInputStream().use { artifacts.put(s.jobId, call.parameters["sha"]!!, bytes, offset, it) }
                call.respond(UploadReply(result))
            }
        }
    }
}
