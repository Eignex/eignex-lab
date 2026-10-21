package com.eignex.lab

import com.eignex.lab.checker.CaseChecks
import com.eignex.lab.checker.CheckCase
import com.eignex.lab.checker.FormatChecker
import com.eignex.lab.checker.LabCheck
import com.eignex.lab.checker.SmtChecker
import com.eignex.lab.checker.SourceChecker
import com.eignex.lab.checker.SourceLocator
import com.eignex.lab.checker.Verdict
import com.eignex.lab.checker.sha256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A failed checker never changes a Klause result: only a definite rejection (`invalid`) may. Every other check state,
 * the lab's or the bench's, leaves the solver's reported claim counting as it was reported, everywhere it is counted,
 * and only adds a mark.
 */
class CheckerInvariantTest {
    private val corpus = Files.createTempDirectory("corpus").toFile()
    private val work = Files.createTempDirectory("checks").toFile()
    private val source = File(corpus, "smt-test/QF_LIA/p.smt2").apply {
        parentFile.mkdirs()
        writeText("(set-logic QF_LIA)\n(declare-fun x () Int)\n(assert (> x 4))\n(check-sat)\n(exit)\n")
    }
    private val smt = Problem("smt-test", "QF_LIA/p", collection = "smt-test", format = "SMTLIB")

    /** An adapter that throws [error] whatever it is asked. */
    private class Throwing(private val error: () -> Throwable) : FormatChecker {
        override val format = "SMTLIB"
        override val policy = "lab-test-throwing-v1"
        override val checker = "throwing"
        override fun check(case: CheckCase, work: File): Verdict = throw error()
    }

    private fun dispatcher(vararg adapters: FormatChecker) = SourceChecker(adapters.toList(), SourceLocator(corpus, hash = ::sha256), work)

    /** A claim the solver reported: what every check state but a rejection must leave counting as it is. */
    private data class Claim(val name: String, val feasible: Boolean, val objective: Double?, val proven: Boolean) {
        val exact get() = objective?.toLong()?.toString()
        val reported get() = Reported(feasible, proven, objective, if (feasible) TIME_TO_BEST else null, if (proven || !feasible) 2 else 1)
    }

    /** The parts of an [Outcome] that decide how a result counts. */
    private data class Reported(val feasible: Boolean?, val proven: Boolean, val objective: Double?, val timeToBestMs: Long?, val rank: Int)

    private fun Outcome.reported() = Reported(feasible, proven, objective, timeToBestMs, rank)

    private val withdrawnClaim = Reported(null, false, null, null, 0)

    private val claims = listOf(
        Claim("optimum", feasible = true, objective = 81.0, proven = true),
        Claim("incumbent", feasible = true, objective = 81.0, proven = false),
        Claim("infeasible", feasible = false, objective = null, proven = true),
    )

    /**
     * How the bench left a record: the claim in place, or moved aside into `stats.reported*` (with the solver's time to
     * it in the producer record) when its own check did not accept it.
     */
    private enum class Bench(val validation: String?, val reason: String?, val policy: String, val withdrawn: Boolean) {
        NONE(null, null, SourceValidation.UNCHECKED_POLICY, false),
        VALID("valid", "source constraints evaluate true", "xcsp3-source-witness-v3", false),
        UNKNOWN_KEPT("unknown", "source checking cannot complete: constraint circuit is outside checker arithmetic auditing", "xcsp3-source-witness-v3", false),
        NO_RESULT(null, null, "xcsp3-source-witness-v3", false),
        // Job 7068's MPS records: a bench built without the checker, and ex9's input the checker could not read.
        FAULT_WITHDRAWN("unknown", "MPS source checking failed: missing packaged MIPLIB solchecker", "mps-miplib2017-point-gzip-v1", true),
        UNDECIDED_WITHDRAWN("unknown", "MPS source checking failed: lower bounds following upper bounds are unsupported", "mps-miplib2017-point-gzip-v1", true),
        INVALID_WITHDRAWN("invalid", "row c3 violated by 2", "mps-miplib2017-point-gzip-v1", true),
    }

