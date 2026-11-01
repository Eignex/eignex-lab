package com.eignex.lab

import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.nomad.*
import com.eignex.lab.bench.BenchRequest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** An experiment's `vcpus`: its input and retired alias, and the solve and preparation budgets it buys. */
class VcpusTest {
    private val spec = ExperimentSpec("e", bench = BenchSpec("bench.json"), host = HostPlacement.AUTO)
    private fun job(vcpus: Int, experiment: ExperimentSpec = spec) =
        Job(1, "e", "main", status = Status.QUEUED, cancelRequested = false, setupDone = false, createdAt = 0, vcpus = vcpus, experiment = experiment)
    private fun target(cpuMillis: Int, memoryMiB: Int, logicalCpus: Int = 16) = NomadQueueTarget(NomadBatchJob("t", NomadPlatform.LINUX, "linux-$cpuMillis",
        NomadCpuCapacity(logicalCpus * 1000, logicalCpus), ResourceBudget(cpuMillis, memoryMiB), listOf("worker"), "registry/worker@sha256:" + "d".repeat(64)), setOf("arch:x86_64"))
    private val wide = target(16000, 65536)
    private val settings = NomadQueueSettings("release", "http://localhost:8091", listOf(wide), caseMemoryMiB = 1024, sessionMemoryMiB = 512)
    private fun input(text: String) = Json.decodeFromJsonElement(ExperimentSpec.serializer(), experimentInput(Json.parseToJsonElement(text).jsonObject))

    @Test fun `vcpus is the submitted field and parallel its alias with the same meaning`() {
        val base = """"name":"e","bench":{"manifest":"bench.json"}"""
        assertEquals(4, input("{$base,\"vcpus\":4}").vcpus)
        assertEquals(4, input("{$base,\"parallel\":4}").vcpus)
        assertEquals(4, input("{$base,\"vcpus\":4,\"parallel\":4}").vcpus)
        assertNull(input("{$base}").vcpus)
        val conflict = assertFailsWith<IllegalArgumentException> { input("{$base,\"vcpus\":4,\"parallel\":2}") }
        assertContains(conflict.message!!, "vcpus")
        // The stored recipe keeps its key, so pinned workers and recorded specs read it unchanged.
        val stored = Json.encodeToJsonElement(ExperimentSpec.serializer(), spec.copy(vcpus = 3)).jsonObject
        assertEquals(3, stored["parallel"]!!.jsonPrimitive.int)
        assertEquals(3, storedExperiment(stored.toString()).vcpus)
        // Schedule responses speak the public vocabulary.
        val public = scheduleExperiment(spec.copy(vcpus = 3))
        assertEquals(3, public["vcpus"]!!.jsonPrimitive.int)
        assertNull(public["parallel"])
        assertEquals(3, input(public.toString()).vcpus)
    }

    @Test fun `recording modes default to one vCPU`() {
        assertEquals(1, input("""{"name":"e","bench":{"manifest":"bench.json"},"measurement":"resources"}""").vcpus)
        assertEquals(2, input("""{"name":"e","bench":{"manifest":"bench.json"},"measurement":"resources","vcpus":2}""").vcpus)
    }

    @Test fun `a comparison series keeps its fingerprint at one vCPU and starts another above it`() {
        val series = spec.copy(bench = BenchSpec("bench.json", request = BenchRequest(parameters = mapOf("comparison-series" to "s"))))
        // The fingerprint of a recorded `parallel: 1` series, before `vcpus` existed.
        val recorded = storedExperiment(Json.encodeToString(ExperimentSpec.serializer(), series.copy(vcpus = 1)))
        assertEquals(1, recorded.vcpus)
        assertNotEquals(Experiments.fingerprint(series.copy(vcpus = 2)), Experiments.fingerprint(series.copy(vcpus = 1)))
        assertNotEquals(Experiments.fingerprint(series.copy(vcpus = 3)), Experiments.fingerprint(series.copy(vcpus = 2)))
        assertEquals(Experiments.fingerprint(spec.copy(vcpus = 1)), Experiments.fingerprint(spec.copy(vcpus = 16)))
    }

