package com.eignex.lab

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** A point estimate with its 95% interval. */
@Serializable
data class Estimate(val value: Double, val low: Double, val high: Double)

/** One arm's totals with intervals from resampling problems. */
@Serializable
data class ArmStats(
    val label: String,
    val problems: Int,
    /** Problems solved, a problem's runs (seeds, repeats) averaged. */
    val solved: Estimate,
    /** Mean PAR-2 seconds: the time to the best solution when solved, twice the budget when not. */
    val par2: Estimate,
    val score: Estimate,
)

/** An arm against the baseline arm, over the problems either of them solved. */
@Serializable
data class Paired(
    val label: String,
    val problems: Int,
    /** Geometric mean of this arm's PAR-2 time over the baseline's: below 1 is faster. */
    val timeRatio: Estimate,
    /** Two-sided Wilcoxon signed-rank p on the per-problem log time ratios. */
    val wilcoxonP: Double,
    val better: Int,
    val worse: Int,
    /** Two-sided sign test p on the better and worse problems. */
    val signP: Double,
)

/** A problem whose runs of one arm disagree: the verdict changed, or the time spread is wide. */
@Serializable
data class Noise(val problem: Problem, val arm: String, val runs: Int, val verdicts: Int, val spread: Double)

@Serializable
data class Statistics(val arms: List<ArmStats>, val paired: List<Paired>, val noise: List<Noise>)

/**
 * Intervals and tests over an experiment's cases. Problems are the unit: an arm's runs of one problem (its seeds and
 * repeats) are averaged first, so a problem counts once however often it ran, and the bootstrap resamples problems.
 * The paired figures compare each arm with the first on the problems both ran, matched run by run.
 */
object Stats {
    fun of(labels: List<String>, cases: List<CaseResult>, resamples: Int = RESAMPLES): Statistics {
        val outcomes = cases.mapNotNull { case -> Outcome.of(case.record)?.let { case to it } }
        val byProblem = outcomes.groupBy { it.first.problem }
        val problems = byProblem.keys.toList()
        val random = Random(SEED)
        val arms = labels.map { label ->
            val ran = problems.filter { p -> byProblem.getValue(p).any { it.first.arm == label } }
            val solved = ran.map { p -> runs(byProblem, p, label).map { if (it.rank > 0) 1.0 else 0.0 }.average() }
            val par2 = ran.map { p -> runs(byProblem, p, label).map { par2(it) / MS_PER_S }.average() }
            val score = ran.map { p -> points(byProblem.getValue(p), label) }
            val sample = resample(ran.size, random, resamples)
            ArmStats(label, ran.size, bootstrap(solved, sample, List<Double>::sum), bootstrap(par2, sample, List<Double>::average),
                bootstrap(score, sample, List<Double>::sum))
        }
        val paired = labels.drop(1).map { label -> paired(labels.first(), label, byProblem, random, resamples) }
        return Statistics(arms, paired, noise(labels, byProblem))
    }

    private fun paired(
        baseline: String,
        label: String,
        byProblem: Map<Problem, List<Pair<CaseResult, Outcome>>>,
        random: Random,
        resamples: Int,
    ): Paired {
        val logRatios = ArrayList<Double>()
        var better = 0
        var worse = 0
        for (entries in byProblem.values) {
            val mine = entries.filter { it.first.arm == label }.associate { key(it.first) to it.second }
            val theirs = entries.filter { it.first.arm == baseline }.associate { key(it.first) to it.second }
            val matched = mine.keys.intersect(theirs.keys)
            if (matched.isEmpty()) continue
            val gained = matched.sumOf { Compare.points(mine.getValue(it), theirs.getValue(it)) }
            val conceded = matched.sumOf { Compare.points(theirs.getValue(it), mine.getValue(it)) }
            if (gained > conceded) better++ else if (gained < conceded) worse++
            // Problems neither solved say nothing about speed; both sit at twice the budget.
            if (matched.none { mine.getValue(it).rank > 0 || theirs.getValue(it).rank > 0 }) continue
            logRatios += ln(matched.map { par2(mine.getValue(it)) }.average() / matched.map { par2(theirs.getValue(it)) }.average())
        }
        val sample = resample(logRatios.size, random, resamples)
        val logMean = bootstrap(logRatios, sample, List<Double>::average)
        return Paired(
            label, logRatios.size, Estimate(exp(logMean.value), exp(logMean.low), exp(logMean.high)),
            wilcoxon(logRatios), better, worse, signTest(better, worse),
        )
    }

    private fun noise(labels: List<String>, byProblem: Map<Problem, List<Pair<CaseResult, Outcome>>>): List<Noise> =
        byProblem.flatMap { (problem, entries) ->
            labels.mapNotNull { label ->
                val runs = entries.filter { it.first.arm == label }.map { it.second }
                if (runs.size < 2) return@mapNotNull null
                val verdicts = runs.map { it.rank to it.objective }.distinct().size
                val solvedTimes = runs.filter { it.rank > 0 }.map { it.timeMs.toDouble() }
                val spread = if (solvedTimes.size < 2) 0.0 else stdev(solvedTimes) / solvedTimes.average()
                Noise(problem, label, runs.size, verdicts, spread).takeIf { verdicts > 1 || spread > NOISY_SPREAD }
            }
        }.sortedWith(compareByDescending<Noise> { it.verdicts }.thenByDescending { it.spread })