    private fun record(claim: Claim, bench: Bench): String {
        val stats = buildList {
            add("\"solveTime\":\"9.5\"")
            bench.validation?.let { add("\"sourceValidation\":\"$it\"") }
            bench.reason?.let { add("\"sourceValidationReason\":\"$it\"") }
            if (bench.validation != null) {
                add("\"reportedFeasible\":\"${claim.feasible}\"")
                add("\"reportedObjective\":\"${claim.objective}\"")
                add("\"reportedExactObjective\":\"${claim.exact}\"")
                add("\"reportedProven\":\"${claim.proven}\"")
            }
            if (bench.withdrawn && claim.feasible) {
                add("\"sourceValidationProducerRecordJson\":\"{\\\"timeToBestMs\\\":$TIME_TO_BEST}\"")
            }
        }.joinToString(",")
        val shown = if (bench.withdrawn) Claim(claim.name, false, null, false) else claim
        val feasible = if (bench.withdrawn) "null" else "${claim.feasible}"
        return """{"problem":"${claim.name}","solver":"klause","kind":"optimize","maximize":false,"feasible":$feasible,
            "objective":${shown.objective},"exactObjective":${shown.exact?.let { "\"$it\"" }},"proven":${shown.proven},
            "timeToBestMs":${if (!bench.withdrawn && claim.feasible) TIME_TO_BEST else null},"budgetMs":10000,
            "command":"klause-cli","validationPolicy":"${bench.policy}","finalWitness":"(\n(define-fun x () Int 5)\n)",
            "sourceHashes":{"model":"${sha256(source)}"},"stats":{$stats}}"""
    }

    /** Each lab check state, by name: `none` for a case the lab has not checked, `exception` for an adapter that threw. */
    private val labStates: Map<String, String?> by lazy {
        fun check(state: String, reason: String) = LabCheck("lab-test-v1", state, reason, "test").json()
        val thrown = dispatcher(Throwing { IllegalStateException("boom") })
            .check(smt, Json.parseToJsonElement(record(claims[0], Bench.NONE)) as JsonObject)!!
        mapOf(
            "none" to null,
            LabCheck.VALID to check(LabCheck.VALID, "accepted"),
            LabCheck.INVALID to check(LabCheck.INVALID, "rejected: c3"),
            LabCheck.UNKNOWN to check(LabCheck.UNKNOWN, "could not decide: limit"),
            LabCheck.NOT_RUN to check(LabCheck.NOT_RUN, "did not finish within 60 s"),
            LabCheck.UNSUPPORTED to check(LabCheck.UNSUPPORTED, "lexicographic objectives are not managed"),
            "exception" to thrown.json(),
        )
    }

    private fun recordOf(claim: Claim, bench: Bench, lab: String): JsonElement? =
        withLabCheck(Json.parseToJsonElement(record(claim, bench)), labStates.getValue(lab))

    /** Whether the check state that is in force rejects the claim: the lab's verdict where there is one, else the bench's. */
    private fun rejected(bench: Bench, lab: String) = lab == LabCheck.INVALID || (lab == "none" && bench == Bench.INVALID_WITHDRAWN)

    private val states = Bench.entries.flatMap { bench -> labStates.keys.map { bench to it } }
    private val standing = states.filterNot { (bench, lab) -> rejected(bench, lab) }

    @Test
    fun `an adapter that throws is a check that did not run, with the reason`() {
        val check = LabCheck.parse(labStates.getValue("exception")!!)!!
        assertEquals(LabCheck.NOT_RUN, check.state)
        assertEquals("the checker could not run: boom", check.reason)
    }

    @Test
    fun `every check state but a rejection leaves the solver's claim counting as it reported it`() {
        for (claim in claims) for ((bench, lab) in states) {
            val outcome = Outcome.of(recordOf(claim, bench, lab))!!
            val expected = if (rejected(bench, lab)) withdrawnClaim else claim.reported
            assertEquals(expected, outcome.reported(), "${claim.name}, bench ${bench.name}, lab $lab")
        }
    }

    @Test
    fun `a check that did not reject only marks the claim, unchecked or indeterminate with its reason`() {
        val claim = claims[1]
        for ((bench, lab) in standing) {
            val outcome = Outcome.of(recordOf(claim, bench, lab))!!
            val expected = when (lab) {
                LabCheck.VALID -> WitnessCheck.ACCEPTED
                LabCheck.UNKNOWN -> WitnessCheck.INDETERMINATE
                LabCheck.NOT_RUN, LabCheck.UNSUPPORTED, "exception" -> WitnessCheck.UNCHECKED
                else -> when (bench) {
                    Bench.VALID -> WitnessCheck.ACCEPTED
                    Bench.NONE, Bench.FAULT_WITHDRAWN -> WitnessCheck.UNCHECKED
                    else -> WitnessCheck.INDETERMINATE
                }
            }
            val what = "bench ${bench.name}, lab $lab"
            assertEquals(expected, outcome.witness, what)
            val cell = verdict(outcome, Status.DONE)
            assertTrue(cell.startsWith("81 <small"), "$what: $cell")
            assertContains(cell, ">${expected.word}</small>", message = what)
            if (expected == WitnessCheck.INDETERMINATE) assertContains(cell, outcome.sourceValidationReason ?: "no result recorded", message = what)
            if (lab == "exception") assertContains(cell, "the checker could not run: boom", message = what)
        }
        // ex9 of job 7068: the bench's checker could not read the input; the solver's 81 counts, marked indeterminate.
        val ex9 = verdict(Outcome.of(recordOf(claim, Bench.UNDECIDED_WITHDRAWN, "none")), Status.DONE)
        assertContains(ex9, "lower bounds following upper bounds are unsupported; reported objective 81.0")
        for (lab in listOf("none", LabCheck.INVALID)) {
            val bench = if (lab == "none") Bench.INVALID_WITHDRAWN else Bench.NONE
            assertContains(verdict(Outcome.of(recordOf(claim, bench, lab)), Status.DONE), "invalid solution")
        }
    }

