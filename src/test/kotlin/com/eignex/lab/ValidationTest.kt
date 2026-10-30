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

import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidationTest {
    /** Job 6991, case 3: a proven optimum the solver reported, with no source check asked for. */
    private val unchecked = """{"problem":"2017/hrc/exp1-1-945","solver":"klause","kind":"optimize","maximize":false,
        "feasible":true,"objective":11.0,"exactObjective":"11","proven":true,"timeToBestMs":5158,"budgetMs":10000,
        "command":"klause-cli","validationPolicy":"reported-result-v1","stats":{"solveTime":"8.136"}}"""

    /** Job 7004: a MiniZinc witness the source accepted, with the solver's optimality claim beside it. */
    private val accepted = """{"problem":"1d_rubiks_cube/1d_rubiks_cube","solver":"klause","kind":"optimize","maximize":false,
        "feasible":true,"objective":8.0,"exactObjective":"8","proven":true,"timeToBestMs":2614,"budgetMs":5000,
        "command":"klause-cli","validationPolicy":"minizinc-source-witness-v3","stats":{"solveTime":"3.175",
        "sourceValidation":"valid","sourceValidationReason":"source constraints evaluate true with candidate pinned",
        "sourceValidationScope":"minizinc-source","floatApproximation":"false","reportedFeasible":"true",
        "reportedObjective":"8.0","reportedProven":"true"}}"""

    /** Job 7005: the same hrc instance, its witness rejected by the original source. */
    private val rejected = """{"problem":"2017/hrc/exp1-1-945","solver":"klause","kind":"optimize","maximize":false,
        "feasible":null,"objective":null,"exactObjective":null,"proven":false,"timeToBestMs":null,"budgetMs":5000,
        "command":"klause-cli","validationPolicy":"minizinc-source-witness-v3","stats":{"solveTime":"4.556",
        "sourceValidation":"invalid","sourceValidationReason":"source constraints reject candidate",
        "sourceValidationScope":"minizinc-source","floatApproximation":"false","reportedFeasible":"true",
        "reportedObjective":"12.0","reportedProven":"false"}}"""

    /** Job 7004: an XCSP3 witness the checker could not decide; the solution keeps its credit. */
    private val indeterminate = """{"problem":"CarpetCutting-test05_c23","solver":"klause","kind":"optimize","maximize":false,
        "feasible":true,"objective":1436.0,"exactObjective":"1436","proven":false,"timeToBestMs":4698,"budgetMs":5000,
        "command":"klause-cli","validationPolicy":"xcsp3-source-witness-v3","stats":{"solveTime":"4.753",
        "sourceValidation":"unknown","sourceValidationReason":"source checking cannot complete: expression SET is outside checker arithmetic auditing",
        "sourceValidationScope":"xcsp3-source","floatApproximation":"false","reportedFeasible":"true",
        "reportedObjective":"1436.0","reportedProven":"false"}}"""

    /** Job 7004: an MPS point the checker could not decide, which the bench withdrew and the lab keeps the credit of. */
    private val withdrawn = """{"problem":"bell3a","solver":"klause","kind":"optimize","maximize":false,
        "feasible":null,"objective":null,"exactObjective":null,"proven":false,"timeToBestMs":null,"budgetMs":5000,
        "command":"klause-cli","validationPolicy":"mps-miplib2017-point-v1","stats":{"solveTime":"4.883",
        "sourceValidation":"unknown","sourceValidationReason":"MPS source checking failed: number outside upstream decimal input",
        "sourceValidationScope":"ground-original-mps-witness","floatApproximation":"false","reportedFeasible":"true",
        "reportedObjective":"892384.9959999999","reportedProven":"false"}}"""

    /**
     * Job 7068, case 48 (miplib2003/opt1217), trimmed: a bench built without the MIPLIB solchecker withdrew the
     * solver's solution, though the checker never ran. Its producer record is cut to the fields the lab reads.
     */
    private val checkerMissing = """{"problem":"opt1217","solver":"klause","engine":null,"processors":1,"search":"free",
        "seed":3,"budgetMs":10000,"kind":"optimize","maximize":false,"feasible":null,"objective":null,"timeToBestMs":null,
        "timeToFirstFeasibleMs":null,"proven":false,"gitSha":"b417cf18b074785b295b14141aa868b15135d36b",
        "command":"klause-cli --format mps opt1217.mps.zst -t 10000 -r 3 -s -a --output-objective","elapsedMs":10168,
        "validationPolicy":"mps-miplib2017-point-gzip-v1","stats":{"sourceConclusion":"satisfiable","solveTime":"9.162",
        "sourceValidation":"unknown","sourceValidationReason":"MPS source checking failed: missing packaged MIPLIB solchecker",
        "sourceValidationScope":"ground-original-mps-witness","reportedFeasible":"true","reportedObjective":"-16.0",
        "reportedExactObjective":"-16","reportedProven":"false",
        "sourceValidationProducerRecordJson":"{\"problem\":\"opt1217\",\"feasible\":true,\"objective\":-16.0,\"timeToBestMs\":3651,\"proven\":false}"}}"""

    /** Job 7068, case 55 (miplib2010/ex9), trimmed: the checker ran and could not read the input; the claim stands. */
    private val unreadable = """{"problem":"ex9","solver":"klause","budgetMs":10000,"kind":"optimize","maximize":false,
        "feasible":null,"objective":null,"timeToBestMs":null,"proven":false,"command":"klause-cli","elapsedMs":10200,
        "validationPolicy":"mps-miplib2017-point-gzip-v1","stats":{"solveTime":"9.5","sourceValidation":"unknown",
        "sourceValidationReason":"MPS source checking failed: lower bounds following upper bounds are unsupported",
        "sourceValidationScope":"ground-original-mps-witness","reportedFeasible":"true","reportedObjective":"81.0",
        "reportedExactObjective":"81","reportedProven":"false"}}"""

    private fun outcome(record: String) = Outcome.of(Json.parseToJsonElement(record))!!

    private fun case(arm: String, problem: String, record: String) =
        CaseResult(0, Status.DONE, Problem("s", problem, "s", format = "MINIZINC"), arm, null, Json.parseToJsonElement(record))

    private fun committed(file: String) = ExperimentSpec(file.removeSuffix(".json"), bench = BenchSpec("bench.json", sourceValidation = true))

    private val scheduled = listOf("status-sweep.json", "klause-reference.json", "klause-reference-ls.json",
        "klause-reference-bt.json", "klause-reference-parallel.json")

    @Test
    fun `a scheduled run asks for validation over a config or grid that turned it off, and other jobs are left alone`() {
        val spec = ExperimentSpec("s", listOf(mapOf("suite" to "s")), base = mapOf("timeout" to "10000"),
            configs = listOf(mapOf(SourceValidation.PARAM to "false")), grid = mapOf(SourceValidation.GZIP_PARAM to listOf("false")))
        val reference = ExperimentSpec("r", listOf(mapOf("suite" to "s")), base = mapOf("backend" to REFERENCE_BACKEND))
        val profile = ExperimentSpec("p", profile = ProfileMain("com.eignex.tools.profile.Tool", classpath = listOf("tools/lib/*")))

        val arm = Experiments.arms(SourceValidation.scheduled(spec)).single()

        assertEquals(listOf("true", "true"), listOf(arm.values[SourceValidation.PARAM], arm.values[SourceValidation.GZIP_PARAM]))
        assertEquals(reference, SourceValidation.scheduled(reference))
        assertEquals(profile, SourceValidation.scheduled(profile))
    }

    @Test
    fun `a checked run stays on its schedule's trend line, but is never confirmed against an unchecked one`() {
        val unchecked = ExperimentSpec("s", listOf(mapOf("suite" to "s")), base = mapOf("timeout" to "10000"))
        val checked = SourceValidation.scheduled(unchecked)

        assertEquals(Experiments.trendFingerprint(unchecked), Experiments.trendFingerprint(checked))
        assertNotEquals(Experiments.fingerprint(unchecked), Experiments.fingerprint(checked))
        // Another timeout is another line still.
        assertNotEquals(Experiments.trendFingerprint(unchecked), Experiments.trendFingerprint(unchecked.copy(base = mapOf("timeout" to "60000"))))
    }

    @Test
    fun `a confirmation asks for validation on both commits even when its run's spec did not`() {
        val run = ExperimentSpec("status-sweep@bbb", listOf(mapOf("set" to "sweep")), base = mapOf("timeout" to "10000", "ref" to "bbb"))
        val flips = Flips(lost = listOf(Problem("s", "a")), gained = emptyList())

        val arms = Experiments.arms(Confirm.spec("status-sweep", run, "aaa111111111", "bbb222222222", flips, job = 7))

        assertEquals(listOf(Confirm.BEFORE, Confirm.AFTER), arms.map { it.label })
        assertTrue(arms.all { SourceValidation.requested(it.values) })
    }

    @Test
    fun `saving the committed spec over a schedule that already runs it queues no extra run`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val spec = committed("status-sweep.json")
        val id = store.schedules.create("status-sweep", "main", spec.copy(base = spec.base - SourceValidation.SCHEDULED.keys), 120)
        store.schedules.ran(id, "abc", 1)

        store.schedules.update(id, spec, intervalSec = null)

        assertEquals("abc", store.schedules.all().single().lastSha)
    }

    @Test
    fun `a verdict says whether its solution was source-checked, unchecked or indeterminate`() {
        val checked = verdict(outcome(accepted), Status.DONE)
        val reported = verdict(outcome(unchecked), Status.DONE)
        val undecided = verdict(outcome(indeterminate), Status.DONE)
        val restored = verdict(outcome(withdrawn), Status.DONE)

        assertTrue(checked.startsWith("8* <small class=\"DONE\""), checked)
        assertContains(checked, ">checked</small>")
        assertContains(checked, "a proof of optimality is the solver's own claim")
        assertTrue(reported.startsWith("11* <small class=\"muted\""), reported)
        assertContains(reported, "policy reported-result-v1")
        assertContains(reported, ">unchecked</small>")
        assertTrue(undecided.startsWith("1436 <small class=\"PARTIAL\""), undecided)
        assertContains(undecided, "expression SET is outside checker arithmetic auditing")
        assertContains(undecided, ">check indeterminate</small>")
        assertTrue(restored.startsWith("892384.9959999999 <small class=\"PARTIAL\""), restored)
        assertContains(restored, "reported objective 892384.9959999999")
        assertEquals("<span class=\"FAILED\" title=\"source constraints reject candidate\">invalid solution</span>",
            verdict(outcome(rejected), Status.DONE))
    }

    @Test
    fun `no mark where there is no Klause solution claim to check`() {
        val undecided = """{"solver":"klause","kind":"satisfy","feasible":null,"proven":false,"budgetMs":5000,
            "validationPolicy":"xcsp3-source-witness-v3","stats":{"sourceValidation":"unknown","sourceValidationReason":"no feasible candidate to check"}}"""
        val reference = """{"solver":"highs","kind":"optimize","feasible":true,"objective":3,"proven":true,"budgetMs":5000,
            "validationPolicy":"reported-result-v1","stats":{}}"""
        val infeasible = """{"solver":"klause","kind":"satisfy","feasible":false,"proven":true,"budgetMs":5000,"validationPolicy":"reported-result-v1"}"""

        assertEquals("<span class=\"muted\">unknown</span>", verdict(outcome(undecided), Status.DONE))
        assertContains(verdict(outcome(reference), Status.DONE), ">unchecked</small>")
        assertEquals("infeasible", verdict(outcome(infeasible), Status.DONE))
    }

    @Test
    fun `arms under different validation policies are labelled and their comparison warns`() {
        val before = listOf(case("6991", "hrc", unchecked), case("6991", "rubiks", unchecked))
        val after = listOf(case("7004", "hrc", rejected), case("7004", "rubiks", accepted))

        val mixed = Compare.compare(listOf("6991", "7004"), before + after)
        val alike = Compare.compare(listOf("7004", "b"), after + after.map { it.copy(arm = "b") })

        assertEquals(listOf(0, 2), mixed.arms.map { it.validationMismatches })
        assertEquals(mapOf(WitnessCheck.UNCHECKED to 2), mixed.arms[0].witnesses)
        assertEquals(mapOf(WitnessCheck.INVALID to 1, WitnessCheck.ACCEPTED to 1), mixed.arms[1].witnesses)
        assertTrue(mixesValidation(mixed.arms))
        assertFalse(mixesValidation(alike.arms))

        val config = Config(dataDir = Files.createTempDirectory("lab"), auth = Auth.Disabled)
        val arms = listOf(PlannedArm(Arm("6991", emptyMap()), "abc"), PlannedArm(Arm("7004", emptyMap()), "def"))
        val page = comparePage(config, emptyList(), arms, before + after)

        assertContains(page, "These arms ran under different validation policies")
        assertContains(page, "<b>6991</b> <span class=\"muted\" title=\"2 unchecked Solver solutions")
        assertContains(page, "<b>7004</b> <span class=\"DONE\" title=\"1 checked, 1 invalid Solver solutions")
        assertFalse("different validation policies" in comparePage(config, emptyList(), arms.take(1), before))
    }

    @Test
    fun `a trend run says whether it asked for source validation`() {
        val spec = committed("status-sweep.json")
        val saved = spec.copy(bench = spec.bench!!.copy(sourceValidation = false))
        fun run(experiment: ExperimentSpec) = Trend.run(
            Job(1, "status-sweep@abc", "main", "abc", Status.DONE, cancelRequested = false, setupDone = true, createdAt = 0,
                experiment = experiment),
            listOf(PlannedArm(Arm("base", emptyMap()), "abc")),
            listOf(case("base", "rubiks", accepted)),
        )!!

        assertTrue(run(spec).sourceChecked)
        assertFalse(run(SourceValidation.scheduled(saved)).sourceChecked)
        assertFalse(run(saved).sourceChecked)
        assertContains(trendPage("status-sweep", listOf(run(saved), run(spec).copy(job = 2)), "https://github.com/x/y"),
            "runs marked unchecked report the solver's own claims")
    }
}
