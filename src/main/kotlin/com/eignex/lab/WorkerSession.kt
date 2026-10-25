package com.eignex.lab

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Duration
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
    private fun runRemainingMs(): Long? = runDeadline.get().takeIf { it != Long.MAX_VALUE }?.let { (it - System.nanoTime()) / 1_000_000 }
    private fun checkRun() { if (runRemainingMs()?.let { it <= 0 } == true) throw ProfileRunTimeout() }
    private fun request(path: String, method: String = "GET", body: HttpRequest.BodyPublisher = HttpRequest.BodyPublishers.noBody(), owner: AttemptOwner? = null, range: Long = 0): HttpResponse<ByteArray> {
        val timeout = if (path == "run-timeout") 30_000L else { checkRun(); minOf(30_000, runRemainingMs() ?: 30_000).coerceAtLeast(1) }
        val builder = HttpRequest.newBuilder(URI.create(api.toString().trimEnd('/') + "/worker/v1/sessions/${NomadClient.encode(id)}/$path"))
            .timeout(Duration.ofMillis(timeout)).header("Authorization", "Bearer $token").header("X-Lab-Allocation", allocation)
            .header("X-Lab-Identity", Files.readString(identityFile).trim()).header("Content-Type", "application/json")
        if (owner != null) builder.header("X-Lab-Attempt", Json.encodeToString(owner))
        if (range > 0) builder.header("Range", "bytes=$range-")
        val response = client.send(builder.method(method, body).build(), HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() in setOf(401, 403, 409)) throw ExecutionFenced("worker allocation fenced")
        check(response.statusCode() in 200..299) { "worker API HTTP ${response.statusCode()}" }
        return response
    }
    private fun json(path: String, body: String, owner: AttemptOwner? = null) = request(path, "POST", HttpRequest.BodyPublishers.ofString(body), owner)
    private fun <T> retry(operation: () -> T): T {
        var failure: Exception? = null
        repeat(3) { attempt ->
            try { return operation() } catch (fenced: ExecutionFenced) { throw fenced } catch (timeout: ProfileRunTimeout) { throw timeout } catch (e: Exception) {
                if (e is InterruptedException) throw e
                failure = e; if (attempt < 2) Thread.sleep((attempt + 1L) * 250)
            }
        }
        throw failure!!
    }
    fun run() {
        Files.createDirectories(root)
        val manifest = retry { Json.decodeFromString<WorkManifest>(String(json("register", Json.encodeToString(WorkerRegistration(release))).body())) }
        require(manifest.session == id && manifest.release == release)
        val observation = ResourceObservation.read(manifest.budget)
        val executor = IterationExecutor(ProcessSupervisor(supervisorSocket), VerifiedCache(root.resolve("cache")),
            { bundle -> download(bundle) }, launcher, if (CliMeasurements.localMacOs) "mac" else "linux", workerArch(),
            WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(supervisorSocket)))
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
                require((lease.runRemainingMs != null) == (manifest.experiment.profile?.runTimeoutSec != null)) { "profile deadline missing from lease" }
                lease.runRemainingMs?.let { allowance ->
                    val candidate = start + allowance.coerceAtLeast(0) * 1_000_000
                    runDeadline.updateAndGet { minOf(it, candidate) }
                }
                // The lease starts no earlier than request dispatch. Network latency consumes our local allowance.
                deadline.set(start + (lease.leaseMs - 10_000) * 1_000_000)
            }
            renew()
            val heartbeat = Executors.newSingleThreadScheduledExecutor { Thread(it, "worker-lease").apply { isDaemon = true } }
            heartbeat.scheduleWithFixedDelay({
                try { renew() } catch (_: ExecutionFenced) { deadline.set(0) } catch (_: Exception) { /* Existing monotonic deadline remains authoritative. */ }
            }, 10, 10, TimeUnit.SECONDS)
            fun remaining() = (deadline.get() - System.nanoTime()) / 1_000_000
            fun phase(value: String) { check(remaining() > 0); retry { json("phase", Json.encodeToString(WorkerPhase(claim.owner, value))) } }
            val directory = root.resolve("attempts/${claim.owner.iteration.replace(':', '-')}-${claim.owner.generation}")
            val logs = streamLogs(claim, directory)
            try {
                phase("preparing")
                val result = if (manifest.preparationTimeoutSec != null) {
                    val preparation = PreparationExecutor(ProcessSupervisor(supervisorSocket), VerifiedCache(root.resolve("cache")),
                        WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(supervisorSocket)), ::download,
                        if (CliMeasurements.localMacOs) "mac" else "linux", workerArch())
                        .run(manifest, directory, ::runRemainingMs, ::remaining, selectProblems = { selected ->
                            retry { Json.decodeFromString<List<List<Problem>>>(String(json("selection", Json.encodeToString(WorkerSelection(claim.owner, selected))).body())) }
                        }) { file -> upload(file, claim.owner) }
                    IterationResult(emptyList(), artifacts = preparation.artifacts, preparation = preparation)
                } else executor.run(manifest.copy(budget = manifest.budget.copy(parallel = claim.parallel)), claim.indices, directory, allocation, claim.owner.generation, { phase("executing") }, { case -> retry { json("progress", Json.encodeToString(WorkerProgress(claim.owner, case.index))) } }, ::runRemainingMs, ::remaining)
                phase("uploading")
                val outputs = result.artifacts.toMutableMap()
                for (case in result.cases) {
                    for (suffix in listOf("out", "err", "exit", "termination")) {
                        val name = "${case.index}.$suffix"; val file = directory.resolve(name)
                        if (Files.isRegularFile(file)) outputs[name] = upload(file, claim.owner)
                    }
                    val cases = directory.resolve("cases/${case.index}")
                    if (Files.isDirectory(cases)) Files.walk(cases).use { files -> files.filter(Files::isRegularFile).forEach { file ->
                        val name = directory.relativize(file).toString().replace('\\', '/'); outputs[name] = upload(file, claim.owner)
                    } }
                }
                val resourceFile = directory.resolve("resource-observation.json")
                Files.writeString(resourceFile, Json.encodeToString(observation.copy(counters = ResourceObservation.counters())))
                outputs["nomad/${claim.owner.iteration.replace(':', '-')}-${claim.owner.generation}-resources.json"] = upload(resourceFile, claim.owner)
                retry { json("commit", Json.encodeToString(WorkerCommit(claim.owner, result.copy(artifacts = outputs)))) }
                // Only the durable acceptance acknowledgement permits removing the private worktree and captures.
                logs.close()
                deleteTree(directory)
                if (result.runTimedOut) return
            } catch (_: ProfileRunTimeout) {
                retry { json("run-timeout", Json.encodeToString(claim.owner)) }
                return
            } catch (failure: WorkerBuildFailure) {
                retry { json("build-failed", Json.encodeToString(WorkerBuildError(claim.owner, failure.detail))) }
                return
            } finally { logs.close(); deadline.set(0); heartbeat.shutdownNow() }
        }
        } finally { nativeOwnership?.close() }
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

    private fun download(bundle: WorkerBundle): java.io.InputStream {
        val directory = Files.createDirectories(root.resolve("downloads")); val file = directory.resolve("${bundle.sha256}.zip")
        if (Files.exists(file) && hashFile(file) == bundle.sha256) return Files.newInputStream(file)
        retry {
            while (true) {
                checkRun()
                val offset = if (Files.exists(file)) Files.size(file) else 0
                val response = retry { request("inputs/${bundle.sha256}", range = offset) }
                val range = requireNotNull(response.headers().firstValue("Content-Range").orElse(null))
                val match = requireNotNull(Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(range))
                val from = match.groupValues[1].toLong(); val end = match.groupValues[2].toLong(); val total = match.groupValues[3].toLong()
                require(from == offset && total in 1..32L*1024*1024*1024 && end < total && end - from + 1 == response.body().size.toLong())
                Files.write(file, response.body(), CREATE, APPEND)
                if (end + 1 == total) break
            }
            if (hashFile(file) != bundle.sha256) { Files.delete(file); error("input checksum mismatch") }
        }
        return Files.newInputStream(file)
    }
    internal fun upload(file: Path, owner: AttemptOwner): String {
        val sha = hashFile(file); val size = Files.size(file)
        while (true) {
            val complete = retry {
                // Re-read durable progress after every ambiguous PUT, including a partially received chunk.
                val offset = Json.decodeFromString<UploadReply>(String(request("uploads/$sha", owner = owner).body())).offset
                require(offset in 0..size)
                if (offset == size && size > 0) return@retry true
                val count = minOf(size - offset, 8L * 1024 * 1024).toInt()
                val bytes = Files.newInputStream(file).use { input -> input.skipNBytes(offset); input.readNBytes(count) }
                request("uploads/$sha?bytes=$size&offset=$offset", "PUT", HttpRequest.BodyPublishers.ofByteArray(bytes), owner)
                size == 0L
            }
            if (complete) return sha
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
            1
        }
    }
}
