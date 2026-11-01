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

import com.eignex.lab.bench.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.TimeUnit
import kotlin.test.*
import com.eignex.lab.execution.CaseChecks

/** Explicit local release gate: requires built consumer checkouts, never resolves or publishes a release. */
@Tag("bench-adapters")
class BenchAdapterIntegrationTest {
    @Test fun `prepared repository adapters plan execute transfer and ingest their real reports`() {
        val scenarios = BenchProtocol.json.decodeFromString<List<Scenario>>(Files.readString(Path.of(
            requireNotNull(System.getenv("LAB_BENCH_ADAPTER_MATRIX")) { "Set LAB_BENCH_ADAPTER_MATRIX to a scenario JSON file" })))
        val root = Files.createTempDirectory("bench-adapters-")
        println("Integration outputs: $root")
        val script = root.resolve("supervisor.py")
        javaClass.getResourceAsStream("/nomad/process_supervisor.py")!!.use { Files.copy(it, script) }
        val socket = root.resolve("supervisor.sock")
        val process = ProcessBuilder("python3", script.toString(), "--socket", socket.toString())
            .redirectError(root.resolve("supervisor.err").toFile()).start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!Files.exists(socket)) { check(process.isAlive && System.nanoTime() < deadline); Thread.sleep(20) }
            val supervisor = ProcessSupervisor(socket)
            for (scenario in scenarios) {
                val name = scenario.name
                val source = Path.of(scenario.source)
                val directory = Files.createDirectories(root.resolve(name))
                val stage = directory.resolve("source")
                command(listOf("git", "clone", "--quiet", "--depth=1", "--no-local", source.toUri().toString(), stage.toString()), root)
                val tracked = ProcessBuilder("git", "ls-files", "-z").directory(source.toFile()).start()
                val paths = tracked.inputStream.readBytes().toString(Charsets.UTF_8).split('\u0000').filter(String::isNotEmpty)
                check(tracked.waitFor() == 0)
                paths.forEach { copy(source.resolve(it), stage.resolve(it)) }
                copy(source.resolve(scenario.manifest), stage.resolve(scenario.manifest))
                scenario.outputs.forEach { copy(source.resolve(it), stage.resolve(it)) }
                val revision = ProcessBuilder("git", "rev-parse", "HEAD").directory(stage.toFile()).start().let { p ->
                    p.inputStream.bufferedReader().readText().trim().also { check(p.waitFor() == 0) }
                }
                verify(scenario, directory.resolve("run"), stage, revision, supervisor)
            }
        } finally { process.destroy(); process.waitFor(10, TimeUnit.SECONDS); process.destroyForcibly() }
    }

    @kotlinx.serialization.Serializable
    data class Scenario(val name: String, val repository: String, val source: String, val manifest: String,
                        val outputs: List<String>, val parameters: Map<String, String> = emptyMap(),
                        val configs: List<Map<String, String>> = listOf(emptyMap()), val base: Map<String, String> = emptyMap(), val repeats: Int = 1, val sourceValidation: Boolean = false)

    private fun verify(scenario: Scenario, root: Path, stage: Path, revision: String, supervisor: ProcessSupervisor) {
        val name = scenario.name
        Files.createDirectories(root)
        val store = Store(root.resolve("lab.db"))
        val artifacts = LocalArtifactStore(root.resolve("objects"))
        val jobs = mutableMapOf<String, JsonObject>()
        val control = object : NomadControl {
            override fun job(id: String) = jobs[id]
            override fun submit(job: JsonObject) { jobs[job.getValue("ID").jsonPrimitive.content] = job }
            override fun stop(id: String) { jobs.remove(id) }
            override fun allocations(job: String) = emptyList<NomadAllocation>()
            override fun allocation(id: String): NomadAllocation? = null
            override fun jwks() = buildJsonObject { putJsonArray("keys") {} }
        }
        val config = Config(dataDir = root, jobsDir = root.resolve("jobs"), corpusDir = root.resolve("checker-corpus"), auth = Auth.Disabled)
        val checks = CaseChecks(store, { Checkers.of(config.paths) }) { id, line -> println("check $id: $line") }
        val backend = NomadBackend(store, control, artifacts, { root.resolve("jobs/$it") }, checkCase = { id, index ->
            val (problem, arm) = requireNotNull(store.plans.caseOf(id, index))
            if (SourceValidation.requested(store.job(id)?.experiment, arm.values)) checks.check(id, index, problem)?.json() ?: "no-checker"
            else "not-required"
        })
        var spec = ExperimentSpec("$name-adapter", repository = scenario.repository, bench = BenchSpec(scenario.manifest, BenchRequest(scenario.parameters), sourceValidation = scenario.sourceValidation), configs = scenario.configs, base = scenario.base, repeats = scenario.repeats, confirm = true, host = HostPlacement.AUTO)
        Experiments.validate(spec, 4)
        var job = store.create(spec.name, revision, emptyList(), experiment = spec)
        fun upload(file: Path): String = hashFile(file).also { sha -> Files.newInputStream(file).use { artifacts.put(job, sha, Files.size(file), 0, it) } }
        val identity = WorkerBundle("0".repeat(64), listOf(InputFile("placeholder", "0".repeat(64), 0)), spec.repository, revision,
            "linux", "amd64", "local-unreleased-integration")
        val bundle = archive(stage, root.resolve("input.zip"), identity) {}
        upload(root.resolve("input.zip"))
        var arms = Experiments.arms(spec).map { PlannedArm(it, revision) }
        val cache = VerifiedCache(root.resolve("cache"))
        fun checkingInputs(preparation: PreparationResult) {
            if (!scenario.sourceValidation) return
            for (bundle in preparation.bundles) {
                val tree = cache.stage(bundle) { artifacts.open(job, bundle.sha256) }
                val link = config.jobDir(job).resolve("nomad/prepared-source/${bundle.commit}")
                Files.createDirectories(link.parent)
                Files.createSymbolicLink(link, tree.toAbsolutePath())
            }
            val corpus = preparation.corpus?.let { input -> cache.stage(input) { artifacts.open(job, input.sha256) } }
                ?: Files.createDirectories(root.resolve("empty-corpus"))
            Files.createSymbolicLink(config.jobDir(job).resolve("nomad/prepared-corpus"), corpus.toAbsolutePath())
        }
        fun manifest(id: String, cases: List<WorkCase>, bundles: List<WorkerBundle>, budget: ResourceBudget, corpus: WorkerBundle? = null) =
            WorkManifest(id, "integration", spec, arms, cases, bundles, budget, preparationTimeoutSec = if (cases.isEmpty()) 600 else null, inputCorpus = corpus)
        fun claim(manifest: WorkManifest): IterationClaim {
            val reference = manifest.arms.any { it.arm.values["backend"] == REFERENCE_BACKEND }
            // Admission is emulated; processes execute on this host, including the real reference launcher.
            val template = if (reference) NomadBatchJob("template", NomadPlatform.MAC, "fixture", NomadCpuCapacity(8000, 8),
                manifest.budget, listOf("/fixture/release/bin/lab", "worker"))
                else NomadBatchJob("template", NomadPlatform.LINUX, "fixture", NomadCpuCapacity(8000, 8),
                    manifest.budget, listOf("worker"), "registry/worker@sha256:" + "d".repeat(64))
            val session = backend.enqueue(job, manifest, template, "http://localhost:8091")
            val task = Json.parseToJsonElement(store.execution.submission(session.id)).jsonObject.getValue("TaskGroups").jsonArray[0].jsonObject.getValue("Tasks").jsonArray[0].jsonObject
            val token = task.getValue("Env").jsonObject.getValue("LAB_WORKER_TOKEN").jsonPrimitive.content
            store.execution.bind(session.id, token, "allocation-${session.id}", "integration-host", "integration")
            return store.execution.claim(session.id, token, "allocation-${session.id}")!!
        }
        fun lease(owner: AttemptOwner): () -> Long {
            var renewedAt = 0L
            var expires = 0L
            return {
                val at = now()
                if (at - renewedAt >= 10000) { expires = store.execution.renew(owner); renewedAt = at }
                expires - at
            }
        }
        val prepManifest = manifest("prepare", emptyList(), listOf(bundle), ResourceBudget(4000, 8192))
        val prepClaim = claim(prepManifest)
        store.execution.phase(prepClaim.owner, "preparing")
        val preparation = PreparationExecutor(supervisor, cache, WorkerBuildCache(root.resolve("builds"), supervisor),
            { Files.newInputStream(artifacts.file(job, it.sha256)) }, "linux", "amd64", shared = null).run(
                prepManifest, root.resolve("prepare"), { null }, lease(prepClaim.owner), { backend.selectPreparation(prepClaim.owner, it) }, upload = ::upload)
        store.execution.phase(prepClaim.owner, "uploading")
        assertTrue(backend.commit(prepClaim.owner, IterationResult(emptyList(), preparation.artifacts, preparation = preparation)))
        checkingInputs(preparation)
        val plan = requireNotNull(preparation.benchPlan)
        assertNotNull(preparation.benchDescriptor)
        val problems = plan.cases.map(BenchRuns::problem)
        val cases = Experiments.cases(problems.size, arms.size, emptyList(), spec.repeats).map { case ->
            case.copy(seed = problems[case.problem].benchCase!!.attributes["seed"]?.toLongOrNull())
        }
        fun execution(case: Case) = preparation.armPlans.getValue(arms[case.arm].arm.label).cases[case.problem]
        store.prepareNomad(job, arms, problems, cases, cases.map { case ->
            val record = execution(case)
            CaseCommand("adapter ${record.id}", record.resources.timeoutSeconds, record.resources.cpus)
        })
        val resources = preparation.armPlans.values.map(BenchRuns::resources)
        val work = cases.mapIndexed { index, case -> WorkCase(index, case.arm, problems[case.problem],
            problems[case.problem].benchCase!!.attributes["seed"]?.toLongOrNull(), case.repeat,
            execution(case).resources.timeoutSeconds, execution(case)) }
        val execution = manifest("solve", work, preparation.bundles, ResourceBudget(resources.maxOf { it.cpus } * 1000, resources.maxOf { it.memoryMiB }.toInt() + 512), preparation.corpus)
        while (store.unclaimed(job) > 0) {
            val claim = claim(execution)
            assertEquals(arms.size, claim.indices.size)
            store.execution.phase(claim.owner, "executing")
            val attempt = root.resolve("solve-${claim.indices.first()}")
            val result = IterationExecutor(supervisor, cache, { Files.newInputStream(artifacts.file(job, it.sha256)) }, System.getenv("LAB_BENCH_REFERENCE_LAUNCHER") ?: "unused", "linux", "amd64")
                .run(execution, claim.indices, attempt, "integration-host", claim.owner.generation, remainingLeaseMs = lease(claim.owner))
            val outputs = Files.walk(attempt).use { files -> files.filter(Files::isRegularFile).filter {
                val relative = attempt.relativize(it).toString()
                relative.startsWith("cases/") || claim.indices.any { index -> relative in setOf("$index.out", "$index.err", "$index.exit", "$index.termination") }
            }.toList().associate { attempt.relativize(it).toString() to upload(it) } }
            store.execution.phase(claim.owner, "uploading")
            assertTrue(backend.commit(claim.owner, result.copy(artifacts = outputs)))
            for (actual in result.cases) {
            val index = actual.index
            assertEquals(0, actual.exitCode, "${actual.record}\n${Files.readString(attempt.resolve("$index.err"))}")
            if (execution.arms[work.single { it.index == index }.arm].arm.values["backend"] == REFERENCE_BACKEND) {
                val domain = Json.parseToJsonElement(requireNotNull(actual.record)).jsonObject
                assertNotNull(domain["feasible"]?.jsonPrimitive?.booleanOrNull)
                assertEquals(execution.arms[work.single { it.index == index }.arm].arm.values["solver"],
                    domain["solver"]?.jsonPrimitive?.content)
            } else {
                val record = requireNotNull(BenchRuns.result(actual.record))
                assertEquals(BenchStatus.SUCCESS, record.status)
                assertTrue(record.artifacts.isNotEmpty())
                record.artifacts.forEach { artifact -> assertTrue(outputs.containsKey("cases/$index/out/${artifact.path}")) }
                if (work.single { it.index == index }.executionCase?.attributes?.get("recordSchema") == "solver-v1") {
                    val domain = Json.parseToJsonElement(actual.record!!).jsonObject
                    assertTrue("sourceHashes" in domain && "buildProvenance" in domain && "labBench" in domain)
                }
            }
            }
        }
        // Reconcile accepted records through the coordinator projection used by the API and file browser.
        backend.reconcile()
        val stored = store.plans.cases(job)
        assertEquals(cases.size, stored.size)
        if (scenario.sourceValidation) assertTrue(stored.all { it.record!!.jsonObject["labCheck"]?.jsonObject?.get("state")?.jsonPrimitive?.content == "valid" })
        assertTrue(stored.filter { result -> arms.single { it.arm.label == result.arm }.arm.values["backend"] != REFERENCE_BACKEND }
            .all { BenchRuns.result(it.record?.toString())?.status == BenchStatus.SUCCESS })
        for (case in stored) BenchRuns.result(case.record?.toString())?.artifacts.orEmpty().forEach { artifact ->
            assertTrue(Files.isRegularFile(root.resolve("jobs/$job/cases/${case.index}/out/${artifact.path}")))
        }
        val originalJob = job
        val frozen = plan.cases.take(1)
        val selectedValues = arms.first().arm.values - "ref"
        spec = spec.copy(name = Confirm.name("$name-replay", revision, originalJob), base = selectedValues, grid = emptyMap(), repeats = 1,
            configs = listOf(mapOf("label" to "before", "ref" to revision), mapOf("label" to "after", "ref" to revision)),
            bench = spec.work.bench!!.copy(frozenCases = frozen, replayJobs = mapOf("before" to originalJob, "after" to originalJob)))
        Experiments.validate(spec, 4)
        job = store.create(spec.name, revision, emptyList(), experiment = spec)
        arms = Experiments.arms(spec).map { PlannedArm(it, revision) }
        val replay = requireNotNull(benchReplay(replaySources(store, spec, arms), spec.work.bench!!))
        for (input in preparation.bundles + listOfNotNull(preparation.corpus)) {
            artifacts.open(originalJob, input.sha256).use { stream -> artifacts.put(job, input.sha256, artifacts.size(originalJob, input.sha256), 0, stream) }
        }
        val replayManifest = manifest("replay-prepare", emptyList(), preparation.bundles, ResourceBudget(4000, 8192)).copy(benchReplay = replay)
        val replayClaim = claim(replayManifest)
        store.execution.phase(replayClaim.owner, "preparing")
        val replayPreparation = PreparationExecutor(supervisor, cache, WorkerBuildCache(root.resolve("replay-builds"), supervisor),
            { Files.newInputStream(artifacts.file(job, it.sha256)) }, "linux", "amd64", shared = null,
            archives = WorkerArchiveCache(root.resolve("replay-archives")) { _, _, _, _, _ -> error("replay must retain accepted archive bytes") }).run(
                replayManifest, root.resolve("replay-prepare"), { null }, lease(replayClaim.owner), { backend.selectPreparation(replayClaim.owner, it) }, upload = ::upload)
        assertEquals(preparation.bundles.map { it.sha256 }, replayPreparation.bundles.map { it.sha256 })
        assertFalse(replayPreparation.artifacts.keys.any { it.contains("bench-plan-") })
        store.execution.phase(replayClaim.owner, "uploading")
        assertTrue(backend.commit(replayClaim.owner, IterationResult(emptyList(), replayPreparation.artifacts, preparation = replayPreparation)))
        checkingInputs(replayPreparation)
        val replayProblems = replayPreparation.selections.single()
        val replayCases = Experiments.cases(replayProblems.size, arms.size, emptyList()).map { case ->
            case.copy(seed = replayProblems[case.problem].benchCase!!.attributes["seed"]?.toLongOrNull())
        }
        val replayWork = replayCases.mapIndexed { index, case ->
            val execution = replayPreparation.armPlans.getValue(arms[case.arm].arm.label).cases[case.problem]
            WorkCase(index, case.arm, replayProblems[case.problem], case.seed, case.repeat, execution.resources.timeoutSeconds, execution)
        }
        store.prepareNomad(job, arms, replayProblems, replayCases, replayWork.map { CaseCommand("replay", it.timeoutSec, it.executionCase!!.resources.cpus) })
        val replayExecution = manifest("replay-solve", replayWork, replayPreparation.bundles,
            ResourceBudget(resources.maxOf { it.cpus } * 1000, resources.maxOf { it.memoryMiB }.toInt() + 512), replayPreparation.corpus)
        val paired = claim(replayExecution)
        assertEquals(2, paired.indices.size)
        store.execution.phase(paired.owner, "executing")
        val replayDirectory = root.resolve("replay-solve")
        val replayResult = IterationExecutor(supervisor, cache, { Files.newInputStream(artifacts.file(job, it.sha256)) }, System.getenv("LAB_BENCH_REFERENCE_LAUNCHER") ?: "unused", "linux", "amd64")
            .run(replayExecution, paired.indices, replayDirectory, "integration-host", paired.owner.generation, remainingLeaseMs = lease(paired.owner))
        val replayArtifacts = Files.walk(replayDirectory).use { files -> files.filter(Files::isRegularFile).filter { file ->
            val relative = replayDirectory.relativize(file).toString()
            relative.startsWith("cases/") || paired.indices.any { index -> relative in setOf("$index.out", "$index.err", "$index.exit", "$index.termination") }
        }.toList().associate { replayDirectory.relativize(it).toString() to upload(it) } }
        store.execution.phase(paired.owner, "uploading")
        assertTrue(backend.commit(paired.owner, replayResult.copy(artifacts = replayArtifacts)))
        assertTrue(replayResult.cases.all { it.exitCode == 0 }, replayResult.cases.joinToString { it.record.orEmpty() })
        backend.reconcile()
        assertEquals(Status.DONE, store.job(job)!!.status)
        assertEquals(2, store.plans.cases(job).size)
        if (scenario.sourceValidation) assertTrue(store.plans.cases(job).all { it.record!!.jsonObject["labCheck"]?.jsonObject?.get("state")?.jsonPrimitive?.content == "valid" })
        println("Verified $name replay: retained build hashes, no planner or build invocation, complete paired receipt")
        Files.writeString(root.resolve("metrics.csv"), BenchRuns.metricsCsv(stored))
        println("Verified $name: ${plan.cases.size} cases, ${stored.sumOf { BenchRuns.result(it.record?.toString())?.artifacts?.size ?: 0 }} ingested artifacts")
    }

    private fun command(argv: List<String>, directory: Path) {
        val process = ProcessBuilder(argv).directory(directory.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
    }

    private fun copy(source: Path, target: Path) {
        require(Files.exists(source)) { "Build the prepared adapter output first: $source" }
        if (Files.isDirectory(source)) Files.walk(source).use { files -> files.forEach { file ->
            val destination = target.resolve(source.relativize(file))
            if (Files.isDirectory(file)) Files.createDirectories(destination) else copy(file, destination)
        } } else { Files.createDirectories(target.parent); Files.copy(source, target, REPLACE_EXISTING, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES) }
    }
}
