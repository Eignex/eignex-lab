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
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** A bounded experiment session. Infrastructure failures exit nonzero; ordinary solver outcomes are committed. */
class WorkerSession(private val api: URI, private val id: String, private val token: String, private val allocation: String,
                    private val identityFile: Path, private val release: String, private val root: Path,
                    private val supervisorSocket: Path, private val launcher: String, private val privateTransport: Boolean = false,
                    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()) {
    init { require(api.scheme == "https" || (api.scheme == "http" && (privateTransport || api.host in setOf("127.0.0.1", "localhost", "::1")))); require(api.userInfo == null && api.rawQuery == null) }
    private val runDeadline = AtomicLong(Long.MAX_VALUE)
    private val retryLeaseDeadline = AtomicLong(Long.MAX_VALUE)
    private val retries = WorkerRetry()
    @Volatile internal var preparationRecorder: PreparationRecorder? = null
    private fun leaseRemainingMs(): Long = retryLeaseDeadline.get().takeIf { it != Long.MAX_VALUE }
        ?.let { (it - System.nanoTime()) / 1_000_000 } ?: Long.MAX_VALUE
    private fun runRemainingMs(): Long? = runDeadline.get().takeIf { it != Long.MAX_VALUE }?.let { (it - System.nanoTime()) / 1_000_000 }
    private fun checkRun() { if (runRemainingMs()?.let { it <= 0 } == true) throw RunDeadlineExceeded() }
    private fun request(path: String, method: String = "GET", body: HttpRequest.BodyPublisher = HttpRequest.BodyPublishers.noBody(), owner: AttemptOwner? = null, range: Long = 0): HttpResponse<ByteArray> {
        val started = System.nanoTime()
        val timeout = if (path == "run-timeout") 30_000L else if (path == "preparation-diagnostics") {
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
            .header("Content-Type", if (path == "commit-packed") "application/octet-stream" else "application/json")
        if (owner != null) builder.header("X-Lab-Attempt", Json.encodeToString(owner))
        if (range > 0) builder.header("Range", "bytes=$range-")
        val upload = Regex("uploads/([0-9a-f]{64})(?:\\?(.*))?").matchEntire(path)
        fun record(status: Int?, responseOffset: Long? = null, failure: String? = null) {
            if (upload == null) return
            val offset = Regex("(?:^|&)offset=([0-9]+)(?:&|$)").find(upload.groupValues[2])?.groupValues?.get(1)?.toLongOrNull()
            preparationRecorder?.upload(PreparationUploadDiagnostic(upload.groupValues[1], method, status, offset,
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
        if (path in setOf("commit-inline", "commit-packed") && response.statusCode() in setOf(404, 405)) return response
        if (response.statusCode() in setOf(429, 500, 502, 503, 504)) throw WorkerUnavailable(response.statusCode())
        check(response.statusCode() in 200..299) { "worker API HTTP ${response.statusCode()}" }
        return response
    }
    private fun json(path: String, body: String, owner: AttemptOwner? = null) = request(path, "POST", HttpRequest.BodyPublishers.ofString(body), owner)
    private fun <T> retry(ignoreRunDeadline: Boolean = false, operation: () -> T): T = retries.run({
        minOf(leaseRemainingMs(),
            if (ignoreRunDeadline) Long.MAX_VALUE else runRemainingMs() ?: Long.MAX_VALUE)
    }, operation)
    fun run() {
        Files.createDirectories(root)
        val manifest = retry { Json.decodeFromString<WorkManifest>(String(json("register", Json.encodeToString(WorkerRegistration(release))).body())) }
        require(manifest.session == id && manifest.release == release)
        val observation = ResourceObservation.read(manifest.budget)
        val cacheRoot = System.getenv("LAB_WORKER_CACHE_ROOT")?.let(Path::of) ?: root
        val builds = WorkerBuildCache(cacheRoot.resolve("builds"), ProcessSupervisor(supervisorSocket),
            observeBuild = { stdout, stderr, exit -> preparationRecorder?.build(stdout, stderr, exit) })
        Thread({
            try { builds.maintain() } catch (_: java.io.IOException) { println("worker build cache cleanup deferred") }
        }, "worker-build-cleanup").apply { isDaemon = true; start() }
        val inputCache = WorkerInputCache(cacheRoot.resolve("inputs"), builds = builds)
        val workspaceStorage = WorkerWorkspaceStorage(cacheRoot.resolve("workspaces"))
        val executor = IterationExecutor(ProcessSupervisor(supervisorSocket), VerifiedCache(root.resolve("cache")),
            { bundle -> download(bundle) }, launcher, if (CliMeasurements.localMacOs) "mac" else "linux", workerArch(),
            builds, inputCache, workspaceStorage.workspaces)
        val nativeOwnership = if (CliMeasurements.localMacOs) ProcessSupervisor(supervisorSocket).reserve(requireNotNull(System.getenv("LAB_WORKER_EXPERIMENT"))) else null
        try {
        while (true) {
            if (System.getenv("LAB_WORKER_DRAIN_FILE")?.let { Files.exists(Path.of(it)) } == true) return
            val response = retry { json("claim", "{}") }
            if (response.statusCode() == 204) return
            val claim = Json.decodeFromString<IterationClaim>(String(response.body()))
            val deadline = AtomicLong(0)
            fun renew() {
                val start = System.nanoTime()
                val lease = Json.decodeFromString<LeaseReply>(String(json("renew", Json.encodeToString(claim.owner)).body()))
                require(lease.leaseMs in 10_001..ExecutionStore.LEASE_MS)
                require((lease.runRemainingMs != null) == (Experiments.runTimeoutSec(manifest.experiment) != null)) { "run deadline missing from lease" }
                lease.runRemainingMs?.let { allowance ->
                    val candidate = start + allowance.coerceAtLeast(0) * 1_000_000
                    runDeadline.updateAndGet { minOf(it, candidate) }
                }
                // The lease starts no earlier than request dispatch. Network latency consumes our local allowance.
                deadline.set(start + (lease.leaseMs - 10_000) * 1_000_000)
                retryLeaseDeadline.set(deadline.get())
            }
            retry { renew() }
            val heartbeat = Executors.newSingleThreadScheduledExecutor { Thread(it, "worker-lease").apply { isDaemon = true } }
            heartbeat.scheduleWithFixedDelay({
                try { renew() } catch (_: ExecutionFenced) { deadline.set(0) } catch (_: Exception) { /* Existing monotonic deadline remains authoritative. */ }
            }, 10, 10, TimeUnit.SECONDS)
            fun remaining() = (deadline.get() - System.nanoTime()) / 1_000_000
            fun phase(value: String) { if (remaining() <= 0) throw LeaseLost("attempt lease ended"); retry { json("phase", Json.encodeToString(WorkerPhase(claim.owner, value))) } }
            val directory = root.resolve("attempts/${claim.owner.iteration.replace(':', '-')}-${claim.owner.generation}")
            val logs = streamLogs(claim, directory)
            try {
                val preparationStarted = System.nanoTime()
                var executionStarted = preparationStarted
                phase("preparing")
                val result = if (manifest.preparationTimeoutSec != null) {
                    val trace = PreparationTrace()
                    val recorder = PreparationRecorder(observation, trace)
                    preparationRecorder = recorder
                    val nextDiagnostic = AtomicLong(0)
                    fun reportDiagnostics() { runCatching {
                        json("preparation-diagnostics", Json.encodeToString(WorkerPreparationDiagnostics(claim.owner,
                            recorder.snapshot(leaseRemainingMs(), runRemainingMs()))))
                    } }
                    val reporter = Executors.newSingleThreadScheduledExecutor { Thread(it, "preparation-progress").apply { isDaemon = true } }
                    fun reportPreparation() {
                        runCatching { json("preparation-progress", Json.encodeToString(WorkerPreparationProgress(claim.owner, trace.snapshot()))) }
                        val at = System.nanoTime()
                        val next = nextDiagnostic.get()
                        if (at >= next && nextDiagnostic.compareAndSet(next, at + 5_000_000_000)) reportDiagnostics()
                    }
                    reporter.scheduleWithFixedDelay(::reportPreparation, 0, 1, TimeUnit.SECONDS)
                    try {
                        fun signature(input: InputSignature): SignedInput = retry {
                            val payload = Json.encodeToString(InputSignature.serializer(), input)
                            Json.decodeFromString<SignedInput>(String(request("input-signature", "POST", HttpRequest.BodyPublishers.ofString(payload), owner = claim.owner).body()))
                        }
                        val preparation = InputSigningRelay(::signature).use { relay ->
                            PreparationExecutor(ProcessSupervisor(supervisorSocket), VerifiedCache(root.resolve("cache")),
                                builds, { download(it, trace) }, if (CliMeasurements.localMacOs) "mac" else "linux", workerArch(),
                                SharedInputs.environment(publishSignature = ::signature), relay.address, inputCache, workspaceStorage.workspaces, trace, archives = WorkerArchiveCache(cacheRoot.resolve("archives")))
                                .run(manifest, directory, ::runRemainingMs, ::remaining, selectProblems = { selected ->
                                    retry { Json.decodeFromString<List<List<Problem>>>(String(json("selection", Json.encodeToString(WorkerSelection(claim.owner, selected))).body())) }
                                }) { file ->
                                    val regional = if (file.fileName.toString().endsWith(".zip")) BuildArchives.worker(publishSignature = ::signature) else null
                                    // A store that refuses the archive (not yet granted, say) leaves it to the coordinator.
                                    regional?.let { runCatching { publishBuild(it, file, trace) }
                                        .onFailure { failure -> println("regional build archive refused, using the coordinator: ${failure.message}") }.getOrNull() }
                                        ?: upload(file, claim.owner, reusable = file.fileName.toString().endsWith(".zip"), trace = trace)
                                }
                        }
                        reportPreparation()
                        IterationResult(emptyList(), artifacts = preparation.artifacts, preparation = preparation)
                    } catch (failure: Exception) {
                        recorder.failed(failure)
                        throw failure
                    } finally {
                        reporter.shutdownNow()
                        reportDiagnostics()
                        preparationRecorder = null
                    }
                } else executor.run(manifest.copy(budget = manifest.budget.copy(parallel = claim.parallel)), claim.indices, directory, allocation, claim.owner.generation, { phase("executing"); executionStarted = System.nanoTime() }, { case -> retry { json("progress", Json.encodeToString(WorkerProgress(claim.owner, case.index))) } }, ::runRemainingMs, ::remaining)
                val outputStarted = System.nanoTime()
                phase("uploading")
                val outputs = linkedMapOf<String, Path>()
                for (case in result.cases) {
                    for (suffix in listOf("out", "err", "exit", "termination")) {
                        val name = "${case.index}.$suffix"; val file = directory.resolve(name)
                        if (Files.isRegularFile(file)) outputs[name] = file
                    }
                    val cases = directory.resolve("cases/${case.index}")
                    if (Files.isDirectory(cases)) Files.walk(cases).use { files -> files.filter(Files::isRegularFile).forEach { file ->
                        val name = directory.relativize(file).toString().replace('\\', '/'); outputs[name] = file
                    } }
                }
                val resourceFile = directory.resolve("resource-observation.json")
                Files.writeString(resourceFile, Json.encodeToString(observation.copy(counters = ResourceObservation.counters(),
                    iterationParallel = claim.parallel, iterationCases = claim.indices.size)))
                outputs["nomad/${claim.owner.iteration.replace(':', '-')}-${claim.owner.generation}-resources.json"] = resourceFile
                val mode = commitResult(claim.owner, result, outputs)
                println("worker timing: session=$id iteration=${claim.owner.iteration} preparation=${manifest.preparationTimeoutSec != null} stagingMs=${(executionStarted - preparationStarted) / 1_000_000} executionMs=${(outputStarted - executionStarted) / 1_000_000} outputMs=${(System.nanoTime() - outputStarted) / 1_000_000} outputBytes=${outputs.values.sumOf(Files::size)} mode=$mode")
                // Only the durable acceptance acknowledgement permits removing the private worktree and captures.
                logs.close()
                deleteTree(directory)
                if (result.runTimedOut) return
            } catch (_: RunDeadlineExceeded) {
                retry(ignoreRunDeadline = true) { json("run-timeout", Json.encodeToString(claim.owner)) }
                return
            } catch (failure: WorkerBuildFailure) {
                retry { json("build-failed", Json.encodeToString(WorkerBuildError(claim.owner, failure.detail))) }
                return
            } finally { logs.close(); deadline.set(0); heartbeat.shutdownNow(); retryLeaseDeadline.set(Long.MAX_VALUE) }
        }
        } finally { try { nativeOwnership?.close() } finally { workspaceStorage.close() } }
    }

    private fun streamLogs(claim: IterationClaim, directory: Path): AutoCloseable {
        val offsets = mutableMapOf<String, Long>()
        val service = Executors.newSingleThreadScheduledExecutor { Thread(it, "worker-output").apply { isDaemon = true } }
        service.scheduleWithFixedDelay({
            for (index in claim.indices) for (stream in listOf("out", "err")) {
                if (Thread.currentThread().isInterrupted) return@scheduleWithFixedDelay
                val name = "$index.$stream"; val path = directory.resolve(name)
                val offset = offsets[name] ?: 0
                if (!Files.isRegularFile(path) || offset >= WorkerLogs.MAX_BYTES) continue
                runCatching {
                    val bytes = java.io.RandomAccessFile(path.toFile(), "r").use { input ->
                        val count = minOf((input.length() - offset).coerceAtLeast(0), WorkerLogs.CHUNK_BYTES.toLong()).toInt()
                        input.seek(offset); ByteArray(count).also { input.readFully(it) }
                    }
                    if (bytes.isNotEmpty()) {
                        val response = request("logs/$index/$stream?offset=$offset", "PUT", HttpRequest.BodyPublishers.ofByteArray(bytes), claim.owner)
                        val accepted = Json.decodeFromString<UploadReply>(String(response.body())).offset
                        require(accepted in offset..minOf(offset + bytes.size, WorkerLogs.MAX_BYTES)); offsets[name] = accepted
                    }
                } // Provisional output retries independently; accepted artifacts still transfer in full.
            }
        }, 0, 1, TimeUnit.SECONDS)
        return AutoCloseable { service.shutdownNow() }
    }

    /** Range retries retain only one response chunk. Extraction verifies the entire object and every file as it writes. */
    internal fun download(bundle: WorkerBundle): java.io.InputStream = download(bundle, null)
    private fun download(bundle: WorkerBundle, trace: PreparationTrace?): java.io.InputStream =
        regionalDownload(bundle, trace) ?: coordinatorDownload(bundle, trace)

    private val regionalBuilds: BuildArchives? by lazy { BuildArchives.worker() }

    /** A build archive from the regional store when this cloud worker can reach it; null leaves it to the coordinator. */
    private fun regionalDownload(bundle: WorkerBundle, trace: PreparationTrace?): java.io.InputStream? {
        // Coordinator-published sources carry their build recipe; only built archives go to the regional store.
        val regional = regionalBuilds?.takeIf { bundle.build == null } ?: return null
        Files.createDirectories(root)
        val file = Files.createTempFile(root, ".build-", ".zip")
        val fetched = runCatching {
            checkRun()
            if (regional.size(bundle.sha256) == null) false
            else {
                if (trace != null) trace.step("download") { regional.get(bundle.sha256, file) } else regional.get(bundle.sha256, file)
                true
            }
        }.getOrElse { failure -> println("regional build archive unavailable, using the coordinator: ${failure.message}"); false }
        if (!fetched) { Files.deleteIfExists(file); return null }
        // Extraction verifies the whole archive against [bundle] as it reads it.
        return object : java.io.FilterInputStream(Files.newInputStream(file)) {
            override fun close() { try { super.close() } finally { Files.deleteIfExists(file) } }
        }
    }

    private fun coordinatorDownload(bundle: WorkerBundle, trace: PreparationTrace?): java.io.InputStream = object : java.io.InputStream() {
        private var chunk = java.io.ByteArrayInputStream(byteArrayOf())
        private var offset = 0L
        private var total: Long? = null
        private var closed = false
        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 255 }
        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            java.util.Objects.checkFromIndexSize(start, length, buffer.size)
            check(!closed) { "input stream closed" }; checkRun()
            if (length == 0) return 0
            if (chunk.available() == 0) {
                if (total == offset) return -1
                fun transfer() = retry { request("inputs/${bundle.sha256}", range = offset) }
                val response = trace?.step("download", ::transfer) ?: transfer()
                val range = requireNotNull(response.headers().firstValue("Content-Range").orElse(null))
                val match = requireNotNull(Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(range))
                val from = match.groupValues[1].toLong(); val end = match.groupValues[2].toLong(); val size = match.groupValues[3].toLong()
                require(from == offset && size in 1..32L*1024*1024*1024 && end < size &&
                    end - from + 1 == response.body().size.toLong() && response.body().size in 1..8*1024*1024)
                require(total == null || total == size) { "input size changed between ranges" }
                total = size; offset = end + 1
                chunk = java.io.ByteArrayInputStream(response.body())
            }
            return chunk.read(buffer, start, length)
        }
        override fun close() { closed = true; chunk = java.io.ByteArrayInputStream(byteArrayOf()) }
    }
    internal fun commitResult(owner: AttemptOwner, result: IterationResult, files: Map<String, Path>): String {
        if (result.preparation == null && files.size <= INLINE_FILES && files.values.sumOf(Files::size) <= INLINE_BYTES) {
            val blobs = linkedMapOf<String, String>()
            val outputs = result.artifacts.toMutableMap()
            for ((name, file) in files) {
                val bytes = Files.readAllBytes(file)
                val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).hex()
                outputs[name] = sha; blobs[sha] = Base64.getEncoder().encodeToString(bytes)
            }
            val reply = retry { json("commit-inline", Json.encodeToString(WorkerInlineCommit(owner, result.copy(artifacts = outputs), blobs))) }
            if (reply.statusCode() in 200..299) return "inline"
        }
        if (result.preparation == null && files.size in 1..INLINE_FILES && files.values.sumOf(Files::size) <= PACKED_BYTES) {
            val outputs = result.artifacts.toMutableMap()
            val sources = linkedMapOf<String, Path>()
            for ((name, file) in files) { val sha = hashFile(file); outputs[name] = sha; sources.putIfAbsent(sha, file) }
            val manifest = WorkerPackedCommit(owner, result.copy(artifacts = outputs), sources.map { ResultBlob(it.key, Files.size(it.value)) })
            manifest.validate()
            val header = Json.encodeToString(manifest).toByteArray(); require(header.size <= PACKED_HEADER_BYTES)
            val prefix = java.nio.ByteBuffer.allocate(4 + header.size).putInt(header.size).put(header).array()
            val body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),
                *sources.values.map { HttpRequest.BodyPublishers.ofFile(it) }.toTypedArray())
            val reply = retry { request("commit-packed", "POST", body) }
            if (reply.statusCode() in 200..299) return "packed"
        }
        val outputs = result.artifacts.toMutableMap()
        for ((name, file) in files) outputs[name] = upload(file, owner)
        retry { json("commit", Json.encodeToString(WorkerCommit(owner, result.copy(artifacts = outputs)))) }
        return "resumable"
    }

    /** Keep a cloud build archive in the regional store, by content hash, instead of sending it to the coordinator. */
    private fun publishBuild(regional: BuildArchives, file: Path, trace: PreparationTrace): String {
        val verificationStarted = System.nanoTime()
        val sha = hashFile(file); val size = Files.size(file)
        val verificationMs = (System.nanoTime() - verificationStarted) / 1_000_000
        val present = retry { regional.size(sha) } == size
        if (!present) retry { regional.put(file, sha) }
        check(retry { regional.size(sha) } == size) { "regional build archive unavailable after publication" }
        trace.cache("upload", if (present) "hit" else "miss", if (present) "regional-present" else "regional-absent",
            verificationMs = verificationMs, bytes = size, checkedFiles = 1, hashedFiles = 1, hashedBytes = size)
        return sha
    }

    internal fun upload(file: Path, owner: AttemptOwner, reusable: Boolean = false, trace: PreparationTrace? = null): String {
        val verificationStarted = System.nanoTime()
        val sha = hashFile(file); val size = Files.size(file)
        val verificationMs = (System.nanoTime() - verificationStarted) / 1_000_000
        var sent = false
        var resumed = false
        val reuse = if (reusable) "reuse=true&" else ""
        var offset: Long? = null
        while (true) {
            val complete = retry {
                try {
                    // Query progress only initially or after an ambiguous response; successful PUTs carry the next offset.
                    val queriedOffset = offset == null
                    val from = offset ?: Json.decodeFromString<UploadReply>(String(request("uploads/$sha" + if (reusable) "?reuse=true" else "", owner = owner).body())).offset
                    require(from in 0..size)
                    if (from == size && size > 0) return@retry true
                    if (queriedOffset && from > 0) resumed = true
                    val count = minOf(size - from, 8L * 1024 * 1024).toInt()
                    val bytes = Files.newInputStream(file).use { input -> input.skipNBytes(from); input.readNBytes(count) }
                    sent = true
                    val reply = request("uploads/$sha?${reuse}bytes=$size&offset=$from", "PUT", HttpRequest.BodyPublishers.ofByteArray(bytes), owner)
                    val next = Json.decodeFromString<UploadReply>(String(reply.body())).offset
                    require(next in from..size && (next > from || size == 0L))
                    offset = next
                    next == size
                } catch (failure: Exception) { offset = null; throw failure }
            }
            if (complete) {
                if (reusable) trace?.cache("upload", if (sent) "miss" else "hit",
                    if (!sent) "coordinator-present" else if (resumed) "partial-upload" else "absent", verificationMs = verificationMs, bytes = size, checkedFiles = 1, hashedFiles = 1, hashedBytes = size)
                return sha
            }
        }
    }

    companion object {
        fun main(args: List<String>): Int = runCatching {
            require(args.isEmpty()) { "worker uses allocation environment only" }
            fun env(key: String) = requireNotNull(System.getenv(key)) { "$key is required" }
            val root = Path.of(System.getenv("LAB_WORKER_ROOT") ?: env("NOMAD_TASK_DIR") + "/worker").toAbsolutePath()
            WorkerSession(URI.create(env("LAB_WORKER_API")), env("LAB_WORKER_SESSION"), env("LAB_WORKER_TOKEN"), env("NOMAD_ALLOC_ID"),
                Path.of(env("LAB_WORKER_IDENTITY_FILE")), env("LAB_WORKER_RELEASE"), root, Path.of(env("LAB_SUPERVISOR_SOCKET")),
                env("LAB_WORKER_LAUNCHER"), System.getenv("LAB_WORKER_PRIVATE_TRANSPORT") == "true").run()
            0
        }.getOrElse { error ->
            val cause = generateSequence(error) { it.cause }.last()
            System.err.println("worker: ${cause.javaClass.simpleName}: ${when (cause) { is java.nio.file.NoSuchFileException -> "missing file ${cause.file}"; is ExecutionFenced -> "allocation fenced"; is IllegalStateException -> cause.message?.take(160); else -> "session ended" }}")
            // Frames identify failed runtime checks without logging manifests, environment, or credentials.
            cause.stackTrace.take(12).forEach { frame -> System.err.println("worker: at $frame") }
            workerProcessExit(cause)
        }
    }
}
