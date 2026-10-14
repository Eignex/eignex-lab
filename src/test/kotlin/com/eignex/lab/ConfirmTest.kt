package com.eignex.lab

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfirmTest {
    private fun case(problem: String, arm: String, solved: Boolean, repeat: Int = 0) = CaseResult(
        0, Status.DONE, Problem("s", problem), arm,
        record = Json.parseToJsonElement(
            if (solved) """{"kind":"satisfy","feasible":true,"timeToBestMs":5,"budgetMs":1000}""" else """{"kind":"satisfy","budgetMs":1000}""",
        ),
        repeat = repeat,
    )

    private fun optimization(value: String?, proven: Boolean = false, maximize: Boolean = false,
                             repeat: Int = 0, arm: String = Confirm.BEFORE, extra: String = "") = CaseResult(
        repeat, Status.DONE, Problem("s", "opt"), arm, seed = 3,
        record = Json.parseToJsonElement("""{"kind":"optimize","feasible":true,"proven":$proven,
            "maximize":$maximize,"objective":9007199254740992${value?.let { ",\"exactObjective\":\"$it\"" } ?: ""}$extra}"""),
        repeat = repeat,
    )

    @Test
    fun `still solved proof losses and gains are selected separately from feasibility`() {
        val before = listOf(optimization("7", proven = true))
        val after = listOf(optimization("7"))
        val loss = Confirm.flips(before, after)
        assertTrue(loss.lost.isEmpty())
        assertEquals(listOf("opt"), loss.proofLost.map { it.problem })
        assertEquals(loss.proofLost, Confirm.flips(after, before).proofGained)
        assertEquals(loss.confirmable, Confirm.spec("s", ExperimentSpec("s", emptyList()), "a", "b", loss, 1).problemList)
    }

    @Test
    fun `objectives compare exact values past doubles and rational values in each direction`() {
        for ((a, b) in listOf("9007199254740992" to "9007199254740993", "1/3" to "2/5")) {
            val loss = Confirm.flips(listOf(optimization(a)), listOf(optimization(b)))
            assertEquals(listOf("opt"), loss.objectiveLost.map { it.problem })
            assertTrue(loss.lost.isEmpty())
            assertEquals(loss.objectiveLost, Confirm.flips(listOf(optimization(a, maximize = true)),
                listOf(optimization(b, maximize = true))).objectiveGained)
        }
        assertTrue(Confirm.flips(listOf(optimization("2/6")), listOf(optimization("1/3"))).problems.isEmpty())
    }

    @Test
    fun `malformed exact values cannot fall back or establish optimal proof strength`() {
        val invalid = listOf(optimization("1/0", proven = true))
        val valid = listOf(optimization("7"))
        assertTrue(Confirm.flips(invalid, valid).problems.isEmpty())
    }

    @Test
    fun `contradicted proof and rejected witnesses cannot earn proof or objective transitions`() {
        assertTrue(Confirm.flips(listOf(optimization("7", proven = true)),
            listOf(optimization("6"))).problems.isEmpty())
        assertTrue(Confirm.flips(listOf(optimization("7", proven = true)),
            listOf(optimization("8", proven = true))).problems.isEmpty())
        for (stats in listOf("\"sourceValidation\":\"invalid\"", "\"floatApproximation\":true,\"sourceValidation\":\"valid\"")) {
            val rejected = listOf(optimization("7", proven = true, extra = ",\"stats\":{$stats}"))
            assertTrue(Confirm.flips(rejected, listOf(optimization("7"))).proofLost.isEmpty())
        }
    }

    @Test
    fun `quality confirmation requires a majority of complete matching blocks and ignores time`() {
        val before = (0..2).map { optimization("7", repeat = it) }
        val after = (0..2).map { optimization(if (it == 0) "8" else "7", repeat = it, arm = Confirm.AFTER) }
        assertTrue(Confirm.confirmed(before + after).objectiveLost.isEmpty())
        val held = (0..2).map { optimization("8", repeat = it, arm = Confirm.AFTER) }
        assertEquals(1, Confirm.confirmed(before + held).objectiveLost.size)
        assertTrue(Confirm.confirmed(before + held.take(1)).objectiveLost.isEmpty())
        assertTrue(Confirm.confirmed(before + held + held).objectiveLost.isEmpty())
        assertTrue(Confirm.flips(before, before.map { it.copy(record = Json.parseToJsonElement(
            """{"kind":"optimize","feasible":true,"exactObjective":"7","timeToBestMs":9000}""")) }).problems.isEmpty())
    }

    @Test
    fun `infeasibility retains its existing implicit proof meaning when the flag is absent`() {
        val claim = case("unsat", "x", false).copy(record = Json.parseToJsonElement(
            """{"kind":"satisfy","feasible":false}"""))
        val flagged = claim.copy(record = Json.parseToJsonElement(
            """{"kind":"satisfy","feasible":false,"proven":true}"""))
        assertTrue(Confirm.flips(listOf(claim), listOf(flagged)).problems.isEmpty())
        assertTrue(Confirm.flips(listOf(flagged), listOf(claim)).problems.isEmpty())
    }

    @Test
    fun `a satisfaction witness is not an optimization or infeasibility proof transition`() {
        val sat = case("sat", "x", true)
        val reportedProof = sat.copy(record = Json.parseToJsonElement(
            """{"kind":"satisfy","feasible":true,"proven":true}"""))
        assertTrue(Confirm.flips(listOf(reportedProof), listOf(sat)).proofLost.isEmpty())
    }

    @Test
    fun `legacy numeric objectives remain comparable while source mismatches cannot vote`() {
        fun record(value: String, hashes: String = "") = optimization(null).copy(record = Json.parseToJsonElement(
            """{"kind":"optimize","feasible":true,"objective":$value$hashes}"""))
        assertEquals(1, Confirm.flips(listOf(record("1.25")), listOf(record("1.5"))).objectiveLost.size)
        assertEquals(1, Confirm.flips(listOf(record("1", ",\"sourceHashes\":null")),
            listOf(record("2", ",\"sourceHashes\":{\"model\":\"b\"}"))).objectiveLost.size)
        assertEquals(1, Confirm.flips(listOf(record("1", ",\"sourceHashes\":{}")),
            listOf(record("2", ",\"sourceHashes\":{\"model\":\"b\"}"))).objectiveLost.size)
        assertTrue(Confirm.flips(listOf(record("1", ",\"sourceHashes\":{\"model\":\"a\"}")),
            listOf(record("2", ",\"sourceHashes\":{\"model\":\"b\"}"))).problems.isEmpty())
    }

    @Test
    fun `proof confirmations pair seeds and require majority rather than any proof`() {
        val before = (0..2).map { optimization("7", proven = true, repeat = it) }
        val after = (0..2).map { optimization("7", proven = it != 0, repeat = it, arm = Confirm.AFTER) }
        assertTrue(Confirm.confirmed(before + after).proofLost.isEmpty())
        assertTrue(Confirm.flips(before, after.map { it.copy(seed = 5) }).proofLost.isEmpty())
        assertEquals(1, Confirm.confirmed(before + after.map { it.copy(record = optimization("7").record) }).proofLost.size)
    }

    @Test
    fun `infeasibility contradictions never earn proof credit and feasibility flips retain their old rule`() {
        val noSolution = optimization(null, proven = true).copy(record = Json.parseToJsonElement(
            """{"kind":"optimize","feasible":false,"proven":true}"""))
        val solution = optimization("7")
        assertTrue(Confirm.flips(listOf(noSolution), listOf(solution)).problems.isEmpty())
        assertEquals(1, Confirm.flips(listOf(noSolution), listOf(case("opt", "x", false))).lost.size)
    }

    @Test
    fun `flips are the problems both runs ran that one solved and the other did not`() {
        val before = listOf(case("a", "x", true), case("b", "x", false), case("c", "x", true), case("only-before", "x", true))
        val after = listOf(case("a", "x", false), case("b", "x", true), case("c", "x", true))

        val flips = Confirm.flips(before, after)

        assertEquals(listOf(listOf("a"), listOf("b")), listOf(flips.lost.map { it.problem }, flips.gained.map { it.problem }))
    }

    @Test
    fun `a confirmation counts a flip only when the repeats agree with it`() {
        val held = (0..2).flatMap { listOf(case("held", Confirm.BEFORE, true, it), case("held", Confirm.AFTER, false, it)) }
        val noise = (0..2).flatMap { listOf(case("noise", Confirm.BEFORE, true, it), case("noise", Confirm.AFTER, it != 0, it)) }

        val confirmed = Confirm.confirmed(held + noise)

        assertEquals(listOf("held"), confirmed.lost.map { it.problem })
        assertTrue(confirmed.gained.isEmpty())
    }

    @Test
    fun `the sign test is two-sided and certain of nothing for an even split`() {
        assertEquals(1.0, Confirm.signTest(3, 3))
        assertEquals(2.0 / 32768, Confirm.signTest(15, 0), 1e-12)
    }

    @Test
    fun `a confirmation runs the flips on both commits, three times each`() {
        val sweep = ExperimentSpec("status-sweep@bbb", listOf(mapOf("set" to "sweep")), base = mapOf("timeout" to "10000", "ref" to "bbb"), priority = -1)
        val flips = Flips(lost = listOf(Problem("s", "a")), gained = listOf(Problem("s", "b")))

        val spec = Confirm.spec("status-sweep", sweep, "aaa111111111", "bbb222222222", flips, job = 7)
        val arms = Experiments.arms(spec)

        assertEquals("status-sweep~confirm@bbb222222-7", spec.name)
        assertEquals(listOf("before" to "aaa111111111", "after" to "bbb222222222"), arms.map { it.label to it.ref })
        assertEquals(listOf(3, 2, 0), listOf(spec.repeats, spec.problemList.size, spec.priority))
        assertEquals(10_000L, arms.first().timeoutMs)
        Experiments.validate(spec, maxParallel = 6)
    }

    @Test
    fun `an objective that moved without a proof is not rerun, a lost solution or proof is`() {
        val worse = Confirm.flips(listOf(optimization("3")), listOf(optimization("5")))

        assertTrue(worse.objectiveLost.isNotEmpty() || worse.objectiveGained.isNotEmpty())
        assertTrue(worse.confirmable.isEmpty())
        assertTrue(Confirm.spec("s", ExperimentSpec("s", emptyList()), "a", "b", worse, 1).problemList.isEmpty())
        val proofLost = Confirm.flips(listOf(optimization("3", proven = true)), listOf(optimization("3")))
        assertEquals(proofLost.proofLost, proofLost.confirmable)
    }

    @Test
    fun `a confirmation runs one priority above its schedule`() {
        val flips = Confirm.flips(listOf(optimization("3", proven = true)), listOf(optimization("3")))
        assertEquals(-1, Confirm.spec("s", ExperimentSpec("s", emptyList(), priority = -2), "a", "b", flips, 1).priority)
    }
}
