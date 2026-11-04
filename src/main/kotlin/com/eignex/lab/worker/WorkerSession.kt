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
import java.util.concurrent.atomic.AtomicReference

/** A bounded experiment session. Infrastructure failures exit nonzero; ordinary solver outcomes are committed. */
class WorkerSession(private val api: URI, private val id: String, private val token: String, private val allocation: String,
                    private val identityFile: Path, private val release: String, private val root: Path,
                    private val supervisorSocket: Path, private val launcher: String, private val privateTransport: Boolean = false,
                    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(),
                    drainFile: Path? = System.getenv("LAB_WORKER_DRAIN_FILE")?.let(Path::of)) {
    init { require(api.scheme == "https" || (api.scheme == "http" && (privateTransport || api.host in setOf("127.0.0.1", "localhost", "::1")))); require(api.userInfo == null && api.rawQuery == null) }
    private val runDeadline = AtomicLong(Long.MAX_VALUE)
    private val retryLeaseDeadline = AtomicLong(Long.MAX_VALUE)
    private val retries = WorkerRetry()
    private val interruption = WorkerInterruption(drainFile)
    @Volatile internal var preparationRecorder: PreparationRecorder? = null
    private fun leaseRemainingMs(): Long = minOf(interruption.remainingMs(), retryLeaseDeadline.get().takeIf { it != Long.MAX_VALUE }
        ?.let { (it - System.nanoTime()) / 1_000_000 } ?: Long.MAX_VALUE)
    private fun runRemainingMs(): Long? = runDeadline.get().takeIf { it != Long.MAX_VALUE }?.let { (it - System.nanoTime()) / 1_000_000 }
    private fun checkRun() { if (runRemainingMs()?.let { it <= 0 } == true) throw RunDeadlineExceeded() }
    private fun request(path: String, method: String = "GET", body: HttpRequest.BodyPublisher = HttpRequest.BodyPublishers.noBody(), owner: AttemptOwner? = null, range: Long = 0): HttpResponse<ByteArray> {
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
        if (path in setOf("commit-inline", "commit-packed", "commit-packed-large", "uploads-packed") && response.statusCode() in setOf(404, 405)) return response
        if (response.statusCode() in setOf(408, 429, 500, 502, 503, 504)) throw WorkerUnavailable(response.statusCode())
        check(response.statusCode() in 200..299) { "worker API HTTP ${response.statusCode()}" }
        return response
    }
    private fun json(path: String, body: String, owner: AttemptOwner? = null) = request(path, "POST", HttpRequest.BodyPublishers.ofString(body), owner)
    private fun <T> retry(ignoreRunDeadline: Boolean = false, operation: () -> T): T = retries.run({
        minOf(leaseRemainingMs(),
            if (ignoreRunDeadline) Long.MAX_VALUE else runRemainingMs() ?: Long.MAX_VALUE)
    }, operation)
    private fun <T> publicationRetry(operation: () -> T): T = retries.runWhileOwned({
        minOf(leaseRemainingMs(), runRemainingMs() ?: Long.MAX_VALUE)
    }, operation)
    /** Appends what [console] holds past the last accepted offset to the job's files, a chunk at a time. */
    private class ConsoleUpload(val console: WorkerConsole) { var offset = 0L; var unsupported = false }

    private fun uploadConsole(upload: ConsoleUpload) = synchronized(upload) {
        if (upload.unsupported) return@synchronized
        System.out.flush()
        try {
            while (upload.offset < WorkerLogs.MAX_WORKER_BYTES) {
                val bytes = java.io.RandomAccessFile(upload.console.file.toFile(), "r").use { input ->
                    val count = minOf((input.length() - upload.offset).coerceAtLeast(0), WorkerLogs.CHUNK_BYTES.toLong()).toInt()
                    input.seek(upload.offset); ByteArray(count).also { input.readFully(it) }
                }
                if (bytes.isEmpty()) break
                val response = request("worker-log?offset=${upload.offset}", "PUT", HttpRequest.BodyPublishers.ofByteArray(bytes))
                val accepted = Json.decodeFromString<UploadReply>(String(response.body())).offset
                require(accepted in upload.offset..upload.offset + bytes.size)
                if (accepted == upload.offset) break
                upload.offset = accepted
            }
        } catch (failure: IllegalStateException) {
            // A coordinator without the route answers 404: stop asking rather than retry every interval.
            if (failure.message?.endsWith("HTTP 404") == true) upload.unsupported = true
        } catch (_: Exception) {
            // Diagnostics only; the next interval resumes from the last accepted offset.
        }
    }

    /** The failure [run] already printed to the uploaded console, which [main] need not print again. */
    @Volatile private var reported: Throwable? = null

    fun run() {
        Files.createDirectories(root)
        val console = ConsoleUpload(WorkerConsole(root.resolve("worker-console.log"), WorkerLogs.MAX_WORKER_BYTES))
        val consoleUploads = Executors.newSingleThreadScheduledExecutor { Thread(it, "worker-console").apply { isDaemon = true } }
        val consoleHook = Thread({ uploadConsole(console) }, "worker-console-final")
        Runtime.getRuntime().addShutdownHook(consoleHook)
        try {
            consoleUploads.scheduleWithFixedDelay({ uploadConsole(console) }, CONSOLE_UPLOAD_SEC, CONSOLE_UPLOAD_SEC, TimeUnit.SECONDS)
            runSession()
        } catch (failure: Throwable) {
            // Printed here, inside the uploaded console, so the job's worker log ends with why the session ended.
            printFailure(failure)
            reported = failure
            throw failure
        } finally {
            consoleUploads.shutdownNow()
            uploadConsole(console)
            runCatching { Runtime.getRuntime().removeShutdownHook(consoleHook) }
            console.console.close()
        }
    }

    private fun runSession() {
        val manifest = retry { Json.decodeFromString<WorkManifest>(String(json("register", Json.encodeToString(WorkerRegistration(release))).body())) }
        require(manifest.session == id && manifest.release == release)
        val observation = ResourceObservation.read(manifest.budget)
        val cacheRoot = System.getenv("LAB_WORKER_CACHE_ROOT")?.let(Path::of) ?: root
        val gradleCache = GradleCacheProxy({ method, key, body ->
            val response = request("gradle-cache/$key", method, body?.let(HttpRequest.BodyPublishers::ofByteArray) ?: HttpRequest.BodyPublishers.noBody())
            response.statusCode() to response.body()
        })
        val builds = WorkerBuildCache(cacheRoot.resolve("builds"), ProcessSupervisor(supervisorSocket),
            observeBuild = { stdout, stderr, exit -> preparationRecorder?.build(stdout, stderr, exit) }, remoteCache = { gradleCache.url })
        Thread({
            try { builds.maintain() } catch (_: java.io.IOException) { println("worker build cache cleanup deferred") }
        }, "worker-build-cleanup").apply { isDaemon = true; start() }
        val inputCache = WorkerInputCache(cacheRoot.resolve("inputs"), builds = builds)
        val workspaceStorage = WorkerWorkspaceStorage(cacheRoot.resolve("workspaces"))
        val executor = IterationExecutor(ProcessSupervisor(supervisorSocket), VerifiedCache(root.resolve("cache")),
            { bundle -> download(bundle) }, launcher, if (CliMeasurements.localMacOs) "mac" else "linux", workerArch(),
            builds, inputCache, workspaceStorage.workspaces)
        val nativeOwnership = if (CliMeasurements.localMacOs) ProcessSupervisor(supervisorSocket).reserve(requireNotNull(System.getenv("LAB_WORKER_EXPERIMENT"))) else null
        val resourcesStarted = System.nanoTime()
        val resourceOwner = AtomicReference<AttemptOwner?>()
        fun checkpoint(retirement: String? = null) {
            val owner = resourceOwner.get() ?: return
            runCatching {
                val sample = ResourceCheckpoint((System.nanoTime() - resourcesStarted) / 1_000_000,
                    observation.budget, observation.enforcement, observation.cpuMax, observation.memoryMax,
                    ResourceObservation.counters(), "worker-cumulative-lower-bound", retirement)
                json("resource-checkpoint", Json.encodeToString(WorkerResourceCheckpoint(owner, sample)))
            } // Evidence has a short single-request timeout; it must never block work recovery.
        }
        val resourceReporter = Executors.newSingleThreadScheduledExecutor { Thread(it, "worker-resources").apply { isDaemon = true } }
        val shutdownHook = Thread({ checkpoint(if (interruption.requested()) "provider-interruption" else "worker-shutdown") }, "worker-retirement")
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        resourceReporter.scheduleWithFixedDelay({ checkpoint() }, 0, 5, TimeUnit.SECONDS)
        try {
        var heldSince: Long? = null
        while (true) {
            if (interruption.requested()) return
            val response = retry { json("claim", """{"wait":true}""") }
            if (response.statusCode() == 204) return
            if (response.statusCode() == 202) {
                // Journal pressure: keep this session and its warm workspaces, but not indefinitely.
                val started = heldSince ?: System.nanoTime().also { heldSince = it }
                if (System.nanoTime() - started > TimeUnit.MILLISECONDS.toNanos(MAX_SOLVE_WAIT_MS)) return
                val retryMs = runCatching { Json.decodeFromString<SolveWait>(String(response.body())).retryMs }.getOrDefault(5_000L)
                Thread.sleep(retryMs.coerceIn(1_000L, 30_000L))
                continue
            }
            heldSince = null
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
            resourceOwner.set(claim.owner)
            checkpoint()
            val heartbeat = Executors.newSingleThreadScheduledExecutor { Thread(it, "worker-lease").apply { isDaemon = true } }
            heartbeat.scheduleWithFixedDelay({
                try { renew() } catch (_: ExecutionFenced) { deadline.set(0) } catch (_: Exception) { /* Existing monotonic deadline remains authoritative. */ }
            }, 10, 10, TimeUnit.SECONDS)
            fun remaining() = minOf(interruption.remainingMs(), (deadline.get() - System.nanoTime()) / 1_000_000)
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
                                SharedInputs.environment(publishSignature = ::signature), relay.address, inputCache, workspaceStorage.workspaces, trace, archives = WorkerArchiveCache(cacheRoot.resolve("archives")),
                                nativeTrees = NativePreparedTrees.environment(), nativeJob = System.getenv("LAB_WORKER_EXPERIMENT")?.toLong())
                                .run(manifest, directory, ::runRemainingMs, ::remaining, selectProblems = { selected ->
                                    retry { Json.decodeFromString<List<List<Problem>>>(String(json("selection", Json.encodeToString(WorkerSelection(claim.owner, selected))).body())) }
                                }, cachedSelections = { keys ->
                                    retry { Json.decodeFromString<List<BenchSelectionEntry>>(String(json("bench-selections", Json.encodeToString(WorkerBenchSelections(claim.owner, keys))).body())) }
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
                if (interruption.requested()) return
                retry { json("build-failed", Json.encodeToString(WorkerBuildError(claim.owner, failure.detail))) }
                return
            } finally { logs.close(); deadline.set(0); heartbeat.shutdownNow(); retryLeaseDeadline.set(Long.MAX_VALUE) }
        }
        } catch (failure: Exception) {
            // A known provider withdrawal is routine replacement, never a deterministic build poison pill.
            if (!interruption.requested()) throw failure
        } finally {
            resourceReporter.shutdownNow()
            checkpoint(if (interruption.requested()) "provider-interruption" else null)
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            try { nativeOwnership?.close() } finally { try { workspaceStorage.close() } finally { gradleCache.close() } }
        }
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
        }, 0, LIVE_LOG_INTERVAL_SEC, TimeUnit.SECONDS)
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
        val regional = regionalBuilds?.takeIf { bundle.build == null && bundle.tree == null } ?: return null
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
        val outputs = result.artifacts.toMutableMap()
        val sources = linkedMapOf<String, Path>()
        val hashes = mutableMapOf<Path, String>()
        if (result.preparation == null) for ((name, file) in files) {
            val sha = hashes.getOrPut(file) { hashFile(file) }; outputs[name] = sha; sources.putIfAbsent(sha, file)
        }
        val uniqueBytes = sources.values.sumOf(Files::size)
        // Transport limits count distinct blobs, not all the artifact names that may refer to the same bytes.
        if (result.preparation == null && sources.size <= INLINE_FILES && uniqueBytes <= INLINE_BYTES) {
            val blobs = sources.mapValues { Base64.getEncoder().encodeToString(Files.readAllBytes(it.value)) }
            val body = Json.encodeToString(WorkerInlineCommit(owner, result.copy(artifacts = outputs), blobs))
            // File bytes alone do not bound the request: full assignment records can exceed them by megabytes.
            if (body.toByteArray().size <= PACKED_HEADER_BYTES) {
                val reply = publicationRetry { json("commit-inline", body) }
                if (reply.statusCode() in 200..299) return "inline"
            }
        }
        if (result.preparation == null && sources.size in 1..INLINE_FILES && uniqueBytes <= PACKED_BYTES) {
            val manifest = WorkerPackedCommit(owner, result.copy(artifacts = outputs), sources.map { ResultBlob(it.key, Files.size(it.value)) })
            manifest.validate()
            val header = Json.encodeToString(manifest).toByteArray()
            require(header.size <= LARGE_RESULT_BYTES) { "result manifest exceeds the 64 MiB transport limit" }
            val path = if (header.size <= PACKED_HEADER_BYTES) "commit-packed" else "commit-packed-large"
            val reply = publicationRetry { request(path, "POST", packedBody(header, sources)) }
            if (reply.statusCode() in 200..299) return "packed"
        }
        var mode = "resumable"
        if (result.preparation == null) {
            val remaining = sources.toMutableMap()
            val batch = linkedMapOf<String, Path>(); var batchBytes = 0L
            fun flushBatch(): Boolean {
                if (batch.isEmpty()) return true
                val header = Json.encodeToString(WorkerPackedArtifacts(owner,
                    batch.map { ResultBlob(it.key, Files.size(it.value)) })).toByteArray()
                require(header.size <= PACKED_HEADER_BYTES)
                val reply = publicationRetry { request("uploads-packed", "POST", packedBody(header, batch)) }
                if (reply.statusCode() !in 200..299) return false // Older coordinators retain ordinary uploads.
                batch.keys.forEach { remaining.remove(it) }; batch.clear(); batchBytes = 0; mode = "packed-batches"
                return true
            }
            var supported = true
            for ((sha, file) in sources) {
                val size = Files.size(file)
                if (size > PACKED_BYTES) continue // Large individual files retain resumable range uploads.
                if (batch.size == INLINE_FILES || size > PACKED_BYTES - batchBytes) {
                    if (!flushBatch()) { supported = false; break }
                }
                batch[sha] = file; batchBytes += size
            }
            if (supported) flushBatch()
            // Deduplicate fallback requests too, including when the coordinator lacks the packed upload route.
            for ((sha, file) in remaining) uploadVerified(file, owner, sha, Files.size(file), 0, publication = true)
        } else for ((name, file) in files) outputs[name] = upload(file, owner)
        val body = Json.encodeToString(WorkerCommit(owner, result.copy(artifacts = outputs)))
        val bytes = body.toByteArray().size
        require(bytes <= LARGE_RESULT_BYTES) { "result manifest exceeds the 64 MiB transport limit" }
        val path = if (result.preparation == null && bytes > PACKED_HEADER_BYTES) "commit-large" else "commit"
        if (result.preparation == null) publicationRetry { json(path, body) } else retry { json(path, body) }
        return mode
    }

    private fun packedBody(header: ByteArray, sources: Map<String, Path>): HttpRequest.BodyPublisher {
        val prefix = java.nio.ByteBuffer.allocate(4 + header.size).putInt(header.size).put(header).array()
        return HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),
            *sources.values.map { HttpRequest.BodyPublishers.ofFile(it) }.toTypedArray())
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

    internal fun upload(file: Path, owner: AttemptOwner, reusable: Boolean = false, trace: PreparationTrace? = null, publication: Boolean = false): String {
        val verificationStarted = System.nanoTime()
        val sha = hashFile(file); val size = Files.size(file)
        val verificationMs = (System.nanoTime() - verificationStarted) / 1_000_000
        return uploadVerified(file, owner, sha, size, verificationMs, reusable, trace, publication)
    }
    private fun uploadVerified(file: Path, owner: AttemptOwner, sha: String, size: Long, verificationMs: Long,
                               reusable: Boolean = false, trace: PreparationTrace? = null, publication: Boolean = false): String {
        var sent = false
        var resumed = false
        val reuse = if (reusable) "reuse=true&" else ""
        var offset: Long? = null
        fun <T> transfer(operation: () -> T): T = if (publication) publicationRetry(operation) else retry(operation = operation)
        while (true) {
            val complete = transfer {
                try {
                    // Query progress only initially or after an ambiguous response; successful PUTs carry the next offset.
                    val queriedOffset = offset == null
                    val from = offset ?: Json.decodeFromString<UploadReply>(String(request("uploads/$sha" + if (reusable) "?reuse=true" else "", owner = owner).body())).offset
                    require(from in 0..size)
                    if (from == size && size > 0) return@transfer true
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
        /** Longest continuous journal-pressure wait before an idle worker releases its node. */
        internal const val MAX_SOLVE_WAIT_MS = 15 * 60_000L
        /** Provisional output only: each upload is a coordinator write per stream, and accepted output transfers in full. */
        internal const val LIVE_LOG_INTERVAL_SEC = 5L
        /** How often the worker's console is appended to its job's files: one small coordinator write per session. */
        internal const val CONSOLE_UPLOAD_SEC = 10L

        fun main(args: List<String>): Int = runCatching {
            require(args.isEmpty()) { "worker uses allocation environment only" }
            fun env(key: String) = requireNotNull(System.getenv(key)) { "$key is required" }
            val root = Path.of(System.getenv("LAB_WORKER_ROOT") ?: env("NOMAD_TASK_DIR") + "/worker").toAbsolutePath()
            var session: WorkerSession? = null
            runCatching {
                WorkerSession(URI.create(env("LAB_WORKER_API")), env("LAB_WORKER_SESSION"), env("LAB_WORKER_TOKEN"), env("NOMAD_ALLOC_ID"),
                    Path.of(env("LAB_WORKER_IDENTITY_FILE")), env("LAB_WORKER_RELEASE"), root, Path.of(env("LAB_SUPERVISOR_SOCKET")),
                    env("LAB_WORKER_LAUNCHER"), System.getenv("LAB_WORKER_PRIVATE_TRANSPORT") == "true").also { session = it }.run()
            }.onFailure { error -> if (session?.reported !== error) printFailure(error) }.getOrThrow()
            0
        }.getOrElse { error -> workerProcessExit(generateSequence(error) { it.cause }.last()) }

        private fun printFailure(error: Throwable) {
            val cause = generateSequence(error) { it.cause }.last()
            System.err.println("worker: ${cause.javaClass.simpleName}: ${when (cause) { is java.nio.file.NoSuchFileException -> "missing file ${cause.file}"; is ExecutionFenced -> "allocation fenced"; is IllegalStateException -> cause.message?.take(160); else -> "session ended" }}")
            // Frames identify failed runtime checks without logging manifests, environment, or credentials.
            cause.stackTrace.take(12).forEach { frame -> System.err.println("worker: at $frame") }
        }
    }
}
