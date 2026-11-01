package com.eignex.lab

import com.eignex.lab.util.*
import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.execution.*
import com.eignex.lab.worker.*
import com.eignex.lab.nomad.*
import com.eignex.lab.web.*
import com.eignex.lab.cli.*

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.netty.Netty
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.time.Duration
import java.net.URI
import java.net.http.HttpClient as JavaHttpClient
import java.net.http.HttpRequest as JavaHttpRequest
import java.net.http.HttpResponse as JavaHttpResponse
import kotlin.test.*

class NomadBackendTest {
    @Test fun `packed results enforce ownership and checksums before durable acceptance and replay`() = testApplication {
        val id = setup(1); register()
        val claim = store.execution.claim(session.id, token, "allocation")!!
        store.execution.phase(claim.owner, "uploading")
        val journal = ResultJournal(root.resolve("packed-journal"), artifacts, start = false)
        val durableBackend = NomadBackend(store, control, journal, { root.resolve("jobs/$it") })
        application { workerApi(store, durableBackend, journal) { true } }
        val bytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        val result = IterationResult(claim.indices.map { ExecutionCaseResult(it, 0, 1, 2) }, mapOf("0.out" to sha))
        suspend fun send(owner: AttemptOwner, content: ByteArray): HttpResponse {
            val header = Json.encodeToString(WorkerPackedCommit(owner, result, listOf(ResultBlob(sha, bytes.size.toLong())))).toByteArray()
            val body = java.nio.ByteBuffer.allocate(4 + header.size + content.size).putInt(header.size).put(header).put(content).array()
            return client.post("/worker/v1/sessions/${session.id}/commit-packed") {
                header(HttpHeaders.Authorization, "Bearer $token"); header("X-Lab-Allocation", "allocation"); header("X-Lab-Identity", jwt())
                setBody(body)
            }
        }
        try {
            assertEquals(HttpStatusCode.Conflict, send(claim.owner.copy(generation = claim.owner.generation + 1), bytes).status)
            assertEquals(HttpStatusCode.BadRequest, send(claim.owner, ByteArray(bytes.size)).status)
            assertEquals(0, journal.status().batches); assertTrue(store.execution.accepted(id).isEmpty())
            assertEquals(HttpStatusCode.NoContent, send(claim.owner, bytes).status)
            assertEquals(HttpStatusCode.NoContent, send(claim.owner, bytes).status)
            assertEquals(1, journal.status().batches); assertEquals(1, store.execution.accepted(id).size)
            durableBackend.reconcile()
            assertEquals(Status.DONE, store.job(id)!!.status)
            assertEquals(sha, hashFile(root.resolve("jobs/$id/0.out")))
            assertFailsWith<IllegalArgumentException> { artifacts.file(id, sha) }
            assertTrue(journal.drainOne()); assertEquals(sha, hashFile(artifacts.file(id, sha)))
        } finally { journal.close() }
    }

    @Test fun `journaled acceptance survives coordinator restart and projects while the external store is unavailable`() = testApplication {
        val id = setup(1); register()
        val claim = store.execution.claim(session.id, token, "allocation")!!
        store.execution.phase(claim.owner, "uploading")
        val offline = object : ArtifactStore by artifacts {
            override fun putAll(job: Long, blobs: Map<String, ByteArray>) { error("external unavailable") }
        }
        val directory = root.resolve("journal")
        val journal = ResultJournal(directory, offline, start = false)
        val durableBackend = NomadBackend(store, control, journal, { root.resolve("jobs/$it") })
        val bytes = "journaled capture".toByteArray(); val sha = ExecutionStore.digest(String(bytes))
        val body = WorkerInlineCommit(claim.owner, IterationResult(claim.indices.map { ExecutionCaseResult(it, 0, 1, 2) }, mapOf("0.out" to sha)),
            mapOf(sha to Base64.getEncoder().encodeToString(bytes)))
        application { workerApi(store, durableBackend, journal) { true } }
        try {
            val response = client.post("/worker/v1/sessions/${session.id}/commit-inline") {
                header(HttpHeaders.Authorization, "Bearer $token"); header("X-Lab-Allocation", "allocation"); header("X-Lab-Identity", jwt())
                setBody(Json.encodeToString(body))
            }
            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals(1, store.execution.accepted(id).size)
            assertFailsWith<IllegalArgumentException> { artifacts.file(id, sha) }
        } finally { journal.close() }
        ResultJournal(directory, artifacts, start = false).use { recovered ->
            val freshStore = Store(root.resolve("lab.db"))
            NomadBackend(freshStore, control, recovered, { root.resolve("jobs/$it") }).reconcile()
            assertEquals(String(bytes), Files.readString(root.resolve("jobs/$id/0.out")))
            assertEquals(Status.DONE, freshStore.job(id)!!.status)
            assertTrue(recovered.drainOne()); assertEquals(0, recovered.status().batches)
            assertEquals(String(bytes), Files.readString(artifacts.file(id, sha)))
        }
    }

