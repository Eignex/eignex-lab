package com.eignex.lab

import com.eignex.lab.bench.*
import java.nio.file.Files
import kotlin.test.*

class BenchWorkflowTest {
    private fun workload(id: String = "runtime-a", digest: String = "input") = BenchCase(id,
        parameters = mapOf("opaque" to id), attributes = mapOf("comparisonKey" to "model/7/0", "labInputDigest" to digest))

    @Test fun `paired arms retain their own runtime cases under one frozen workload identity`() {
        val before = workload()
        val after = workload("runtime-b")
        val aligned = BenchRuns.align(listOf(before), BenchPlan(listOf(after)))
        assertEquals(after, aligned.cases.single())
        assertEquals(BenchRuns.problem(before).problem, BenchRuns.problem(after).problem)
        assertNotEquals(before.parameters, aligned.cases.single().parameters)
    }

    @Test fun `paired planning refuses changed input bytes missing workloads and ambiguous identities`() {
        val frozen = workload()
        for (cases in listOf(listOf(workload(digest = "changed")), emptyList(), listOf(workload(), workload("another")))) {
            assertFailsWith<IllegalArgumentException> { BenchRuns.align(listOf(frozen), BenchPlan(cases)) }
        }
    }

    @Test fun `directory inputs are pinned by content rather than only their path`() {
        val root = Files.createTempDirectory("bench-pair")
        try {
            Files.createDirectories(root.resolve("include"))
            val file = root.resolve("include/data.inc")
            Files.writeString(file, "first")
            val case = workload().copy(inputs = listOf(BenchInput("include", role = "include")))
            val before = BenchRuns.pinned(case, root, root)
            Files.writeString(file, "second")
            val after = BenchRuns.pinned(case, root, root)
            assertFailsWith<IllegalArgumentException> { BenchRuns.align(listOf(before), BenchPlan(listOf(after))) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `configuration grids pass opaque request values without adding solver options`() {
        val spec = ExperimentSpec("pair", repository = "Eignex/example", bench = BenchSpec("lab.json", BenchRequest(mapOf("mode" to "base"))),
            configs = listOf(mapOf("ref" to "before"), mapOf("ref" to "after")), grid = mapOf("param.policy" to listOf("a", "b")), repeats = 3)
        Experiments.validate(spec, 4)
        val arms = Experiments.arms(spec)
        assertEquals(4, arms.size)
        assertEquals(mapOf("mode" to "base", "policy" to "a"), BenchRuns.request(spec.bench!!, arms.first()).parameters)
        assertEquals(listOf("before", "after"), arms.map { it.ref }.distinct())
        assertFailsWith<IllegalArgumentException> { Experiments.validate(spec.copy(base = mapOf("engine" to "solver-specific")), 4) }
    }

    @Test fun `confirmations preserve repository request placement and exact pinned workloads`() {
        val frozen = BenchRuns.problem(workload())
        val spec = ExperimentSpec("sweep", repository = "Eignex/example", host = "aws", nodeTags = NodeSelector(require = setOf("arch:x86_64")),
            bench = BenchSpec("tools/bench.json", BenchRequest(mapOf("selection" to "small")), sourceValidation = true),
            base = mapOf("param.policy" to "selected"), grid = mapOf("param.mode" to listOf("first", "second")))
        val confirmation = Confirm.spec("sweep", spec, "a".repeat(40), "b".repeat(40), Flips(listOf(frozen), emptyList()), 7)
        Experiments.validate(confirmation, 4)
        assertEquals(spec.repository, confirmation.repository)
        assertEquals(spec.host, confirmation.host)
        assertEquals(spec.nodeTags, confirmation.nodeTags)
        assertEquals(listOf(frozen.benchCase), confirmation.bench!!.frozenCases)
        assertEquals(spec.bench!!.request, confirmation.bench.request)
        assertEquals(listOf("a".repeat(40), "b".repeat(40)), Experiments.arms(confirmation).map { it.ref })
        assertTrue(Experiments.arms(confirmation).all { it.values["param.mode"] == "first" })
        assertEquals(Confirm.REPEATS, confirmation.repeats)
    }

    @Test fun `legacy solver recipes and implicit profile classpaths are refused`() {
        assertFailsWith<IllegalArgumentException> { Experiments.validate(ExperimentSpec("old", problems = listOf(mapOf("suite" to "specific"))), 4) }
        assertFailsWith<IllegalArgumentException> { ProfileRuns.validate("Eignex/example", ProfileMain("com.eignex.tools.Main")) }
        val profile = ProfileMain("com.eignex.tools.Main", classpath = listOf("tools/lib/*"))
        assertEquals(emptyList(), ProfileRuns.buildTasks("Eignex/example", profile))
        val command = ProfileRuns.command("/work", "Eignex/example", profile, 0, "/corpus", false)
        assertContains(command, "LAB_CORPUS")
        assertFalse(command.contains("KLAUSE"))
    }

    @Test fun `reference plans use declared source files and their own CPU and timeout budgets`() {
        val source = workload().copy(inputs = listOf(BenchInput("model.cnf", role = "model")), attributes = workload().attributes + mapOf("recordSchema" to "solver-v1", "format" to "DIMACS"))
        val arm = Arm("reference", mapOf("backend" to "reference", "solver" to "kissat", "timeout" to "1000", "processors" to "2"))
        val plan = BenchRuns.referencePlan(BenchPlan(listOf(source)), arm)
        assertEquals(2, plan.cases.single().resources.cpus)
        assertEquals(Experiments.caseTimeoutSec(arm), plan.cases.single().resources.timeoutSeconds)
        assertEquals(source.inputs, plan.cases.single().inputs)
        val spec = ExperimentSpec("reference", repository = "Eignex/example", base = arm.values, bench = BenchSpec("bench.json"))
        Experiments.validate(spec, 4)
    }
}
