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
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScoreboardTest {
    private val arm = Arm("candidate", mapOf("timeout" to "60000"))

    private fun case(problem: String, record: String, collection: String = "c", format: String = "MINIZINC") = CaseResult(
        0, Status.DONE, Problem("s", problem, collection = collection, format = format, category = "OPTIMIZATION"), "candidate",
        record = Json.parseToJsonElement(record),
    )

    private fun solved(objective: Double, timeMs: Long, proven: Boolean = false) =
        """{"kind":"optimize","maximize":false,"feasible":true,"objective":$objective,"proven":$proven,"timeToBestMs":$timeMs,"budgetMs":60000}"""

    private fun ref(solver: String, objective: Double?, elapsedMs: Long, budgetMs: Long = 60_000, proven: Boolean = false) =
        Reference(solver, maximize = false, objective = objective, feasible = objective?.let { true }, proven = proven,
            elapsedMs = elapsedMs, budgetMs = budgetMs)

    @Test
    fun `an equal result is split by time only when the run had the reference's budget on one core`() {
        val mine = Outcome.of(Json.parseToJsonElement(solved(10.0, 1_000)))!!
        val theirs = mine.copy(timeToBestMs = 3_000)

        assertEquals(listOf(0.75, 0.5), listOf(Scoreboard.points(mine, theirs, timed = true), Scoreboard.points(mine, theirs, timed = false)))
    }

    @Test
    fun `objectives within the reference tolerance are equal, a better one takes the point`() {
        val mine = Outcome.of(Json.parseToJsonElement(solved(1480.0, 1_000)))!!

        assertEquals(0.5, Scoreboard.points(mine, mine.copy(objective = 1479.99999), timed = false))
        assertEquals(0.0, Scoreboard.points(mine, mine.copy(objective = 1400.0), timed = false))
    }

    @Test
    fun `a board scores each reference solver and the best of them per group, and a cell lists its problems worst first`() {
        val cases = listOf(case("p1", solved(10.0, 1_000)), case("p2", solved(20.0, 1_000), format = "XCSP3"))
        val references = mapOf(
            ProblemKey("c", "p1") to listOf(ref("cp-sat", 12.0, 500), ref("chuffed", 10.0, 500, proven = true)),
            ProblemKey("c", "p2") to listOf(ref("cp-sat", 15.0, 500, proven = true)),
        )

        val board = Scoreboard.board(cases, arm, references, emptyMap(), Dimension.FORMAT)
        val worst = Scoreboard.problems(cases, arm, references, emptyMap(), Dimension.FORMAT, null, "cp-sat")

        assertEquals(listOf("best", "chuffed", "cp-sat"), board.solvers)
        assertEquals(ScoreCell(1.0, 1.0, 2, 60_000, mapOf(60_000L to 2)), board.total.cells["cp-sat"])
        assertEquals(listOf("minizinc", "xcsp3"), board.rows.map { it.group }.sorted())
        assertEquals(listOf("p2", "p1"), worst.map { it.problem.problem })
    }

    @Test
    fun `a board scores only the reference rows on the source files the run used`() {
        val record = solved(30.0, 1_000).dropLast(1) + ""","sourceHashes":{"model":"bacp-24"}}"""
        val identity = References.identity(Json.parseToJsonElement(record))
        val cases = listOf(case("p1", record), case("p2", solved(20.0, 1_000)))
        val references = mapOf(
            ProblemKey("c", "p1") to listOf(ref("chuffed", 38.0, 500, proven = true).copy(sourceIdentity = "bacp-9"),
                ref("cp-sat", 30.0, 1_000).copy(version = "cp-sat-fixture", sourceIdentity = identity)),
            ProblemKey("c", "p2") to listOf(ref("cp-sat", 15.0, 500).copy(sourceIdentity = identity)),
        )

        val board = Scoreboard.board(cases, arm, references, emptyMap(), Dimension.FORMAT)

        assertEquals(listOf("best", "cp-sat"), board.solvers)
        // p1 on its own files' row only; p2's run recorded no files, so its bound row still counts.
        assertEquals(ScoreCell(0.5, 1.5, 2, 60_000, mapOf(60_000L to 2)), board.total.cells["cp-sat"])
    }

    @Test
    fun `a filter keeps only the problems that take its value`() {
        val cases = listOf(case("p1", solved(10.0, 1_000)), case("p2", solved(20.0, 1_000), format = "XCSP3"))
        val references = mapOf(ProblemKey("c", "p1") to listOf(ref("cp-sat", 12.0, 500)), ProblemKey("c", "p2") to listOf(ref("cp-sat", 15.0, 500)))

        val board = Scoreboard.board(cases, arm, references, emptyMap(), Dimension.KIND, mapOf(Dimension.FORMAT to "xcsp3"))

        assertEquals(1, board.total.problems)
    }

    @Test
    fun `local-search scoring ignores proofs and splits an equal result evenly, whatever the times`() {
        val proven = Outcome.of(Json.parseToJsonElement(solved(10.0, 1_000, proven = true)))!!
        val unproven = proven.copy(proven = false, timeToBestMs = 5_000)

        assertEquals(listOf(1.0, 0.5), listOf(
            Scoreboard.points(proven, unproven, timed = true, Scoring.COMPLETE),
            Scoreboard.points(proven, unproven, timed = true, Scoring.INCOMPLETE),
        ))
    }

    @Test
    fun `problems group by the class and traits of candidate's own profile of the model`() {
        val record = """{"kind":"optimize","maximize":false,"feasible":true,"objective":1,"proven":false,"timeToBestMs":5,"budgetMs":60000,""" +
            """"stats":{"profile":"FiniteCp optimizing=true wide=false scheduling=true"}}"""
        val cases = listOf(case("p1", record), case("p2", solved(2.0, 5)))
        val references = mapOf(ProblemKey("c", "p1") to listOf(ref("cp-sat", 1.0, 5)), ProblemKey("c", "p2") to listOf(ref("cp-sat", 2.0, 5)))

        val byClass = Scoreboard.board(cases, arm, references, emptyMap(), Dimension.CLASS).rows.map { it.group }.sorted()
        val byTrait = Scoreboard.board(cases, arm, references, emptyMap(), Dimension.TRAIT).rows.map { it.group }.sorted()

        assertEquals(listOf("finite-domain CP", "unknown class"), byClass)
        assertEquals(listOf("scheduling", "unknown"), byTrait)
    }

    @Test
    fun `a run candidate did not profile takes its class from the logic, then the pipeline, then a failed load`() {
        fun record(stats: String) = """{"kind":"satisfy","maximize":false,"feasible":true,"objective":null,"proven":true,"timeToBestMs":5,"budgetMs":60000,"stats":{$stats}}"""
        val cases = listOf(
            case("nra", record(""""solvePipeline":"refuted"""")),
            case("poly", record(""""solvePipeline":"polynomial-theory"""")),
            case("bad", record(""""loadError":"no such file"""")),
        )
        val features = mapOf(ProblemKey("c", "nra") to ProblemFeatures("c", "nra", logic = "QF_NRA"))

        val profiles = Scoreboard.profiles(cases, features).mapKeys { it.key.problem }

        assertEquals(mapOf("nra" to "Nonlinear", "bad" to "did not load"), profiles.mapValues { it.value.problemClass })
        assertEquals(listOf("unknown"), Scoreboard.values(Dimension.TRAIT, cases[0].problem, null, emptyList(), profiles["nra"]))
    }

    /** A 10 s run against cp-sat verdicts, three of them reached at 60 s and two at 10 s. */
    private val shortArm = Arm("candidate", mapOf("timeout" to "10000"))
    private val mixedCases = listOf(
        case("p1", solved(10.0, 1_000)), case("p2", solved(20.0, 2_000)), case("p3", solved(30.0, 4_000, proven = true)),
        case("p4", solved(40.0, 1_000)), case("p5", solved(50.0, 1_000)),
    ).map { it.copy(record = Json.parseToJsonElement(it.record.toString().replace("60000", "10000"))) }
    private val mixedReferences = mapOf(
        ProblemKey("c", "p1") to listOf(ref("cp-sat", 10.0, 30_000, proven = true)),
        ProblemKey("c", "p2") to listOf(ref("cp-sat", 20.0, 6_000, budgetMs = 10_000)),
        ProblemKey("c", "p3") to listOf(ref("cp-sat", 30.0, 1_000, budgetMs = 10_000, proven = true)),
        ProblemKey("c", "p4") to listOf(ref("cp-sat", 40.0, 9_000)),
        ProblemKey("c", "p5") to listOf(ref("cp-sat", 45.0, 50_000)),
    )

    @Test
    fun `mixed budgets keep their quality scores and say which budgets each cell and problem compared`() {
        val complete = Scoreboard.board(mixedCases, shortArm, mixedReferences, emptyMap(), Dimension.FORMAT)
        val incomplete = Scoreboard.board(mixedCases, shortArm, mixedReferences, emptyMap(), Dimension.FORMAT, scoring = Scoring.INCOMPLETE)
        val problems = Scoreboard.problems(mixedCases, shortArm, mixedReferences, emptyMap(), Dimension.FORMAT, null, "cp-sat")

        // The points as scored before the budgets were shown: only p2, equal at equal budgets, is split by time.
        assertEquals(ScoreCell(1.45, 3.55, 5, 10_000, mapOf(10_000L to 2, 60_000L to 3)), complete.total.cells["cp-sat"])
        assertEquals(ScoreCell(2.0, 3.0, 5, 10_000, mapOf(10_000L to 2, 60_000L to 3)), incomplete.total.cells["cp-sat"])
        assertEquals(
            listOf("p1" to (0.0 to 1.0), "p5" to (0.0 to 1.0), "p3" to (0.2 to 0.8), "p4" to (0.5 to 0.5), "p2" to (0.75 to 0.25)),
            problems.map { it.problem.problem to (it.candidate to it.reference) },
        )
        assertTrue(complete.total.cells.getValue("cp-sat").budgetsDiffer)
        assertEquals("budgets differ: 10 s vs 60 s", problems.first { it.problem.problem == "p1" }.untimed)
        assertEquals(listOf(true, null), problems.first { it.problem.problem == "p2" }.let { listOf(it.timed, it.untimed) })
    }

    @Test
    fun `the page shows both budgets in a cell and its problems, and marks a cell quality-only only when they differ`() {
        val job = Job(7388, "status-sweep@abc", "main", status = Status.DONE, cancelRequested = false, setupDone = true, createdAt = 0, experiment = ExperimentSpec("status-sweep"))
        fun page(arm: Arm, references: Map<ProblemKey, List<Reference>>): String {
            val board = Scoreboard.board(mixedCases, arm, references, emptyMap(), Dimension.FORMAT)
            val problems = Scoreboard.problems(mixedCases, arm, references, emptyMap(), Dimension.FORMAT, "all", "cp-sat")
            val view = ScoreboardView(listOf(job), job, listOf("candidate"), "candidate", Dimension.FORMAT, emptyMap(), Scoring.COMPLETE,
                emptyMap(), board, "all" to "cp-sat", problems)
            return referencesPage(emptyList(), ReferenceFilter(), emptyList(), 0, scoreboard = view).markup
        }
        val equal = mixedReferences.mapValues { (_, rows) -> rows.map { it.copy(budgetMs = 10_000) } }

        val mixed = page(shortArm, mixedReferences)
        val same = page(shortArm, equal)

        assertContains(mixed, "10 s vs 10–60 s")
        assertContains(mixed, "quality only, budgets differ</small>")
        assertContains(mixed, "reference had a longer budget")
        assertContains(mixed, "<td class=\"num\">10 s vs 60 s</td><td class=\"PARTIAL\">no, budgets differ: 10 s vs 60 s</td>")
        assertContains(same, "10 s vs 10 s</small>")
        assertFalse(same.contains("quality only, budgets differ</small>"))
        assertContains(same, "<td class=\"num\">10 s vs 10 s</td><td class=\"muted\">yes</td>")
        assertContains(same, "lab a/b experiment")
    }
}
