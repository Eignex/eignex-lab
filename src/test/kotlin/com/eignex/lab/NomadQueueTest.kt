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
    @Test fun `cloud preparation waits before publication and charging until the preceding placement binds`() {
        val first = job(ExperimentSpec("first-build", host = Experiments.AWS_HOST))
        val second = job(ExperimentSpec("second-build", host = Experiments.AWS_HOST))
        val third = job(ExperimentSpec("third-build", host = Experiments.AWS_HOST))
        val cloud = settings.copy(targets = settings.targets.filter { !it.native })
        val admission = queue(policy = cloud)
        fun bind(job: Long, allocation: String) {
            val session = store.execution.sessions(job).single()
            val payload = Json.parseToJsonElement(store.execution.submission(session.id)).jsonObject
            val env = payload.getValue("TaskGroups").jsonArray.single().jsonObject.getValue("Tasks").jsonArray.single().jsonObject.getValue("Env").jsonObject
            store.execution.bind(session.id, env.getValue("LAB_WORKER_TOKEN").jsonPrimitive.content, allocation, "busy", "release")
        }
        admission.reconcile()
        assertEquals(1, publications)
        assertTrue(store.execution.sessions(second).isEmpty())
        assertEquals(0L, store.job(second)!!.runMs)
        assertNull(store.job(second)!!.runningSince)
        bind(first, "first-allocation")
        admission.reconcile()
        assertEquals(2, publications)
        assertTrue(store.execution.sessions(third).isEmpty())
        bind(second, "second-allocation")
        admission.reconcile()
        val payload = store.execution.submission(store.execution.sessions(third).single().id)
        assertEquals(setOf("busy"), (SessionPlacement.of(payload).pool as WorkerPool.Cloud).excludedNodes)
        assertEquals(0L, store.job(third)!!.runMs)
        assertNull(store.job(third)!!.runningSince)
    }

    @Test fun `retired persisted recipes fail before source publication or allocation`() {
        val spec = ExperimentSpec("status-sweep@old", problems = listOf(mapOf("set" to "sweep")), host = Experiments.LAB_HOST)
        val id = store.create(spec.name, "main", emptyList(), vcpus = 1, experiment = spec)
        repeat(3) { queue().reconcile() }
        assertEquals(Status.FAILED, store.job(id)!!.status)
        assertContains(store.job(id)!!.error!!, "legacy solver recipes must be migrated")
        assertEquals(0, publications)
        assertTrue(store.execution.sessions(id).isEmpty())
    }

    @Test fun `tags cannot change while source publication is pending before the first session`() = testApplication {
        val id = job()
        val frozen = store.job(id)!!.experiment
        NomadQueue(config, store, backend, artifacts, settings, { true }, source = { _, _, _ -> null }).reconcile()
        assertTrue(store.execution.sessions(id).isEmpty())
        val apiStore = Store(config.database)
        application { api(config.copy(auth = Auth.Disabled), apiStore, HostReport("fixture", 4, "java")) }
        val response = client.post("/jobs/$id/tags") {
            contentType(ContentType.Application.Json); setBody("""{"tags":["cloud"]}""")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(frozen, store.job(id)!!.experiment)
    }

    @Test fun `an admitted preparation rejects tags edits and retains its frozen recipe`() = testApplication {
        val id = job()
        queue().reconcile()
        val session = store.execution.sessions(id).single()
        val frozen = store.execution.manifest(session.id)
        assertTrue(store.job(id)!!.commands.isEmpty())
        application { api(config.copy(auth = Auth.Disabled), store, HostReport("fixture", 4, "java")) }
        val response = client.post("/jobs/$id/tags") {
            contentType(ContentType.Application.Json); setBody("""{"tags":["cloud"]}""")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(frozen.experiment, store.job(id)!!.experiment)
        assertEquals(frozen, store.execution.manifest(session.id))
    }

    @Test fun `trusted native target uses loopback worker API without changing the cloud endpoint`() {
        val policy = settings.copy(targets = settings.targets.map { target ->
            if (target.os != "mac") target else target.copy(template = target.template.copy(environment = target.template.environment +
                ("LAB_WORKER_API" to "http://127.0.0.1:8091")))
        })
        val id = job(ExperimentSpec("loopback", problems = listOf(mapOf("suite" to "s")), host = Experiments.LAB_HOST))
        queue(policy = policy).reconcile()
        val payload = Json.parseToJsonElement(store.execution.submission(store.execution.sessions(id).single().id)).jsonObject
        val env = payload.getValue("TaskGroups").jsonArray[0].jsonObject.getValue("Tasks").jsonArray[0].jsonObject.getValue("Env").jsonObject
        assertEquals("http://127.0.0.1:8091", env.getValue("LAB_WORKER_API").jsonPrimitive.content)
        assertEquals(settings.workerApi, policy.workerApi)
    }

    @Test fun `slow source publication does not block admission of a prepared experiment`() {
        val ready = job()
        queue().reconcile(); prepare(ready); backend.reconcile()
        val slow = job(); store.setPriority(slow, 100)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        CoordinatorWork<Long, Pair<List<PlannedArm>, List<WorkerBundle>>>("source-test", 1).use { work ->
            try {
                val asynchronous = NomadQueue(config, store, backend, artifacts, settings, { true }, nativeAllowed = { true },
                    source = { job, _, _ -> work.poll(job.id) { entered.countDown(); release.await(); error("unfinished source") } })
                asynchronous.reconcile()
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(store.execution.sessions(slow).isEmpty())
                assertTrue(store.execution.sessions(ready).any { it.executionClass == ExecutionClass.SOLVE })
                assertTrue(store.job(ready)!!.setupDone)
            } finally { release.countDown() }
        }
    }

    @Test fun `higher priority sweep takes native capacity before an older auto job`() {
        val ordinary = job()
        val sweep = job(ExperimentSpec("status-sweep@abc", problems = listOf(mapOf("suite" to "s")), host = Experiments.LAB_HOST, priority = 100))
        store.setPriority(sweep, 100)
        queue().reconcile()
        assertContains(store.execution.submission(store.execution.sessions(ordinary).single().id), "hard-quota")
        assertContains(store.execution.submission(store.execution.sessions(sweep).single().id), "advisory-native")
    }

    @Test fun `higher priority auto work can use local ahead of a lower priority sweep`() {
        val sweep = job(ExperimentSpec("status-sweep@abc", problems = listOf(mapOf("suite" to "s")), host = Experiments.LAB_HOST))
        val ordinary = job()
        store.setPriority(ordinary, 101)
        queue(cores = 1).reconcile()
        assertTrue(store.execution.sessions(sweep).isEmpty())
        assertContains(store.execution.submission(store.execution.sessions(ordinary).single().id), "advisory-native")
    }

    @Test fun `lower priority pinned work cannot reserve spare native capacity ahead of automatic work`() {
        val sweep = job(ExperimentSpec("status-sweep@abc", problems = listOf(mapOf("suite" to "s")), host = Experiments.LAB_HOST))
        val ordinary = job()
        store.setPriority(ordinary, 101)
        queue().reconcile()
        assertTrue(store.execution.sessions(sweep).isEmpty())
        assertContains(store.execution.submission(store.execution.sessions(ordinary).single().id), "advisory-native")
    }

    @Test fun `a priority edit transfers pending native capacity without replacing the lower priority session`() {
        val ordinary = job()
        queue().reconcile()
        val pending = store.execution.sessions(ordinary).single()
        assertContains(store.execution.submission(pending.id), "advisory-native")
        val urgent = job()
        store.setPriority(urgent, 10)
        backend.reconcile()
        queue().reconcile()
        assertEquals(Desired.STOP, store.execution.session(pending.id)!!.desired)
        assertContains(store.execution.submission(store.execution.sessions(urgent).single().id), "advisory-native")
        assertTrue(store.execution.sessions(ordinary).all { it.desired == Desired.STOP })
        assertEquals(0, store.execution.failureCount(ordinary))
    }

    @Test fun `paused reserved work lets idle native capacity contribute to auto jobs`() {
        val sweep = job(ExperimentSpec("status-sweep@abc", problems = listOf(mapOf("suite" to "s")), host = Experiments.LAB_HOST))
        store.setPaused(sweep, true)
        val ordinary = job()
        queue().reconcile()
        assertTrue(store.execution.sessions(sweep).isEmpty())
        assertContains(store.execution.submission(store.execution.sessions(ordinary).single().id), "advisory-native")
    }

    @Test fun `missing source fails once while network failures remain queued for retry`() {
        val missing = job()
        var missingCalls = 0
        val missingQueue = NomadQueue(config, store, backend, artifacts, settings, { true }, source = { _, _, _ ->
            missingCalls++
            throw IllegalArgumentException("source ref 'absent' was not found after a successful fetch")
        })
        repeat(2) { missingQueue.reconcile() }
        assertEquals(Status.FAILED, store.job(missing)!!.status)
        assertContains(store.job(missing)!!.error!!, "source ref 'absent'")
        assertEquals(1, missingCalls)
        assertTrue(store.execution.sessions(missing).isEmpty())

        val network = job()
        var networkCalls = 0
        val networkQueue = NomadQueue(config, store, backend, artifacts, settings, { true }, source = { _, _, _ ->
            networkCalls++
            throw java.io.IOException("GitHub connection timed out")
        })
        repeat(2) { networkQueue.reconcile() }
        assertEquals(Status.QUEUED, store.job(network)!!.status)
        assertEquals(2, networkCalls)
        assertTrue(store.execution.sessions(network).isEmpty())
    }

    @Test fun `cloud preparation prefers the largest fitting shape regardless of configuration order`() {
        val large = target(NomadPlatform.LINUX, "large")
        val small = large.copy(template = large.template.copy(nodeClass = "small", cpuCapacity = NomadCpuCapacity(2000, 2), budget = ResourceBudget(2000, 2048, 2)))
        val policy = settings.copy(targets = listOf(small, large))
        val id = job(ExperimentSpec("large-cloud", problems = listOf(mapOf("suite" to "s")), host = Experiments.AWS_HOST))
        queue(policy = policy).reconcile()
        assertContains(store.execution.submission(store.execution.sessions(id).single().id), "\"RTarget\":\"large\"")
    }

    @Test fun `cloud preparation fills a running small node before launching a large one`() {
        val large = target(NomadPlatform.LINUX, "large")
        val small = large.copy(template = large.template.copy(nodeClass = "small", cpuCapacity = NomadCpuCapacity(2000, 2), budget = ResourceBudget(2000, 2048, 2)))
        control.nodeInventory["warm"] = buildJsonObject {
            put("ID", "warm"); put("NodeClass", "small"); put("Status", "ready")
            putJsonObject("Attributes") { put("kernel.name", "linux"); put("cpu.usablecompute", "2000"); put("cpu.numcores", "2") }
            putJsonObject("NodeResources") { putJsonObject("Memory") { put("MemoryMB", 2048) } }
        }
        val id = job(ExperimentSpec("reuse-small-cloud", problems = listOf(mapOf("suite" to "s")), host = Experiments.AWS_HOST))
        queue(policy = settings.copy(targets = listOf(large, small))).reconcile()
        assertContains(store.execution.submission(store.execution.sessions(id).single().id), "\"RTarget\":\"small\"")
    }

    @Test fun `a cloud job's vcpus select the large pool in one session`() {
        val large = target(NomadPlatform.LINUX, "large").let {
            it.copy(template = it.template.copy(cpuCapacity = NomadCpuCapacity(16000, 16), budget = ResourceBudget(16000, 64512)))
        }
        val small = large.copy(template = large.template.copy(nodeClass = "small", cpuCapacity = NomadCpuCapacity(2000, 2), budget = ResourceBudget(2000, 5120)))
        val policy = settings.copy(targets = listOf(small, large), caseMemoryMiB = 4096, sessionMemoryMiB = 512, maxSessionsPerJob = 6)
        val id = job(ExperimentSpec("large-solves", problems = listOf(mapOf("suite" to "s")), vcpus = 6, host = Experiments.AWS_HOST))
        queue(policy = policy).reconcile(); prepare(id, 6); backend.reconcile()
        repeat(1) {
            queue(policy = policy).reconcile()
            val session = store.execution.sessions(id).single { it.executionClass == ExecutionClass.SOLVE && it.node == null }
            val submission = Json.parseToJsonElement(store.execution.submission(session.id)).jsonObject
            val nodeClass = submission.getValue("Constraints").jsonArray.map { it.jsonObject }
                .single { it["LTarget"]?.jsonPrimitive?.content == "${'$'}{node.class}" }.getValue("RTarget").jsonPrimitive.content
            assertEquals("large", nodeClass)
            assertFalse(submission.getValue("Constraints").jsonArray.any { it.jsonObject["LTarget"]?.jsonPrimitive?.content == "${'$'}{node.unique.id}" })
            val token = submission.getValue("TaskGroups").jsonArray[0].jsonObject.getValue("Tasks").jsonArray[0].jsonObject
                .getValue("Env").jsonObject.getValue("LAB_WORKER_TOKEN").jsonPrimitive.content
            store.execution.bind(session.id, token, "allocation-${session.id}", "large-node", "release")
        }
        repeat(2) { queue(policy = policy).reconcile() }
        val solves = store.execution.sessions(id).filter { it.executionClass == ExecutionClass.SOLVE }
        // Six vCPUs: one session of five cases, not six single-case workers.
        assertEquals(1, solves.size)
        assertEquals(ResourceBudget(6000, 512 + 5 * 512, 5), solves.single().budget)
        assertEquals(setOf("large-node"), solves.map { it.node }.toSet())
    }

    private fun pools() = CapacitySettings(listOf(CapacityPool("fixture-spot", "portable-spot", 8, 8192, 2,
        setOf("arch:x86_64"), minPriority = 0)), 16)

    @Test fun `low priority local work does not reserve cloud slots that cannot grow capacity`() {
        val id = job(ExperimentSpec("background", vcpus = 8, priority = -2, host = HostPlacement.AUTO))
        store.setPriority(id, -2)
        queue(capacity = pools()).reconcile(); prepare(id, 8); backend.reconcile()
        queue(cores = 4, capacity = pools()).reconcile()
        val active = store.execution.sessions(id).filter { it.executionClass == ExecutionClass.SOLVE && it.desired != Desired.STOP }
        assertEquals(1, active.size)
        assertTrue(active.all { store.execution.placement(it.id).native })
    }

    @Test fun `unbound cloud intent yields its slot to native capacity without touching bound workers`() {
        val id = job(ExperimentSpec("reclaim", vcpus = 8, priority = -2, host = HostPlacement.AUTO))
        store.setPriority(id, -2)
        queue().reconcile(); prepare(id, 8); backend.reconcile()
        // Initial policy predates capacity-aware admission and fills the remaining slot with a cloud intent.
        queue(cores = 4).reconcile()
        val pending = store.execution.sessions(id).single { it.executionClass == ExecutionClass.SOLVE &&
            it.desired == Desired.RUN && !store.execution.placement(it.id).native }
        val frozen = store.execution.manifest(pending.id)
        queue(capacity = pools()).reconcile()
        assertEquals(Desired.STOP, store.execution.session(pending.id)!!.desired)
        assertEquals(frozen, store.execution.manifest(pending.id))
        assertTrue(store.execution.sessions(id).filter { it.executionClass == ExecutionClass.SOLVE && it.desired == Desired.RUN }
            .all { store.execution.placement(it.id).native })
        assertEquals(0L, store.job(id)!!.runMs)
        assertTrue(store.execution.events(id).any { it.detail == "lost:placement-native-capacity" })
    }

    @Test fun `a cloud row that bound before native handoff keeps its owner and complete attempt`() {
        val id = job(ExperimentSpec("bound-cloud", vcpus = 8, priority = -2, host = HostPlacement.AUTO))
        store.setPriority(id, -2)
        queue().reconcile(); prepare(id, 8); backend.reconcile(); queue(cores = 4).reconcile()
        val cloud = store.execution.sessions(id).single { it.executionClass == ExecutionClass.SOLVE &&
            it.desired == Desired.RUN && !store.execution.placement(it.id).native }
        val submitted = Json.parseToJsonElement(store.execution.submission(cloud.id)).jsonObject
        val token = submitted.getValue("TaskGroups").jsonArray[0].jsonObject.getValue("Tasks").jsonArray[0].jsonObject
            .getValue("Env").jsonObject.getValue("LAB_WORKER_TOKEN").jsonPrimitive.content
        store.execution.bind(cloud.id, token, "cloud-bound", "cloud-node", "release")
        val claim = store.execution.claim(cloud.id, token, "cloud-bound")!!
        store.execution.phase(claim.owner, "executing")
        queue(capacity = pools()).reconcile()
        assertEquals(Desired.RUN, store.execution.session(cloud.id)!!.desired)
        assertEquals("cloud-node", store.execution.session(cloud.id)!!.node)
        store.execution.renew(claim.owner)
    }

    @Test fun `cloud only background work can still request capacity below the native priority floor`() {
        val id = job(ExperimentSpec("cloud-background", vcpus = 1, priority = -2, host = Experiments.AWS_HOST))
        store.setPriority(id, -2)
        queue(capacity = pools()).reconcile()
        assertTrue(store.execution.sessions(id).isNotEmpty())
        assertFalse(store.execution.placement(store.execution.sessions(id).single().id).native)
    }

    @Test fun `cloud preparation reuses a running larger shape before starting a small one`() {
        val large = target(NomadPlatform.LINUX, "large")
        val small = large.copy(template = large.template.copy(nodeClass = "small", cpuCapacity = NomadCpuCapacity(2000, 2), budget = ResourceBudget(2000, 2048, 2)))
        control.nodeInventory["warm"] = buildJsonObject {
            put("ID", "warm"); put("NodeClass", "large"); put("Status", "ready")
            putJsonObject("Attributes") { put("kernel.name", "linux"); put("cpu.usablecompute", "8000"); put("cpu.numcores", "8") }
            putJsonObject("NodeResources") { putJsonObject("Memory") { put("MemoryMB", 4096) } }
        }
        val id = job(ExperimentSpec("reuse-cloud", problems = listOf(mapOf("suite" to "s")), host = Experiments.AWS_HOST))
        queue(policy = settings.copy(targets = listOf(small, large))).reconcile()
        assertContains(store.execution.submission(store.execution.sessions(id).single().id), "\"RTarget\":\"large\"")
    }

    private val root = Files.createTempDirectory("nomad-queue")
    private val store = Store(root.resolve("lab.db"))
    private val artifacts = LocalArtifactStore(root.resolve("artifacts"))
    private val control = object : NomadControl {
        val jobs = mutableMapOf<String, JsonObject>()
        val nodeInventory = mutableMapOf<String, JsonObject>()
        override fun nodes() = nodeInventory.values.toList()
        override fun node(id: String) = nodeInventory[id]
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
    /** Eight vCPUs: more than one four-vCPU worker holds. */
    private val wide = ExperimentSpec("portable", vcpus = 8, host = HostPlacement.AUTO)
    /** [settings] with workers of [cpuMillis] each, so a job's vCPUs spread over several single-case sessions. */
    private fun narrow(cpuMillis: Int) = settings.copy(targets = settings.targets.map {
        it.copy(template = it.template.copy(budget = it.template.budget.copy(cpuMillis = cpuMillis)))
    }, preparationBudget = ResourceBudget(cpuMillis, 512))
    private val settings = NomadQueueSettings("release", "http://127.0.0.1:8091",
        listOf(target(NomadPlatform.MAC, "native"), target(NomadPlatform.LINUX, "portable-spot")),
        ResourceBudget(1000, 512), caseMemoryMiB = 512, sessionMemoryMiB = 256, maxSessionsPerJob = 4)
    private val file = root.resolve("queue.json").also { Files.writeString(it, Json.encodeToString(settings)) }
    private val config = Config(dataDir = root, jobsDir = root.resolve("jobs"), minFreeBytes = 0, solveJavaOpts = "-Xmx256m", cores = 8,
        cpuCapabilities = CpuCapabilities("arm64", setOf("neon"), "fixture", true), nomadQueueSettings = file)
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
    private fun queue(cores: Int = config.cores, policy: NomadQueueSettings = settings, capacity: CapacitySettings? = null,
                      allowed: () -> Boolean = { true }) = NomadQueue(config.copy(cores = cores), store, backend, artifacts, policy, allowed,
        completed = { completions++ }, nativeAllowed = { true }, capacitySettings = capacity, source = { job, targets, _ ->
            publications++
            Experiments.arms(job.experiment!!).map { PlannedArm(it, "a".repeat(40)) } to targets.distinctBy { it.os to it.arch }.map { input(job.id, it.os, it.arch) }
        })
    private fun job(spec: ExperimentSpec = ExperimentSpec("portable", host = HostPlacement.AUTO)): Long {
        val generic = if (spec.isProfile || spec.work.bench != null) spec else spec.copy(problems = emptyList(), bench = BenchSpec("bench.json"))
        return store.create(generic.name, "main", emptyList(), vcpus = generic.vcpus ?: 2, experiment = generic)
    }
    private fun preparing(id: Long): Pair<IterationClaim, WorkManifest> {
        val session = store.execution.sessions(id).single { it.executionClass == ExecutionClass.PREPARE }
        val recipe = store.execution.manifest(session.id)
        val submission = Json.parseToJsonElement(store.execution.submission(session.id)).jsonObject
        val token = submission.getValue("TaskGroups").jsonArray[0].jsonObject.getValue("Tasks").jsonArray[0].jsonObject.getValue("Env").jsonObject.getValue("LAB_WORKER_TOKEN").jsonPrimitive.content
        store.execution.bind(session.id, token, "prep-allocation-$id", "native", "release")
        return store.execution.claim(session.id, token, "prep-allocation-$id")!! to recipe
    }
    private fun prepare(id: Long, count: Int = 3,
                        selections: (WorkManifest, com.eignex.lab.bench.BenchPlan) -> List<BenchSelectionEntry> = { _, _ -> emptyList() }) {
        val (claim, recipe) = preparing(id)
        val bundle = recipe.bundles.first { it.os == "mac" || recipe.bundles.none { it.os == "mac" } }
        val outputs = mapOf("preparation/${bundle.commit}.zip" to bundle.sha256)
        store.execution.phase(claim.owner, "preparing")
        val plan = com.eignex.lab.bench.BenchPlan(List(count) { com.eignex.lab.bench.BenchCase("p$it",
            resources = com.eignex.lab.bench.BenchResources(recipe.arms.maxOf { it.arm.cores }, 512, 1)) })
        val selected = backend.selectPreparation(claim.owner, listOf(plan.cases.map(BenchRuns::problem)))
        val result = PreparationResult(selected, listOf(bundle), artifacts = outputs, benchPlan = plan,
            benchDescriptor = com.eignex.lab.bench.BenchDescriptor("fixture", "generic queue fixture"),
            armPlans = recipe.arms.associate { it.arm.label to plan }, selectionCache = selections(recipe, plan))
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
        val id = job(wide); queue().reconcile()
        assertEquals(listOf(ExecutionClass.PREPARE), store.execution.sessions(id).map { it.executionClass })
        // The preparation holds the job's vCPUs within its worker's four, with memory scaled from the configured floor.
        assertEquals(ResourceBudget(4000, 2048), store.execution.sessions(id).single().budget)
        queue().reconcile(); assertEquals(1, publications)
        prepare(id, 6); queue().reconcile()
        val planned = store.job(id)!!
        assertTrue(planned.setupDone); assertEquals(6, planned.commands.size)
        assertEquals("a".repeat(40), planned.sha)
        val solves = store.execution.sessions(id).filter { it.executionClass == ExecutionClass.SOLVE }
        // Eight vCPUs over four-vCPU workers: two sessions, each running three cases beside the worker's vCPU. The
        // preparation still holds four native vCPUs until it stops, so the second spills to Spot.
        assertEquals(2, solves.size)
        assertEquals(1, solves.count { store.execution.submission(it.id).contains("advisory-native") })
        assertEquals(1, solves.count { store.execution.submission(it.id).contains("hard-quota") })
        assertTrue(solves.all { it.budget == ResourceBudget(4000, 256 + 3 * 512, 3) })
        assertTrue(solves.all { store.execution.manifest(it.id).solveJavaOptions == "-Xmx256m" })
        queue().reconcile(); assertEquals(2, store.execution.sessions(id).count { it.executionClass == ExecutionClass.SOLVE })
        assertEquals(1, publications)
    }

    @Test fun `accepted selections are found by key from a later preparation and leave with their job`() {
        val descriptor = com.eignex.lab.bench.BenchDescriptor("fixture", "generic queue fixture")
        fun entries(key: Char) = { recipe: WorkManifest, plan: com.eignex.lab.bench.BenchPlan ->
            recipe.arms.mapIndexed { index, arm -> BenchSelectionEntry("$key$index".padEnd(64, key), arm.arm.label, descriptor, plan, "e".repeat(64)) }
        }
        val first = job(); queue().reconcile(); queue().reconcile()
        prepare(first, selections = entries('c'))
        val stored = entries('c')(store.execution.manifest(store.execution.sessions(first).first().id),
            com.eignex.lab.bench.BenchPlan(List(3) { com.eignex.lab.bench.BenchCase("p$it",
                resources = com.eignex.lab.bench.BenchResources(1, 512, 1)) }))
        // The accepted preparation keeps no copy; the cache table does.
        assertTrue(store.execution.acceptedPreparation(first)!!.selectionCache.isEmpty())

        val second = job(); queue().reconcile(); queue().reconcile()
        val (claim, _) = preparing(second)
        store.execution.phase(claim.owner, "preparing")
        assertEquals(stored.take(1), store.execution.benchSelections(claim.owner, listOf(stored.first().key, "d".repeat(64))))
        val plan = store.executionRead { db ->
            db.sqlRows("EXPLAIN QUERY PLAN SELECT entry FROM bench_selections WHERE selection_key = ?", stored.first().key) { it.getString(4) }
        }
        assertTrue(plan.single().contains("USING INDEX"), plan.toString())
        val cascade = store.executionRead { db ->
            db.sqlRows("EXPLAIN QUERY PLAN DELETE FROM bench_selections WHERE job_id = ?", first) { it.getString(4) }
        }
        assertTrue(cascade.single().contains("USING INDEX bench_selection_job"), cascade.toString())

        // An offered plan that is not the accepted one is refused with the whole commit.
        val third = job(); queue().reconcile(); queue().reconcile()
        assertFailsWith<IllegalArgumentException> { prepare(third, selections = { recipe, plan ->
            entries('f')(recipe, plan.copy(cases = plan.cases.drop(1))) }) }

        // Deleting the job row (its sessions need not stop here) fires this table's own cascade.
        store.finish(first, Status.CANCELLED)
        store.executionTransaction { db -> db.sqlUpdate("DELETE FROM jobs WHERE id = ?", first) }
        assertEquals(emptyList(), store.execution.benchSelections(claim.owner, stored.map { it.key }))
    }

    @Test fun `normal API submissions choose Nomad and provider neutral cloud placement without AWS settings`() = testApplication {
        application { api(config.copy(auth = Auth.Disabled), store, HostReport("fixture", 4, "java")) }
        val response = client.post("/experiments") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"api-nomad","description":"exercise automatic admission","bench":{"manifest":"bench.json"},"base":{"ref":"${"a".repeat(40)}"},"tags":["cloud"]}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val job = store.active().single()
        assertEquals(Backend.NOMAD, job.backend)
        assertEquals(Experiments.AWS_HOST, job.experiment!!.host)
    }

    @Test fun `normal API accepts a bounded cloud profile under Nomad`() = testApplication {
        application { api(config.copy(auth = Auth.Disabled), store, HostReport("fixture", 4, "java")) }
        val response = client.post("/experiments") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"cloud-profile","description":"bounded Spot profile","profile":{"main":"com.eignex.Main","classpath":["tools/lib/*"],"runTimeoutSec":600},"base":{"ref":"${"a".repeat(40)}"},"tags":["cloud","spot"]}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val job = store.active().single()
        assertEquals(Backend.NOMAD, job.backend)
        assertEquals(Experiments.AWS_HOST, job.experiment!!.host)
        assertEquals(600L, job.experiment!!.work.profile!!.runTimeoutSec)
        Experiments.validate(job.experiment!!, 4)
    }

    @Test fun `one case cannot reserve a nonexistent parallel slot and native saturation spills to Spot`() {
        val id = job(wide); queue().reconcile(); prepare(id, 6); backend.reconcile()
        NomadQueue(config.copy(cores = 4), store, backend, artifacts, settings, { true }, nativeAllowed = { true },
            source = { _, _, _ -> error("source already pinned") }).reconcile()
        val solves = store.execution.sessions(id).filter { it.executionClass == ExecutionClass.SOLVE }
        assertEquals(1, solves.count { store.execution.submission(it.id).contains("advisory-native") })
        assertEquals(1, solves.count { store.execution.submission(it.id).contains("hard-quota") })
        assertTrue(solves.all { it.budget.parallel == 3 && it.budget.cpuMillis == 4000 })
        // Two vCPUs leave one case slot whatever the template allows.
        assertEquals(ResourceBudget(2000, 768, 1), settings.solveBudget(settings.targets.first(), store.job(job())!!))
    }

    @Test fun `restart preserves admitted templates release and budgets when operator settings change`() {
        val id = job(); queue().reconcile(); prepare(id)
        val changed = settings.copy(release = "different-release", caseMemoryMiB = 1024, maxSessionsPerJob = 1)
        NomadQueue(config, Store(root.resolve("lab.db")), backend, artifacts, changed, { true }, nativeAllowed = { true },
            source = { _, _, _ -> error("source already pinned") }).reconcile()
        val solves = store.execution.sessions(id).filter { it.executionClass == ExecutionClass.SOLVE }
        assertEquals(1, solves.size)
        assertTrue(solves.all { it.release == "release" && it.budget.memoryMiB == 768 })
        assertEquals(1, publications)
    }

    @Test fun `explicit cloud per host cap is independent of worker CPU requests`() {
        val id = job(ExperimentSpec("cloud-slots", problems = listOf(mapOf("suite" to "s")), vcpus = 8, host = Experiments.AWS_HOST))
        val narrow = narrow(1000)
        queue(policy = narrow.copy(perHostParallel = 2)).reconcile(); prepare(id, 4); backend.reconcile(); queue(policy = narrow.copy(perHostParallel = 2)).reconcile()
        fun solves() = store.execution.sessions(id).filter { it.executionClass == ExecutionClass.SOLVE }
        fun bind(session: ExecutionSession, node: String) {
            val spec = Json.parseToJsonElement(store.execution.submission(session.id)).jsonObject
            val token = spec.getValue("TaskGroups").jsonArray[0].jsonObject.getValue("Tasks").jsonArray[0].jsonObject
                .getValue("Env").jsonObject.getValue("LAB_WORKER_TOKEN").jsonPrimitive.content
            store.execution.bind(session.id, token, "allocation-${session.id}", node, "release")
        }
        assertEquals(1, solves().size)
        queue(policy = settings.copy(perHostParallel = 2)).reconcile(); assertEquals(1, solves().size, "an unplaced session must not cause duplicate placements")
        bind(solves().single { it.node == null }, "cloud-node-a")
        queue(policy = settings.copy(perHostParallel = 2)).reconcile(); assertEquals(2, solves().size)
        bind(solves().single { it.node == null }, "cloud-node-a")
        queue(policy = settings.copy(perHostParallel = 2)).reconcile(); assertEquals(3, solves().size)
        val thirdSession = solves().single { it.node == null }
        val third = Json.parseToJsonElement(store.execution.submission(thirdSession.id)).jsonObject
        val exclusion = third.getValue("Constraints").jsonArray.map { it.jsonObject }
            .single { it["LTarget"]?.jsonPrimitive?.content == "${'$'}{node.unique.id}" }
        assertEquals("!=", exclusion.getValue("Operand").jsonPrimitive.content)
        assertEquals("cloud-node-a", exclusion.getValue("RTarget").jsonPrimitive.content)
        bind(thirdSession, "cloud-node-b")
        queue(policy = settings.copy(perHostParallel = 2)).reconcile(); assertEquals(4, solves().size)
        val fourth = store.execution.submission(solves().single { it.node == null }.id)
        assertTrue(fourth.contains("cloud-node-a"))
        assertFalse(fourth.contains("cloud-node-b"), "the other host still has an experiment parallel slot")
    }

    @Test fun `default cloud workers share nodes and a live vCPU limit bounds frozen jobs`() {
        val id = job(ExperimentSpec("sharing", problems = listOf(mapOf("suite" to "s")), vcpus = 4, host = Experiments.AWS_HOST))
        queue(policy = narrow(1000)).reconcile(); prepare(id, 4); backend.reconcile()
        val policy = narrow(1000).copy(maxVcpusPerJob = 2)
        repeat(4) {
            queue(policy = policy).reconcile()
            store.execution.sessions(id).filter { it.executionClass == ExecutionClass.SOLVE && it.node == null }.forEach { session ->
                val spec = Json.parseToJsonElement(store.execution.submission(session.id)).jsonObject
                assertFalse(spec.getValue("Constraints").jsonArray.any { it.jsonObject["LTarget"]?.jsonPrimitive?.content == "${'$'}{node.unique.id}" })
                val token = spec.getValue("TaskGroups").jsonArray[0].jsonObject.getValue("Tasks").jsonArray[0].jsonObject.getValue("Env").jsonObject.getValue("LAB_WORKER_TOKEN").jsonPrimitive.content
                store.execution.bind(session.id, token, "allocation-${session.id}", "same-node", "release")
            }
        }
        val sessions = store.execution.sessions(id).filter { it.executionClass == ExecutionClass.SOLVE }
        assertEquals(2, sessions.size)
        assertEquals(2000, sessions.sumOf { it.budget.cpuMillis })
        assertEquals(setOf("same-node"), sessions.map { it.node }.toSet())
    }

    @Test fun `parallel one requests one worker across the cluster without reserving a node`() {
        val id = job(ExperimentSpec("one-vcpu", problems = listOf(mapOf("suite" to "s")), vcpus = 1, host = Experiments.AWS_HOST))
        queue().reconcile(); prepare(id, 8); backend.reconcile()
        repeat(3) { queue().reconcile() }
        val solves = store.execution.sessions(id).filter { it.executionClass == ExecutionClass.SOLVE && it.desired != Desired.STOP }
        assertEquals(1, solves.size)
        assertEquals(1000, solves.single().budget.cpuMillis)
        assertFalse(store.execution.submission(solves.single().id).contains("node.unique.id"))
    }

    @Test fun `domain completion is acknowledged once across coordinator instances`() {
        val id = job(); store.finish(id, Status.DONE)
        queue().reconcile(); queue().reconcile()
        assertEquals(1, completions)
        assertTrue(Store(root.resolve("lab.db")).execution.pendingDomainCompletion().isEmpty())
    }

    @Test fun `an ended job's unstopped native session no longer holds the native budget`() {
        val ended = job(ExperimentSpec("ended-native", problems = listOf(mapOf("suite" to "s")), vcpus = 1, host = Experiments.LAB_HOST))
        val live = job(ExperimentSpec("live-native", problems = listOf(mapOf("suite" to "s")), vcpus = 1, host = Experiments.LAB_HOST))
        queue(cores = 2).reconcile()
        assertEquals(2000L to 1024L, store.execution.nativeReservation())
        // Ended without its session being stopped, as when observing the job keeps failing.
        store.finish(ended, Status.FAILED, "lost")
        assertEquals(1000L to 512L, store.execution.nativeReservation())
        assertTrue(store.execution.sessions(live).isNotEmpty())
    }

    @Test fun `native preparations share CPU and memory budgets and overflow spills to Spot`() {
        val first = job(ExperimentSpec("first-native", problems = listOf(mapOf("suite" to "s")), vcpus = 1, host = Experiments.LAB_HOST))
        val second = job(ExperimentSpec("second-native", problems = listOf(mapOf("suite" to "s")), vcpus = 1, host = Experiments.LAB_HOST))
        val waiting = job(ExperimentSpec("waiting-native", problems = listOf(mapOf("suite" to "s")), vcpus = 1, host = Experiments.LAB_HOST))
        val automatic = job(ExperimentSpec("automatic", problems = listOf(mapOf("suite" to "s")), vcpus = 1, host = Experiments.AUTO_HOST))
        queue(cores = 2).reconcile()
        assertEquals(1, store.execution.sessions(first).size)
        assertEquals(1, store.execution.sessions(second).size)
        assertEquals(2000L to 1024L, store.execution.nativeReservation())
        assertTrue(store.execution.sessions(waiting).isEmpty())
        assertTrue(store.execution.submission(store.execution.sessions(automatic).single().id).contains("hard-quota"))
        store.requestCancel(first); backend.reconcile(); queue(cores = 2).reconcile()
        assertEquals(1, store.execution.sessions(waiting).size)
        assertEquals(0, store.execution.failureCount(waiting))
    }

    @Test fun `native replacement waits for resource capacity rather than experiment ownership`() {
        val waiting = job(ExperimentSpec("retry-native", problems = listOf(mapOf("suite" to "s")), host = Experiments.LAB_HOST))
        queue(cores = 1).reconcile()
        store.execution.loseSession(store.execution.sessions(waiting).single().id, "infrastructure:lost")
        val owner = job(ExperimentSpec("owner-native", problems = listOf(mapOf("suite" to "s")), host = Experiments.LAB_HOST))
        queue(cores = 1).reconcile(); backend.reconcile()
        assertEquals(1, store.execution.sessions(waiting).size)
        store.requestCancel(owner); backend.reconcile(); backend.reconcile()
        assertEquals(2, store.execution.sessions(waiting).size)
        assertEquals(1, store.execution.sessions(waiting).count { it.desired == Desired.RUN })
    }

    @Test fun `native ownership is fenced atomically even when admission and replacement race`() {
        val owner = job(ExperimentSpec("atomic-owner", problems = listOf(mapOf("suite" to "s")), host = Experiments.LAB_HOST))
        queue().reconcile()
        val waiting = job(ExperimentSpec("atomic-waiting", problems = listOf(mapOf("suite" to "s")), host = Experiments.LAB_HOST))
        val original = store.execution.sessions(owner).single()
        val manifest = store.execution.manifest(original.id).copy(session = java.util.UUID.randomUUID().toString(), experiment = store.job(waiting)!!.experiment!!)
        val template = settings.targets.single { it.os == "mac" }.template.copy(budget = manifest.budget)
        assertFailsWith<IllegalStateException> { backend.enqueue(waiting, manifest, template, settings.workerApi) }
        assertTrue(store.execution.sessions(waiting).isEmpty())
        assertEquals(Status.QUEUED, store.job(waiting)!!.status)
        store.requestCancel(owner); backend.reconcile()
        backend.enqueue(waiting, manifest, template, settings.workerApi)
        assertEquals(manifest.session, store.execution.sessions(waiting).single().id)
    }

    @Test fun `repeated Spot losses replenish workers without failing healthy siblings or republishing source`() {
        val id = job(wide); queue().reconcile(); prepare(id, 6); backend.reconcile(); queue(cores = 4).reconcile()
        val siblings = store.execution.sessions(id).filter { it.executionClass == ExecutionClass.SOLVE && store.execution.submission(it.id).contains("advisory-native") }.map { it.id }.toSet()
        repeat(8) {
            val active = store.execution.sessions(id).single { it.executionClass == ExecutionClass.SOLVE && it.desired != Desired.STOP && store.execution.submission(it.id).contains("hard-quota") }
            store.execution.loseSession(active.id, "infrastructure:spot")
            queue(cores = 4).reconcile()
            assertEquals(Status.RUNNING, store.job(id)!!.status)
            assertNull(store.job(id)!!.error)
            assertEquals(2, store.execution.sessions(id).count { it.executionClass == ExecutionClass.SOLVE && it.desired != Desired.STOP })
            assertTrue(store.execution.sessions(id).filter { it.id in siblings }.all { it.desired == Desired.RUN })
        }
        assertTrue(store.job(id)!!.commands.all { it.status == Status.QUEUED })
        assertEquals(8, store.execution.failureCount(id))
        assertEquals(1, store.execution.accepted(id).size)
        assertEquals(1, publications)
    }

    @Test fun `holds pauses and cancellation never admit new work`() {
        val id = job(); queue { false }.reconcile(); assertTrue(store.execution.sessions(id).isEmpty())
        store.setPaused(id, true); queue().reconcile(); assertTrue(store.execution.sessions(id).isEmpty())
        store.setPaused(id, false); queue().reconcile(); prepare(id)
        store.requestCancel(id); queue().reconcile()
        assertFalse(store.job(id)!!.setupDone)
        assertEquals(listOf(ExecutionClass.PREPARE), store.execution.sessions(id).map { it.executionClass })
    }

    @Test fun `cloud selection needs no AWS settings and profiles use Spot unless explicitly on demand`() {
        val spec = ExperimentSpec("cloud", profile = ProfileMain(main = "com.eignex.Main", runTimeoutSec = 60, classpath = listOf("tools/lib/*")), nodeTags = NodeSelector(require = setOf("cloud")))
        assertEquals(listOf(CapacityMarket.SPOT), settings.matching(spec, spec.name).map { it.market })
        assertEquals(Experiments.AWS_HOST, Placement.host(spec, spec.name, config.workers))
        assertTrue(settings.matching(spec.copy(nodeTags = NodeSelector(require = setOf("on-demand"))), spec.name).isEmpty())
        assertFailsWith<IllegalArgumentException> { settings.solveOptions("-Xmx8g") }
        assertEquals("-Xmx256m", settings.solveOptions("-Xmx256m"))
    }

    @Test fun `a lost worker is replenished while other sessions keep solving without publishing source again`() {
        val id = job(wide); queue().reconcile(); prepare(id, 6); queue().reconcile()
        val solve = store.execution.sessions(id).first { it.executionClass == ExecutionClass.SOLVE }
        store.execution.loseSession(solve.id, "infrastructure:spot")
        queue().reconcile()
        assertEquals(2, store.execution.sessions(id).count { it.executionClass == ExecutionClass.SOLVE && it.desired != Desired.STOP })
        assertEquals(1, publications)
        assertEquals(6, store.job(id)!!.commands.size)
    }
}