    @Test fun `small durable commits remain responsive when all bulk upload workers are blocked`() {
        setup(1); register()
        val claim = store.execution.claim(session.id, token, "allocation")!!
        store.execution.phase(claim.owner, "uploading")
        val entered = CountDownLatch(4); val release = CountDownLatch(1)
        val blocked = object : ArtifactStore by artifacts {
            override fun put(job: Long, sha: String, bytes: Long, offset: Long, input: java.io.InputStream): Long {
                entered.countDown(); check(release.await(10, SECONDS))
                return artifacts.put(job, sha, bytes, offset, input)
            }
        }
        val server = embeddedServer(Netty, configure = {
            connectors.add(EngineConnectorBuilder().apply { host = "127.0.0.1"; port = 0 })
        }) { workerApi(store, backend(), blocked) { true } }.start()
        try {
            val port = kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors().single().port }
            JavaHttpClient.newHttpClient().use { http ->
                fun request(path: String) = JavaHttpRequest.newBuilder(URI("http://127.0.0.1:$port/worker/v1/sessions/${session.id}/$path"))
                    .timeout(Duration.ofSeconds(3)).header("Authorization", "Bearer $token")
                    .header("X-Lab-Allocation", "allocation").header("X-Lab-Identity", jwt())
                val uploads = (0..3).map { index ->
                    val bytes = "bulk-$index".toByteArray(); val sha = ExecutionStore.digest(String(bytes))
                    http.sendAsync(request("uploads/$sha?bytes=${bytes.size}&offset=0").header("X-Lab-Attempt", Json.encodeToString(claim.owner))
                        .PUT(JavaHttpRequest.BodyPublishers.ofByteArray(bytes)).build(), JavaHttpResponse.BodyHandlers.ofString())
                }
                assertTrue(entered.await(2, SECONDS))
                val bytes = "small result".toByteArray(); val sha = ExecutionStore.digest(String(bytes))
                val body = WorkerInlineCommit(claim.owner, IterationResult(claim.indices.map { ExecutionCaseResult(it, 0, 1, 2) }, mapOf("0.out" to sha)),
                    mapOf(sha to Base64.getEncoder().encodeToString(bytes)))
                val response = http.send(request("commit-inline").POST(JavaHttpRequest.BodyPublishers.ofString(Json.encodeToString(body))).build(), JavaHttpResponse.BodyHandlers.ofString())
                assertEquals(204, response.statusCode()); assertTrue(uploads.none { it.isDone })
                release.countDown(); uploads.forEach { assertEquals(200, it.get(3, SECONDS).statusCode()) }
            }
        } finally { release.countDown(); server.stop(0, 1000) }
    }

    @Test fun `cached signing keys preserve token expiry notice rotation and fail closed on expired refresh`() {
        setup()
        var clock = 0L
        val identity = NomadIdentity(control::jwks, monotonicMs = { clock })
        repeat(8) { identity.verify(jwt(), session, "allocation") }
        assertEquals(1, control.keyReads)
        assertFailsWith<IllegalArgumentException> { identity.verify(jwt(expires = now()/1000 - 1), session, "allocation") }
        control.keyId = "rotated"; clock = 1001
        identity.verify(jwt(kid = "rotated"), session, "allocation")
        assertEquals(2, control.keyReads)
        clock = 31_002; control.keyFailure = true
        assertFailsWith<IllegalStateException> { identity.verify(jwt(kid = "rotated"), session, "allocation") }
        control.keyFailure = false
        identity.verify(jwt(kid = "rotated"), session, "allocation")
    }

    @Test fun `small outputs commit durably in one request and lost acknowledgements remain idempotent`() = testApplication {
        val id = setup(1); register()
        val claim = store.execution.claim(session.id, token, "allocation")!!
        store.execution.phase(claim.owner, "uploading")
        val bytes = "inline capture".toByteArray(); val hash = ExecutionStore.digest(String(bytes))
        val result = IterationResult(claim.indices.map { ExecutionCaseResult(it, 0, 1, 2) }, mapOf("0.out" to hash))
        val body = WorkerInlineCommit(claim.owner, result, mapOf(hash to Base64.getEncoder().encodeToString(bytes)))
        application { workerApi(store, backend(), artifacts) { true } }
        suspend fun send(value: WorkerInlineCommit) = client.post("/worker/v1/sessions/${session.id}/commit-inline") {
            header(HttpHeaders.Authorization, "Bearer $token"); header("X-Lab-Allocation", "allocation"); header("X-Lab-Identity", jwt())
            setBody(Json.encodeToString(value))
        }
        repeat(2) { assertEquals(HttpStatusCode.NoContent, send(body).status) }
        assertEquals(String(bytes), Files.readString(artifacts.file(id, hash)))
        assertEquals(1, store.execution.accepted(id).size)
        assertEquals(HttpStatusCode.Conflict, send(body.copy(result = result.copy(cases = result.cases.map { it.copy(exitCode = 1) }))).status)
        backend().reconcile()
        assertEquals(String(bytes), Files.readString(root.resolve("jobs/$id/0.out")))
        assertEquals(Status.DONE, store.job(id)!!.status)
    }

    @Test fun `inline outputs reject corrupt bytes and cancelled ownership before acceptance`() = testApplication {
        val id = setup(1); register()
        val claim = store.execution.claim(session.id, token, "allocation")!!
        store.execution.phase(claim.owner, "uploading")
        val hash = ExecutionStore.digest("expected")
        val body = WorkerInlineCommit(claim.owner, IterationResult(claim.indices.map { ExecutionCaseResult(it, 0, 1, 2) }, mapOf("0.out" to hash)),
            mapOf(hash to Base64.getEncoder().encodeToString("corrupt".toByteArray())))
        application { workerApi(store, backend(), artifacts) { true } }
        suspend fun send() = client.post("/worker/v1/sessions/${session.id}/commit-inline") {
            header(HttpHeaders.Authorization, "Bearer $token"); header("X-Lab-Allocation", "allocation"); header("X-Lab-Identity", jwt())
            setBody(Json.encodeToString(body))
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, send().status)
        assertTrue(store.execution.accepted(id).isEmpty())
        store.requestCancel(id)
        assertEquals(HttpStatusCode.Conflict, send().status)
        assertFailsWith<IllegalArgumentException> { artifacts.file(id, hash) }
    }

    @Test fun `automatic native work yields between iterations after another automatic job is reprioritized`() {
        nativePreemption("ordinary", "urgent")
    }

    @Test fun `local only native work yields between iterations to higher priority automatic work`() {
        nativePreemption("sweep@abc", "urgent")
    }

    @Test fun `local only native work yields between iterations to higher priority local only work`() {
        nativePreemption("sweep@abc", "other-sweep@abc")
    }

    private fun nativePreemption(name: String, waitingName: String) {
        val spec = ExperimentSpec(name, problems = listOf(mapOf("suite" to "s")), grid = mapOf("x" to listOf("a", "b")),
            seeds = listOf(1), host = if ('@' in name) Experiments.LAB_HOST else Experiments.AUTO_HOST)
        val id = setup(spec = spec, platform = NomadPlatform.MAC)
        store.setup(id, "a".repeat(40))
        register()
        val claim = backend().claim(session.id, token, "allocation")!!
        val waiting = store.create(waitingName, "main", emptyList(), priority = -1, experiment = spec.copy(
            name = waitingName, host = if ('@' in waitingName) Experiments.LAB_HOST else Experiments.AUTO_HOST))
        store.setPriority(waiting, 10)

        // Priority edits cannot revoke a running pair or turn a retried claim into new ownership.
        assertEquals(claim, backend().claim(session.id, token, "allocation"))
        store.execution.renew(claim.owner)
        store.execution.phase(claim.owner, "uploading")
        assertTrue(backend().commit(claim.owner, IterationResult(claim.indices.map { ExecutionCaseResult(it, 0, 1, 2) })))
        assertNull(backend().claim(session.id, token, "allocation"))
        assertEquals(Desired.DRAIN, store.execution.session(session.id)!!.desired)
        assertEquals(claim.indices.toSet(), store.job(id)!!.commands.filter { it.status == Status.DONE }.map { it.index }.toSet())
        assertTrue(store.job(id)!!.commands.filter { it.index !in claim.indices }.all { it.status == Status.QUEUED })

        control.allocations["allocation"] = control.allocations.getValue("allocation").copy(client = "complete")
        backend().reconcile()
        assertTrue(store.execution.sessions(id).all { it.desired == Desired.STOP }, "lower-priority work must not immediately replace itself")
        store.setPaused(waiting, true)
        backend().reconcile()
        val replacement = store.execution.sessions(id).single { it.desired == Desired.RUN }
        val replacementToken = Json.parseToJsonElement(store.execution.submission(replacement.id)).jsonObject
            .getValue("TaskGroups").jsonArray[0].jsonObject.getValue("Tasks").jsonArray[0].jsonObject
            .getValue("Env").jsonObject.getValue("LAB_WORKER_TOKEN").jsonPrimitive.content
        store.execution.bind(replacement.id, replacementToken, "resume", "node", "release")
        val resumed = backend().claim(replacement.id, replacementToken, "resume")!!
        assertTrue(resumed.indices.none { it in claim.indices }, "accepted results survive yielding and resumption")
        assertEquals(0, store.execution.failureCount(id))
    }

    @Test fun `paused solve stops its clock after draining and resumes without losing accepted rows`() {
        val spec = ExperimentSpec("paused", profile = ProfileMain(main = "com.eignex.Main", runTimeoutSec = 60,
            classpath = listOf("tools/lib/*")), host = HostPlacement.AUTO)
        val id = setup(problems = 3, spec = spec); register()
        val claim = backend().claim(session.id, token, "allocation")!!
        store.setPaused(id, true)
        backend().reconcile()
        assertEquals(Desired.DRAIN, store.execution.session(session.id)!!.desired)
        assertNotNull(store.job(id)!!.runningSince, "a draining row still consumes its allowance")
        store.execution.phase(claim.owner, "uploading")
        assertTrue(backend().commit(claim.owner, IterationResult(claim.indices.map { ExecutionCaseResult(it, 0, now() - 1, now()) })))
        control.allocations["allocation"] = control.allocations.getValue("allocation").copy(client = "complete")
        backend(Store(file)).reconcile()
        val paused = store.job(id)!!
        assertNull(paused.runningSince)
        assertEquals(paused.runMs, paused.elapsedMs(now() + 120_000))
        store.execution.expireRunDeadlines(now() + 120_000)
        assertEquals(Status.RUNNING, store.job(id)!!.status)
        assertEquals(claim.indices.toSet(), store.job(id)!!.commands.filter { it.status == Status.DONE }.map { it.index }.toSet())

        store.setPaused(id, false)
        backend(Store(file)).reconcile()
        useReplacement(id, "resumed", "node")
        assertNotNull(store.job(id)!!.runningSince)
        assertEquals(paused.runMs, store.job(id)!!.runMs)
        assertTrue(store.execution.remainingRunMs(session.id)!! in 1L..60_000L)
        val resumed = backend().claim(session.id, token, "resumed")!!
        assertTrue(resumed.indices.none { it in claim.indices })
    }

    @Test fun `paused preparation freezes its clock after the worker drains across a restart`() {
        val spec = ExperimentSpec("paused-preparation", bench = BenchSpec("bench.json", runTimeoutSec = 60), host = HostPlacement.AUTO)
        val id = setup(spec = spec, preparation = true); register()
        assertNotNull(backend().claim(session.id, token, "allocation"))
        store.setPaused(id, true)
        backend().reconcile()
        assertNotNull(store.job(id)!!.runningSince)
        control.allocations["allocation"] = control.allocations.getValue("allocation").copy(client = "complete")
        backend(Store(file)).reconcile()
        val paused = store.job(id)!!
        assertNull(paused.runningSince)
        assertEquals(paused.runMs, paused.elapsedMs(now() + 120_000))
        store.execution.expireRunDeadlines(now() + 120_000)
        assertEquals(Status.RUNNING, store.job(id)!!.status)
        assertTrue(store.execution.sessions(id).all { it.desired == Desired.STOP })
        assertEquals(1, store.execution.sessions(id).size)
    }

    @Test fun `reprioritization releases an unbound native reservation`() {
        val id = setup(platform = NomadPlatform.MAC)
        val waiting = store.create("urgent", "main", emptyList(), priority = 10,
            experiment = ExperimentSpec("urgent", problems = listOf(mapOf("suite" to "s"))))
        backend().reconcile()
        assertEquals(Desired.STOP, store.execution.session(session.id)!!.desired)
        assertEquals(0L to 0L, store.execution.nativeReservation())
        assertTrue(store.execution.sessions(waiting).isEmpty())
        assertEquals(0, store.execution.failureCount(id))
    }

    @Test fun `saturated log workers retry the final status after the job leaves the active set`() {
        val id = setup(); register()
        var available = false
        val delayed = NomadBackend(store, control, artifacts, { root.resolve("jobs/$it") },
            writeLog = { _, write -> if (available) { write(); true } else false })
        delayed.reconcile(); store.requestCancel(id); delayed.reconcile()
        assertFalse(Files.exists(root.resolve("jobs/$id/job.log")))
        available = true; delayed.reconcile()
        assertContains(Files.readString(root.resolve("jobs/$id/job.log")), "CANCELLED")
    }

    @Test fun `blocked artifact upload leaves single call thread available for renewals and capacity`() {
        setup(1); backend().reconcile(); register()
        val claim = store.execution.claim(session.id, token, "allocation")!!
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val blocked = object : ArtifactStore by artifacts {
            override fun put(job: Long, sha: String, bytes: Long, offset: Long, input: java.io.InputStream): Long {
                entered.countDown(); check(release.await(10, SECONDS))
                return artifacts.put(job, sha, bytes, offset, input)
            }
        }
        val secret = "capacity-secret-".repeat(4)
        val key = root.resolve("capacity-token").also { Files.writeString(it, secret) }
        val inventory = root.resolve("inventory.json").also {
            Files.writeString(it, Json.encodeToString(CapacityInventory(now(), setOf("fixture"), emptyList())))
        }
        val pool = CapacityPool("fixture", "fixture", 4, 8192, 1, setOf("arch:x86_64"))
        val collector = CapacityCollector(CapacitySettings(listOf(pool), 16), listOf(inventory), key,
            root.resolve("capacity-state.json"), store, control, { true }, emptySet())
        collector.collect()
        val server = embeddedServer(Netty, configure = {
            connectors.add(EngineConnectorBuilder().apply { host = "127.0.0.1"; port = 0 })
            callGroupSize = 1; workerGroupSize = 1; connectionGroupSize = 1
        }) { workerApi(store, backend(), blocked) { true }; capacityApi(collector) }.start()
        try {
            val port = kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors().single().port }
            JavaHttpClient.newHttpClient().use { http ->
                fun request(path: String) = JavaHttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(3))
                fun worker(path: String) = request("/worker/v1/sessions/${session.id}/$path")
                    .header("Authorization", "Bearer $token").header("X-Lab-Allocation", "allocation").header("X-Lab-Identity", jwt())
                val bytes = "blocked upload".toByteArray(); val sha = ExecutionStore.digest(String(bytes))
                val upload = http.sendAsync(worker("uploads/$sha?bytes=${bytes.size}&offset=0")
                    .header("X-Lab-Attempt", Json.encodeToString(claim.owner))
                    .PUT(JavaHttpRequest.BodyPublishers.ofByteArray(bytes)).build(), JavaHttpResponse.BodyHandlers.ofString())
                assertTrue(entered.await(2, SECONDS))
                val capacity = http.send(request("/capacity/snapshot").header("Authorization", "Bearer $secret").GET().build(), JavaHttpResponse.BodyHandlers.ofString())
                assertEquals(200, capacity.statusCode())
                val renew = http.send(worker("renew").POST(JavaHttpRequest.BodyPublishers.ofString(Json.encodeToString(claim.owner))).build(), JavaHttpResponse.BodyHandlers.ofString())
                assertEquals(200, renew.statusCode())
                assertFalse(upload.isDone)
                release.countDown()
                assertEquals(200, upload.get(3, SECONDS).statusCode())
            }
        } finally { release.countDown(); server.stop(0, 1000) }
    }

    @Test fun `pending result copy never completes early and restart recovers the durable projection`() {
        val id = setup(1); register()
        val claim = store.execution.claim(session.id, token, "allocation")!!
        val bytes = "accepted result".toByteArray(); val sha = ExecutionStore.digest(String(bytes))
        artifacts.put(id, sha, bytes.size.toLong(), 0, ByteArrayInputStream(bytes))
        store.execution.phase(claim.owner, "uploading")
        backend().commit(claim.owner, IterationResult(claim.indices.map { ExecutionCaseResult(it, 0, now()-1, now(), "{}") }, mapOf("0.out" to sha)))
        val delayed = NomadBackend(store, control, artifacts, { root.resolve("jobs/$it") }, projectResult = { _, _, _ -> false })
        delayed.reconcile()
        assertTrue(store.execution.ingested(id).isEmpty())
        assertEquals(Status.RUNNING, store.job(id)!!.status)
        assertFalse(Files.exists(root.resolve("jobs/$id/0.out")))
        // Even pre-existing case checks cannot complete a job whose accepted files are still pending.
        claim.indices.forEach { store.execution.checkComplete(id, it, "valid") }
        assertFalse(store.execution.finishIfComplete(id))
        backend(Store(file)).reconcile()
        assertEquals(String(bytes), Files.readString(root.resolve("jobs/$id/0.out")))
        assertEquals(Status.DONE, store.job(id)!!.status)
    }

    @Test fun `auto native worker finishes its iteration then yields to a sweep without an infrastructure failure`() {
        val id = setup(platform = NomadPlatform.MAC)
        register()
        val claim = backend().claim(session.id, token, "allocation")!!
        store.create("status-sweep@abc", "main", emptyList(), experiment = ExperimentSpec("status-sweep@abc",
            problems = listOf(mapOf("suite" to "s")), host = Experiments.LAB_HOST))
        assertTrue(store.execution.hasActiveAttempt(session.id))
        store.execution.phase(claim.owner, "uploading")
        assertTrue(backend().commit(claim.owner, IterationResult(claim.indices.map { ExecutionCaseResult(it, 0, 1, 2) })))
        assertNull(backend().claim(session.id, token, "allocation"))
        assertEquals(Desired.DRAIN, store.execution.session(session.id)!!.desired)
        assertEquals(0, store.execution.failureCount(id))
    }

    @Test fun `cloud workers continue claiming while a local reference waits`() {
        setup(); register()
        store.create("reference", "main", emptyList(), experiment = ExperimentSpec("reference",
            problems = listOf(mapOf("suite" to "s")), base = mapOf("backend" to REFERENCE_BACKEND, "solver" to "highs")))
        assertNotNull(backend().claim(session.id, token, "allocation"))
        assertEquals(Desired.RUN, store.execution.session(session.id)!!.desired)
    }

    @Test fun `sharing policy retires a bound worker only after its current accepted iteration`() {
        val id = setup()
        backend().reconcile(); register()
        val claim = store.execution.claim(session.id, token, "allocation")!!
        val frozen = store.execution.manifest(session.id)
        val changed = NomadBackend(store, control, artifacts, { root.resolve("jobs/$it") }, placementPolicy = { job ->
            JsonObject(job + ("Meta" to JsonObject(job.getValue("Meta").jsonObject + ("lab_per_host_parallel" to JsonPrimitive("4")))))
        })
        changed.reconcile()
        assertEquals(Desired.DRAIN, store.execution.session(session.id)!!.desired)
        store.execution.phase(claim.owner, "uploading")
        assertTrue(store.execution.commit(claim.owner, IterationResult(claim.indices.map { ExecutionCaseResult(it, 0, 1, 2) })))
        assertNull(store.execution.claim(session.id, token, "allocation"))
        assertEquals(frozen, store.execution.manifest(session.id))
        assertEquals(0, store.execution.failureCount(id))
    }

    @Test fun `default sharing strips old scheduler exclusions and retains operator exclusions`() {
        val template = NomadBatchJob("template", NomadPlatform.LINUX, "fixture", NomadCpuCapacity(4000, 4), ResourceBudget(1000, 1024),
            listOf("worker"), "registry/worker@sha256:" + "d".repeat(64), excludedNodes = setOf("operator"))
        val settings = NomadQueueSettings("release", "http://localhost:8091", listOf(NomadQueueTarget(template, setOf("arch:x86_64"))))
        val original = template.copy(excludedNodes = setOf("operator", "old-parallel-cap")).render()
        val policy = NomadPlacementPolicy(settings)
        val shared = policy.apply(original)
        val exclusions = shared.getValue("Constraints").jsonArray.filter { it.jsonObject["Operand"]?.jsonPrimitive?.content == "!=" }
        assertEquals(listOf("operator"), exclusions.map { it.jsonObject.getValue("RTarget").jsonPrimitive.content })
        assertEquals(original["TaskGroups"], shared["TaskGroups"])
        assertEquals(shared, policy.apply(shared))
    }

    @Test fun `approved worker patch replaces failed infrastructure while freezing all consumer inputs`() {
        val id = setup()
        val previous = session
        val frozen = manifest
        val before = store.execution.submission(session.id)
        val template = NomadBatchJob("template", NomadPlatform.LINUX, "fixture", NomadCpuCapacity(4000, 4), frozen.budget,
            listOf("worker"), "registry/patched@sha256:" + "e".repeat(64))
        val settings = NomadQueueSettings("patched", "http://localhost:8091", listOf(NomadQueueTarget(template, setOf("arch:x86_64"))), workerReplaces = setOf("release"))
        store.execution.loseSession(session.id, "infrastructure:worker-bug")
        val patched = NomadBackend(store, control, artifacts, { root.resolve("jobs/$it") }, workerRuntime = NomadWorkerRuntime(settings)::replace)
        patched.reconcile(); patched.reconcile()
        val next = store.execution.sessions(id).single { it.desired == Desired.RUN }
        assertEquals(frozen.copy(session = next.id, release = "patched"), store.execution.manifest(next.id))
        assertEquals(before, store.execution.submission(previous.id))
        val payload = Json.parseToJsonElement(store.execution.submission(next.id)).jsonObject
        val task = payload.getValue("TaskGroups").jsonArray.single().jsonObject.getValue("Tasks").jsonArray.single().jsonObject
        assertEquals(template.image, task.getValue("Config").jsonObject.getValue("image").jsonPrimitive.content)
        assertEquals("patched", task.getValue("Env").jsonObject.getValue("LAB_WORKER_RELEASE").jsonPrimitive.content)
        assertEquals(1, store.execution.failureCount(id), "the existing infrastructure-loss event is preserved")
        assertEquals(frozen.experiment, store.execution.manifest(next.id).experiment)
        assertEquals(frozen.bundles, store.execution.manifest(next.id).bundles)
    }

    private fun cpuNode(id: String, mhz: Int, nodeClass: String = "fixture", cores: Int = 4) = buildJsonObject {
        put("ID", id); put("NodeClass", nodeClass); put("Status", "ready"); put("SchedulingEligibility", "eligible")
        putJsonObject("Attributes") { put("kernel.name", "linux"); put("cpu.usablecompute", mhz.toString()); put("cpu.numcores", cores.toString()) }
        putJsonObject("NodeResources") { putJsonObject("Memory") { put("MemoryMB", 8192) } }
    }

    @Test fun `pending frozen work is recalibrated for replacement CPU frequency and completes`() {
        val id = setup(1)
        val previous = session
        val frozen = manifest
        val original = store.execution.submission(previous.id)
        control.allocations.clear()
        control.nodeInventory["replacement-node"] = cpuNode("replacement-node", 6000)

        backend().reconcile()
        backend(Store(file)).reconcile()

        val replacement = store.execution.sessions(id).single { it.desired == Desired.RUN }
        val payload = control.jobs.getValue(replacement.nomadJobId)
        val task = payload.getValue("TaskGroups").jsonArray.single().jsonObject.getValue("Tasks").jsonArray.single().jsonObject
        assertEquals(3000, task.getValue("Resources").jsonObject.getValue("CPU").jsonPrimitive.int)
        assertTrue(payload.getValue("Constraints").jsonArray.any { it.jsonObject["LTarget"]?.jsonPrimitive?.content == "${'$'}{attr.cpu.usablecompute}" &&
            it.jsonObject["RTarget"]?.jsonPrimitive?.content == "6000" })
        assertEquals(frozen.copy(session = replacement.id), store.execution.manifest(replacement.id))
        assertEquals(original, store.execution.submission(previous.id))
        assertEquals(0, store.execution.failureCount(id))
        assertFalse(control.jobs.containsKey(previous.nomadJobId))

        useReplacement(id, "replacement-allocation", "replacement-node")
        val claim = backend().claim(session.id, token, "replacement-allocation")!!
        store.execution.phase(claim.owner, "uploading")
        backend().commit(claim.owner, IterationResult(claim.indices.map { ExecutionCaseResult(it, 0, now() - 1, now(), "{}") }))
        backend().reconcile()
        assertEquals(Status.DONE, store.job(id)!!.status)
        assertEquals(2, store.job(id)!!.done)
    }

    @Test fun `a busy original CPU shape does not strand work beside a free compatible shape`() {
        control.nodeInventory["busy"] = cpuNode("busy", 4000)
        control.nodeInventory["free"] = cpuNode("free", 6000)
        control.nodeTasks["busy"] = listOf(buildJsonObject {
            put("ClientStatus", "running")
            putJsonObject("AllocatedResources") { putJsonObject("Tasks") { putJsonObject("worker") {
                putJsonObject("Cpu") { put("CpuShares", 4000) }; putJsonObject("Memory") { put("MemoryMB", 1024) }
            } } }
        })
        setup(1)
        val payload = Json.parseToJsonElement(store.execution.submission(session.id)).jsonObject
        assertTrue(payload.getValue("Constraints").jsonArray.any { it.jsonObject["LTarget"]?.jsonPrimitive?.content == "${'$'}{attr.cpu.usablecompute}" &&
            it.jsonObject["RTarget"]?.jsonPrimitive?.content == "6000" })
        assertEquals(ResourceBudget(2000, 1024, 2), store.execution.manifest(session.id).budget)
    }

    @Test fun `CPU recalibration leaves an already bound allocation untouched`() {
        val id = setup(1); register()
        val original = store.execution.submission(session.id)
        control.nodeInventory["replacement"] = cpuNode("replacement", 6000)
        backend().reconcile()
        assertEquals(listOf(session.id), store.execution.sessions(id).filter { it.desired == Desired.RUN }.map { it.id })
        assertEquals(original, store.execution.submission(session.id))
        assertNotNull(backend().claim(session.id, token, "allocation"))
    }

    @Test fun `a pending allocation without resource telemetry does not fail job admission`() {
        control.nodeInventory["incomplete"] = cpuNode("incomplete", 6000)
        control.nodeTasks["incomplete"] = listOf(buildJsonObject { put("ClientStatus", "pending"); put("AllocatedResources", JsonNull) })
        val id = setup(1)
        assertNotEquals(Status.FAILED, store.job(id)!!.status)
        assertEquals(1, store.execution.sessions(id).size)
    }

    @Test fun `registration omits new optional fields for frozen older workers`() = testApplication {
        setup(preparation = true)
        application { workerApi(store, backend(), artifacts) { true } }
        val response = client.post("/worker/v1/sessions/${session.id}/register") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-Lab-Allocation", "allocation")
            header("X-Lab-Identity", jwt())
            setBody(Json.encodeToString(WorkerRegistration("release")))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertFalse("sharedInputs" in Json.parseToJsonElement(body).jsonObject)
        assertEquals(manifest, Json.decodeFromString<WorkManifest>(body))
    }

    private val root = Files.createTempDirectory("nomad-backend")
    private val file = root.resolve("lab.db")
    private val store = Store(file)
    private val artifacts = LocalArtifactStore(root.resolve("artifacts"))
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private val control = object : NomadControl {
        override var namespace = "default"
        val jobs = mutableMapOf<String, JsonObject>(); val allocations = mutableMapOf<String, NomadAllocation>()
        val nodeInventory = mutableMapOf<String, JsonObject>()
        val nodeTasks = mutableMapOf<String, List<JsonObject>>()
        override fun nodes() = nodeInventory.values.toList()
        override fun node(id: String) = nodeInventory[id]
        override fun nodeAllocations(id: String) = nodeTasks[id].orEmpty()
        var submissions = 0; var failAfterSubmit = false
        override fun job(id: String) = jobs[id]
        override fun submit(job: JsonObject) { submissions++; jobs[job.getValue("ID").jsonPrimitive.content] = job; if (failAfterSubmit) { failAfterSubmit = false; error("lost acknowledgement") } }
        override fun stop(id: String) { jobs.remove(id) }
        override fun allocations(job: String) = allocations.values.filter { it.job == job }
        override fun allocation(id: String) = allocations[id]
        var keyReads = 0; var keyId = "test"; var keyFailure = false
        override fun jwks(): JsonObject {
            keyReads++; check(!keyFailure) { "issuer unavailable" }
            val key = pair.public as RSAPublicKey
            return buildJsonObject { putJsonArray("keys") { add(buildJsonObject { put("kid", keyId); put("kty", "RSA"); put("n", b64(key.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())); put("e", b64(key.publicExponent.toByteArray())) }) } }
        }
    }
    private fun backend(s: Store = store) = NomadBackend(s, control, artifacts, { root.resolve("jobs/$it") })
    private lateinit var session: ExecutionSession
    private lateinit var token: String
    private lateinit var manifest: WorkManifest
    private fun setup(problems: Int = 2, spec: ExperimentSpec = ExperimentSpec("test", problems = listOf(mapOf("suite" to "s")), grid = mapOf("x" to listOf("a", "b")), seeds = listOf(1), host = HostPlacement.AUTO), name: String = spec.name, platform: NomadPlatform = NomadPlatform.LINUX, preparation: Boolean = false, sessionId: String = "session"): Long {
        val spec = if (preparation && !spec.isProfile && spec.work.bench == null) spec.copy(problems = emptyList(), bench = BenchSpec("bench.json",
            referenceFilter = spec.problems.firstOrNull()?.get("reference")?.let { ReferenceFilterMode.valueOf(it.uppercase()) } ?: ReferenceFilterMode.ANY)) else spec
        val arms = Experiments.arms(spec).map { PlannedArm(it, "a".repeat(40)) }
        val cases = Experiments.cases(problems, arms.size, spec.seeds)
        val plannedProblems = List(problems) { Problem("s", "p$it") }
        val id = store.create(name, "main", emptyList(), vcpus = 2, experiment = spec)
        if (!preparation) store.plan(id, arms, plannedProblems, cases, cases.map { "true" to 5L })
        val bundle = WorkerBundle("b".repeat(64), listOf(InputFile("bin/bench", "c".repeat(64), 5)), Repositories.DEFAULT, "a".repeat(40), "linux", "amd64", "test")
        manifest = WorkManifest(sessionId, "release", spec, arms, if (preparation) emptyList() else cases.mapIndexed { index, case -> WorkCase(index, case.arm, plannedProblems[case.problem], case.seed, case.repeat, 5) }, listOf(bundle), ResourceBudget(2000, 1024, 2), preparationTimeoutSec = if (preparation) 60 else null)
        val command = if (platform == NomadPlatform.LINUX) listOf("worker") else listOf("/releases/fixture/bin/eignex-lab", "worker")
        val image = if (platform == NomadPlatform.LINUX) "registry/worker@sha256:" + "d".repeat(64) else null
        session = backend().enqueue(id, manifest, NomadBatchJob("placeholder", platform, "fixture", NomadCpuCapacity(4000, 4), manifest.budget, command, image), "http://127.0.0.1:8091")
        token = Json.parseToJsonElement(store.execution.submission(session.id)).jsonObject.getValue("TaskGroups").jsonArray[0].jsonObject.getValue("Tasks").jsonArray[0].jsonObject.getValue("Env").jsonObject.getValue("LAB_WORKER_TOKEN").jsonPrimitive.content
        control.allocations["allocation"] = NomadAllocation("allocation", session.nomadJobId, "node", "running", "run")
        return id
    }
    private fun jwt(allocation: String = "allocation", expires: Long = now()/1000 + 60, singleAudience: Boolean = false, namespace: String = control.namespace, kid: String = "test"): String {
        val header = b64("""{"alg":"RS256","kid":"$kid"}""".toByteArray())
        val claims = buildJsonObject { put("exp", expires); if (singleAudience) put("aud", "eignex-lab-worker") else putJsonArray("aud") { add("eignex-lab-worker") }; put("nomad_namespace", namespace); put("nomad_job_id", session.nomadJobId); put("nomad_task", "worker"); put("nomad_allocation_id", allocation) }
        val payload = "$header.${b64(claims.toString().toByteArray())}"
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(payload.toByteArray()) }.sign()
        return "$payload.${b64(signature)}"
    }
    private fun register(allocation: String = "allocation") = backend().register(session.id, token, allocation, jwt(allocation), "release")
    private fun useReplacement(id: Long, allocation: String, node: String) {
        session = store.execution.sessions(id).single { it.desired == Desired.RUN }
        token = Json.parseToJsonElement(store.execution.submission(session.id)).jsonObject.getValue("TaskGroups").jsonArray[0].jsonObject
            .getValue("Tasks").jsonArray[0].jsonObject.getValue("Env").jsonObject.getValue("LAB_WORKER_TOKEN").jsonPrimitive.content
        control.allocations[allocation] = NomadAllocation(allocation, session.nomadJobId, node, "running", "run")
        register(allocation)
    }

    @Test fun `an older native retry uses configured capacity without requiring an idle host`() {
        val job = setup(platform = NomadPlatform.MAC, preparation = true)
        val previous = session
        val frozen = manifest
        val originalSubmission = store.execution.submission(previous.id)
        store.execution.loseSession(previous.id, "infrastructure:failed")
        setup(name = "other", platform = NomadPlatform.MAC, preparation = true, sessionId = "other")
        fun configured(cpu: Int) = NomadBackend(store, control, artifacts, { root.resolve("jobs/$it") },
            nativeCapacity = ResourceBudget(cpu, 4096))

        configured(3000).reconcile()
        assertTrue(store.execution.sessions(job).all { it.desired == Desired.STOP }, "another worker's reservation must fit too")
        configured(4000).reconcile()
        val replacement = store.execution.sessions(job).single { it.desired != Desired.STOP }
        assertNotEquals(previous.id, replacement.id)
        assertEquals(previous.budget, replacement.budget)
        assertEquals(frozen.copy(session = replacement.id), store.execution.manifest(replacement.id))
        assertEquals(originalSubmission, store.execution.submission(previous.id))
        val meta = Json.parseToJsonElement(store.execution.submission(replacement.id)).jsonObject.getValue("Meta").jsonObject
        assertEquals("4000", meta.getValue("lab_native_cpu_limit").jsonPrimitive.content)
        assertEquals("4096", meta.getValue("lab_native_memory_limit").jsonPrimitive.content)
    }

    @Test fun `intentional unallocated native handoff waits for queue admission instead of recreating a cloud worker`() {
        val id = setup(1)
        val previous = session
        assertTrue(store.execution.loseUnallocatedSession(previous.id, "placement-native-capacity"))
        backend().reconcile()
        assertEquals(listOf(previous.id), store.execution.sessions(id).map { it.id })
        assertEquals(Desired.STOP, store.execution.session(previous.id)!!.desired)
    }

    @Test fun `repeated Spot loss replaces preparation on new nodes before there are planned commands`() {
        val id = setup(preparation = true); backend().reconcile(); register()
        var allocation = "allocation"
        repeat(8) { index ->
            val previous = session
            val frozen = store.execution.manifest(previous.id)
            val claim = backend().claim(previous.id, token, allocation)!!
            assertEquals("preparation", claim.owner.iteration)
            control.allocations[allocation] = control.allocations.getValue(allocation).copy(client = "lost")
            backend(Store(file)).reconcile()
            assertFailsWith<ExecutionFenced> { store.execution.renew(claim.owner) }
            val replacement = store.execution.sessions(id).single { it.desired == Desired.RUN }
            assertEquals(ExecutionClass.PREPARE, replacement.executionClass)
            assertNotEquals(previous.id, replacement.id)
            assertEquals(frozen.copy(session = replacement.id), store.execution.manifest(replacement.id))
            assertEquals(Status.RUNNING, store.job(id)!!.status)
            assertNull(store.job(id)!!.error)
            assertTrue(store.job(id)!!.commands.isEmpty())
            allocation = "replacement-$index"
            useReplacement(id, allocation, "replacement-node-$index")
        }
        assertEquals(8, store.execution.failureCount(id))
    }

    @Test fun `preparation loss keeps controller termination evidence without counting OOM as software poison`() {
        val id = setup(preparation = true); register()
        val claim = backend().claim(session.id, token, "allocation")!!
        store.execution.phase(claim.owner, "preparing")
        val diagnostic = NomadTaskTermination("failed", "run", "dead", listOf("Started", "Terminated"), 137, 9, true, false, false, false)
        control.allocations["allocation"] = control.allocations.getValue("allocation").copy(client = "failed", termination = diagnostic)
        backend(Store(file)).reconcile()
        val events = Store(file).execution.events(id)
        val retained = events.single { it.detail.startsWith("preparation-termination:") }
        assertEquals(diagnostic, Json.decodeFromString<NomadTaskTermination>(retained.detail.removePrefix("preparation-termination:")))
        assertFalse(events.any { it.detail.startsWith("preparation-worker-exit:") })
        assertEquals(Status.RUNNING, store.job(id)!!.status)
        assertFailsWith<ExecutionFenced> { store.execution.renew(claim.owner) }
    }

    @Test fun `three proven worker exits at the same preparation stage stop automatic replacement`() {
        val id = setup(preparation = true); register()
        var allocation = "allocation"
        repeat(3) { index ->
            val claim = backend().claim(session.id, token, allocation)!!
            store.execution.phase(claim.owner, "preparing")
            store.execution.preparationProgress(claim.owner, PreparationProgress("selection", 60_000, 60_000, emptyMap()))
            // The worker reports the exception that ends it before it exits.
            store.execution.preparationDiagnostics(claim.owner, PreparationDiagnostics(60_000, "selection", session.budget, "advisory-native",
                null, null, emptyMap(), 1000, null, 2, error = "IllegalStateException: corpus pack $index unavailable"))
            control.allocations[allocation] = control.allocations.getValue(allocation).copy(client = "failed", workerExit = NomadWorkerExit(WORKER_SOFTWARE_EXIT))
            backend(Store(file)).reconcile()
            if (index < 2) {
                assertEquals(Status.RUNNING, store.job(id)!!.status)
                allocation = "crash-replacement-$index"
                useReplacement(id, allocation, "node-$index")
            }
        }
        assertEquals(Status.FAILED, store.job(id)!!.status)
        assertContains(store.job(id)!!.error!!, "3 worker exits without acceptance at stage selection")
        assertContains(store.job(id)!!.error!!, "exit 70, release release): IllegalStateException: corpus pack 2 unavailable")
        assertTrue(store.execution.sessions(id).all { it.desired == Desired.STOP })
        repeat(3) { backend().reconcile() }
        assertEquals(3, store.execution.sessions(id).size)
        assertEquals(3, store.execution.events(id).count { it.detail.startsWith("preparation-worker-exit:") })

        assertTrue(store.retry(id)); backend().reconcile()
        useReplacement(id, "retry-crash", "retry-node")
        val claim = backend().claim(session.id, token, "retry-crash")!!
        store.execution.phase(claim.owner, "preparing")
        store.execution.preparationProgress(claim.owner, PreparationProgress("selection", 1, 1, emptyMap()))
        control.allocations["retry-crash"] = control.allocations.getValue("retry-crash").copy(client = "failed", workerExit = NomadWorkerExit(WORKER_SOFTWARE_EXIT))
        backend().reconcile()
        assertEquals(Status.RUNNING, store.job(id)!!.status, "manual retry starts a new crash budget")
    }

    @Test fun `failed preparation allocations with legacy temporary or absent exit evidence remain infrastructure recovery`() {
        val id = setup(preparation = true); register()
        var allocation = "allocation"
        repeat(9) { index ->
            val claim = backend().claim(session.id, token, allocation)!!
            store.execution.phase(claim.owner, "preparing")
            control.allocations[allocation] = control.allocations.getValue(allocation).copy(client = "failed",
                workerExit = when (index % 3) { 0 -> null; 1 -> NomadWorkerExit(1); else -> NomadWorkerExit(WORKER_TEMPORARY_EXIT) })
            backend().reconcile()
            assertEquals(Status.RUNNING, store.job(id)!!.status)
            allocation = "infra-replacement-$index"
            useReplacement(id, allocation, "node-$index")
        }
        assertFalse(store.execution.events(id).any { it.detail.startsWith("preparation-worker-exit:") })
    }

    @Test fun `software crashes before registration cannot replace preparation forever`() {
        val id = setup(preparation = true)
        var allocation = "allocation"
        repeat(3) { index ->
            control.allocations[allocation] = control.allocations.getValue(allocation).copy(client = "failed", workerExit = NomadWorkerExit(WORKER_SOFTWARE_EXIT))
            backend(Store(file)).reconcile()
            if (index < 2) {
                session = store.execution.sessions(id).single { it.desired == Desired.RUN }
                allocation = "startup-failure-$index"
                control.allocations[allocation] = NomadAllocation(allocation, session.nomadJobId, "node-$index", "running", "run")
            }
        }
        assertEquals(Status.FAILED, store.job(id)!!.status)
        assertContains(store.job(id)!!.error!!, "3 worker exits without acceptance at stage registration")
        assertEquals(3, store.execution.sessions(id).size)
        assertTrue(store.execution.sessions(id).all { it.desired == Desired.STOP })
    }

    private fun benchProblem(name: String, collection: String = "") = BenchRuns.problem(com.eignex.lab.bench.BenchCase(name,
        attributes = mapOf("suite" to "s", "collection" to collection)))

    private fun benchPreparation(selections: List<List<Problem>>, bundles: List<WorkerBundle>, features: List<ProblemFeatures> = emptyList(),
                                 artifacts: Map<String, String>) = PreparationResult(selections, bundles, features = features, artifacts = artifacts,
        benchPlan = com.eignex.lab.bench.BenchPlan(selections.flatten().map { it.benchCase!! }),
        benchDescriptor = com.eignex.lab.bench.BenchDescriptor("fixture", "protocol fixture"),
        armPlans = manifest.arms.associate { it.arm.label to com.eignex.lab.bench.BenchPlan(selections.flatten().map { it.benchCase!! }) })

    @ParameterizedTest
    @EnumSource(NomadPlatform::class)
    fun `native snapshots cannot name another job or be supplied by a cloud allocation`(platform: NomadPlatform) {
        val id = setup(1, ExperimentSpec("snapshot-owner", bench = BenchSpec("bench.json"), host = HostPlacement.AUTO),
            platform = platform, preparation = true)
        register()
        val claim = backend().claim(session.id, token, "allocation")!!
        val original = manifest.bundles.single()
        val directories = listOf("bin")
        val metadata = preparedTreeManifest(original.files, directories).toByteArray()
        val sha = preparedTreeDigest(original.files, directories)
        val bundle = original.copy(sha256 = sha, tree = PreparedTree(if (platform == NomadPlatform.MAC) id + 1 else id, directories))
        artifacts.put(id, sha, metadata.size.toLong(), 0, metadata.inputStream())
        store.execution.phase(claim.owner, "preparing")
        val selected = backend().selectPreparation(claim.owner, listOf(listOf(benchProblem("p"))))
        val outputs = mapOf(preparationArtifact(bundle) to sha)
        store.execution.phase(claim.owner, "uploading")
        assertFailsWith<IllegalArgumentException> {
            backend().commit(claim.owner, IterationResult(emptyList(), outputs,
                preparation = benchPreparation(selected, listOf(bundle), artifacts = outputs)))
        }
        assertNull(store.execution.acceptedPreparation(id))
    }

    @ParameterizedTest
    @EnumSource(value = Status::class, names = ["DONE", "FAILED"])
    fun `bench replay pins accepted builds and refuses changed revisions requests and inputs`(status: Status) {
        val originalSpec = ExperimentSpec("original", bench = BenchSpec("bench.json", com.eignex.lab.bench.BenchRequest(mapOf("mode" to "original"))), host = HostPlacement.AUTO)
        val id = setup(1, originalSpec, preparation = true)
        register()
        val claim = backend().claim(session.id, token, "allocation")!!
        val bytes = "prepared build".toByteArray()
        val sha = ExecutionStore.digest(String(bytes))
        artifacts.put(id, sha, bytes.size.toLong(), 0, ByteArrayInputStream(bytes))
        val prepared = manifest.bundles.single().copy(sha256 = sha)
        val case = benchProblem("p").benchCase!!
        val problem = BenchRuns.problem(case.copy(attributes = case.attributes + ("labInputDigest" to ExecutionStore.digest(""))))
        val outputs = mapOf("preparation/${prepared.commit}.zip" to sha)
        store.execution.phase(claim.owner, "preparing")
        val selected = backend().selectPreparation(claim.owner, listOf(listOf(problem)))
        val preparation = benchPreparation(selected, listOf(prepared), artifacts = outputs)
        store.execution.phase(claim.owner, "uploading")
        backend().commit(claim.owner, IterationResult(emptyList(), outputs, preparation = preparation))
        store.plan(id, manifest.arms, listOf(problem), Experiments.cases(1, 1, emptyList()), listOf("true" to 5L))
        store.finish(id, status)
        val spec = originalSpec.copy(configs = listOf(mapOf("label" to "before", "ref" to prepared.commit), mapOf("label" to "after", "ref" to prepared.commit)),
            bench = originalSpec.work.bench!!.copy(frozenCases = listOf(problem.benchCase!!), replayJobs = mapOf("before" to id, "after" to id)))
        val arms = Experiments.arms(spec).map { PlannedArm(it, prepared.commit) }

        val replay = replaySources(store, spec, arms)

        assertEquals(setOf("before", "after"), replay.keys)
        assertTrue(replay.values.all { it.preparation.bundles.single().sha256 == sha })
        assertEquals(setOf("before", "after"), benchReplay(replay, spec.work.bench!!)!!.plans.keys)
        assertFailsWith<IllegalArgumentException> { replaySources(store, spec.copy(bench = spec.work.bench!!.copy(request = com.eignex.lab.bench.BenchRequest(mapOf("mode" to "changed")))), arms) }
        assertFailsWith<IllegalArgumentException> { replaySources(store, spec, arms.map { it.copy(sha = "f".repeat(40)) }) }
        assertFailsWith<IllegalArgumentException> { replaySources(store, spec.copy(bench = spec.work.bench!!.copy(manifest = "other.json")), arms) }
        val changed = problem.benchCase!!.copy(attributes = problem.benchCase!!.attributes + ("labInputDigest" to ExecutionStore.digest("changed")))
        assertFailsWith<IllegalArgumentException> { replaySources(store, spec.copy(bench = spec.work.bench!!.copy(frozenCases = listOf(changed))), arms) }
        val missing = problem.benchCase!!.copy(attributes = mapOf("comparisonKey" to "missing"))
        assertFailsWith<IllegalArgumentException> { replaySources(store, spec.copy(bench = spec.work.bench!!.copy(frozenCases = listOf(missing))), arms) }
    }

    @Test fun `failed replay requires a retained preparation`() {
        val original = ExperimentSpec("missing-preparation", bench = BenchSpec("bench.json"), host = HostPlacement.AUTO)
        val id = setup(1, original, preparation = true)
        val problem = benchProblem("p")
        val cases = Experiments.cases(1, manifest.arms.size, emptyList())
        store.plan(id, manifest.arms, listOf(problem), cases, cases.map { "true" to 5L })
        store.finish(id, Status.FAILED)
        val arm = manifest.arms.single()
        val spec = original.copy(bench = original.work.bench!!.copy(
            frozenCases = listOf(benchProblem("p").benchCase!!), replayJobs = mapOf(arm.arm.label to id)))

        val failure = assertFailsWith<IllegalArgumentException> { replaySources(store, spec, listOf(arm)) }

        assertContains(failure.message.orEmpty(), "preparation")
    }

    @Test fun `replay preserves the retained runtime for each arm`() {
        val original = ExperimentSpec("paired-replay", bench = BenchSpec("bench.json"),
            configs = listOf(mapOf("label" to "ordinary", "param.mode" to "false"),
                mapOf("label" to "incremental", "param.mode" to "true")), host = HostPlacement.AUTO)
        val id = setup(1, original, preparation = true)
        register()
        val claim = backend().claim(session.id, token, "allocation")!!
        val bytes = "prepared pair".toByteArray()
        val sha = ExecutionStore.digest(String(bytes))
        artifacts.put(id, sha, bytes.size.toLong(), 0, ByteArrayInputStream(bytes))
        val prepared = manifest.bundles.single().copy(sha256 = sha)
        val case = benchProblem("p").benchCase!!
        val problem = BenchRuns.problem(case.copy(attributes = case.attributes + ("labInputDigest" to ExecutionStore.digest(""))))
        val outputs = mapOf(("preparation/" + prepared.commit + ".zip") to sha)
        store.execution.phase(claim.owner, "preparing")
        val selected = backend().selectPreparation(claim.owner, listOf(listOf(problem)))
        val preparation = benchPreparation(selected, listOf(prepared), artifacts = outputs).copy(
            armPlans = manifest.arms.associate { arm ->
                arm.arm.label to com.eignex.lab.bench.BenchPlan(listOf(problem.benchCase!!.copy(
                    parameters = mapOf("mode" to arm.arm.values.getValue("param.mode")))))
            })
        store.execution.phase(claim.owner, "uploading")
        backend().commit(claim.owner, IterationResult(emptyList(), outputs, preparation = preparation))
        val cases = Experiments.cases(1, manifest.arms.size, emptyList())
        store.plan(id, manifest.arms, listOf(problem), cases, cases.map { "true" to 5L })
        store.finish(id, Status.DONE)
        val spec = original.copy(bench = original.work.bench!!.copy(frozenCases = listOf(problem.benchCase!!),
            replayJobs = manifest.arms.associate { it.arm.label to id }))
        val arms = Experiments.arms(spec).map { PlannedArm(it, prepared.commit) }

        val replay = replaySources(store, spec, arms)
        val plans = benchReplay(replay, spec.work.bench!!)!!.plans

        assertEquals(mapOf("ordinary" to "false", "incremental" to "true"),
            plans.mapValues { it.value.cases.single().parameters.getValue("mode") })
    }

    @Test fun `accepted preparation is retained without completing the experiment or preparing twice`() {
        val id = setup(preparation = true); backend().reconcile(); register()
        val claim = backend().claim(session.id, token, "allocation")!!
        val bytes = "prepared source".toByteArray(); val sha = ExecutionStore.digest(String(bytes))
        artifacts.put(id, sha, bytes.size.toLong(), 0, ByteArrayInputStream(bytes))
        val prepared = manifest.bundles.single().copy(sha256 = sha)
        val outputs = mapOf("preparation/${prepared.commit}.zip" to sha)
        store.execution.phase(claim.owner, "preparing")
        val selected = backend().selectPreparation(claim.owner, listOf(listOf(benchProblem("p"))))
        val preparation = benchPreparation(selected, listOf(prepared), artifacts = outputs)
        store.execution.phase(claim.owner, "uploading")
        backend().commit(claim.owner, IterationResult(emptyList(), outputs, preparation = preparation))
        backend(Store(file)).reconcile()
        assertEquals(Status.RUNNING, store.job(id)!!.status)
        assertFalse(store.job(id)!!.setupDone)
        assertEquals(1, store.execution.sessions(id).size)
        assertTrue(store.execution.sessions(id).all { it.desired == Desired.STOP })
        assertEquals(preparation, store.execution.accepted(id).single().second.preparation)
        assertEquals("prepared source", Files.readString(root.resolve("jobs/$id/preparation/${prepared.commit}.zip")))
        val problems = selected.flatten()
        val cases = Experiments.cases(problems.size, manifest.arms.size, manifest.experiment.seeds)
        store.plan(id, manifest.arms, problems, cases, cases.map { "true" to 5L })
        repeat(3) { backend(Store(file)).reconcile() }
        assertEquals(1, store.execution.sessions(id).size, "published cases must not resurrect accepted preparation")
    }

    @Test fun `selection approval is durable idempotent and cannot be changed by a preparing worker`() {
        val spec = ExperimentSpec("selection", problems = listOf(mapOf("suite" to "s", "reference" to "proven", "max" to "1")), host = HostPlacement.AUTO)
        val id = setup(2, spec, preparation = true); register()
        val claim = backend().claim(session.id, token, "allocation")!!
        store.execution.phase(claim.owner, "preparing")
        store.references.put(listOf(ProblemKey("", "p1") to Reference("gecode", false, 1.0, true, true, 100, 60000)), "fixture")
        val requested = listOf(listOf(benchProblem("p0"), benchProblem("p1")))
        val selected = backend().selectPreparation(claim.owner, requested)
        assertEquals(listOf("p1"), selected.single().map { it.problem })
        store.references.retire("", "gecode")
        assertEquals(selected, backend(Store(file)).selectPreparation(claim.owner, requested))
        assertFailsWith<ExecutionFenced> { backend().selectPreparation(claim.owner, requested.map { it.reversed() }) }
        val bytes = "source".toByteArray(); val sha = ExecutionStore.digest(String(bytes))
        artifacts.put(id, sha, bytes.size.toLong(), 0, ByteArrayInputStream(bytes))
        val outputs = mapOf("preparation/${manifest.bundles.single().commit}.zip" to sha)
        val forged = benchPreparation(listOf(listOf(benchProblem("p0"))), listOf(manifest.bundles.single().copy(sha256 = sha)), artifacts = outputs)
        store.execution.phase(claim.owner, "uploading")
        assertFailsWith<IllegalArgumentException> { backend().commit(claim.owner, IterationResult(emptyList(), outputs, preparation = forged)) }
        assertTrue(store.execution.accepted(id).isEmpty())
    }

    @Test fun `extra substring annotations do not reject or expand an approved preparation`() {
        val spec = ExperimentSpec("annotations", problems = listOf(mapOf("suite" to "s", "reference" to "any")), host = HostPlacement.AUTO)
        val id = setup(1, spec, preparation = true); register()
        val claim = backend().claim(session.id, token, "allocation")!!
        val bytes = "prepared source".toByteArray(); val sha = ExecutionStore.digest(String(bytes))
        artifacts.put(id, sha, bytes.size.toLong(), 0, ByteArrayInputStream(bytes))
        val prepared = manifest.bundles.single().copy(sha256 = sha)
        val outputs = mapOf("preparation/${prepared.commit}.zip" to sha)
        store.execution.phase(claim.owner, "preparing")
        val selected = backend().selectPreparation(claim.owner, listOf(listOf(benchProblem("ej", "miplib"))))
        val feature = ProblemFeatures("miplib", "ej", listOf("mip"))
        val preparation = benchPreparation(selected, listOf(prepared), features = listOf(feature, feature.copy(problem = "sutlej")), artifacts = outputs)
        val result = IterationResult(emptyList(), outputs, preparation = preparation)
        store.execution.phase(claim.owner, "uploading")
        assertTrue(backend().commit(claim.owner, result))
        assertTrue(backend().commit(claim.owner, result), "ambiguous acknowledgements retain the normalized acceptance")
        val accepted = store.execution.accepted(id).single().second.preparation!!
        assertEquals(selected, accepted.selections)
        assertEquals(listOf(feature), accepted.features)
        assertEquals(Status.RUNNING, store.job(id)!!.status)
    }

    @Test fun `empty approved selection is a permanent preparation failure`() {
        val spec = ExperimentSpec("empty", problems = listOf(mapOf("suite" to "s", "reference" to "proven")), host = HostPlacement.AUTO)
        val id = setup(1, spec, preparation = true); register()
        val claim = backend().claim(session.id, token, "allocation")!!
        store.execution.phase(claim.owner, "preparing")
        assertFailsWith<ExecutionFenced> { backend().selectPreparation(claim.owner, listOf(listOf(benchProblem("p0")))) }
        assertEquals(Status.FAILED, store.job(id)!!.status)
        assertTrue(store.job(id)!!.error!!.contains("reference filter"))
        assertTrue(store.execution.sessions(id).all { it.desired == Desired.STOP })
    }

    @Test fun `retired frozen preparation is fenced instead of returning retryable selection errors`() = testApplication {
        val id = setup(preparation = true)
        val legacy = manifest.experiment.copy(bench = null, problems = listOf(mapOf("set" to "sweep")))
        store.executionTransaction { db ->
            db.sqlUpdate("UPDATE jobs SET experiment = ? WHERE id = ?", Json.encodeToString(legacy), id)
            db.sqlUpdate("UPDATE execution_manifests SET manifest = ? WHERE session_id = ?", Json.encodeToString(manifest.copy(experiment = legacy, schemaVersion = 1)), session.id)
        }
        register()
        val claim = backend().claim(session.id, token, "allocation")!!
        store.execution.phase(claim.owner, "preparing")
        application { workerApi(store, backend(), artifacts) { true } }
        repeat(3) {
            val response = client.post("/worker/v1/sessions/${session.id}/selection") {
                header(HttpHeaders.Authorization, "Bearer $token"); header("X-Lab-Allocation", "allocation"); header("X-Lab-Identity", jwt())
                setBody(Json.encodeToString(WorkerSelection(claim.owner, listOf(listOf(Problem("profile", "main"))))))
            }
            assertEquals(HttpStatusCode.Conflict, response.status)
            backend().reconcile()
        }
        assertEquals(Status.FAILED, store.job(id)!!.status)
        assertContains(store.job(id)!!.error!!, "repository bench manifest or explicit profile")
        assertEquals(1, store.execution.sessions(id).size)
        assertTrue(store.execution.sessions(id).all { it.desired == Desired.STOP })
    }

    @Test fun `retired preparation cannot be replaced after infrastructure loss`() {
        val id = setup(preparation = true)
        val legacy = manifest.experiment.copy(bench = null)
        store.executionTransaction { db ->
            db.sqlUpdate("UPDATE execution_manifests SET manifest = ? WHERE session_id = ?", Json.encodeToString(manifest.copy(experiment = legacy, schemaVersion = 1)), session.id)
        }
        store.execution.loseSession(session.id, "infrastructure:failed")
        repeat(3) { backend().reconcile() }
        assertEquals(Status.FAILED, store.job(id)!!.status)
        assertContains(store.job(id)!!.error!!, "legacy solver recipes must be migrated")
        assertEquals(1, store.execution.sessions(id).size)
    }

    @Test fun `repeated internal selection failures are bounded across restart and replacement and reset on manual retry`() {
        val id = setup(preparation = true); register()
        fun broken() = NomadBackend(Store(file), control, artifacts, { root.resolve("jobs/$it") },
            selectProblems = { _, _, _, _ -> error("broken selection invariant") })
        val requested = listOf(listOf(benchProblem("p0")))
        var allocation = "allocation"
        repeat(3) { index ->
            val claim = backend().claim(session.id, token, allocation)!!
            store.execution.phase(claim.owner, "preparing")
            if (index < 2) {
                assertFailsWith<IllegalStateException> { broken().selectPreparation(claim.owner, requested) }
                assertEquals(Status.RUNNING, store.job(id)!!.status)
                store.execution.loseSession(session.id, "infrastructure:failed")
                backend(Store(file)).reconcile()
                allocation = "error-replacement-$index"
                useReplacement(id, allocation, "node-$index")
            } else assertFailsWith<ExecutionFenced> { broken().selectPreparation(claim.owner, requested) }
        }
        assertEquals(Status.FAILED, store.job(id)!!.status)
        assertContains(store.job(id)!!.error!!, "problem selection failed 3 times")
        assertContains(store.job(id)!!.error!!, "broken selection invariant")
        assertTrue(store.execution.sessions(id).all { it.desired == Desired.STOP })
        repeat(3) { backend().reconcile() }
        assertEquals(3, store.execution.sessions(id).size)

        assertTrue(store.retry(id))
        backend().reconcile(); useReplacement(id, "manual-retry", "repaired-node")
        val retry = backend().claim(session.id, token, "manual-retry")!!
        store.execution.phase(retry.owner, "preparing")
        assertFailsWith<IllegalStateException> { broken().selectPreparation(retry.owner, requested) }
        assertEquals(Status.RUNNING, store.job(id)!!.status, "manual retry grants a fresh error budget")
        assertEquals(requested, backend().selectPreparation(retry.owner, requested))
    }

    @Test fun `selection outages and cancellation do not consume the poison error budget`() {
        val id = setup(preparation = true); register()
        val claim = backend().claim(session.id, token, "allocation")!!
        store.execution.phase(claim.owner, "preparing")
        val requested = listOf(listOf(benchProblem("p0")))
        val held = NomadBackend(store, control, artifacts, { root.resolve("jobs/$it") }, workAllowed = { false })
        val offline = NomadBackend(store, control, artifacts, { root.resolve("jobs/$it") },
            selectProblems = { _, _, _, _ -> throw java.io.IOException("temporary storage failure") })
        val cancelled = NomadBackend(store, control, artifacts, { root.resolve("jobs/$it") },
            selectProblems = { _, _, _, _ -> throw java.util.concurrent.CancellationException("shutting down") })
        repeat(4) {
            assertFailsWith<IllegalStateException> { held.selectPreparation(claim.owner, requested) }
            assertFailsWith<java.io.IOException> { offline.selectPreparation(claim.owner, requested) }
            assertFailsWith<java.util.concurrent.CancellationException> { cancelled.selectPreparation(claim.owner, requested) }
        }
        assertFalse(store.execution.events(id).any { it.detail.startsWith("preparation-error:") })
        assertEquals(Status.RUNNING, store.job(id)!!.status)
        assertEquals(requested, backend().selectPreparation(claim.owner, requested))
    }

    @Test fun `pending source checks preserve accepted output without blocking reconciliation or finishing early`() {
        val id = setup(1); register()
        val claim = backend().claim(session.id, token, "allocation")!!
        store.execution.phase(claim.owner, "uploading")
        backend().commit(claim.owner, IterationResult(claim.indices.map { ExecutionCaseResult(it, 0, now() - 1, now(), "{}") }))
        var ready = false
        val asynchronous = NomadBackend(store, control, artifacts, { root.resolve("jobs/$it") }, checkCase = { _, _ -> if (ready) "valid" else null })
        asynchronous.reconcile()
        assertEquals(Status.RUNNING, store.job(id)!!.status)
        assertEquals(1, store.execution.accepted(id).size)
        assertTrue(store.execution.checksComplete(id).isEmpty())
        ready = true; asynchronous.reconcile()
        assertEquals(Status.DONE, store.job(id)!!.status)
    }

    @Test fun `nondefault namespace scopes both submitted jobs and signed worker registration`() {
        control.namespace = "benchmarks"; setup(); backend().reconcile()
        assertEquals("benchmarks", control.jobs.getValue(session.nomadJobId).getValue("Namespace").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { backend().register(session.id, token, "allocation", jwt(namespace = "default"), "release") }
        assertEquals(manifest, register())
    }

    @Test fun `an intent Nomad keeps refusing neither blocks later intents nor stays forever`() {
        setup(sessionId = "colliding")
        val colliding = session
        control.jobs[colliding.nomadJobId] = buildJsonObject { putJsonObject("Meta") { put("lab_spec_hash", "other") } }
        setup(sessionId = "second")
        val backend = backend()

        backend.reconcile()
        assertTrue(control.jobs.containsKey(session.nomadJobId))
        assertEquals(listOf(colliding.id), store.execution.intents().map { it.session })

        repeat(NomadBackend.MAX_INTENT_ATTEMPTS - 1) { backend.reconcile() }
        assertTrue(store.execution.intents().isEmpty())
        assertTrue(store.execution.events(colliding.jobId).any { it.detail.startsWith("intent-abandoned:submit") })
    }

    @Test fun `cancelled jobs retain stop intents until a restarted coordinator delivers them`() {
        val id = setup(); backend().reconcile(); register()
        store.requestCancel(id)
        assertFalse(store.deleteJob(id))
        assertTrue(control.jobs.containsKey(session.nomadJobId))
        backend(Store(file)).reconcile()
        assertFalse(control.jobs.containsKey(session.nomadJobId))
        assertTrue(store.deleteJob(id))
        assertTrue(store.execution.intents().isEmpty())
    }

    @Test fun `profile timeout fails permanently and projects accepted capture output after restart`() {
        val id = setup(1, ExperimentSpec("profile", profile = ProfileMain(main = "com.eignex.Main", runTimeoutSec = 30, classpath = listOf("tools/lib/*"))), platform = NomadPlatform.MAC)
        register()
        val claim = store.execution.claim(session.id, token, "allocation")!!
        val bytes = "capture flushed during grace".toByteArray(); val hash = ExecutionStore.digest(String(bytes))
        artifacts.put(id, hash, bytes.size.toLong(), 0, ByteArrayInputStream(bytes))
        store.execution.phase(claim.owner, "uploading")
        backend().commit(claim.owner, IterationResult(claim.indices.map { ExecutionCaseResult(it, 124, now()-1, now()) }, mapOf("cases/0/profile.jfr" to hash), runTimedOut = true))
        backend(Store(file)).reconcile()
        assertEquals(Status.FAILED, store.job(id)!!.status)
        assertEquals(String(bytes), Files.readString(root.resolve("jobs/$id/cases/0/profile.jfr")))
        assertTrue(store.execution.sessions(id).all { it.desired == Desired.STOP })
        assertEquals(0, store.execution.failureCount(id))
    }

    @Test fun `repeated Spot loss retains committed results and finishes unfinished work on another machine`() {
        val id = setup(); register()
        val accepted = backend().claim(session.id, token, "allocation")!!
        store.execution.phase(accepted.owner, "uploading")
        val result = IterationResult(accepted.indices.map { ExecutionCaseResult(it, 0, now() - 1, now(), "{}") })
        backend().commit(accepted.owner, result)
        backend().reconcile()
        var allocation = "allocation"
        repeat(8) { index ->
            val previous = session
            val frozen = store.execution.manifest(previous.id)
            val original = store.execution.submission(previous.id)
            val claim = backend().claim(previous.id, token, allocation)!!
            assertTrue(claim.indices.none { it in accepted.indices })
            store.execution.phase(claim.owner, "executing")
            control.allocations[allocation] = control.allocations.getValue(allocation).copy(client = "lost")
            backend().reconcile()
            val restarted = Store(file)
            assertEquals(Status.RUNNING, restarted.job(id)!!.status)
            assertNull(restarted.job(id)!!.error)
            assertEquals(accepted.indices.size, restarted.job(id)!!.done)
            assertEquals(result, restarted.execution.accepted(id).single().second)
            assertFailsWith<ExecutionFenced> { restarted.execution.renew(claim.owner) }
            assertFailsWith<ExecutionFenced> { backend(restarted).commit(claim.owner, IterationResult(claim.indices.map { ExecutionCaseResult(it, 0, now() - 1, now(), "{}") })) }
            val replacement = restarted.execution.sessions(id).single { it.desired == Desired.RUN }
            assertNull(replacement.node)
            assertNull(replacement.allocation)
            assertEquals(frozen.copy(session = replacement.id), restarted.execution.manifest(replacement.id))
            assertEquals(original, restarted.execution.submission(previous.id))
            val constraints = Json.parseToJsonElement(restarted.execution.submission(replacement.id)).jsonObject.getValue("Constraints").jsonArray
            assertTrue(constraints.none { it.jsonObject["LTarget"]?.jsonPrimitive?.content == "${'$'}{node.unique.id}" && it.jsonObject["Operand"]?.jsonPrimitive?.content == "=" })
            allocation = "replacement-$index"
            useReplacement(id, allocation, "replacement-node-$index")
        }
        val remaining = backend().claim(session.id, token, allocation)!!
        store.execution.phase(remaining.owner, "uploading")
        backend().commit(remaining.owner, IterationResult(remaining.indices.map { ExecutionCaseResult(it, 0, now() - 1, now(), "{}") }))
        backend(Store(file)).reconcile()
        assertEquals(Status.DONE, store.job(id)!!.status)
        assertEquals(8, store.execution.failureCount(id))
        assertEquals(2, store.execution.accepted(id).size)
        assertEquals(8, store.execution.events(id).count { it.detail.startsWith("lost:infrastructure:") })
    }

    @Test fun `cloud admission refuses local placement exceptions and respects schedule cloud opt in`() {
        val spec = ExperimentSpec("test", problems = listOf(mapOf("suite" to "s")), host = HostPlacement.AUTO)
        for ((candidate, name) in listOf(spec.copy(host = HostPlacement.LOCAL) to "test", spec to "test${Confirm.SUFFIX}", spec to "schedule@sha",
            spec.copy(base = mapOf("backend" to REFERENCE_BACKEND, "solver" to "scip")) to "test")) {
            assertFailsWith<IllegalArgumentException>(name) { setup(1, candidate, name) }
        }
        store.schedules.create("schedule", "main", spec, 3600)
        setup(1, spec, "schedule@sha")
    }

    @Test fun `cloud workers can stage exact problem lists`() {
        val spec = ExperimentSpec("exact", problemList = listOf(Problem("s", "p0")), host = HostPlacement.AUTO)
        val id = setup(1, spec)
        assertEquals(id, session.jobId)
    }

    @Test fun `cloud workers enforce the whole run profile deadline`() {
        val spec = ExperimentSpec("bounded", profile = ProfileMain(main = "com.eignex.Main", runTimeoutSec = 1, classpath = listOf("tools/lib/*")), host = HostPlacement.AUTO)
        val id = setup(1, spec)
        assertEquals(id, session.jobId)
    }

    @Test fun `ambiguous registration retries persisted identity and coordinator restart retains claims`() {
        val id = setup(); control.failAfterSubmit = true
        backend().reconcile()
        assertEquals(1, store.execution.intents().size)
        backend(Store(file)).reconcile(); assertEquals(1, control.submissions); assertTrue(store.execution.intents().isEmpty())
        assertEquals(manifest, register())
        val claim = store.execution.claim(session.id, token, "allocation")!!
        backend(Store(file)).reconcile()
        assertEquals(claim.indices, Store(file).execution.claim(session.id, token, "allocation")!!.indices)
        assertEquals(0, store.job(id)!!.done)
    }

    @Test fun `signed workload allocation identity rejects spoofing and expired tokens`() {
        setup()
        assertFailsWith<IllegalArgumentException> { backend().register(session.id, token, "other", jwt(), "release") }
        assertFailsWith<IllegalArgumentException> { backend().register(session.id, token, "allocation", jwt(expires = now()/1000 - 1), "release") }
        val damaged = jwt().split('.').toMutableList().also { it[1] = b64("{}".toByteArray()) }.joinToString(".")
        assertFailsWith<IllegalArgumentException> { backend().register(session.id, token, "allocation", damaged, "release") }
        backend().register(session.id, token, "allocation", jwt(singleAudience = true), "release")
        register()
        assertFailsWith<IllegalArgumentException> { backend().register(session.id, token, "allocation", jwt(), "wrong-release") }
    }

    @Test fun `verified artifacts project only accepted complete iterations and survive restart`() {
        val id = setup(1); register()
        val claim = store.execution.claim(session.id, token, "allocation")!!
        val bytes = "case log".toByteArray(); val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        assertEquals(3, artifacts.put(id, sha, bytes.size.toLong(), 0, ByteArrayInputStream(bytes.take(3).toByteArray())))
        assertFailsWith<IllegalArgumentException> { artifacts.file(id, sha) }
        artifacts.put(id, sha, bytes.size.toLong(), 3, ByteArrayInputStream(bytes.drop(3).toByteArray()))
        store.execution.phase(claim.owner, "uploading")
        val result = IterationResult(claim.indices.map { ExecutionCaseResult(it, 0, now()-1, now(), "{}") }, mapOf("0.out" to sha))
        assertTrue(backend().commit(claim.owner, result)); assertTrue(backend().commit(claim.owner, result))
        assertFalse(Files.exists(root.resolve("jobs/$id/0.out")))
        backend(Store(file)).reconcile()
        assertEquals("case log", Files.readString(root.resolve("jobs/$id/0.out")))
        assertEquals(Status.DONE, store.job(id)!!.status)
    }

    @Test fun `provisional log retries are bounded and fenced without overwriting accepted output`() {
        val id = setup(); register()
        val claim = store.execution.claim(session.id, token, "allocation")!!
        val logs = backend().logs; val bytes = "provisional".toByteArray()
        assertEquals(bytes.size.toLong(), logs.append(claim.owner, 0, "out", 0, bytes))
        assertEquals(bytes.size.toLong(), logs.append(claim.owner, 0, "out", 0, bytes))
        assertFailsWith<IllegalArgumentException> { logs.append(claim.owner, 0, "out", 0, "different".toByteArray()) }
        val directory = root.resolve("jobs/$id")
        assertEquals("provisional", Files.readString(WorkerLogs.resolve(store, directory, id, listOf("0.out"))!!))
        assertFalse(Files.exists(directory.resolve("0.out")))
        assertFailsWith<ExecutionFenced> { logs.append(claim.owner, 99, "out", 0, bytes) }
        var offset = logs.append(claim.owner, 1, "err", 0, byteArrayOf(1))
        while (offset < WorkerLogs.MAX_BYTES) offset = logs.append(claim.owner, 1, "err", offset, ByteArray(WorkerLogs.CHUNK_BYTES))
        assertEquals(WorkerLogs.MAX_BYTES, offset)
        assertEquals(offset, logs.append(claim.owner, 1, "err", offset, bytes))
        store.execution.loseSession(session.id, "revoked")
        assertNull(WorkerLogs.resolve(store, directory, id, listOf("0.out")))
        assertFailsWith<ExecutionFenced> { logs.append(claim.owner, 0, "out", bytes.size.toLong(), bytes) }
    }

    @Test fun `durable event projection supplies lab tail and never recreates trimmed ended jobs`() {
        val id = setup(); register(); val backend = backend()
        backend.reconcile()
        val path = root.resolve("jobs/$id/job.log")
        assertTrue(Files.readString(path).contains("session-created"))
        assertFalse(Files.readString(path).contains(token))
        store.requestCancel(id); backend.reconcile()
        assertTrue(Files.readString(path).contains("CANCELLED"))
        deleteTree(path.parent); backend.reconcile()
        assertFalse(Files.exists(path.parent))
    }

    @Test fun `accepted output still ingests after cancellation wins before reconciliation`() {
        val id = setup(); register()
        val claim = store.execution.claim(session.id, token, "allocation")!!
        val bytes = "accepted before cancel".toByteArray(); val hash = ExecutionStore.digest(String(bytes))
        artifacts.put(id, hash, bytes.size.toLong(), 0, ByteArrayInputStream(bytes))
        store.execution.phase(claim.owner, "uploading")
        backend().commit(claim.owner, IterationResult(claim.indices.map { ExecutionCaseResult(it, 0, now()-1, now(), "{}") }, mapOf("0.out" to hash)))
        store.requestCancel(id)
        assertEquals(listOf(id), store.execution.pendingProjectionJobs())
        backend().reconcile()
        assertEquals(Status.CANCELLED, store.job(id)!!.status)
        assertEquals("accepted before cancel", Files.readString(root.resolve("jobs/$id/0.out")))
        assertTrue(store.execution.pendingProjectionJobs().isEmpty())
    }

    @Test fun `lost allocation fences incomplete iteration and enqueues a replacement with new credentials`() {
        val id = setup(); register()
        val claim = store.execution.claim(session.id, token, "allocation")!!
        store.execution.phase(claim.owner, "executing")
        control.allocations["allocation"] = control.allocations.getValue("allocation").copy(client = "lost")
        backend().reconcile()
        assertFailsWith<ExecutionFenced> { store.execution.renew(claim.owner) }
        val replacement = store.execution.sessions(id).single { it.desired == Desired.RUN }
        assertNotEquals(session.id, replacement.id)
        assertEquals(1, store.execution.failureCount(id))
        assertEquals(2, store.execution.intents().size)
        assertFailsWith<ExecutionFenced> { store.execution.credential(replacement.id, token) }
        assertTrue(store.job(id)!!.commands.all { it.status == Status.QUEUED })
    }

    @Test fun `an admission hold preserves active worker leases and progress but blocks new work`() = testApplication {
        setup(1)
        var allowed = true
        application { workerApi(store, backend(), artifacts) { allowed } }
        fun HttpRequestBuilder.credentials() { header(HttpHeaders.Authorization, "Bearer $token"); header("X-Lab-Allocation", "allocation"); header("X-Lab-Identity", jwt()) }
        client.post("/worker/v1/sessions/session/register") { credentials(); setBody(Json.encodeToString(WorkerRegistration("release"))) }
        val claim = Json.decodeFromString<IterationClaim>(client.post("/worker/v1/sessions/session/claim") { credentials() }.bodyAsText())
        allowed = false
        assertEquals(HttpStatusCode.OK, client.post("/worker/v1/sessions/session/renew") { credentials(); setBody(Json.encodeToString(claim.owner)) }.status)
        assertEquals(HttpStatusCode.NoContent, client.post("/worker/v1/sessions/session/phase") { credentials(); setBody(Json.encodeToString(WorkerPhase(claim.owner, "executing"))) }.status)
        assertEquals(HttpStatusCode.NoContent, client.post("/worker/v1/sessions/session/progress") { credentials(); setBody(Json.encodeToString(WorkerProgress(claim.owner, claim.indices.first()))) }.status)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/worker/v1/sessions/session/claim") { credentials() }.status)
        store.requestCancel(session.jobId)
        assertEquals(HttpStatusCode.Conflict, client.post("/worker/v1/sessions/session/renew") { credentials(); setBody(Json.encodeToString(claim.owner)) }.status)
    }

    @Test fun `worker API has no operator routes and scopes mutation and uploads to its signed allocation`() = testApplication {
        setup(1)
        application { workerApi(store, backend(), artifacts) { true } }
        fun HttpRequestBuilder.credentials() { header(HttpHeaders.Authorization, "Bearer $token"); header("X-Lab-Allocation", "allocation"); header("X-Lab-Identity", jwt()) }
        assertEquals(HttpStatusCode.NotFound, client.get("/jobs").status)
        assertEquals(HttpStatusCode.Conflict, client.post("/worker/v1/sessions/session/register") { setBody(Json.encodeToString(WorkerRegistration("release"))) }.status)
        assertEquals(HttpStatusCode.OK, client.post("/worker/v1/sessions/session/register") { credentials(); setBody(Json.encodeToString(WorkerRegistration("release"))) }.status)
        val claimed = client.post("/worker/v1/sessions/session/claim") { credentials() }
        val claim = Json.decodeFromString<IterationClaim>(claimed.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, client.get("/worker/v1/sessions/session/inputs/" + "f".repeat(64)) { credentials() }.status)
        assertEquals(HttpStatusCode.NoContent, client.post("/worker/v1/sessions/session/phase") { credentials(); setBody(Json.encodeToString(WorkerPhase(claim.owner, "executing"))) }.status)
        assertEquals(HttpStatusCode.OK, client.put("/worker/v1/sessions/session/logs/0/out?offset=0") {
            credentials(); header("X-Lab-Attempt", Json.encodeToString(claim.owner)); setBody("live output")
        }.status)
        assertEquals(HttpStatusCode.Conflict, client.put("/worker/v1/sessions/session/logs/0/out?offset=0") {
            credentials(); setBody("missing attempt")
        }.status)
        assertEquals(HttpStatusCode.NoContent, client.post("/worker/v1/sessions/session/progress") { credentials(); setBody(Json.encodeToString(WorkerProgress(claim.owner, claim.indices.first()))) }.status)
        store.requestCancel(session.jobId)
        val late = client.post("/worker/v1/sessions/session/renew") { credentials(); setBody(Json.encodeToString(claim.owner)) }
        assertEquals(HttpStatusCode.Conflict, late.status)
    }
}
