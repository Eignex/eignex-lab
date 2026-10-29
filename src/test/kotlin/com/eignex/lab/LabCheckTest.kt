package com.eignex.lab

import com.eignex.lab.checker.CaseChecks
import com.eignex.lab.checker.LabCheck
import com.eignex.lab.checker.Policies
import com.eignex.lab.checker.SourceChecker
import com.eignex.lab.checker.SourceLocator
import com.eignex.lab.checker.Xcsp3Checker
import com.eignex.lab.checker.sha256
import io.ktor.serialization.kotlinx.json.DefaultJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How the lab's own verdict reads everywhere a case is shown or scored. */
class LabCheckTest {
    /** A Klause XCSP3 claim the bench reported as it is, under `reported-result-v1`. */
    private val claim = """{"problem":"VRP_LC-09-05-10-s3_c24","solver":"klause","kind":"optimize","maximize":false,
        "feasible":true,"objective":426.0,"exactObjective":"426","proven":false,"timeToBestMs":4000,"budgetMs":10000,
        "command":"klause-cli","validationPolicy":"reported-result-v1","stats":{"solveTime":"9.1"}}"""

    /** Job 7151's kind of record: the bench's own check could not decide on a `circuit`, and kept the claim. */
    private val undecided = """{"problem":"VRP_LC-09-05-10-s3_c24","solver":"klause","kind":"optimize","maximize":false,
        "feasible":true,"objective":426.0,"exactObjective":"426","proven":false,"timeToBestMs":4000,"budgetMs":10000,
        "command":"klause-cli","validationPolicy":"xcsp3-source-witness-v3","stats":{"solveTime":"9.1",
        "sourceValidation":"unknown","sourceValidationReason":"source checking cannot complete: constraint circuit is outside checker arithmetic auditing",
        "reportedFeasible":"true","reportedObjective":"426.0","reportedProven":"false"}}"""

    /** An MPS point the bench withdrew when its own check could not read the input. */
    private val withdrawn = """{"problem":"ex9","solver":"klause","kind":"optimize","maximize":false,"feasible":null,
        "objective":null,"timeToBestMs":null,"proven":false,"budgetMs":10000,"command":"klause-cli",
        "validationPolicy":"mps-miplib2017-point-gzip-v1","stats":{"solveTime":"9.5","sourceValidation":"unknown",
        "sourceValidationReason":"MPS source checking failed: lower bounds following upper bounds are unsupported",
        "reportedFeasible":"true","reportedObjective":"81.0","reportedExactObjective":"81","reportedProven":"false",
        "sourceValidationProducerRecordJson":"{\"timeToBestMs\":5000}"}}"""

    private fun check(state: String, reason: String = "because") =
        LabCheck(Policies.XCSP3, state, reason, "xcsp3-tools SolutionChecker", Xcsp3Checker.VERSION).json()

    private fun outcome(record: String, state: String?, reason: String = "because") =
        Outcome.of(withLabCheck(Json.parseToJsonElement(record), state?.let { check(it, reason) }))!!

    @Test
    fun `historical lab verdicts should have no retained checker inputs`() {
        val historical = """{"policy":"lab-smtlib-z3-algebraic-4.16.0-v2","state":"valid","reason":"checked","checker":"z3"}"""

        val parsed = LabCheck.parse(historical)!!

        assertTrue(parsed.inputs.isEmpty())
    }

