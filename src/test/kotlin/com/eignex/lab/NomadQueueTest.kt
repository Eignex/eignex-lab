package com.eignex.lab

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class NomadQueueTest {
    private val root = Files.createTempDirectory("nomad-queue")
    private val store = Store(root.resolve("lab.db"))
    private val artifacts = LocalArtifactStore(root.resolve("artifacts"))
    private val control = object : NomadControl {
        val jobs = mutableMapOf<String, JsonObject>()
        override fun job(id: String) = jobs[id]
        override fun submit(job: JsonObject) { jobs[job.getValue("ID").jsonPrimitive.content] = job }
        override fun stop(id: String) { jobs.remove(id) }
        override fun allocations(job: String) = emptyList<NomadAllocation>()
        override fun allocation(id: String): NomadAllocation? = null
        override fun jwks() = buildJsonObject { putJsonArray("keys") {} }
    }
    private val backend = NomadBackend(store, control, artifacts, { root.resolve("jobs/$it") })
    private fun target(platform: NomadPlatform, name: String, market: CapacityMarket = CapacityMarket.SPOT): NomadQueueTarget {
        val mac = platform == NomadPlatform.MAC
        return NomadQueueTarget(NomadBatchJob("template", platform, name, NomadCpuCapacity(8000, 8), ResourceBudget(4000, 4096, 2),
            if (mac) listOf("/releases/fixture/bin/eignex-lab", "worker") else listOf("worker"),
            if (mac) null else "registry/worker@sha256:" + "d".repeat(64)), setOf(if (mac) "arch:arm64" else "arch:x86_64"), market)
    }
    private val settings = NomadQueueSettings("release", "http://127.0.0.1:8091",
        listOf(target(NomadPlatform.MAC, "native"), target(NomadPlatform.LINUX, "portable-spot")),
        ResourceBudget(1000, 512), caseMemoryMiB = 512, sessionMemoryMiB = 256, maxSessionsPerJob = 4)
    private val file = root.resolve("queue.json").also { Files.writeString(it, Json.encodeToString(settings)) }
    private val config = Config(dataDir = root, jobsDir = root.resolve("jobs"), minFreeBytes = 0, solveJavaOpts = "-Xmx256m", cores = 8,
        cpuCapabilities = CpuCapabilities("arm64", setOf("neon"), "fixture", true), executionBackend = ExecutionBackendKind.NOMAD, nomadQueueSettings = file)
    private var publications = 0
    private var completions = 0
    private fun input(job: Long, os: String, arch: String): WorkerBundle {
        val text = "fixture"
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("fixture")); zip.write(text.toByteArray()); zip.closeEntry()
        } }.toByteArray()
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).hex()
        artifacts.put(job, sha, bytes.size.toLong(), 0, ByteArrayInputStream(bytes))
        return WorkerBundle(sha, listOf(InputFile("fixture", ExecutionStore.digest(text), text.length.toLong())), Repositories.DEFAULT,
            "a".repeat(40), os, arch, "fixture")
    }
    private fun queue(allowed: () -> Boolean = { true }) = NomadQueue(config, store, backend, artifacts, settings, allowed,
        completed = { completions++ }, nativeAllowed = { true }, source = { job, targets, _ ->
            publications++
            Experiments.arms(job.experiment!!).map { PlannedArm(it, "a".repeat(40)) } to targets.map { input(job.id, it.os, it.arch) }
        })
    private fun job(spec: ExperimentSpec = ExperimentSpec("portable", problems = listOf(mapOf("suite" to "s")), host = "auto")): Long =
        store.create(spec.name, "main", emptyList(), parallel = 2, experiment = spec, backend = ExecutionBackendKind.NOMAD)
    private fun prepare(id: Long, count: Int = 3) {
        val session = store.execution.sessions(id).single { it.executionClass == "prepare" }
        val recipe = store.execution.manifest(session.id)
        val submission = Json.parseToJsonElement(store.execution.submission(session.id)).jsonObject
        val token = submission.getValue("TaskGroups").jsonArray[0].jsonObject.getValue("Tasks").jsonArray[0].jsonObject.getValue("Env").jsonObject.getValue("LAB_WORKER_TOKEN").jsonPrimitive.content
        store.execution.bind(session.id, token, "prep-allocation", "native", "release")
        val claim = store.execution.claim(session.id, token, "prep-allocation")!!
        val bundle = recipe.bundles.first { it.os == "mac" || recipe.bundles.none { it.os == "mac" } }
        val outputs = mapOf("preparation/${bundle.commit}.zip" to bundle.sha256)
        store.execution.phase(claim.owner, "preparing")
        val selected = backend.selectPreparation(claim.owner, listOf(List(count) { Problem("s", "p$it") }))
        val result = PreparationResult(selected, listOf(bundle), artifacts = outputs)
        store.execution.phase(claim.owner, "uploading")
        backend.commit(claim.owner, IterationResult(emptyList(), outputs, preparation = result))
    }

    @Test fun `operator example decodes into pinned native and Spot worker templates`() {
        val example = Json.decodeFromString<NomadQueueSettings>(Files.readString(Path.of("deploy/nomad/queue.example.json")))
        assertEquals(setOf("mac", "linux"), example.targets.map { it.os }.toSet())
        assertEquals(CapacityMarket.SPOT, example.targets.single { it.os == "linux" }.market)
        example.targets.forEach { it.template.render() }
    }

    @Test fun `queue automatically prepares once publishes its plan and shares native and Spot workers`() {
        val id = job(); queue().reconcile()
        assertEquals(listOf("prepare"), store.execution.sessions(id).map { it.executionClass })
        queue().reconcile(); assertEquals(1, publications)
        prepare(id); queue().reconcile()
        val planned = store.job(id)!!
        assertTrue(planned.setupDone); assertEquals(3, planned.commands.size)
        assertEquals("a".repeat(40), planned.sha)
        val solves = store.execution.sessions(id).filter { it.executionClass == "solve" }
        assertEquals(3, solves.size)
        assertEquals(2, solves.count { store.execution.submission(it.id).contains("advisory-native") })
        assertEquals(1, solves.count { store.execution.submission(it.id).contains("hard-quota") })
        assertTrue(solves.all { it.budget.cpuMillis == 1000 && it.budget.memoryMiB == 768 && it.budget.parallel == 1 })
        assertTrue(solves.all { store.execution.manifest(it.id).solveJavaOptions == "-Xmx256m" })
        queue().reconcile(); assertEquals(3, store.execution.sessions(id).count { it.executionClass == "solve" })
        assertEquals(1, publications)
    }

    @Test fun `normal API submissions choose Nomad and provider neutral cloud placement without AWS settings`() = testApplication {
        application { api(config.copy(passwordHash = null), store, HostReport("fixture", 4, "java", "vector", true, emptyList())) }
        val response = client.post("/experiments") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"api-nomad","description":"exercise automatic admission","problems":{"suite":"s"},"base":{"ref":"${"a".repeat(40)}"},"nodeTags":{"require":["cloud"]}}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val job = store.active().single()
        assertEquals(ExecutionBackendKind.NOMAD, job.backend)
        assertEquals(Experiments.AWS_HOST, job.experiment!!.host)
        assertNull(config.aws)
    }

    @Test fun `reference filtering refills its cap before atomically publishing the plan`() {
        val spec = ExperimentSpec("filtered", problems = listOf(mapOf("suite" to "s", "reference" to "proven", "max" to "1")), host = "auto")
        val id = job(spec); queue().reconcile()
        store.putReferences(listOf(("" to "p1") to Reference("gecode", false, 1.0, true, true, 100, 60000)), "fixture")
        prepare(id); queue().reconcile()
        assertTrue(store.job(id)!!.setupDone)
        assertEquals(listOf("p1"), store.casePlan(id).map { it.problem.problem })
        assertEquals(1, store.job(id)!!.commands.size)
    }

    @Test fun `normal API accepts a bounded cloud profile under Nomad`() = testApplication {
        application { api(config.copy(passwordHash = null), store, HostReport("fixture", 4, "java", "vector", true, emptyList())) }
        val response = client.post("/experiments") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"cloud-profile","description":"bounded Spot profile","profile":{"main":"com.eignex.Main","runTimeoutSec":600},"base":{"ref":"${"a".repeat(40)}"},"nodeTags":{"require":["cloud","spot"]}}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val job = store.active().single()
        assertEquals(ExecutionBackendKind.NOMAD, job.backend)
        assertEquals(Experiments.AWS_HOST, job.experiment!!.host)
        assertEquals(600L, job.experiment!!.profile!!.runTimeoutSec)
        assertFailsWith<IllegalArgumentException> { Experiments.validate(job.experiment!!, 4) }
    }

    @Test fun `one case cannot reserve a nonexistent parallel slot and native saturation spills to Spot`() {
        val id = job(); queue().reconcile(); prepare(id); backend.reconcile()
        NomadQueue(config.copy(cores = 1), store, backend, artifacts, settings, { true }, nativeAllowed = { true },
            source = { _, _, _ -> error("source already pinned") }).reconcile()
        val solves = store.execution.sessions(id).filter { it.executionClass == "solve" }
        assertEquals(1, solves.count { store.execution.submission(it.id).contains("advisory-native") })
        assertEquals(1, solves.count { store.execution.submission(it.id).contains("hard-quota") })
        assertTrue(solves.all { it.budget.parallel == 1 && it.budget.cpuMillis == 1000 })
    }

    @Test fun `restart preserves admitted templates release and budgets when operator settings change`() {
        val id = job(); queue().reconcile(); prepare(id)
        val changed = settings.copy(release = "different-release", caseMemoryMiB = 1024, maxSessionsPerJob = 1)
        NomadQueue(config, Store(root.resolve("lab.db")), backend, artifacts, changed, { true }, nativeAllowed = { true },
            source = { _, _, _ -> error("source already pinned") }).reconcile()
        val solves = store.execution.sessions(id).filter { it.executionClass == "solve" }
        assertEquals(3, solves.size)
        assertTrue(solves.all { it.release == "release" && it.budget.memoryMiB == 768 })
        assertEquals(1, publications)
    }

    @Test fun `cloud admission waits for placement and excludes hosts at the experiment parallel cap`() {
        val id = job(ExperimentSpec("cloud-slots", problems = listOf(mapOf("suite" to "s")), host = Experiments.AWS_HOST))
        queue().reconcile(); prepare(id, 4); backend.reconcile(); queue().reconcile()
        fun solves() = store.execution.sessions(id).filter { it.executionClass == "solve" }
        fun bind(session: ExecutionSession, node: String) {
            val spec = Json.parseToJsonElement(store.execution.submission(session.id)).jsonObject
            val token = spec.getValue("TaskGroups").jsonArray[0].jsonObject.getValue("Tasks").jsonArray[0].jsonObject
                .getValue("Env").jsonObject.getValue("LAB_WORKER_TOKEN").jsonPrimitive.content
            store.execution.bind(session.id, token, "allocation-${session.id}", node, "release")
        }
        assertEquals(1, solves().size)
        queue().reconcile(); assertEquals(1, solves().size, "an unplaced session must not cause duplicate placements")
        bind(solves().single { it.node == null }, "cloud-node-a")
        queue().reconcile(); assertEquals(2, solves().size)
        bind(solves().single { it.node == null }, "cloud-node-a")
        queue().reconcile(); assertEquals(3, solves().size)
        val thirdSession = solves().single { it.node == null }
        val third = Json.parseToJsonElement(store.execution.submission(thirdSession.id)).jsonObject
        val exclusion = third.getValue("Constraints").jsonArray.map { it.jsonObject }
            .single { it["LTarget"]?.jsonPrimitive?.content == "${'$'}{node.unique.id}" }
        assertEquals("!=", exclusion.getValue("Operand").jsonPrimitive.content)
        assertEquals("cloud-node-a", exclusion.getValue("RTarget").jsonPrimitive.content)
        bind(thirdSession, "cloud-node-b")
        queue().reconcile(); assertEquals(4, solves().size)
        val fourth = store.execution.submission(solves().single { it.node == null }.id)
        assertTrue(fourth.contains("cloud-node-a"))
        assertFalse(fourth.contains("cloud-node-b"), "the other host still has an experiment parallel slot")
    }

    @Test fun `domain completion is acknowledged once across coordinator instances`() {
        val id = job(); store.finish(id, Status.DONE)
        queue().reconcile(); queue().reconcile()
        assertEquals(1, completions)
        assertTrue(Store(root.resolve("lab.db")).execution.pendingDomainCompletion().isEmpty())
    }

    @Test fun `infrastructure retry limit also applies while some solve workers are still active`() {
        val id = job(); queue().reconcile(); prepare(id); queue().reconcile()
        repeat(4) {
            val active = store.execution.sessions(id).first { it.executionClass == "solve" && it.desired != "stop" }
            store.execution.loseSession(active.id, "infrastructure:spot")
            queue().reconcile()
        }
        assertEquals(Status.FAILED, store.job(id)!!.status)
        assertTrue(store.job(id)!!.commands.all { it.status == Status.CANCELLED })
        assertTrue(store.execution.sessions(id).all { it.desired == "stop" })
        assertEquals(1, publications)
    }

    @Test fun `holds pauses and cancellation never admit new work`() {
        val id = job(); queue { false }.reconcile(); assertTrue(store.execution.sessions(id).isEmpty())
        store.setPaused(id, true); queue().reconcile(); assertTrue(store.execution.sessions(id).isEmpty())
        store.setPaused(id, false); queue().reconcile(); prepare(id)
        store.requestCancel(id); queue().reconcile()
        assertFalse(store.job(id)!!.setupDone)
        assertEquals(listOf("prepare"), store.execution.sessions(id).map { it.executionClass })
    }

    @Test fun `cloud selection needs no AWS settings and profiles use Spot unless explicitly on demand`() {
        val spec = ExperimentSpec("cloud", profile = ProfileMain(main = "com.eignex.Main", runTimeoutSec = 60), nodeTags = NodeSelector(require = setOf("cloud")))
        assertNull(config.aws)
        assertEquals(listOf(CapacityMarket.SPOT), settings.matching(spec, spec.name).map { it.market })
        assertEquals(Experiments.AWS_HOST, Placement.host(spec, spec.name, config))
        assertTrue(settings.matching(spec.copy(nodeTags = NodeSelector(require = setOf("on-demand"))), spec.name).isEmpty())
        assertFailsWith<IllegalArgumentException> { settings.solveOptions("-Xmx8g") }
        assertEquals("-Xmx256m", settings.solveOptions("-Xmx256m"))
    }

    @Test fun `a lost worker is replenished while other sessions keep solving without publishing source again`() {
        val id = job(); queue().reconcile(); prepare(id); queue().reconcile()
        val solve = store.execution.sessions(id).first { it.executionClass == "solve" }
        store.execution.loseSession(solve.id, "infrastructure:spot")
        queue().reconcile()
        assertEquals(3, store.execution.sessions(id).count { it.executionClass == "solve" && it.desired != "stop" })
        assertEquals(1, publications)
        assertEquals(3, store.job(id)!!.commands.size)
    }
}
