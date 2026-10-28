package com.eignex.lab

import com.eignex.lab.bench.*
import java.nio.file.Files
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class BenchRunsTest {
    @Test fun `bench command preserves frozen parameters and uses only repository runtime flags`() {
        val root = Files.createTempDirectory("bench-runs")
        try {
            val entry = BenchEntryPoint("com.eignex.tools.AdapterKt", listOf("build/lib/*"), listOf(":tools:prepare"), listOf("-Xmx256m"))
            Files.writeString(root.resolve("bench.json"), BenchProtocol.json.encodeToString(entry))
            val case = BenchCase("case with spaces", mapOf("argv" to "['literal $ value']"))
            val command = BenchRuns.command(root, BenchSpec("bench.json"), case, 7, root.resolve("corpus"), "Eignex/tools")
            assertContains(command, "LAB_CORPUS=")
            assertContains(command, "bench-case.json")
            assertContains(command, "literal $ value")
            assertContains(command, "-Xmx256m")
            assertFalse("StartFlightRecording" in command || "jdk.incubator.vector" in command)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `solver domain records preserve exact objectives witnesses and provenance`() {
        val case = BenchCase("solver", attributes = mapOf("recordSchema" to "solver-v1"))
        val domain = buildJsonObject { put("solver", "klause"); put("exactObjective", "9007199254740993"); put("finalWitness", "x = 9007199254740993"); putJsonObject("buildProvenance") { put("fingerprint", "build") } }
        val result = BenchResult(case.id, details = domain)
        val record = Json.parseToJsonElement(BenchRuns.record(BenchProtocol.json.encodeToString(result), case, 0)).jsonObject
        assertEquals(domain, JsonObject(record - "labBench"))
        assertEquals(result, BenchRuns.result(record.toString()))
        assertFailsWith<IllegalArgumentException> { BenchRuns.record(BenchProtocol.json.encodeToString(result.copy(caseId = "wrong")), case, 0) }
    }

    @Test fun `solver adapter exceptions can retain a generic failure without a domain record`() {
        val case = BenchCase("solver", attributes = mapOf("recordSchema" to "solver-v1"))
        val failure = BenchResult(case.id, BenchStatus.FAILED, message = "CLI missing")
        val encoded = BenchProtocol.json.encodeToString(failure)
        assertEquals(failure, BenchRuns.result(BenchRuns.record(encoded, case, 1)))
        assertFailsWith<IllegalArgumentException> { BenchRuns.record(encoded, case, 0) }
        assertNull(Outcome.of(BenchProtocol.json.parseToJsonElement(encoded)))
    }

    @Test fun `missing malformed and contradictory results fail without preventing artifact transfer`() {
        val case = BenchCase("p")
        listOf(null, "broken json", BenchProtocol.json.encodeToString(BenchResult("wrong"))).forEach { text ->
            val result = BenchRuns.executionRecord(text, case, 0)
            assertEquals(70, result.exit)
            assertEquals(BenchStatus.FAILED, BenchRuns.result(result.json)!!.status)
        }
        assertEquals(124, BenchRuns.executionRecord(null, case, 124).exit)
        val contradiction = BenchRuns.executionRecord(BenchProtocol.json.encodeToString(BenchResult(case.id)), case, 7)
        assertEquals(7, contradiction.exit)
        assertEquals(BenchStatus.FAILED, BenchRuns.result(contradiction.json)!!.status)
    }

    @Test fun `bench schedules retain their explicit check policy without legacy runtime options`() {
        val unchecked = ExperimentSpec("bench", bench = BenchSpec("bench.json"))
        val checked = unchecked.copy(bench = unchecked.bench!!.copy(sourceValidation = true))
        for (spec in listOf(unchecked, checked)) {
            val scheduled = SourceValidation.scheduled(spec)
            assertEquals(spec, scheduled)
            Experiments.validate(scheduled, 4)
            assertEquals(spec.bench!!.sourceValidation, SourceValidation.requested(scheduled))
            assertEquals(spec.bench.sourceValidation, SourceValidation.requested(scheduled, emptyMap()))
        }
        assertNotEquals(Experiments.fingerprint(unchecked), Experiments.fingerprint(checked))
        assertEquals(Experiments.trendFingerprint(unchecked), Experiments.trendFingerprint(checked))
    }

    @Test fun `bench run deadline is explicit bounded and persists without changing the adapter request`() {
        val bench = BenchSpec("bench.json", BenchRequest(mapOf("timeout-ms" to "500")), runTimeoutSec = 30, sourceValidation = true)
        val spec = ExperimentSpec("bench", bench = bench)
        Experiments.validate(spec, 4)
        assertEquals(30L, Experiments.runTimeoutSec(spec))
        assertEquals(spec, storedExperiment(Json.encodeToString(spec)))
        val unlimited = spec.copy(bench = bench.copy(runTimeoutSec = null))
        assertNull(Experiments.runTimeoutSec(unlimited))
        assertEquals(Experiments.fingerprint(unlimited), Experiments.fingerprint(spec))
        for (limit in listOf(0L, -1L, 7201L)) assertFailsWith<IllegalArgumentException> { bench.copy(runTimeoutSec = limit) }
    }

    @Test fun `resolved corpus inputs stay pinned after relocation`() {
        val root = Files.createTempDirectory("bench-inputs")
        try {
            val source = Files.createDirectories(root.resolve("source"))
            val corpus = Files.createDirectories(root.resolve("corpus"))
            val file = corpus.resolve("model.cnf"); Files.writeString(file, "p cnf 1 0")
            val case = BenchCase("p", inputs = listOf(BenchInput("model.cnf", BenchInputRoot.CORPUS, "model", hashFile(file))))
            BenchRuns.validateInputs(case, source, corpus)
            assertEquals("lab-corpus/model.cnf", BenchRuns.problem(case).model)
            Files.writeString(file, "changed")
            assertFailsWith<IllegalArgumentException> { BenchRuns.validateInputs(case, source, corpus) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `bench manifests explicitly fence workers that only understand solver manifests`() {
        val spec = ExperimentSpec("bench", bench = BenchSpec("bench.json"))
        val arm = PlannedArm(Experiments.arms(spec).single(), "a".repeat(40))
        val bundle = WorkerBundle("b".repeat(64), listOf(InputFile("fixture", "c".repeat(64), 0)), spec.repository, arm.sha, "linux", "amd64", "test")
        val manifest = WorkManifest("prepare", "release", spec, listOf(arm), emptyList(), listOf(bundle), ResourceBudget(1000, 1024), preparationTimeoutSec = 60)
        val encoded = Json.encodeToString(manifest)
        assertEquals(2, Json.parseToJsonElement(encoded).jsonObject.getValue("schemaVersion").jsonPrimitive.int)
        assertFailsWith<IllegalArgumentException> { manifest.copy(schemaVersion = 1) }
    }

    @Test fun `generic metrics and artifact links appear without solver verdicts`() {
        val root = Files.createTempDirectory("bench-page")
        try {
            val config = Config(dataDir = root, passwordHash = null)
            val store = Store(config.database)
            val spec = ExperimentSpec("bench", bench = BenchSpec("bench.json"))
            val job = store.job(store.create("bench", "main", emptyList(), experiment = spec))!!
            val result = BenchResult("p", metrics = listOf(BenchMetric("rate", 12.0, "ops/s", MetricDirection.HIGHER)),
                artifacts = listOf(BenchArtifact("report/raw data.csv")))
            val cases = listOf(CaseResult(0, Status.DONE, BenchRuns.problem(BenchCase("p")), "base",
                record = BenchProtocol.json.encodeToJsonElement(BenchResult.serializer(), result)))
            val page = jobPage(config, job, false, cases = cases)
            assertContains(page, "Bench results")
            assertContains(page, "ops/s")
            assertContains(page, "report/raw%20data.csv")
            assertContains(BenchRuns.metricsCsv(cases), "\"rate\",\"12.0\",\"ops/s\",\"higher\"")
            assertNull(Outcome.of(cases.single().record))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `placement reserves declared CPUs and memory and rejects incompatible targets`() {
        val root = Files.createTempDirectory("bench-budget")
        try {
            val store = Store(root.resolve("lab.db"))
            val spec = ExperimentSpec("bench", bench = BenchSpec("bench.json"))
            val job = store.job(store.create("bench", "main", emptyList(), experiment = spec))!!
            val target = NomadQueueTarget(NomadBatchJob("template", NomadPlatform.LINUX, "fixture", NomadCpuCapacity(8000, 8), ResourceBudget(8000, 8192), listOf("worker"), "registry/worker@sha256:" + "d".repeat(64)), setOf("arch:x86_64"))
            val settings = NomadQueueSettings("release", "http://localhost:8091", listOf(target))
            val case = BenchCase("parallel", resources = BenchResources(4, 2048, 10, setOf("platform:linux", "arch:x86_64")))
            assertEquals(ResourceBudget(4000, 2560), settings.solveBudget(target, job, BenchPlan(listOf(case))))
            assertNull(settings.solveBudget(target, job, BenchPlan(listOf(case.copy(resources = case.resources.copy(requiredTags = setOf("platform:mac")))))))
            assertNull(settings.solveBudget(target, job, BenchPlan(listOf(case.copy(resources = case.resources.copy(memoryMiB = 8192))))))
        } finally { root.toFile().deleteRecursively() }
    }
}