    /** The two-sided p of the Wilcoxon signed-rank test that [differences] centre on zero, by the normal
     *  approximation with the tie correction; zeros are dropped. 1.0 when nothing differs. */
    fun wilcoxon(differences: List<Double>): Double {
        val nonzero = differences.filter { it != 0.0 }.sortedBy { abs(it) }
        val n = nonzero.size
        if (n == 0) return 1.0
        val ranks = DoubleArray(n)
        var tieTerm = 0.0
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && abs(nonzero[j + 1]) == abs(nonzero[i])) j++
            val rank = (i + j) / 2.0 + 1
            for (k in i..j) ranks[k] = rank
            val t = (j - i + 1).toDouble()
            tieTerm += t * t * t - t
            i = j + 1
        }
        val positive = nonzero.indices.filter { nonzero[it] > 0 }.sumOf { ranks[it] }
        val mean = n * (n + 1) / 4.0
        val sd = sqrt(n * (n + 1) * (2 * n + 1) / 24.0 - tieTerm / 48)
        if (sd == 0.0) return 1.0
        val z = (abs(positive - mean) - 0.5).coerceAtLeast(0.0) / sd
        return (2 * (1 - normalCdf(z))).coerceIn(0.0, 1.0)
    }

    /** The exact two-sided p of the sign test with [better] and [worse] outcomes. */
    fun signTest(better: Int, worse: Int): Double {
        val n = better + worse
        if (n == 0) return 1.0
        val k = minOf(better, worse)
        // In log space, so a few thousand problems do not overflow the binomial coefficients.
        val logTail = (0..k).map { logChoose(n, it) - n * ln(2.0) }
        val max = logTail.max()
        return (2 * exp(max) * logTail.sumOf { exp(it - max) }).coerceAtMost(1.0)
    }

    private fun bootstrap(values: List<Double>, samples: List<List<Int>>, statistic: (List<Double>) -> Double): Estimate {
        if (values.isEmpty()) return Estimate(0.0, 0.0, 0.0)
        val drawn = samples.map { draw -> statistic(draw.map { values[it] }) }.sorted()
        return Estimate(statistic(values), drawn[(drawn.size * LOW).toInt()], drawn[((drawn.size - 1) * HIGH).toInt()])
    }

    /** [count] indices drawn with replacement, [resamples] times. */
    private fun resample(count: Int, random: Random, resamples: Int): List<List<Int>> =
        if (count == 0) emptyList() else List(resamples) { List(count) { random.nextInt(count) } }

    private fun runs(byProblem: Map<Problem, List<Pair<CaseResult, Outcome>>>, problem: Problem, label: String) =
        byProblem.getValue(problem).filter { it.first.arm == label }.map { it.second }

    /** An arm's mean points on one problem against every other arm, run by matched run. */
    private fun points(entries: List<Pair<CaseResult, Outcome>>, label: String): Double {
        val mine = entries.filter { it.first.arm == label }
        return mine.sumOf { (case, outcome) ->
            entries.filter { it.first.arm != label && key(it.first) == key(case) }.sumOf { Compare.points(outcome, it.second) }
        } / mine.size
    }

    private fun key(case: CaseResult) = case.seed to case.repeat

    /** PAR-2 time, at least [MIN_TIME_MS]: a proof made in presolve reports 0 s, and a ratio of two of them, or of one
     *  and any time, is no measure of speed, only of the clock's resolution. */
    private fun par2(outcome: Outcome): Double =
        if (outcome.rank > 0) maxOf(outcome.timeMs.toDouble(), MIN_TIME_MS) else 2.0 * outcome.budgetMs

    private fun stdev(values: List<Double>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    }

    private fun logChoose(n: Int, k: Int): Double = lnFactorial(n) - lnFactorial(k) - lnFactorial(n - k)

    private fun lnFactorial(n: Int): Double = (2..n).sumOf { ln(it.toDouble()) }

    /** Φ(z), from the error function by Abramowitz and Stegun 7.1.26 (absolute error below 1.5e-7). */
    private fun normalCdf(z: Double): Double {
        val x = abs(z) / sqrt(2.0)
        val t = 1 / (1 + 0.3275911 * x)
        val erf = 1 - t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429)))) * exp(-x * x)
        return if (z >= 0) (1 + erf) / 2 else (1 - erf) / 2
    }

    private const val RESAMPLES = 1000
    private const val SEED = 1L
    private const val LOW = 0.025
    private const val HIGH = 0.975
    private const val MS_PER_S = 1000.0
    private const val MIN_TIME_MS = 10.0
    /** A coefficient of variation of time-to-best above this marks a problem as noisy. */
    private const val NOISY_SPREAD = 0.25
}
