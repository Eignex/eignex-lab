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
    private fun setup(problems: Int = 2): Long {
        val spec = ExperimentSpec("test", problems = listOf(mapOf("suite" to "s")), grid = mapOf("x" to listOf("a", "b")), seeds = listOf(1))
        val arms = Experiments.arms(spec).map { PlannedArm(it, "a".repeat(40)) }
        val cases = Experiments.cases(problems, arms.size, spec.seeds)
        val plannedProblems = List(problems) { Problem("s", "p$it") }
        val id = store.create("test", "main", emptyList(), parallel = 2, experiment = spec, backend = ExecutionBackendKind.NOMAD)
        store.plan(id, arms, plannedProblems, cases, cases.map { "true" to 5L })
        val bundle = WorkerBundle("b".repeat(64), listOf(InputFile("bin/bench", "c".repeat(64), 5)), Repositories.DEFAULT, "a".repeat(40), "linux", "amd64", "test")
        manifest = WorkManifest("session", "release", spec, arms, cases.mapIndexed { index, case -> WorkCase(index, case.arm, plannedProblems[case.problem], case.seed, case.repeat, 5) }, listOf(bundle), ResourceBudget(2000, 1024, 2))
        session = backend().enqueue(id, manifest, NomadBatchJob("placeholder", NomadPlatform.LINUX, "linux", NomadCpuCapacity(4000, 4), manifest.budget, listOf("worker"), "registry/worker@sha256:" + "d".repeat(64)), "http://127.0.0.1:8091")
        token = Json.parseToJsonElement(store.execution.submission(session.id)).jsonObject.getValue("TaskGroups").jsonArray[0].jsonObject.getValue("Tasks").jsonArray[0].jsonObject.getValue("Env").jsonObject.getValue("LAB_WORKER_TOKEN").jsonPrimitive.content
        control.allocations["allocation"] = NomadAllocation("allocation", session.nomadJobId, "node", "running", "run")
        return id
    }
    private fun jwt(allocation: String = "allocation", expires: Long = now()/1000 + 60, singleAudience: Boolean = false): String {
        val header = b64("""{"alg":"RS256","kid":"test"}""".toByteArray())
        val claims = buildJsonObject { put("exp", expires); if (singleAudience) put("aud", "eignex-lab-worker") else putJsonArray("aud") { add("eignex-lab-worker") }; put("nomad_namespace", "default"); put("nomad_job_id", session.nomadJobId); put("nomad_task", "worker"); put("nomad_allocation_id", allocation) }
        val payload = "$header.${b64(claims.toString().toByteArray())}"
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(payload.toByteArray()) }.sign()
        return "$payload.${b64(signature)}"
    }
    private fun register() = backend().register(session.id, token, "allocation", jwt(), "release")

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
        assertEquals(HttpStatusCode.NoContent, client.post("/worker/v1/sessions/session/progress") { credentials(); setBody(Json.encodeToString(WorkerProgress(claim.owner, claim.indices.first()))) }.status)
        store.requestCancel(session.jobId)
        val late = client.post("/worker/v1/sessions/session/renew") { credentials(); setBody(Json.encodeToString(claim.owner)) }
        assertEquals(HttpStatusCode.Conflict, late.status)
    }
}
