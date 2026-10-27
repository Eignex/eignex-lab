package com.eignex.lab

import com.eignex.lab.bench.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Explicit local release gate: requires built consumer checkouts, never resolves or publishes a release. */
@Tag("bench-adapters")
class BenchAdapterIntegrationTest {
    @Test fun `prepared repository adapters plan execute transfer and ingest their real reports`() {
        val consumers = Path.of(requireNotNull(System.getenv("LAB_BENCH_ADAPTER_ROOT")) { "Set LAB_BENCH_ADAPTER_ROOT to the directory containing klause, koblas and kumulant checkouts" })
        val native = System.getenv("LAB_BENCH_ADAPTER_NATIVE") == "true"
        val minizinc = System.getenv("LAB_BENCH_ADAPTER_MINIZINC") == "true"
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
            for (name in listOf("klause", "koblas", "kumulant")) {
                val source = consumers.resolve(name)
                val directory = Files.createDirectories(root.resolve(name))
                val stage = directory.resolve("source")
                command(listOf("git", "clone", "--quiet", "--depth=1", "--no-local", source.toUri().toString(), stage.toString()), root)
                // Overlay the checkout under test, including uncommitted adapters, on self-contained Git metadata.
                val tracked = ProcessBuilder("git", "ls-files", "-z").directory(source.toFile()).start()
                val paths = tracked.inputStream.readBytes().toString(Charsets.UTF_8).split('\u0000').filter(String::isNotEmpty)
                check(tracked.waitFor() == 0)
                paths.forEach { copy(source.resolve(it), stage.resolve(it)) }
                copy(source.resolve("$name-bench/lab"), stage.resolve("$name-bench/lab"))
                val outputs = when (name) {
                    "klause" -> listOf("klause-cli/build/install/klause-cli-jvm", "klause-bench/build/install/klause-bench")
                    "koblas" -> listOf("koblas-bench/build/lab") + if (native) listOf("koblas-bench/build/bin/linuxX64/releaseExecutable") else emptyList()
                    else -> listOf("kumulant-bench/build/benchmarks/jvm/jars", "kumulant-bench/build/benchmark-dependencies.txt") +
                        if (native) listOf("kumulant-bench/build/benchmarks/linuxX64/classes", "kumulant-bench/build/bin/linuxX64/choleskyQualityReleaseExecutable") else emptyList()
                }
                outputs.forEach { copy(source.resolve(it), stage.resolve(it)) }
                val revision = ProcessBuilder("git", "rev-parse", "HEAD").directory(stage.toFile()).start().let { p ->
                    p.inputStream.bufferedReader().readText().trim().also { check(p.waitFor() == 0) }
                }
                val parameters = when (name) {
                    "klause" -> mapOf("suite" to "dimacs-core", "name" to "php4", "timeout-ms" to "1000")
                    "koblas" -> mapOf("targets" to if (native) "jvm-scalar,jvm-simd,native" else "jvm-scalar,jvm-simd", "smoke" to "true", "operation" to "dot")
                    else -> mapOf("backends" to if (native) "scalar,simd,native" else "scalar,simd", "smoke" to "true",
                        "argv" to if (native) "[\"-Pbench.filter=.*UpdateThroughputBenchmark.update\",\"-Pbench.param.name=SumStat\",\"-Pbench.param.concurrency=None\"]" else "[]")
                }
                // Native and JVM engines use different argv dialects; submit their plans separately.
                val requests = when {
                    name == "kumulant" && native -> listOf(parameters + mapOf("backends" to "scalar,simd", "argv" to "[]"), parameters + ("backends" to "native"))
                    name == "klause" && minizinc -> listOf(parameters, mapOf("suite" to "abs-relations", "name" to "magnitude", "timeout-ms" to "1000"))
                    else -> listOf(parameters)
                }
                for ((requestIndex, request) in requests.withIndex()) verify(name, directory.resolve("request-$requestIndex"), stage, revision,
                    "$name-bench/lab/${if (native && name != "klause") "native-linux-x64" else "bench"}.json", request, supervisor)
            }
        } finally { process.destroy(); process.waitFor(10, TimeUnit.SECONDS); process.destroyForcibly() }
    }

    private fun verify(name: String, root: Path, stage: Path, revision: String, entry: String, request: Map<String, String>, supervisor: ProcessSupervisor) {
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
        val backend = NomadBackend(store, control, artifacts, { root.resolve("jobs/$it") })
        val spec = ExperimentSpec("$name-adapter", repository = "Eignex/$name", bench = BenchSpec(entry, BenchRequest(request)), confirm = true, host = "auto")
        Experiments.validate(spec, 4)
        val job = store.create(spec.name, revision, emptyList(), experiment = spec)
        fun upload(file: Path): String = hashFile(file).also { sha -> Files.newInputStream(file).use { artifacts.put(job, sha, Files.size(file), 0, it) } }
        val identity = WorkerBundle("0".repeat(64), listOf(InputFile("placeholder", "0".repeat(64), 0)), spec.repository, revision,
            "linux", "amd64", "local-unreleased-integration")
        val bundle = archive(stage, root.resolve("input.zip"), identity) {}
        upload(root.resolve("input.zip"))
        val arms = Experiments.arms(spec).map { PlannedArm(it, revision) }
        val cache = VerifiedCache(root.resolve("cache"))
        fun manifest(id: String, cases: List<WorkCase>, bundles: List<WorkerBundle>, budget: ResourceBudget, corpus: WorkerBundle? = null) =
            WorkManifest(id, "integration", spec, arms, cases, bundles, budget, preparationTimeoutSec = if (cases.isEmpty()) 600 else null, inputCorpus = corpus)
        fun claim(manifest: WorkManifest): IterationClaim {
            val session = backend.enqueue(job, manifest, NomadBatchJob("template", NomadPlatform.LINUX, "fixture", NomadCpuCapacity(8000, 8),
                manifest.budget, listOf("worker"), "registry/worker@sha256:" + "d".repeat(64)), "http://localhost:8091")
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
                prepManifest, root.resolve("prepare"), { null }, lease(prepClaim.owner), { backend.selectPreparation(prepClaim.owner, it) }, ::upload)
        store.execution.phase(prepClaim.owner, "uploading")
        assertTrue(backend.commit(prepClaim.owner, IterationResult(emptyList(), preparation.artifacts, preparation = preparation)))
        val plan = requireNotNull(preparation.benchPlan)
        assertEquals("$name-bench", preparation.benchDescriptor!!.id)
        val problems = plan.cases.map(BenchRuns::problem)
        val cases = Experiments.cases(problems.size, 1, emptyList())
        store.prepareNomad(job, arms, problems, cases, plan.cases.map { CaseCommand("adapter ${it.id}", it.resources.timeoutSeconds, it.resources.cpus) })
        val resources = BenchRuns.resources(plan)
        val work = plan.cases.mapIndexed { index, case -> WorkCase(index, 0, problems[index], null, 0, case.resources.timeoutSeconds) }
        val execution = manifest("solve", work, preparation.bundles, ResourceBudget(resources.cpus * 1000, resources.memoryMiB.toInt() + 512), preparation.corpus)
        for (index in plan.cases.indices) {
            val claim = claim(execution)
            assertEquals(listOf(index), claim.indices)
            store.execution.phase(claim.owner, "executing")
            val attempt = root.resolve("solve-$index")
            val result = IterationExecutor(supervisor, cache, { Files.newInputStream(artifacts.file(job, it.sha256)) }, "unused", "linux", "amd64")
                .run(execution, claim.indices, attempt, "integration-host", claim.owner.generation, remainingLeaseMs = lease(claim.owner))
            val outputs = Files.walk(attempt).use { files -> files.filter(Files::isRegularFile).filter {
                val relative = attempt.relativize(it).toString()
                relative.startsWith("cases/") || relative in setOf("$index.out", "$index.err", "$index.exit", "$index.termination")
            }.toList().associate { attempt.relativize(it).toString() to upload(it) } }
            store.execution.phase(claim.owner, "uploading")
            assertTrue(backend.commit(claim.owner, result.copy(artifacts = outputs)))
            val actual = result.cases.single()
            assertEquals(0, actual.exitCode, "${actual.record}\n${Files.readString(attempt.resolve("$index.err"))}")
            val record = requireNotNull(BenchRuns.result(actual.record))
            assertEquals(BenchStatus.SUCCESS, record.status)
            assertTrue(record.artifacts.isNotEmpty())
            record.artifacts.forEach { artifact -> assertTrue(outputs.containsKey("cases/$index/out/${artifact.path}")) }
            assertFalse(outputs.keys.any { it.endsWith("profile.jfr") })
            if (name == "klause") {
                val domain = Json.parseToJsonElement(actual.record!!).jsonObject
                assertTrue("sourceHashes" in domain && "buildProvenance" in domain && "labBench" in domain)
            }
        }
        // Reconcile accepted records through the coordinator projection used by the API and file browser.
        backend.reconcile()
        val stored = store.cases(job)
        assertEquals(plan.cases.size, stored.size)
        assertTrue(stored.all { BenchRuns.result(it.record?.toString())?.status == BenchStatus.SUCCESS })
        for (case in stored) BenchRuns.result(case.record?.toString())!!.artifacts.forEach { artifact ->
            assertTrue(Files.isRegularFile(root.resolve("jobs/$job/cases/${case.index}/out/${artifact.path}")))
        }
        Files.writeString(root.resolve("metrics.csv"), BenchRuns.metricsCsv(stored))
        println("Verified $name: ${plan.cases.size} cases, ${stored.sumOf { BenchRuns.result(it.record?.toString())!!.artifacts.size }} ingested artifacts")
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
