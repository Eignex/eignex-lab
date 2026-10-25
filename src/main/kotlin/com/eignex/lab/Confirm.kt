package com.eignex.lab

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigInteger
import java.math.BigDecimal

/** One sweep run against the run before it of the same experiment, problem by problem. */
@Serializable
data class Flips(
    /** Problems the earlier run solved and this one did not. */
    val lost: List<Problem>,
    /** Problems this run solved and the earlier one did not. */
    val gained: List<Problem>,
    val proofLost: List<Problem> = emptyList(),
    val proofGained: List<Problem> = emptyList(),
    val objectiveLost: List<Problem> = emptyList(),
    val objectiveGained: List<Problem> = emptyList(),
) {
    val problems: List<Problem> get() = (lost + gained + proofLost + proofGained + objectiveLost + objectiveGained).distinctBy { it.suite to it.problem }

    /**
     * The flips a confirmation reruns: a solution or a proof gained or lost. An unproven objective that moved is left
     * out: at a sweep's budget it moves with the machine's timing, both ways about equally, run after run, and
     * rerunning dozens of them each sweep took as long as the sweep while confirming nothing.
     */
    val confirmable: List<Problem> get() = (lost + gained + proofLost + proofGained).distinctBy { it.suite to it.problem }

    /** Two-sided sign test on the flips: how likely so lopsided a split is if each flip goes either way at random. */
    val pValue: Double get() = Confirm.signTest(lost.size, gained.size)
}

/**
 * Confirming a sweep's flips. A problem near its budget can flip between two runs on machine noise alone, so a
 * scheduled run that flips problems against the run before it is followed by a confirmation job: just those problems,
 * on both commits, alternating case by case so both see the same machine, [REPEATS] times each. A flip counts as a
 * regression or an improvement only when the repeats agree with it.
 */
object Confirm {
    /** Appended to a schedule's name for its confirmation jobs, which keeps them out of the schedule's trend. */
    const val SUFFIX = "~confirm"
    const val REPEATS = 3
    /** The maximum number of distinct problems one confirmation reruns. Unselected problems stay unconfirmed. */
    const val MAX_PROBLEMS = 40
    const val BEFORE = "before"
    const val AFTER = "after"

    /** Each problem's solved share over its runs in [cases], by its suite and name. */
    fun solved(cases: List<CaseResult>): Map<Pair<String, String>, Double> =
        cases.mapNotNull { case -> Outcome.of(case.record)?.let { case.problem to it } }
            .groupBy({ it.first.suite to it.first.problem }, { if (it.second.rank > 0) 1.0 else 0.0 })
            .mapValues { (_, runs) -> runs.average() }

    /** Feasibility keeps its historical majority rule; proof and quality use matched seed/repeat blocks.
     * Missing/invalid blocks cannot vote, but remain in the denominator. Time never selects a transition.
     */
    fun flips(before: List<CaseResult>, after: List<CaseResult>): Flips {
        val was = solved(before)
        val now = solved(after)
        val problems = (before + after).associateBy { it.problem.suite to it.problem.problem }
        val both = was.keys.intersect(now.keys).sortedWith(compareBy({ it.first }, { it.second }))
        val lost = both.filter { was.getValue(it) >= HALF && now.getValue(it) < HALF }
        val gained = both.filter { was.getValue(it) < HALF && now.getValue(it) >= HALF }
        val proofLost = mutableListOf<Problem>()
        val proofGained = mutableListOf<Problem>()
        val objectiveLost = mutableListOf<Problem>()
        val objectiveGained = mutableListOf<Problem>()
        for (key in both - lost.toSet() - gained.toSet()) {
            val left = before.filter { (it.problem.suite to it.problem.problem) == key }
            val right = after.filter { (it.problem.suite to it.problem.problem) == key }
            val all = left + right
            // A contradicted proof is evidence of disagreement, never evidence of an improvement/loss.
            if (contradictory(all)) continue
            fun blocks(cases: List<CaseResult>) = cases.groupBy { it.seed to it.repeat }
            val a = blocks(left)
            val b = blocks(right)
            val keys = a.keys + b.keys
            var pl = 0; var pg = 0; var ol = 0; var og = 0
            for (block in keys) {
                val ca = a[block]?.singleOrNull() ?: continue
                val cb = b[block]?.singleOrNull() ?: continue
                if (ca.status != Status.DONE || cb.status != Status.DONE) continue
                val hashesA = (ca.record as? JsonObject)?.get("sourceHashes")?.takeUnless { it == JsonNull || (it is JsonObject && it.isEmpty()) }
                val hashesB = (cb.record as? JsonObject)?.get("sourceHashes")?.takeUnless { it == JsonNull || (it is JsonObject && it.isEmpty()) }
                if (hashesA != null && hashesB != null && hashesA != hashesB) continue
                val x = Outcome.of(ca.record) ?: continue
                val y = Outcome.of(cb.record) ?: continue
                if (x.rank == 0 || y.rank == 0 || x.feasible != y.feasible ||
                    x.optimize != y.optimize || x.maximize != y.maximize) continue
                val ox = objective(ca); val oy = objective(cb)
                fun proof(o: Outcome, value: Exact?) = o.feasible == false ||
                    (o.optimize && o.proven && o.feasible == true && value != null)
                val px = proof(x, ox); val py = proof(y, oy)
                if (px && !py) pl++
                if (!px && py) pg++
                if (x.optimize && x.feasible == true && ox != null && oy != null) {
                    val cmp = oy.compareTo(ox) * if (x.maximize) -1 else 1
                    if (cmp > 0) ol++
                    if (cmp < 0) og++
                }
            }
            val problem = problems.getValue(key).problem
            if (pl > keys.size / 2) proofLost += problem
            if (pg > keys.size / 2) proofGained += problem
            if (ol > keys.size / 2) objectiveLost += problem
            if (og > keys.size / 2) objectiveGained += problem
        }
        return Flips(lost.map { problems.getValue(it).problem }, gained.map { problems.getValue(it).problem },
            proofLost, proofGained, objectiveLost, objectiveGained)
    }

