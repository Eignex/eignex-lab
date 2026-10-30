package com.eignex.lab

import com.eignex.lab.bench.BenchCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComparisonControlsTest {
    private fun case(seed: Long = 1, repeat: Int = 0, format: String = "MPS"): CaseResult {
        val controls = mapOf("policy" to "matched-source-v1", "series" to "sdk-linux-v1", "solverSeed" to "$seed",
            "compilerSeed" to "1", "solverBudgetMs" to "1000", "compilerTimeoutSeconds" to "60", "caseTimeoutSeconds" to "280",
            "cpus" to "1", "memoryMiB" to "4096", "heap-mib" to "2560", "gc" to "default", "debug" to "true",
            "measurement" to "none", "exact" to "false", "complete-assignment" to "true", "deadlinePolicy" to "cli-inclusive-v1",
            "runtimeFingerprint" to "runtime", "harnessFingerprint" to "harness", "libraryFingerprint" to "libraries",
            "inputDigest" to "inputs", "compilerFingerprint" to "compiler")
        val plan = BenchCase("s/p/$seed/$repeat", parameters = mapOf("seed" to "$seed", "engine" to "default"),
            attributes = mapOf("suite" to "s", "problem" to "p", "seed" to "$seed", "repeat" to "$repeat"))
        val record = JsonObject(mapOf("solver" to JsonPrimitive("klause"), "seed" to JsonPrimitive(seed),
            "processors" to JsonPrimitive(1), "budgetMs" to JsonPrimitive(1000), "kind" to JsonPrimitive("satisfy"),
            "comparisonControls" to JsonObject(controls.mapValues { JsonPrimitive(it.value) }),
            "sourceHashes" to JsonObject(mapOf("model" to JsonPrimitive("source"))),
            "compiledInputHashes" to JsonObject(mapOf("flatzinc" to JsonPrimitive("fzn"), "flatzincContentV1" to JsonPrimitive("content"), "outputMapping" to JsonPrimitive("ozn")))))
        return CaseResult(repeat, Status.DONE, Problem("s", plan.id, format = format, benchCase = plan), "arm",
            seed = seed, repeat = repeat, record = Json.parseToJsonElement(ComparisonExecution.record(record.toString(),
                ResourceObservation("cgroup-v2-hard", ResourceBudget(1000, 4608), "100000 100000", "4831838208",
                    "25", "Linux", "amd64", emptyMap(), "c".repeat(64), 1, 2), "a".repeat(64), "release")!!))
    }

    @Test
    fun `matched source and runtime controls qualify independently of case paths`() {
        val before = case()
        val after = before.copy(index = 99, arm = "after", problem = before.problem.copy(problem = "relocated"))

        assertNull(ComparisonControls.mismatch(before, after))
        assertEquals(ComparisonControls.problem(before), ComparisonControls.problem(after))
    }

    @Test
    fun `resource runtime seed and migration changes withhold revision attribution`() {
        val before = case()
        val record = before.record as JsonObject
        val controls = record.getValue("comparisonControls") as JsonObject
        for (field in controls.keys) {
            val changed = JsonObject(controls + (field to JsonPrimitive("changed")))
            val after = before.copy(record = JsonObject(record + ("comparisonControls" to changed)))

            assertNotNull(ComparisonControls.mismatch(before, after), field)
        }
    }

    @Test
    fun `missing historical controls and changed compiled bytes cannot qualify`() {
        val before = case(format = "MINIZINC")
        val record = before.record as JsonObject
        assertNotNull(ComparisonControls.mismatch(before, before.copy(record = JsonObject(record - "comparisonControls"))))
        assertNotNull(ComparisonControls.mismatch(before, before.copy(record = JsonObject(record - "sourceHashes"))))
        assertNotNull(ComparisonControls.mismatch(before, before.copy(record = JsonObject(record - "compiledInputHashes"))))
        val changed = JsonObject(record + ("compiledInputHashes" to JsonObject(mapOf("flatzinc" to JsonPrimitive("changed")))))
        assertNotNull(ComparisonControls.mismatch(before, before.copy(record = changed)))
        assertNull(ComparisonControls.mismatch(before, before))
    }

    @Test
    fun `generated path comments retain raw identities without splitting compiled content`() {
        val before = case(format = "MINIZINC")
        val record = before.record as JsonObject
        val compiled = record.getValue("compiledInputHashes") as JsonObject
        val relocated = JsonObject(compiled + ("flatzinc" to JsonPrimitive("other raw path comments")))

        assertNull(ComparisonControls.mismatch(before, before.copy(record = JsonObject(record + ("compiledInputHashes" to relocated)))))
    }

    @Test
    fun `absent or changed execution receipts withhold revision attribution`() {
        val before = case()
        val record = before.record as JsonObject
        val receipt = record.getValue("labExecution") as JsonObject
        val frame = receipt.getValue("frame") as JsonObject
        val changed = JsonObject(record + ("labExecution" to JsonObject(receipt +
            ("frame" to JsonObject(frame + ("cpuIdentity" to JsonPrimitive("d".repeat(64))))))))

        assertNotNull(ComparisonControls.mismatch(before, before.copy(record = JsonObject(record - "labExecution"))))
        assertNotNull(ComparisonControls.mismatch(before, before.copy(record = changed)))
    }

    @Test
    fun `checking policy migrations split series and withhold revision attribution`() {
        val base = case()
        val record = base.record as JsonObject
        val before = base.copy(record = JsonObject(record + ("labCheck" to JsonObject(mapOf(
            "policy" to JsonPrimitive("policy-v1"), "state" to JsonPrimitive("valid"))))))
        val after = base.copy(record = JsonObject(record + ("labCheck" to JsonObject(mapOf(
            "policy" to JsonPrimitive("policy-v2"), "state" to JsonPrimitive("valid"))))))

        assertNotNull(ComparisonControls.mismatch(before, after))
        assertTrue(ComparisonControls.series(before) != ComparisonControls.series(after))
    }

    @Test
    fun `confirmation retains each solver seed once and repeats complete matched blocks`() {
        val cases = listOf(case(1, 0), case(1, 1), case(3, 0), case(3, 1))
        val flips = Flips(lost = listOf(cases.first().problem), gained = emptyList())
        val spec = Confirm.spec("s", ExperimentSpec("s", emptyList(), bench = BenchSpec("bench.json")),
            "before", "after", flips, 7, sourceCases = cases)

        assertEquals(2, spec.bench!!.frozenCases.size)
        assertEquals(setOf("1", "3"), spec.bench!!.frozenCases.map { it.parameters["seed"] }.toSet())
        assertEquals(3, spec.repeats)
        assertTrue(spec.problemList.isEmpty())
    }

    @Test
    fun `planned and outer repeated feasibility losses qualify as complete matching blocks`() {
        val first = case(1, 0).copy(repeat = 0)
        val second = case(1, 1).copy(repeat = 0)
        val rerun = first.copy(repeat = 1)

        val after = listOf(first, second, rerun)
        val before = after.map { it.copy(record = JsonObject((it.record as JsonObject) + ("feasible" to JsonPrimitive(true)))) }

        assertEquals(1, Confirm.flips(before, after).lost.size)
    }
}
