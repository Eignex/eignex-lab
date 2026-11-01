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
import kotlinx.coroutines.withContext
import io.ktor.util.AttributeKey
import java.net.URI

@Serializable data class WorkerRegistration(val release: String)
@Serializable data class WorkerSelection(val owner: AttemptOwner, val selections: List<List<Problem>>)
@Serializable data class WorkerBenchSelections(val owner: AttemptOwner, val keys: List<String>)
@Serializable data class WorkerCommit(val owner: AttemptOwner, val result: IterationResult)
@Serializable data class WorkerPhase(val owner: AttemptOwner, val phase: String)
@Serializable data class WorkerPreparationProgress(val owner: AttemptOwner, val progress: PreparationProgress)
@Serializable data class WorkerPreparationDiagnostics(val owner: AttemptOwner, val diagnostics: PreparationDiagnostics)
@Serializable data class WorkerBuildError(val owner: AttemptOwner, val detail: String)
@Serializable data class WorkerProgress(val owner: AttemptOwner, val index: Int)
@Serializable data class LeaseReply(val leaseMs: Long, val runRemainingMs: Long? = null)
@Serializable data class UploadReply(val offset: Long)

/**
 * Separate listener from the password-authenticated operator API. No operator routes or provider control methods.
 * [inputSigner] is the loopback signer of shared inputs (`LAB_SHARED_INPUT_SIGNER`), read once when the coordinator
 * starts; without one, a request for a signature is refused as unavailable.
 */