    @Test fun `a session runs all but one of its vcpus as cases, divided by a case's cpus`() {
        assertEquals(listOf(1, 1, 3, 15), listOf(1, 2, 4, 16).map { Experiments.caseConcurrency(it, 1) })
        assertEquals(listOf(1, 1, 3, 7), listOf(1, 4, 13, 30).map { Experiments.caseConcurrency(it, 4) })
    }

    @Test fun `a solve session reserves the job's vcpus within its target`() {
        fun budget(vcpus: Int, target: NomadQueueTarget = wide, maxCases: Int = Int.MAX_VALUE) =
            NomadQueueSettings("release", "http://localhost:8091", listOf(target), caseMemoryMiB = 1024, sessionMemoryMiB = 512)
                .solveBudget(target, job(vcpus), maxCases = maxCases)
        assertEquals(ResourceBudget(1000, 1536, 1), budget(1))
        assertEquals(ResourceBudget(4000, 512 + 3 * 1024, 3), budget(4))
        assertEquals(ResourceBudget(16000, 512 + 15 * 1024, 15), budget(16))
        // Capped by the target's CPUs, the session keeps one of them for the worker.
        assertEquals(ResourceBudget(8000, 512 + 7 * 1024, 7), budget(16, target(8000, 65536)))
        // Memory for four cases only: their CPUs and the worker's one.
        assertEquals(ResourceBudget(5000, 512 + 4 * 1024, 4), budget(16, target(16000, 512 + 4 * 1024)))
        // The native worker's case cap does the same, and its vCPUs stay within the local cores.
        assertEquals(ResourceBudget(7000, 512 + 6 * 1024, 6), budget(16, maxCases = 6))
        assertEquals(ResourceBudget(4000, 512 + 3 * 1024, 3), settings.solveBudget(wide, job(16), cpuCeiling = 4000))
        // A case wider than the job's vcpus still gets its CPUs; one wider than the target cannot run there.
        val wideCase = spec.copy(base = mapOf("processors" to "4"))
        val plan = com.eignex.lab.bench.BenchPlan(listOf(com.eignex.lab.bench.BenchCase("c", resources = com.eignex.lab.bench.BenchResources(4, 1024, 10))))
        assertEquals(ResourceBudget(4000, 1536, 1), settings.solveBudget(wide, job(1, wideCase), plan))
        assertEquals(ResourceBudget(16000, 512 + 3 * 1024, 3), settings.solveBudget(wide, job(16, wideCase), plan))
        assertNull(settings.solveBudget(target(3000, 65536), job(16, wideCase), plan))
    }

    @Test fun `a preparation grows with the job's vcpus from the configured floor within its target`() {
        val floor = settings.copy(preparationBudget = ResourceBudget(2000, 4096, 1), maxVcpusPerJob = 32)
        assertEquals(ResourceBudget(2000, 4096), floor.preparationBudget(wide, job(1)))
        assertEquals(ResourceBudget(2000, 4096), floor.preparationBudget(wide, job(2)))
        assertEquals(ResourceBudget(4000, 8192), floor.preparationBudget(wide, job(4)))
        assertEquals(ResourceBudget(16000, 32768), floor.preparationBudget(wide, job(16)))
        // The target's CPUs, its memory, the local cores and the queue's per-job ceiling each cap it.
        assertEquals(ResourceBudget(8000, 16384), floor.preparationBudget(target(8000, 65536), job(16)))
        assertEquals(ResourceBudget(16000, 12288), floor.preparationBudget(target(16000, 12288), job(16)))
        assertEquals(ResourceBudget(6000, 12288), floor.preparationBudget(wide, job(16), cpuCeiling = 6000))
        assertEquals(ResourceBudget(8000, 16384), floor.copy(maxVcpusPerJob = 8).preparationBudget(wide, job(16)))
    }

    @Test fun `the operator queue example still validates with its one-case preparation budget`() {
        val example = Json.decodeFromString<NomadQueueSettings>(Files.readString(Path.of("deploy/nomad/queue.example.json")))
        assertEquals(ResourceBudget(2000, 4096, 1), example.preparationBudget)
        val cloud = example.targets.single { !it.native }
        assertEquals(ResourceBudget(6000, 12288), example.preparationBudget(cloud, job(16)))
        assertEquals(ResourceBudget(6000, 512 + 5 * 4096, 5), example.solveBudget(cloud, job(16)))
    }
}
