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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatsTest {
    private fun case(arm: String, problem: Int, timeMs: Long?, seed: Long? = null, repeat: Int = 0): CaseResult {
        val record = if (timeMs == null) """{"kind":"satisfy","budgetMs":1000}"""
        else """{"kind":"satisfy","feasible":true,"timeToBestMs":$timeMs,"budgetMs":1000}"""
        return CaseResult(0, Status.DONE, Problem("s", "p$problem"), arm, seed, Json.parseToJsonElement(record), repeat)
    }

    @Test
    fun `ten positive differences give the textbook signed-rank p`() {
        val p = Stats.wilcoxon((1..10).map { it.toDouble() })

        assertTrue(abs(p - 0.0059) < 0.0005, "p=$p")
    }

    @Test
    fun `no difference at all gives p of one`() {
        assertEquals(1.0, Stats.wilcoxon(listOf(0.0, 0.0)))
    }

    @Test
    fun `nine better of ten gives the exact two-sided sign test p`() {
        assertTrue(abs(Stats.signTest(9, 1) - 22.0 / 1024) < 1e-9)
    }

    @Test
    fun `an arm twice as slow on every problem has a time ratio of two and a small p`() {
        val cases = (0 until 20).flatMap { listOf(case("a", it, 1000L + it), case("b", it, 2 * (1000L + it))) }

        val paired = Stats.of(listOf("a", "b"), cases).paired.single()

        assertEquals(listOf(2.0, 2.0, 2.0), listOf(paired.timeRatio.value, paired.timeRatio.low, paired.timeRatio.high).map { "%.3f".format(it).toDouble() })
        assertTrue(paired.wilcoxonP < 0.001 && paired.worse == 20)
    }

    @Test
    fun `a problem both arms decide in zero time keeps the time ratio finite`() {
        val cases = listOf(case("a", 0, 0), case("b", 0, 0), case("a", 1, 100), case("b", 1, 200))

        val ratio = Stats.of(listOf("a", "b"), cases).paired.single().timeRatio

        assertTrue(listOf(ratio.value, ratio.low, ratio.high).all { it.isFinite() }, "ratio=$ratio")
    }

    @Test
    fun `problems neither arm solved do not enter the time ratio`() {
        val cases = listOf(case("a", 0, 100), case("b", 0, 200), case("a", 1, null), case("b", 1, null))

        assertEquals(1, Stats.of(listOf("a", "b"), cases).paired.single().problems)
    }

    @Test
    fun `the solved interval brackets the count`() {
        val cases = (0 until 30).map { case("a", it, if (it % 3 == 0) null else 100L) }

        val solved = Stats.of(listOf("a"), cases).arms.single().solved

        assertTrue(solved.value == 20.0 && solved.low < 20.0 && solved.high > 20.0, "$solved")
    }

    @Test
    fun `repeats that disagree on the verdict are noisy`() {
        val cases = listOf(case("a", 0, 100, repeat = 0), case("a", 0, null, repeat = 1), case("a", 1, 100, repeat = 0), case("a", 1, 101, repeat = 1))

        assertEquals(listOf("p0"), Stats.of(listOf("a"), cases).noise.map { it.problem.problem })
    }
}
