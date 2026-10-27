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
    @Test fun `sharing policy retires a bound worker only after its current accepted iteration`() {
        val id = setup()
        backend().reconcile(); register()
        val claim = store.execution.claim(session.id, token, "allocation")!!
        val frozen = store.execution.manifest(session.id)
        val changed = NomadBackend(store, control, artifacts, { root.resolve("jobs/$it") }, placementPolicy = { job ->
            JsonObject(job + ("Meta" to JsonObject(job.getValue("Meta").jsonObject + ("lab_per_host_parallel" to JsonPrimitive("4")))))
        })
        changed.reconcile()
        assertEquals("drain", store.execution.session(session.id)!!.desired)
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
        val next = store.execution.sessions(id).single { it.desired == "run" }
        assertEquals(frozen.copy(session = next.id, release = "patched"), store.execution.manifest(next.id))
        assertEquals(before, store.execution.submission(previous.id))
        val payload = Json.parseToJsonElement(store.execution.submission(next.id)).jsonObject
        val task = payload.getValue("TaskGroups").jsonArray.single().jsonObject.getValue("Tasks").jsonArray.single().jsonObject
        assertEquals(template.image, task.getValue("Config").jsonObject.getValue("image").jsonPrimitive.content)
        assertEquals("patched", task.getValue("Env").jsonObject.getValue("LAB_WORKER_RELEASE").jsonPrimitive.content)
        assertEquals(0, store.execution.failureCount(id))
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

        val replacement = store.execution.sessions(id).single { it.desired == "run" }
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
        assertEquals(listOf(session.id), store.execution.sessions(id).filter { it.desired == "run" }.map { it.id })
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
        override fun jwks(): JsonObject {
            val key = pair.public as RSAPublicKey
            return buildJsonObject { putJsonArray("keys") { add(buildJsonObject { put("kid", "test"); put("kty", "RSA"); put("n", b64(key.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())); put("e", b64(key.publicExponent.toByteArray())) }) } }
        }
    }
    private fun backend(s: Store = store) = NomadBackend(s, control, artifacts, { root.resolve("jobs/$it") })
    private lateinit var session: ExecutionSession
    private lateinit var token: String
    private lateinit var manifest: WorkManifest
    private fun setup(problems: Int = 2, spec: ExperimentSpec = ExperimentSpec("test", problems = listOf(mapOf("suite" to "s")), grid = mapOf("x" to listOf("a", "b")), seeds = listOf(1), host = "auto"), name: String = spec.name, platform: NomadPlatform = NomadPlatform.LINUX, preparation: Boolean = false, sessionId: String = "session"): Long {
        val arms = Experiments.arms(spec).map { PlannedArm(it, "a".repeat(40)) }
        val cases = Experiments.cases(problems, arms.size, spec.seeds)
        val plannedProblems = List(problems) { Problem("s", "p$it") }
        val id = store.create(name, "main", emptyList(), parallel = 2, experiment = spec)
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
    private fun jwt(allocation: String = "allocation", expires: Long = now()/1000 + 60, singleAudience: Boolean = false, namespace: String = control.namespace): String {
        val header = b64("""{"alg":"RS256","kid":"test"}""".toByteArray())
        val claims = buildJsonObject { put("exp", expires); if (singleAudience) put("aud", "eignex-lab-worker") else putJsonArray("aud") { add("eignex-lab-worker") }; put("nomad_namespace", namespace); put("nomad_job_id", session.nomadJobId); put("nomad_task", "worker"); put("nomad_allocation_id", allocation) }
        val payload = "$header.${b64(claims.toString().toByteArray())}"
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(payload.toByteArray()) }.sign()
        return "$payload.${b64(signature)}"
    }
    private fun register(allocation: String = "allocation") = backend().register(session.id, token, allocation, jwt(allocation), "release")
    private fun useReplacement(id: Long, allocation: String, node: String) {
        session = store.execution.sessions(id).single { it.desired == "run" }
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
        assertTrue(store.execution.sessions(job).all { it.desired == "stop" }, "another worker's reservation must fit too")
        configured(4000).reconcile()
        val replacement = store.execution.sessions(job).single { it.desired != "stop" }
        assertNotEquals(previous.id, replacement.id)
        assertEquals(previous.budget, replacement.budget)
        assertEquals(frozen.copy(session = replacement.id), store.execution.manifest(replacement.id))
        assertEquals(originalSubmission, store.execution.submission(previous.id))
        val meta = Json.parseToJsonElement(store.execution.submission(replacement.id)).jsonObject.getValue("Meta").jsonObject
        assertEquals("4000", meta.getValue("lab_native_cpu_limit").jsonPrimitive.content)
        assertEquals("4096", meta.getValue("lab_native_memory_limit").jsonPrimitive.content)
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
            val replacement = store.execution.sessions(id).single { it.desired == "run" }
            assertEquals("prepare", replacement.executionClass)
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

    @Test fun `accepted preparation is retained without completing the experiment or preparing twice`() {
        val id = setup(preparation = true); backend().reconcile(); register()
        val claim = backend().claim(session.id, token, "allocation")!!
        val bytes = "prepared source".toByteArray(); val sha = ExecutionStore.digest(String(bytes))
        artifacts.put(id, sha, bytes.size.toLong(), 0, ByteArrayInputStream(bytes))
        val prepared = manifest.bundles.single().copy(sha256 = sha)
        val outputs = mapOf("preparation/${prepared.commit}.zip" to sha)
        store.execution.phase(claim.owner, "preparing")
        val selected = backend().selectPreparation(claim.owner, listOf(listOf(Problem("s", "p"))))
        val preparation = PreparationResult(selected, listOf(prepared), artifacts = outputs)
        store.execution.phase(claim.owner, "uploading")
        backend().commit(claim.owner, IterationResult(emptyList(), outputs, preparation = preparation))
        backend(Store(file)).reconcile()
        assertEquals(Status.RUNNING, store.job(id)!!.status)
        assertFalse(store.job(id)!!.setupDone)
        assertEquals(1, store.execution.sessions(id).size)
        assertTrue(store.execution.sessions(id).all { it.desired == "stop" })
        assertEquals(preparation, store.execution.accepted(id).single().second.preparation)
        assertEquals("prepared source", Files.readString(root.resolve("jobs/$id/preparation/${prepared.commit}.zip")))
        val problems = selected.flatten()
        val cases = Experiments.cases(problems.size, manifest.arms.size, manifest.experiment.seeds)
        store.plan(id, manifest.arms, problems, cases, cases.map { "true" to 5L })
        repeat(3) { backend(Store(file)).reconcile() }
        assertEquals(1, store.execution.sessions(id).size, "published cases must not resurrect accepted preparation")
    }

    @Test fun `selection approval is durable idempotent and cannot be changed by a preparing worker`() {
        val spec = ExperimentSpec("selection", problems = listOf(mapOf("suite" to "s", "reference" to "proven", "max" to "1")), host = "auto")
        val id = setup(2, spec, preparation = true); register()
        val claim = backend().claim(session.id, token, "allocation")!!
        store.execution.phase(claim.owner, "preparing")
        store.putReferences(listOf(("" to "p1") to Reference("gecode", false, 1.0, true, true, 100, 60000)), "fixture")
        val requested = listOf(listOf(Problem("s", "p0"), Problem("s", "p1")))
        val selected = backend().selectPreparation(claim.owner, requested)
        assertEquals(listOf("p1"), selected.single().map { it.problem })
        store.retireReferenceRows("", "gecode")
        assertEquals(selected, backend(Store(file)).selectPreparation(claim.owner, requested))
        assertFailsWith<ExecutionFenced> { backend().selectPreparation(claim.owner, requested.map { it.reversed() }) }
        val bytes = "source".toByteArray(); val sha = ExecutionStore.digest(String(bytes))
        artifacts.put(id, sha, bytes.size.toLong(), 0, ByteArrayInputStream(bytes))
        val outputs = mapOf("preparation/${manifest.bundles.single().commit}.zip" to sha)
        val forged = PreparationResult(listOf(listOf(Problem("s", "p0"))), listOf(manifest.bundles.single().copy(sha256 = sha)), artifacts = outputs)
        store.execution.phase(claim.owner, "uploading")
        assertFailsWith<IllegalArgumentException> { backend().commit(claim.owner, IterationResult(emptyList(), outputs, preparation = forged)) }
        assertTrue(store.execution.accepted(id).isEmpty())
    }

    @Test fun `extra substring annotations do not reject or expand an approved preparation`() {
        val spec = ExperimentSpec("annotations", problems = listOf(mapOf("suite" to "s", "reference" to "any")), host = "auto")
        val id = setup(1, spec, preparation = true); register()
        val claim = backend().claim(session.id, token, "allocation")!!
        val bytes = "prepared source".toByteArray(); val sha = ExecutionStore.digest(String(bytes))
        artifacts.put(id, sha, bytes.size.toLong(), 0, ByteArrayInputStream(bytes))
        val prepared = manifest.bundles.single().copy(sha256 = sha)
        val outputs = mapOf("preparation/${prepared.commit}.zip" to sha)
        store.execution.phase(claim.owner, "preparing")
        val selected = backend().selectPreparation(claim.owner, listOf(listOf(Problem("s", "ej", collection = "miplib"))))
        val feature = ProblemFeatures("miplib", "ej", listOf("mip"))
        val preparation = PreparationResult(selected, listOf(prepared), features = listOf(feature, feature.copy(problem = "sutlej")), artifacts = outputs)
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
        val spec = ExperimentSpec("empty", problems = listOf(mapOf("suite" to "s", "reference" to "proven")), host = "auto")
        val id = setup(1, spec, preparation = true); register()
        val claim = backend().claim(session.id, token, "allocation")!!
        store.execution.phase(claim.owner, "preparing")
        assertFailsWith<ExecutionFenced> { backend().selectPreparation(claim.owner, listOf(listOf(Problem("s", "p0")))) }
        assertEquals(Status.FAILED, store.job(id)!!.status)
        assertTrue(store.job(id)!!.error!!.contains("reference filter"))
        assertTrue(store.execution.sessions(id).all { it.desired == "stop" })
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
            val replacement = restarted.execution.sessions(id).single { it.desired == "run" }
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
        val spec = ExperimentSpec("test", problems = listOf(mapOf("suite" to "s")), host = "auto")
        for ((candidate, name) in listOf(spec.copy(host = "lab") to "test", spec to "test${Confirm.SUFFIX}", spec to "schedule@sha",
            spec.copy(base = mapOf("backend" to REFERENCE_BACKEND, "solver" to "scip")) to "test")) {
            assertFailsWith<IllegalArgumentException>(name) { setup(1, candidate, name) }
        }
        store.createSchedule("schedule", "main", spec, 3600)
        setup(1, spec, "schedule@sha")
    }

    @Test fun `cloud workers can stage exact problem lists`() {
        val spec = ExperimentSpec("exact", problemList = listOf(Problem("s", "p0")), host = "auto")
        val id = setup(1, spec)
        assertEquals(id, session.jobId)
    }

    @Test fun `cloud workers enforce the whole run profile deadline`() {
        val spec = ExperimentSpec("bounded", profile = ProfileMain(main = "com.eignex.Main", runTimeoutSec = 1), host = "auto")
        val id = setup(1, spec)
        assertEquals(id, session.jobId)
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