    @Test
    fun `each lab verdict maps to its mark, credit and policy`() {
        val valid = outcome(claim, LabCheck.VALID)
        assertEquals(WitnessCheck.ACCEPTED, valid.witness)
        assertEquals(1, valid.rank)
        assertEquals(Policies.XCSP3, valid.validationPolicy)
        assertContains(verdict(valid, Status.DONE), ">checked</small>")
        assertContains(verdict(valid, Status.DONE), "policy ${Policies.XCSP3}")

        val invalid = outcome(claim, LabCheck.INVALID, "1 violated constraints: c_0 : circuit()")
        assertEquals(WitnessCheck.INVALID, invalid.witness)
        assertNull(invalid.feasible)
        assertEquals(0, invalid.rank)
        assertEquals("<span class=\"FAILED\" title=\"1 violated constraints: c_0 : circuit()\">invalid solution</span>", verdict(invalid, Status.DONE))

        val unknown = outcome(claim, LabCheck.UNKNOWN, "the XCSP3 checker could not decide: x")
        assertEquals(WitnessCheck.INDETERMINATE, unknown.witness)
        assertEquals(true, unknown.feasible)
        assertContains(verdict(unknown, Status.DONE), ">check indeterminate</small>")
        assertContains(verdict(unknown, Status.DONE), "reported objective 426")

        val notRun = outcome(claim, LabCheck.NOT_RUN, "the XCSP3 checker did not finish within 60 s")
        assertEquals(WitnessCheck.UNCHECKED, notRun.witness)
        assertEquals(listOf(true, 426.0), listOf(notRun.feasible, notRun.objective))
        assertContains(verdict(notRun, Status.DONE), "the source checker could not run: the XCSP3 checker did not finish within 60 s")
        assertContains(verdict(notRun, Status.DONE), ">unchecked</small>")

        val unsupported = outcome(claim, LabCheck.UNSUPPORTED, "lexicographic objectives are not managed")
        assertEquals(WitnessCheck.UNCHECKED, unsupported.witness)
        assertEquals(1, unsupported.rank)
        assertContains(verdict(unsupported, Status.DONE), "no external checker covers it: lexicographic objectives are not managed")

        // Without a lab verdict a record reads as it always has.
        assertEquals(WitnessCheck.UNCHECKED, outcome(claim, null).witness)
        assertEquals("reported-result-v1", outcome(claim, null).validationPolicy)
        assertEquals(WitnessCheck.INDETERMINATE, outcome(undecided, null).witness)
    }

    @Test
    fun `a lab verdict speaks for the solver's claim that a bench check withdrew`() {
        // The bench's check could not decide, so the claim stands without a lab verdict too, marked indeterminate.
        val undecidedByBench = outcome(withdrawn, null)
        assertEquals(listOf(true, 81.0, 5000L), listOf(undecidedByBench.feasible, undecidedByBench.objective, undecidedByBench.timeToBestMs))
        assertEquals(WitnessCheck.INDETERMINATE, undecidedByBench.witness)
        assertNull(outcome(withdrawn, LabCheck.INVALID).feasible)
        val accepted = outcome(withdrawn, LabCheck.VALID)
        assertEquals(listOf(true, 81.0, 5000L), listOf(accepted.feasible, accepted.objective, accepted.timeToBestMs))
        assertEquals(WitnessCheck.ACCEPTED, accepted.witness)
        assertEquals(WitnessCheck.ACCEPTED, outcome(undecided, LabCheck.VALID).witness)
    }

    @Test
    fun `the store shows a case's lab verdict as its record's labCheck until a new record replaces it`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val problems = listOf(Problem("xcsp3-cop", "VRP_LC-09-05-10-s3_c24", format = "XCSP3"))
        val id = store.create("j", "sha", emptyList(), experiment = ExperimentSpec("j", listOf(mapOf("suite" to "s"))))
        store.startForTest()
        store.plan(id, listOf(PlannedArm(Arm("base", emptyMap()), "sha")), problems, Experiments.cases(1, 1, emptyList()), listOf("true" to 1L))
        store.caseRecord(id, 0, claim)
        assertEquals(WitnessCheck.UNCHECKED, Outcome.of(store.scoringCases(id).single().record)!!.witness)

        store.caseCheck(id, 0, check(LabCheck.VALID))

