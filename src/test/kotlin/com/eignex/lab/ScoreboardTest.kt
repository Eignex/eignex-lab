package com.eignex.lab

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ScoreboardTest {
    private val arm = Arm("klause", mapOf("timeout" to "60000"))

    private fun case(problem: String, record: String, collection: String = "c", format: String = "MINIZINC") = CaseResult(
        0, Status.DONE, Problem("s", problem, collection = collection, format = format, category = "OPTIMIZATION"), "klause",
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
            ("c" to "p1") to listOf(ref("cp-sat", 12.0, 500), ref("chuffed", 10.0, 500, proven = true)),
            ("c" to "p2") to listOf(ref("cp-sat", 15.0, 500, proven = true)),
        )

        val board = Scoreboard.board(cases, arm, references, emptyMap(), Dimension.FORMAT)
        val worst = Scoreboard.problems(cases, arm, references, emptyMap(), Dimension.FORMAT, null, "cp-sat")

        assertEquals(listOf("best", "chuffed", "cp-sat"), board.solvers)
        assertEquals(ScoreCell(1.0, 1.0, 2), board.total.cells["cp-sat"])
        assertEquals(listOf("minizinc", "xcsp3"), board.rows.map { it.group }.sorted())
        assertEquals(listOf("p2", "p1"), worst.map { it.problem.problem })
    }

    @Test
    fun `a filter keeps only the problems that take its value`() {
        val cases = listOf(case("p1", solved(10.0, 1_000)), case("p2", solved(20.0, 1_000), format = "XCSP3"))
        val references = mapOf(("c" to "p1") to listOf(ref("cp-sat", 12.0, 500)), ("c" to "p2") to listOf(ref("cp-sat", 15.0, 500)))

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
}