fun Application.workerApi(
    store: Store,
    backend: NomadBackend,
    artifacts: ArtifactStore,
    inputSigner: URI? = System.getenv("LAB_SHARED_INPUT_SIGNER")?.takeIf { it.isNotBlank() }?.let(::URI),
    workAllowed: () -> Boolean,
) {
    install(ContentNegotiation) { json(Json { encodeDefaults = false }) }
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
    routing {
        route("/worker/v1/sessions/{id}") {
            intercept(ApplicationCallPipeline.Call) {
                withContext(CoordinatorIo.control) { proceed() }
            }
            install(WorkerAuth) { this.backend = backend }
            post("/register") {
                // Its credentials are checked as the session is bound to them; a registration is a release name, no more.
                val c = call.credentials(); val body = Json.decodeFromString<WorkerRegistration>(call.bodyText(REGISTRATION_BYTES))
                call.respond(backend.register(c.session, c.token, c.allocation, c.identity, body.release))
            }
            post("/claim") {
                check(workAllowed()); call.session
                val c = call.credentials(); val claim = backend.claim(c.session, c.token, c.allocation)
                if (claim == null) call.respond(HttpStatusCode.NoContent) else call.respond(claim)
            }
            post("/renew") {
                // Lease and status updates must survive admission holds.
                val s = call.session; val owner = Json.decodeFromString<AttemptOwner>(call.bodyText()); s.requireOwns(owner)
                store.execution.renew(owner); call.respond(LeaseReply(ExecutionStore.LEASE_MS, store.execution.remainingRunMs(s.id)))
            }
            post("/phase") {
                // Lease and status updates must survive admission holds.
                val s = call.session; val body = Json.decodeFromString<WorkerPhase>(call.bodyText()); s.requireOwns(body.owner)
                store.execution.phase(body.owner, body.phase); call.respond(HttpStatusCode.NoContent)
            }
            post("/preparation-progress") {
                val s = call.session; val body = Json.decodeFromString<WorkerPreparationProgress>(call.bodyText(16 * 1024))
                s.requireOwns(body.owner)
                store.execution.preparationProgress(body.owner, body.progress); call.respond(HttpStatusCode.NoContent)
            }
            post("/preparation-diagnostics") {
                val s = call.session; val body = Json.decodeFromString<WorkerPreparationDiagnostics>(call.bodyText(128 * 1024))
                s.requireOwns(body.owner)
                store.execution.preparationDiagnostics(body.owner, body.diagnostics); call.respond(HttpStatusCode.NoContent)
            }
            post("/progress") {
                // Lease and status updates must survive admission holds.
                val s = call.session; val body = Json.decodeFromString<WorkerProgress>(call.bodyText())
                s.requireOwns(body.owner)
                store.execution.caseStarted(body.owner, body.index); call.respond(HttpStatusCode.NoContent)
            }
            put("/logs/{index}/{stream}") {
                withContext(CoordinatorIo.artifacts) {
                    check(workAllowed()); val s = call.session
                    val owner = Json.decodeFromString<AttemptOwner>(call.request.headers["X-Lab-Attempt"] ?: throw ExecutionFenced("attempt required"))
                    s.requireOwns(owner)
                    val bytes = call.receiveChannel().readRemaining((WorkerLogs.CHUNK_BYTES + 1).toLong()).readByteArray()
                    require(bytes.size <= WorkerLogs.CHUNK_BYTES)
                    val offset = backend.logs.append(owner, call.parameters["index"]!!.toInt(), call.parameters["stream"]!!,
                        call.request.queryParameters["offset"]!!.toLong(), bytes)
                    call.respond(UploadReply(offset))
                }
            }
            post("/build-failed") {
                // Lease and status updates must survive admission holds.
                val s = call.session; val body = Json.decodeFromString<WorkerBuildError>(call.bodyText())
                s.requireOwns(body.owner)
                store.execution.rejectBuild(body.owner, body.detail); call.respond(HttpStatusCode.NoContent)
            }
            post("/run-timeout") {
                // Lease and status updates must survive admission holds.
                val s = call.session; val owner = Json.decodeFromString<AttemptOwner>(call.bodyText())
                s.requireOwns(owner)
                store.execution.runTimedOut(owner); call.respond(HttpStatusCode.NoContent)
            }
            post("/selection") {
                withContext(CoordinatorIo.artifacts) {
                    check(workAllowed()); val session = call.session
                    val body = Json.decodeFromString<WorkerSelection>(call.bodyText(64 * 1024 * 1024))
                    require(body.owner.session == session.id && body.owner.allocation == session.allocation)
                    call.respond(backend.selectPreparation(body.owner, body.selections))
                }
            }
            post("/bench-selections") {
                // Read-only: accepted planner outputs by selection key, for the preparing attempt that asks.
                check(workAllowed()); val session = call.session
                require(session.executionClass == ExecutionClass.PREPARE)
                val body = Json.decodeFromString<WorkerBenchSelections>(call.bodyText(64 * 1024))
                require(body.owner.session == session.id && body.owner.allocation == session.allocation)
                require(body.keys.size in 1..BenchSelections.MAX_KEYS && body.keys.all { it.matches(SHA256) })
                call.respond(store.execution.benchSelections(body.owner, body.keys.distinct()))
            }
            post("/input-signature") {
                withContext(CoordinatorIo.artifacts) {
                    check(workAllowed()); val session = call.session
                    require(session.executionClass == ExecutionClass.PREPARE)
                    val owner = Json.decodeFromString<AttemptOwner>(call.request.headers["X-Lab-Attempt"] ?: throw ExecutionFenced("attempt required"))
                    require(owner.session == session.id && owner.allocation == session.allocation); store.execution.authorize(owner)
                    val signature = Json.decodeFromString<InputSignature>(call.bodyText(8192))
                    require(signature.method == "PUT" && signature.sha256?.matches(SHA256) == true)
                    require(signature.key == "input-blobs/${signature.sha256}" || signature.key == "${BuildArchives.PREFIX}/${signature.sha256}" ||
                        signature.key.matches(Regex("corpus-packs/[A-Za-z0-9][A-Za-z0-9._-]*\\.tar")))
                    val signer = inputSigner ?: throw IllegalStateException("no shared input signer is configured")
                    call.respond(signInput(signer, signature))
                }
            }
            post("/commit") {
                withContext(CoordinatorIo.artifacts) {
                    val s = call.session; val body = Json.decodeFromString<WorkerCommit>(call.bodyText(if (s.executionClass == ExecutionClass.PREPARE) 64 * 1024 * 1024 else 4 * 1024 * 1024)); s.requireOwns(body.owner)
                    backend.commit(body.owner, body.result); call.respond(HttpStatusCode.NoContent)
                }
            }
            post("/commit-inline") {
                val started = System.nanoTime()
                withContext(CoordinatorIo.results) {
                    val dispatched = System.nanoTime()
                    check(workAllowed()); val session = call.session
                    val body = Json.decodeFromString<WorkerInlineCommit>(call.bodyText())
                    val received = System.nanoTime()
                    require(body.owner.session == session.id && body.owner.allocation == session.allocation)
                    store.execution.authorizeCommit(body.owner, body.result)
                    val authorized = System.nanoTime()
                    artifacts.putAll(session.jobId, body.decoded())
                    val written = System.nanoTime()
                    backend.commit(body.owner, body.result)
                    val accepted = System.nanoTime()
                    if (accepted - started > 250_000_000) System.err.println("coordinator commit timing: job=${session.jobId} queueMs=${(dispatched-started)/1_000_000} receiveMs=${(received-dispatched)/1_000_000} authorizeMs=${(authorized-received)/1_000_000} writeMs=${(written-authorized)/1_000_000} acceptMs=${(accepted-written)/1_000_000}")
                    call.respond(HttpStatusCode.NoContent)
                }
            }
            post("/commit-packed") {
                if (artifacts !is ResultJournal) { call.respond(HttpStatusCode.NotFound); return@post }
                withContext(CoordinatorIo.packedResults) {
                    check(workAllowed()); val session = call.session
                    call.receiveChannel().toInputStream().use { input ->
                        val body = WorkerPackedCommit.read(input)
                        require(body.owner.session == session.id && body.owner.allocation == session.allocation)
                        store.execution.authorizeCommit(body.owner, body.result)
                        artifacts.putPacked(session.jobId, body.blobs, input)
                        backend.commit(body.owner, body.result)
                    }
                    call.respond(HttpStatusCode.NoContent)
                }
            }
            get("/inputs/{sha}") {
                withContext(CoordinatorIo.artifacts) {
                    check(workAllowed()); val s = call.session; val sha = call.parameters["sha"]!!
                    val manifest = store.execution.manifest(s.id)
                    val bundle = requireNotNull((manifest.bundles + listOfNotNull(manifest.inputCorpus)).firstOrNull { it.sha256 == sha })
                    val exported = if (bundle.tree == null) null else requireNotNull(NativePreparedTrees.environment()).archive(bundle) { check(workAllowed()) }
                    val range = call.request.headers[HttpHeaders.Range] ?: "bytes=0-"
                    val match = requireNotNull(Regex("bytes=([0-9]+)-([0-9]*)").matchEntire(range))
                    val from = match.groupValues[1].toLong()
                    val total = exported?.let(java.nio.file.Files::size) ?: artifacts.size(s.jobId, sha); require(from in 0 until total)
                    val requestedEnd = match.groupValues[2].toLongOrNull() ?: total - 1
                    val end = minOf(requestedEnd, total - 1, from + 8L * 1024 * 1024 - 1); require(end >= from)
                    call.response.status(HttpStatusCode.PartialContent)
                    call.response.header(HttpHeaders.ContentRange, "bytes $from-$end/$total")
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    call.respondOutputStream(ContentType.Application.OctetStream) {
                        withContext(CoordinatorIo.artifacts) {
                            (exported?.let { java.nio.file.Files.newInputStream(it) } ?: artifacts.open(s.jobId, sha, verify = false)).use { input ->
                                input.skipNBytes(from); var left = end - from + 1; val buffer = ByteArray(64 * 1024)
                                while (left > 0) { val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt()); check(n > 0); write(buffer, 0, n); left -= n }
                            }
                        }
                    }
                }
            }
            get("/uploads/{sha}") {
                withContext(CoordinatorIo.artifacts) {
                    check(workAllowed()); val s = call.session; val owner = call.request.headers["X-Lab-Attempt"]?.let { Json.decodeFromString<AttemptOwner>(it) } ?: throw ExecutionFenced("attempt required")
                    s.requireOwns(owner); store.execution.authorize(owner)
                    val sha = call.parameters["sha"]!!
                    val reused = if (call.request.queryParameters["reuse"] == "true") artifacts.reuse(s.jobId, sha) else null
                    call.respond(UploadReply(reused ?: artifacts.offset(s.jobId, sha)))
                }
            }
            put("/uploads/{sha}") {
                withContext(CoordinatorIo.artifacts) {
                    check(workAllowed()); val s = call.session; val owner = Json.decodeFromString<AttemptOwner>(call.request.headers["X-Lab-Attempt"] ?: throw ExecutionFenced("attempt required"))
                    s.requireOwns(owner); store.execution.authorize(owner)
                    val bytes = call.request.queryParameters["bytes"]!!.toLong(); val offset = call.request.queryParameters["offset"]!!.toLong()
                    val result = call.receiveChannel().toInputStream().use { artifacts.put(s.jobId, call.parameters["sha"]!!, bytes, offset, it) }
                    if (result == bytes && call.request.queryParameters["reuse"] == "true") artifacts.remember(s.jobId, call.parameters["sha"]!!)
                    call.respond(UploadReply(result))
                }
            }
        }
    }
}