    private fun run(arm: String, state: Pair<Bench, String>) = claims.mapIndexed { i, claim ->
        CaseResult(i, Status.DONE, Problem("s", claim.name, collection = "c", format = "MPS", category = "OPTIMIZATION"), arm,
            record = recordOf(claim, state.first, state.second))
    }

    @Test
    fun `no confirmation flip comes from a check that did not reject, whatever the check of the run before`() {
        for (before in standing) for (after in standing) {
            val flips = Confirm.flips(run("a", before), run("b", after))
            assertEquals(emptyList(), flips.problems, "before $before, after $after")
        }
        // A rejection is the one check that flips: every claim lost.
        val rejected = Confirm.flips(run("a", Bench.NONE to "none"), run("b", Bench.NONE to LabCheck.INVALID))
        assertEquals(claims.map { it.name }.sorted(), rejected.lost.map { it.problem })
    }

    @Test
    fun `trend points, stats, comparisons and the scoreboard read the same under every check that did not reject`() {
        val job = Job(1, "status-sweep@abc", "main", "abc", Status.DONE, cancelRequested = false, setupDone = true, createdAt = 0,
            experiment = ExperimentSpec("status-sweep", listOf(mapOf("suite" to "s"))))
        val arms = listOf(PlannedArm(Arm("base", emptyMap()), "abc"))
        val references = claims.associate { ("c" to it.name) to Reference("highs", maximize = false, objective = it.objective, feasible = it.feasible, proven = it.proven,
            elapsedMs = 4000, budgetMs = 10000) }
        fun counted(state: Pair<Bench, String>): List<Any?> {
            val cases = run("base", state)
            val arm = Compare.compare(listOf("base"), cases).arms.single()
            return listOf(
                Trend.run(job, arms, cases, references),
                Stats.of(listOf("base"), cases).arms,
                listOf(arm.cases, arm.solved, arm.proven, arm.errors, arm.score),
                Scoreboard.board(cases, arms.single().arm, references.mapValues { listOf(it.value) }, emptyMap(), Dimension.FORMAT),
                References.compare(listOf("base"), cases, references),
            )
        }
        val baseline = counted(Bench.NONE to "none")
        for (state in standing) assertEquals(baseline, counted(state), "$state")
        assertTrue(baseline != counted(Bench.NONE to LabCheck.INVALID))
    }

    @Test
    fun `a float approximation the bench accepted keeps its credit when the lab's check fails`() {
        val grid = record(claims[1], Bench.VALID).replace("\"solveTime\":\"9.5\"", "\"solveTime\":\"9.5\",\"floatApproximation\":\"true\"")
        fun outcome(lab: String) = Outcome.of(withLabCheck(Json.parseToJsonElement(grid), labStates.getValue(lab)))!!
        for (lab in labStates.keys - LabCheck.INVALID) {
            assertEquals(Reported(true, false, 81.0, TIME_TO_BEST, 1), outcome(lab).reported(), lab)
            assertContains(verdict(outcome(lab), Status.DONE), "<small class=\"muted\">grid</small>", message = lab)
        }
        assertEquals(withdrawnClaim, outcome(LabCheck.INVALID).reported())
        // Unaccepted, a grid claim never counted, and a failed lab check does not change that either.
        val unaccepted = grid.replace("\"sourceValidation\":\"valid\"", "\"sourceValidation\":\"unknown\"")
        for ((lab, check) in labStates) if (lab != LabCheck.VALID) {
            assertEquals(withdrawnClaim, Outcome.of(withLabCheck(Json.parseToJsonElement(unaccepted), check))!!.reported(), lab)
        }
    }