        for (case in listOf(store.cases(id).single(), store.scoringCases(id).single())) {
            val record = case.record as JsonObject
            assertEquals(LabCheck.VALID, ((record["labCheck"] as JsonObject)["state"] as JsonPrimitive).content)
            assertEquals(Policies.XCSP3, (record["validationPolicy"] as JsonPrimitive).content)
            assertEquals("reported-result-v1", (record["benchValidationPolicy"] as JsonPrimitive).content)
            assertEquals(WitnessCheck.ACCEPTED, Outcome.of(record)!!.witness)
        }
        assertEquals(listOf(Policies.XCSP3), Compare.compare(listOf("base"), store.cases(id)).arms.single().validationPolicies)
        store.caseRecord(id, 0, claim)
        assertFalse("labCheck" in store.cases(id).single().record.toString())
    }

    @Test
    fun `a recheck job checks an ended job's XCSP3 claims with the official checker, solving nothing`() {
        val corpus = Files.createTempDirectory("corpus")
        val config = Config(dataDir = Files.createTempDirectory("lab"), corpusDir = corpus, auth = Auth.Disabled)
        val store = Store(config.dataDir.resolve("lab.db"))
        val model = File(corpus.toFile(), "xcsp3-test/COP/Circuit-4.xml").apply {
            parentFile.mkdirs()
            writeText("""<instance format="XCSP3" type="COP"><variables><array id="x" size="[4]"> 0..3 </array></variables>
                <constraints><circuit> x[] </circuit></constraints><objectives><minimize> x[0] </minimize></objectives></instance>""")
        }
        fun record(values: String) = """{"problem":"Circuit-4","solver":"klause","kind":"optimize","maximize":false,"feasible":true,
            "objective":1.0,"exactObjective":"1","proven":false,"budgetMs":1000,"command":"klause-cli","validationPolicy":"reported-result-v1",
            "sourceHashes":{"model":"${sha256(model)}"},"finalWitness":"v <instantiation> <list> x[] </list> <values> $values </values> </instantiation>",
            "stats":{}}"""
        val problems = listOf(Problem("xcsp3-test", "Circuit-4", collection = "xcsp3-test", format = "XCSP3"),
            Problem("other", "uf20", format = "DIMACS"))
        val target = store.create("vrp", "sha", emptyList(), experiment = ExperimentSpec("vrp", listOf(mapOf("suite" to "s"))))
        store.startForTest()
        store.plan(target, listOf(PlannedArm(Arm("a", emptyMap()), "sha"), PlannedArm(Arm("b", emptyMap()), "sha")), problems,
            Experiments.cases(2, 2, emptyList()), List(4) { "true" to 1L })
        val cases = store.casePlan(target)
        assertEquals("sha", store.caseSha(target, cases.first().index), "a case's checks read repository files at its arm's commit")
        for (case in cases) {
            store.commandStarted(target, case.index)
            store.commandFinished(target, case.index, 0)
            val ok = if (case.arm == "a") "1 2 3 0" else "1 0 3 2"
            store.caseRecord(target, case.index, if (case.problem.format == "XCSP3") record(ok) else claim)
        }
        store.finish(target, Status.DONE)
        val body = analysisDefaults(Json.parseToJsonElement("""{"recheck":{"job":$target}}""") as JsonObject)
        val spec = DefaultJson.decodeFromJsonElement(ExperimentSpec.serializer(), body)
        assertEquals(AnalyzeSpec(target, kind = AnalyzeSpec.CHECK), spec.analyze)
        assertEquals("recheck-$target", spec.name)
        Experiments.validate(spec, 4)
        val id = store.create(spec.name, "main", emptyList(), experiment = spec)
        val job = store.startForTest()!!
        val checker = SourceChecker(listOf(Xcsp3Checker()), SourceLocator(corpus.toFile()), config.dataDir.resolve("checkers/tmp").toFile())

        Runner(config, store, sourceChecker = { checker }).analyze(job)

        assertEquals(Status.DONE, store.job(id)!!.status)
        val witnesses = store.cases(target).associate { (it.arm to it.problem.format) to Outcome.of(it.record)!!.witness }
        assertEquals(WitnessCheck.ACCEPTED, witnesses["a" to "XCSP3"])
        assertEquals(WitnessCheck.INVALID, witnesses["b" to "XCSP3"])
        assertEquals(WitnessCheck.UNCHECKED, witnesses["a" to "DIMACS"])
        val result = config.jobDir(id).resolve(CaseChecks.RESULT).toFile().readText()
        assertContains(result, "\"valid\":1")
        assertContains(result, "\"invalid\":1")
        assertContains(config.jobDir(id).resolve("job.log").toFile().readText(), "2 of job $target's 2 cases")
        assertFalse(config.worktree(id).toFile().exists())
        assertEquals("job $target is running: check it again once it has ended",
            CaseChecks.refusal(store.job(target)!!.copy(status = Status.RUNNING)))
    }
}
