package com.eignex.lab

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.*

class NomadBackendTest {
    private val root = Files.createTempDirectory("nomad-backend")
    private val file = root.resolve("lab.db")
    private val store = Store(file)
    private val artifacts = LocalArtifactStore(root.resolve("artifacts"))
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private val control = object : NomadControl {
        override var namespace = "default"
        val jobs = mutableMapOf<String, JsonObject>(); val allocations = mutableMapOf<String, NomadAllocation>()
        var submissions = 0; var failAfterSubmit = false
        override fun job(id: String) = jobs[id]
        override fun submit(job: JsonObject) { submissions++; jobs[job.getValue("ID").jsonPrimitive.content] = job; if (failAfterSubmit) { failAfterSubmit = false; error("lost acknowledgement") } }
        override fun stop(id: String) { jobs.remove(id) }
        override fun allocations(job: String) = allocations.values.filter { it.job == job }
        override fun allocation(id: String) = allocations[id]
        override fun jwks(): JsonObject {
            val key = pair.public as RSAPublicKey
            return buildJsonObject { putJsonArray("keys") { add(buildJsonObject { put("kid", "test"); put("kty", "RSA"); put("n", b64(key.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())); put("e", b64(key.publicExponent.toByteArray())) }) } }
        }
    }
    private fun backend(s: Store = store) = NomadBackend(s, control, artifacts, { root.resolve("jobs/$it") })
    private lateinit var session: ExecutionSession
    private lateinit var token: String
    private lateinit var manifest: WorkManifest
    private fun setup(problems: Int = 2, spec: ExperimentSpec = ExperimentSpec("test", problems = listOf(mapOf("suite" to "s")), grid = mapOf("x" to listOf("a", "b")), seeds = listOf(1), host = "auto"), name: String = spec.name, platform: NomadPlatform = NomadPlatform.LINUX, preparation: Boolean = false): Long {
        val arms = Experiments.arms(spec).map { PlannedArm(it, "a".repeat(40)) }
        val cases = Experiments.cases(problems, arms.size, spec.seeds)
        val plannedProblems = List(problems) { Problem("s", "p$it") }
        val id = store.create(name, "main", emptyList(), parallel = 2, experiment = spec, backend = ExecutionBackendKind.NOMAD)
        if (!preparation) store.plan(id, arms, plannedProblems, cases, cases.map { "true" to 5L })
        val bundle = WorkerBundle("b".repeat(64), listOf(InputFile("bin/bench", "c".repeat(64), 5)), Repositories.DEFAULT, "a".repeat(40), "linux", "amd64", "test")
        manifest = WorkManifest("session", "release", spec, arms, if (preparation) emptyList() else cases.mapIndexed { index, case -> WorkCase(index, case.arm, plannedProblems[case.problem], case.seed, case.repeat, 5) }, listOf(bundle), ResourceBudget(2000, 1024, 2), preparationTimeoutSec = if (preparation) 60 else null)
        val command = if (platform == NomadPlatform.LINUX) listOf("worker") else listOf("/releases/fixture/bin/eignex-lab", "worker")
        val image = if (platform == NomadPlatform.LINUX) "registry/worker@sha256:" + "d".repeat(64) else null
        session = backend().enqueue(id, manifest, NomadBatchJob("placeholder", platform, "fixture", NomadCpuCapacity(4000, 4), manifest.budget, command, image), "http://127.0.0.1:8091")
        token = Json.parseToJsonElement(store.execution.submission(session.id)).jsonObject.getValue("TaskGroups").jsonArray[0].jsonObject.getValue("Tasks").jsonArray[0].jsonObject.getValue("Env").jsonObject.getValue("LAB_WORKER_TOKEN").jsonPrimitive.content
        control.allocations["allocation"] = NomadAllocation("allocation", session.nomadJobId, "node", "running", "run")
        return id
    }
    private fun jwt(allocation: String = "allocation", expires: Long = now()/1000 + 60, singleAudience: Boolean = false, namespace: String = control.namespace): String {
        val header = b64("""{"alg":"RS256","kid":"test"}""".toByteArray())
        val claims = buildJsonObject { put("exp", expires); if (singleAudience) put("aud", "eignex-lab-worker") else putJsonArray("aud") { add("eignex-lab-worker") }; put("nomad_namespace", namespace); put("nomad_job_id", session.nomadJobId); put("nomad_task", "worker"); put("nomad_allocation_id", allocation) }
        val payload = "$header.${b64(claims.toString().toByteArray())}"
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(payload.toByteArray()) }.sign()
        return "$payload.${b64(signature)}"
    }
    private fun register() = backend().register(session.id, token, "allocation", jwt(), "release")

    @Test fun `Spot loss replaces preparation before there are planned commands`() {
        val id = setup(preparation = true); backend().reconcile(); register()
        val first = backend().claim(session.id, token, "allocation")!!
        assertEquals("preparation", first.owner.iteration)
        control.allocations["allocation"] = control.allocations.getValue("allocation").copy(client = "lost")
        backend(Store(file)).reconcile()
        val replacement = store.execution.sessions(id).single { it.desired != "stop" }
        assertEquals("prepare", replacement.executionClass)
        assertNotEquals(session.id, replacement.id)
        assertNotNull(store.execution.manifest(replacement.id).preparationTimeoutSec)
        assertTrue(store.job(id)!!.commands.isEmpty())
        assertFailsWith<ExecutionFenced> { store.execution.renew(first.owner) }
    }

    @Test fun `accepted preparation is retained without completing the experiment or preparing twice`() {
        val id = setup(preparation = true); backend().reconcile(); register()
        val claim = backend().claim(session.id, token, "allocation")!!
        val bytes = "prepared source".toByteArray(); val sha = ExecutionStore.digest(String(bytes))
        artifacts.put(id, sha, bytes.size.toLong(), 0, ByteArrayInputStream(bytes))
        val prepared = manifest.bundles.single().copy(sha256 = sha)
        val outputs = mapOf("preparation/${prepared.commit}.zip" to sha)
        val preparation = PreparationResult(listOf(listOf(Problem("s", "p"))), listOf(prepared), artifacts = outputs)
        store.execution.phase(claim.owner, "uploading")
        backend().commit(claim.owner, IterationResult(emptyList(), outputs, preparation = preparation))
        backend(Store(file)).reconcile()
        assertEquals(Status.RUNNING, store.job(id)!!.status)
        assertFalse(store.job(id)!!.setupDone)
        assertEquals(1, store.execution.sessions(id).size)
        assertTrue(store.execution.sessions(id).all { it.desired == "stop" })
        assertEquals(preparation, store.execution.accepted(id).single().second.preparation)
        assertEquals("prepared source", Files.readString(root.resolve("jobs/$id/preparation/${prepared.commit}.zip")))
    }

    @Test fun `nondefault namespace scopes both submitted jobs and signed worker registration`() {
        control.namespace = "benchmarks"; setup(); backend().reconcile()
        assertEquals("benchmarks", control.jobs.getValue(session.nomadJobId).getValue("Namespace").jsonPrimitive.content)
        assertFailsWith<IllegalArgumentException> { backend().register(session.id, token, "allocation", jwt(namespace = "default"), "release") }
        assertEquals(manifest, register())
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
        val id = setup(1, ExperimentSpec("profile", profile = ProfileMain(main = "com.eignex.Main", runTimeoutSec = 30)), platform = NomadPlatform.MAC)
        register()
        val claim = store.execution.claim(session.id, token, "allocation")!!
        val bytes = "capture flushed during grace".toByteArray(); val hash = ExecutionStore.digest(String(bytes))
        artifacts.put(id, hash, bytes.size.toLong(), 0, ByteArrayInputStream(bytes))
        store.execution.phase(claim.owner, "uploading")
        backend().commit(claim.owner, IterationResult(claim.indices.map { ExecutionCaseResult(it, 124, now()-1, now()) }, mapOf("cases/0/profile.jfr" to hash), runTimedOut = true))
        backend(Store(file)).reconcile()
        assertEquals(Status.FAILED, store.job(id)!!.status)
        assertEquals(String(bytes), Files.readString(root.resolve("jobs/$id/cases/0/profile.jfr")))
        assertTrue(store.execution.sessions(id).all { it.desired == "stop" })
        assertEquals(0, store.execution.failureCount(id))
    }

    @Test fun `explicit retry renews the infrastructure allowance without deleting loss history`() {
        val id = setup()
        repeat(4) { index ->
            val active = store.execution.sessions(id).single { it.desired == "run" }
            control.allocations.clear()
            control.allocations["failed-$index"] = NomadAllocation("failed-$index", active.nomadJobId, "node", "failed", "run")
            backend().reconcile()
        }
        assertEquals(Status.FAILED, store.job(id)!!.status)
        assertEquals(4, store.execution.failureCount(id))
        assertTrue(store.retry(id))
        assertEquals(0, Store(file).execution.failureCount(id))
        backend(Store(file)).reconcile()
        assertEquals(Status.QUEUED, store.job(id)!!.status)
        assertEquals(1, store.execution.sessions(id).count { it.desired == "run" })
        assertEquals(4, store.execution.events(id).count { it.detail.startsWith("lost:infrastructure:") })
    }

    @Test fun `cloud admission refuses local placement exceptions and respects schedule cloud opt in`() {
        val spec = ExperimentSpec("test", problems = listOf(mapOf("suite" to "s")), host = "auto")
        for ((candidate, name) in listOf(spec.copy(host = "lab") to "test", spec to "test${Confirm.SUFFIX}", spec to "schedule@sha",
            spec.copy(problemList = listOf(Problem("s", "p0"))) to "test",
            spec.copy(base = mapOf("backend" to REFERENCE_BACKEND, "solver" to "scip")) to "test",
            spec.copy(profile = ProfileMain(main = "Main", runTimeoutSec = 1)) to "test")) {
            assertFailsWith<IllegalArgumentException>(name) { setup(1, candidate, name) }
        }
        store.createSchedule("schedule", "main", spec, 3600)
        setup(1, spec, "schedule@sha")
    }

    @Test fun `ambiguous registration retries persisted identity and coordinator restart retains claims`() {
        val id = setup(); control.failAfterSubmit = true
        assertFailsWith<IllegalStateException> { backend().reconcile() }
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
        val replacement = store.execution.sessions(id).single { it.desired == "run" }
        assertNotEquals(session.id, replacement.id)
        assertEquals(1, store.execution.failureCount(id))
        assertEquals(2, store.execution.intents().size)
        assertFailsWith<ExecutionFenced> { store.execution.credential(replacement.id, token) }
        assertTrue(store.job(id)!!.commands.all { it.status == Status.QUEUED })
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