    @Test
    fun `a reference row ignores any check, being the reference solver's own report`() {
        val reference = """{"solver":"highs","kind":"optimize","maximize":false,"feasible":true,"objective":81.0,"proven":true,
            "budgetMs":10000,"stats":{"solveTime":"1.0"}}"""
        val plain = References.of(Json.parseToJsonElement(reference))
        for ((_, check) in labStates) assertEquals(plain, References.of(withLabCheck(Json.parseToJsonElement(reference), check)!!))
    }

    private fun job(store: Store, record: String): Long {
        val id = store.create("j", "sha", emptyList(), experiment = ExperimentSpec("j", listOf(mapOf("suite" to "s"))))
        store.next()
        store.plan(id, listOf(PlannedArm(Arm("base", SourceValidation.SCHEDULED), "sha")), listOf(smt),
            Experiments.cases(1, 1, emptyList()), listOf("true" to 1L))
        store.commandStarted(id, 0)
        store.commandFinished(id, 0, 0)
        store.caseRecord(id, 0, record)
        return id
    }

    private fun labCheck(store: Store, id: Long): LabCheck? =
        ((store.cases(id).single().record as JsonObject)["labCheck"] as? JsonObject)?.let { LabCheck.parse(it.toString()) }

    @Test
    fun `a checker that throws, in the dispatcher or an adapter, is not-run and changes neither the case nor its result`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        val text = record(claims[1], Bench.NONE)
        val logged = mutableListOf<String>()
        val errors = listOf<() -> Throwable>({ RuntimeException("adapter crashed") }, { NoClassDefFoundError("org/missing/Tool") },
            { StackOverflowError("deep") })
        for (error in errors) {
            val id = job(store, text)
            val before = store.cases(id).single()
            val checks = CaseChecks(store, { dispatcher(Throwing(error)) }) { _, line -> logged += line }

            checks.afterCase(id, 0)
            checks.await(id)

            val after = store.cases(id).single()
            val check = labCheck(store, id)!!
            assertEquals(LabCheck.NOT_RUN, check.state)
            assertContains(check.reason, "the checker could not run: ${error().message}")
            assertEquals(before.status, after.status)
            assertEquals(text, store.caseRecordText(id, 0))
            assertEquals(Outcome.of(before.record)!!.reported(), Outcome.of(after.record)!!.reported())
            assertEquals(WitnessCheck.UNCHECKED, Outcome.of(after.record)!!.witness)
            assertEquals(mapOf(LabCheck.NOT_RUN to 1), checks.recheck(id) { false }.states)
        }
        // Checkers the lab cannot even set up leave the case unchecked, logged, and nothing thrown at the runner.
        val id = job(store, text)
        val broken = CaseChecks(store, { error("no checkers here") }) { _, line -> logged += line }
        broken.afterCase(id, 0)
        broken.await(id)
        assertEquals(null, labCheck(store, id))
        assertEquals(Status.DONE, store.cases(id).single().status)
        assertTrue(logged.any { "no checkers here" in it }, logged.joinToString("\n"))
    }

    @Test
    fun `a checker process that hangs past its time is killed and not-run, and the claim counts as reported`() {
        val store = Store(Files.createTempDirectory("lab").resolve("lab.db"))
        // A satisfiable claim, the one Dolmen checks.
        val text = """{"problem":"p","solver":"klause","kind":"satisfy","maximize":false,"feasible":true,"objective":null,
            "proven":false,"timeToBestMs":$TIME_TO_BEST,"budgetMs":10000,"command":"klause-cli","validationPolicy":"reported-result-v1",
            "finalWitness":"(\n(define-fun x () Int 5)\n)","sourceHashes":{"model":"${sha256(source)}"},"stats":{}}"""
        val id = job(store, text)
        val dolmen = Files.createTempFile("dolmen", "").toFile().apply { writeText("#!/bin/sh\nexec sleep 60\n"); setExecutable(true) }
        val checks = CaseChecks(store, { dispatcher(SmtChecker({ dolmen }, timeoutMs = 300)) }) { _, _ -> }
        val started = System.nanoTime()

        checks.afterCase(id, 0)
        checks.await(id)

        assertTrue((System.nanoTime() - started) / 1_000_000 < 20_000, "the hang was not cut short")
        val check = labCheck(store, id)!!
        assertEquals(LabCheck.NOT_RUN, check.state)
        assertContains(check.reason, "Dolmen did not finish")
        assertEquals(text, store.caseRecordText(id, 0))
        val outcome = Outcome.of(store.cases(id).single().record)!!
        assertEquals(Reported(true, false, null, TIME_TO_BEST, 2), outcome.reported())
        assertEquals(WitnessCheck.UNCHECKED, outcome.witness)
    }

    private companion object {
        const val TIME_TO_BEST = 5000L
    }
}