/** The most a registration's body, a release name, may be. */
private const val REGISTRATION_BYTES = 8 * 1024

private val SESSION = AttributeKey<ExecutionSession>("WorkerSession")

/** The session [WorkerAuth] authenticated this call as. */
private val ApplicationCall.session: ExecutionSession get() = attributes[SESSION]

private fun ApplicationCall.credentials() = WorkerCredentials(parameters["id"]!!,
    request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ") ?: throw ExecutionFenced("credential required"),
    request.headers["X-Lab-Allocation"] ?: throw ExecutionFenced("allocation required"),
    request.headers["X-Lab-Identity"] ?: throw ExecutionFenced("workload identity required"))

private class WorkerAuthConfig {
    lateinit var backend: NomadBackend
}

/**
 * Authenticates every call under a worker session's route before its handler runs, and keeps the session for it
 * ([session]): a route added there is authenticated whether or not it asks. Registration alone is exempt, by name: it
 * presents the credentials the session is then bound to.
 */
private val WorkerAuth = createRouteScopedPlugin("WorkerAuth", ::WorkerAuthConfig) {
    val backend = pluginConfig.backend
    onCall { call ->
        if (call.request.path().endsWith("/register")) return@onCall
        val c = call.credentials()
        call.attributes.put(SESSION, withContext(CoordinatorIo.control) { backend.authenticate(c.session, c.token, c.allocation, c.identity) })
    }
}

/** What a worker presents on every call: its session and bearer token, its Nomad allocation and workload identity. */
internal data class WorkerCredentials(val session: String, val token: String, val allocation: String, val identity: String) {
    override fun toString() = "WorkerCredentials(session=$session, allocation=$allocation)"
}

/** Refuse [owner] unless it is an attempt of this session on its bound allocation. */
internal fun ExecutionSession.requireOwns(owner: AttemptOwner) =
    require(owner.session == id && owner.allocation == allocation) { "attempt owner does not match the session" }