    private fun contradictory(cases: List<CaseResult>): Boolean {
        val valid = cases.mapNotNull { c -> Outcome.of(c.record)?.takeIf { it.rank > 0 }?.let { Triple(c, it, objective(c)) } }
        if (valid.any { it.second.feasible == false } && valid.any { it.second.feasible == true }) return true
        if (valid.map { it.second.optimize to it.second.maximize }.distinct().size > 1) return true
        val optima = valid.filter { it.second.optimize && it.second.proven && it.third != null }
        return optima.any { (_, proof, value) -> valid.any { (_, o, v) ->
            v != null && (if (proof.maximize) v > value!! else v < value!!) ||
                (o.proven && v != null && v.compareTo(value!!) != 0)
        } }
    }

    /**
     * Exact text is authoritative: malformed exact text cannot fall back to a rounded display value. A claim a bench
     * check moved aside ([Outcome.withdrawn]) is read from the solver's own report, as [Outcome.of] reads it, so a
     * check that did not reject it changes no objective here either.
     */
    private fun objective(case: CaseResult): Exact? {
        val record = case.record as? JsonObject ?: return null
        val reported = Outcome.withdrawn(record)
        val stats = record["stats"] as? JsonObject
        val rawExact = if (reported) stats?.get("reportedExactObjective") else record["exactObjective"]
        if (rawExact != null && rawExact !is JsonPrimitive) return null
        val exact = rawExact as? JsonPrimitive
        val text = exact?.content?.takeUnless { it == "null" }
        if (text != null) return Exact.parse(text)
        val value = ((if (reported) stats?.get("reportedObjective") else record["objective"]) as? JsonPrimitive)
            ?.content?.takeUnless { it == "null" } ?: return null
        return runCatching {
            val decimal = BigDecimal(value)
            val scale = decimal.scale()
            if (scale >= 0) Exact(decimal.unscaledValue(), BigInteger.TEN.pow(scale))
            else Exact(decimal.unscaledValue() * BigInteger.TEN.pow(-scale), BigInteger.ONE)
        }.getOrNull()
    }

    private data class Exact(val numerator: BigInteger, val denominator: BigInteger) : Comparable<Exact> {
        override fun compareTo(other: Exact) = (numerator * other.denominator).compareTo(other.numerator * denominator)
        companion object {
            fun parse(text: String): Exact? = runCatching {
                require(text.matches(Regex("[+-]?[0-9]+(/[0-9]+)?")))
                val parts = text.split('/')
                val d = if (parts.size == 2) BigInteger(parts[1]) else BigInteger.ONE
                require(d.signum() > 0)
                Exact(BigInteger(parts[0]), d)
            }.getOrNull()
        }
    }

    /** The name of the confirmation of run [job] of [series] at [after]: the run's id keeps two runs of one commit apart. */
    fun name(series: String, after: String, job: Long) = "$series$SUFFIX@${after.take(SHA_LENGTH)}-$job"

    /** The confirmation of [flips] in run [job] of [spec] at [after], against the earlier run at [before]. Both
     *  commits ask for source validation, as every scheduled run does ([SourceValidation.scheduled]). */
    fun spec(series: String, spec: ExperimentSpec, before: String, after: String, flips: Flips, job: Long): ExperimentSpec {
        val config = spec.configs.firstOrNull().orEmpty() - "ref" - "label"
        return SourceValidation.scheduled(ExperimentSpec(
            name = name(series, after, job),
            description = "Confirms run $job's flips against the run before it at ${before.take(SHA_LENGTH)}: " +
                "${flips.lost.size} feasibility lost and ${flips.gained.size} gained; " +
                "${flips.proofLost.size} proof lost and ${flips.proofGained.size} gained; " +
                "rerun $REPEATS times on both commits (${flips.objectiveLost.size} objective worse and " +
                "${flips.objectiveGained.size} better are not rerun).",
            problems = emptyList(),
            problemList = flips.confirmable.take(MAX_PROBLEMS),
            base = spec.base - "ref",
            configs = listOf(config + mapOf("label" to BEFORE, "ref" to before), config + mapOf("label" to AFTER, "ref" to after)),
            seeds = spec.seeds,
            repeats = REPEATS,
            parallel = spec.parallel,
            measurement = if (spec.measurement == Measurement.RESOURCES) Measurement.RESOURCES else Measurement.NONE,
            // One above its run's schedule, so it settles the run before that schedule's next run starts.
            priority = spec.priority + 1,
            confirm = true,
        ))
    }

    /** What a finished confirmation found: the flips its repeats agree with, each way. */
    fun confirmed(cases: List<CaseResult>): Flips = flips(cases.filter { it.arm == BEFORE }, cases.filter { it.arm == AFTER })

    fun signTest(a: Int, b: Int): Double {
        val n = a + b
        if (n == 0) return 1.0
        var tail = 0.0
        var term = Math.pow(0.5, n.toDouble())
        for (k in 0..minOf(a, b)) {
            tail += term
            term = term * (n - k) / (k + 1)
        }
        return minOf(1.0, 2 * tail)
    }

    private const val HALF = 0.5
    private const val SHA_LENGTH = 9
}
