package com.eignex.lab.analysis

import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The parts of a bench `SolveRecord` a comparison reads. */
data class Outcome(
    val optimize: Boolean,
    val maximize: Boolean,
    /** true: a solution; false: proved infeasible; null: undecided within the budget. */
    val feasible: Boolean?,
    val objective: Double?,
    val proven: Boolean,
    val timeToBestMs: Long?,
    val budgetMs: Long,
    val error: Boolean,
    /** Why solver declined the model, when it did: the run decided nothing, and nothing went wrong. */
    val unsupported: String? = null,
    /** Why the problem never reached the solver: it did not compile or parse. */
    val loadError: String? = null,
    /** The solver's own `solveTime` statistic, in ms. */
    val solveTimeMs: Long? = null,
    /** The solve's wall-clock time, as the bench timed its subprocess; null in records from before it was kept. */
    val elapsedMs: Long? = null,
    val processors: Int? = null,
    /** The check's verdict: the lab's ([com.eignex.lab.checker.LabCheck.state]) where it checked, else the bench's
     *  `sourceValidation` statistic (`valid`, `invalid`, `unknown`). */
    val sourceValidation: SourceVerdict? = null,
    val sourceValidationReason: String? = null,
    val floatApproximation: Boolean = false,
    /** The record's `validationPolicy`: how its claims were judged, `reported-result-v1` for the solver's own report. */
    val validationPolicy: String? = null,
    /** How far a solver record's solution claim was checked ([SourceValidation.check]); null for another solver's. */
    val witness: WitnessCheck? = null,
    /** Whether the solver claimed a solution, as it reported it before any check withdrew the claim. */
    val claimedSolution: Boolean = false,
    /** The solver's own objective and proof claim, kept by a check that withdrew them ([claimedSolution]). */
    val reportedObjective: String? = null,
    val reportedProven: Boolean = false,
    /**
     * Whether a checker accepted the witness: the lab's verdict, or the bench's where the lab's decided nothing, so a
     * lab check that failed never undoes the bench's acceptance. Only a float approximation's claim needs it to count.
     */
    val accepted: Boolean = false,
) {
    init {
        requireConsistent(feasible, objective, proven)
    }

    /** What the solve decided ([Decision]). */
    val decision: Decision get() = Decision.of(feasible, objective, proven)

    /** Solved beats unsolved, and proven (an optimum, or infeasibility) beats merely solved. */
    val rank: Int get() = when {
        error || feasible == null -> 0
        proven || feasible == false || !optimize -> 2
        else -> 1
    }

    /**
     * The time a comparison charges a decided run: to its best solution, or, for a proof without one (an infeasibility
     * proof has no solution), the solve time the solver reported, else the solve's wall-clock time (a model refuted
     * while flattening reports no solve time). Only an undecided run, or a decided one with no time at all, is charged
     * the whole budget, so a fast refutation never reads as a timeout.
     */
    val timeMs: Long get() = if (rank > 0) timeToBestMs ?: solveTimeMs ?: elapsedMs ?: budgetMs else budgetMs

    companion object {
        fun of(record: JsonElement?): Outcome? {
            val fields = record as? JsonObject ?: return null
            if ("caseId" in fields && "solver" !in fields) return null
            fun field(name: String) = fields[name]?.takeUnless { it is JsonObject }?.jsonPrimitive
            // The lab's own verdict ([com.eignex.lab.checker.LabCheck]) speaks for the claim where there is one; the
            // bench's check, if it made one, only for records the lab has not checked.
            val lab = fields["labCheck"] as? JsonObject
            fun labField(name: String) = (lab?.get(name) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
            val bench = SourceVerdict.of(stat(fields, "sourceValidation"))
            val validation = SourceVerdict.of(if (lab != null) labField("state") else bench?.word)
            val reason = if (lab != null) labField("reason") else stat(fields, "sourceValidationReason")
            val approximation = stat(fields, "floatApproximation") == "true"
            // Only a rejection withdraws a claim ([SourceValidation.rejects]): the solver's own claim, which a bench
            // check moved aside when it could not decide or could not run, is read back whatever the check said, and
            // the verdict in force rejects it or leaves it standing.
            val restored = withdrawn(fields)
            val reportedFeasible = if (restored) stat(fields, "reportedFeasible")?.toBooleanStrictOrNull() else field("feasible")?.booleanOrNull
            val objective = if (restored) stat(fields, "reportedObjective")?.toDoubleOrNull() else field("objective")?.doubleOrNull
            val proven = if (restored) stat(fields, "reportedProven") == "true" else field("proven")?.booleanOrNull ?: false
            val timeToBest = field("timeToBestMs")?.longOrNull ?: if (restored) producerTimeToBestMs(fields) else null
            val policy = (fields["validationPolicy"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
            val accepted = validation == SourceVerdict.VALID || (!SourceValidation.rejects(validation) && bench == SourceVerdict.VALID)
            // A float approximation's claim is on a grid, not on the source: it counts only once a checker accepts it,
            // with or without a failed check beside it.
            val rejected = SourceValidation.rejects(validation) ||
                (approximation && (reportedFeasible == false || (reportedFeasible == true && !accepted)))
            return Outcome(
                optimize = field("kind")?.content == "optimize",
                maximize = field("maximize")?.booleanOrNull ?: false,
                feasible = if (rejected) null else reportedFeasible,
                // A record that says two things at once reads as the one it can mean ([Decision]): an infeasibility has no
                // objective, and an undecided solve proved nothing.
                objective = if (rejected || reportedFeasible == false) null else objective,
                proven = !rejected && !approximation && proven && reportedFeasible != null,
                timeToBestMs = if (rejected) null else timeToBest,
                budgetMs = field("budgetMs")?.longOrNull ?: 0,
                error = field("command")?.content == "ERROR" || stat(fields, "referenceError") != null,
                unsupported = stat(fields, "unsupported"),
                loadError = stat(fields, "loadError"),
                sourceValidation = validation,
                sourceValidationReason = reason,
                floatApproximation = approximation,
                validationPolicy = policy,
                witness = if (lab != null) SourceValidation.labCheck(validation) else if (policy != null || fields.containsKey("finalWitness")) SourceValidation.check(validation, policy, reason) else null,
                claimedSolution = reportedFeasible == true || stat(fields, "reportedFeasible") == "true",
                reportedObjective = stat(fields, "reportedObjective")?.takeUnless { it == "null" },
                reportedProven = stat(fields, "reportedProven") == "true",
                accepted = accepted,
                solveTimeMs = stat(fields, "solveTime")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                    ?.let { (it * MS_PER_S).toLong() },
                elapsedMs = field("elapsedMs")?.longOrNull?.takeIf { it >= 0 },
                processors = field("processors")?.content?.toIntOrNull()?.takeIf { it > 0 },
            )
        }

        /**
         * Whether a bench check moved [fields]' claim aside: no claim of its own in the record, the solver's kept in
         * `stats.reportedFeasible` and beside it. [of] reads it back; [Confirm] reads its exact objective back.
         */
        internal fun withdrawn(fields: JsonObject): Boolean =
            (fields["feasible"] as? JsonPrimitive)?.booleanOrNull == null && stat(fields, "reportedFeasible") in setOf("true", "false")

        private const val MS_PER_S = 1000.0

        private fun stat(fields: JsonObject, name: String): String? =
            ((fields["stats"] as? JsonObject)?.get(name) as? JsonPrimitive)?.content

        /**
         * The solver's time to its best solution from the producer record an MPS or SMT check keeps
         * (`sourceValidationProducerRecordJson`), for a claim a check withdrew along with its time. A scoring record
         * carries only that field of it ([SCORING_STATS]).
         */
        private fun producerTimeToBestMs(fields: JsonObject): Long? = stat(fields, "sourceValidationProducerRecordJson")
            ?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() as? JsonObject }
            ?.get("timeToBestMs")?.let { it as? JsonPrimitive }?.longOrNull
    }
}

/** One arm's totals over its cases with a record. */
data class ArmSummary(
    val label: String,
    val cases: Int,
    val solved: Int,
    /** Proven optima and proven infeasibility; a satisfied problem counts as solved only. */
    val proven: Int,
    /** Solver crashes and problems that failed to load. */
    val errors: Int,
    /** Problems solver declined. */
    val unsupported: Int,
    /** Pairwise Borda points against every other arm, over the (problem, seed) pairs both ran. */
    val score: Double,
    /** Against the first arm: pairs this arm did better on, worse on, and drew. */
    val wins: Int,
    val losses: Int,
    val ties: Int,
    val buildFingerprints: List<String> = emptyList(),
    val validationPolicies: List<String> = emptyList(),
    val missingProvenance: Int = 0,
    /** Its cases from an instrumented job ([ExperimentSpec.instrumented]), whose times are no runtime measurement. */
    val instrumented: Int = 0,
    /** Its solver solution claims by how far each was checked against its source ([WitnessCheck]). */
    val witnesses: Map<WitnessCheck, Int> = emptyMap(),
    /** Contests (problem, seed, repeat) where its record's validation policy differs from the first arm's. */
    val validationMismatches: Int = 0,
)

/**
 * Whether [arms] met on problems under different validation policies: one side's solver report counted as a solution
 * or proof against the other's checked witness, and a rejected claim counted as no solution.
 */
fun mixesValidation(arms: List<ArmSummary>): Boolean = arms.any { it.validationMismatches > 0 }

/** Whether [arms] mix instrumented and uninstrumented runs, whose times a comparison must not set side by side. */
fun mixesInstrumented(arms: List<ArmSummary>): Boolean {
    val counted = arms.filter { it.cases > 0 }
    return counted.any { it.instrumented > 0 } && counted.any { it.instrumented < it.cases }
}

data class Comparison(val arms: List<ArmSummary>, val disagreements: List<Disagreement>)

object Compare {
    /**
     * Points [a] earns against [b] on one problem, by the MiniZinc Challenge rule: the
     * better outcome takes 1, where better is solved over unsolved, then proven over unproven, then the better
     * objective; equal outcomes split the point by time, the faster taking the larger share; two unsolved score 0.
     * Times closer than [TIE_MS] or [TIE_SHARE] of the slower are a tie at half each: a run to run difference that
     * small is the machine's noise, and the time split alone would call it a win.
     */
    fun points(a: Outcome, b: Outcome): Double {
        if (a.rank != b.rank) return if (a.rank > b.rank) 1.0 else 0.0
        if (a.rank == 0) return 0.0
        val (oa, ob) = a.objective to b.objective
        if (a.optimize && oa != null && ob != null && oa != ob) return if ((oa > ob) == a.maximize) 1.0 else 0.0
        if (abs(a.timeMs - b.timeMs) <= maxOf(TIE_MS, (TIE_SHARE * maxOf(a.timeMs, b.timeMs)).toLong())) return 0.5
        return b.timeMs.toDouble() / (a.timeMs + b.timeMs)
    }

    private fun provenanceValue(case: CaseResult, name: String): String? =
        ((case.record as? JsonObject)?.get(name) as? JsonPrimitive)?.takeUnless { it.content == "null" }
            ?.content?.takeIf { it.isNotBlank() }

    private fun provenanceValues(cases: List<CaseResult>, label: String, name: String): List<String> =
        cases.filter { it.arm == label }.mapNotNull { provenanceValue(it, name) }.distinct().sorted()

    /** Times this close (ms) are a tie, however short the runs. */
    const val TIE_MS = 250L

    /** Times within this share of the slower are a tie, however long the runs. */
    const val TIE_SHARE = 0.1

    /** Totals, pairwise scores and disagreements over [cases] of the arms named in [labels], in that order. */
    fun compare(labels: List<String>, cases: List<CaseResult>): Comparison {
        val outcomes = cases.mapNotNull { case -> Outcome.of(case.record)?.let { case to it } }
        // A (problem, seed) pair is one contest; seeds pair up across arms, so each arm meets the others on equal terms.
        val contests = outcomes.groupBy { (case, _) -> Triple(case.problem, case.seed, case.repeat) }
            .mapValues { (_, entries) -> entries.associate { (case, outcome) -> case.arm to outcome } }
        val arms = labels.map { label ->
            val own = outcomes.filter { it.first.arm == label }.map { it.second }
            var score = 0.0
            var (wins, losses, ties) = Triple(0, 0, 0)
            var mismatches = 0
            for (contest in contests.values) {
                val mine = contest[label] ?: continue
                for ((other, theirs) in contest) if (other != label) score += points(mine, theirs)
                val baseline = contest[labels.first()]
                if (label != labels.first() && baseline != null) {
                    if (mine.validationPolicy != baseline.validationPolicy) mismatches++
                    val (gained, conceded) = points(mine, baseline) to points(baseline, mine)
                    when {
                        gained > conceded -> wins++
                        gained < conceded -> losses++
                        else -> ties++
                    }
                }
            }
            ArmSummary(
                label, own.size, own.count { it.rank > 0 }, own.count { !it.error && (it.proven || it.feasible == false) },
                own.count { it.error || it.loadError != null }, own.count { it.unsupported != null },
                score, wins, losses, ties,
                buildFingerprints = provenanceValues(cases, label, "buildFingerprint"),
                validationPolicies = provenanceValues(cases, label, "validationPolicy"),
                missingProvenance = cases.count { it.arm == label && it.record != null &&
                    (provenanceValue(it, "buildFingerprint") == null || provenanceValue(it, "validationPolicy") == null) },
                instrumented = outcomes.count { it.first.arm == label && it.first.instrumented },
                witnesses = own.filter { it.claimedSolution }.mapNotNull { it.witness }.groupingBy { it }.eachCount(),
                validationMismatches = mismatches,
            )
        }
        return Comparison(arms, disagreements(outcomes.map { (case, outcome) -> Triple(case.problem, case.arm, outcome) }))
    }

    /**
     * Problems whose outcomes cannot all be right: one arm proves infeasibility while another finds a solution, two
     * arms prove different optima, or an arm reports a solution better than another's proven optimum.
     */
    fun disagreements(outcomes: List<Triple<Problem, String, Outcome>>): List<Disagreement> =
        outcomes.groupBy { it.first }.mapNotNull { (problem, entries) ->
            val infeasible = entries.filter { it.third.feasible == false && !it.third.error }.map { it.second }.distinct()
            val feasible = entries.filter { it.third.feasible == true }.map { it.second }.distinct()
            if (infeasible.isNotEmpty() && feasible.isNotEmpty()) {
                return@mapNotNull Disagreement(problem, "infeasible per ${infeasible.joinToString()}, solved by ${feasible.joinToString()}")
            }
            val optima = entries.filter { it.third.optimize && it.third.proven && it.third.objective != null }
            if (optima.map { it.third.objective }.distinct().size > 1) {
                return@mapNotNull Disagreement(problem, "different proven optima: " + optima.distinctBy { it.second to it.third.objective }
                    .joinToString { "${it.second} ${it.third.objective}" })
            }
            val optimum = optima.firstOrNull() ?: return@mapNotNull null
            val beyond = entries.filter { (_, _, o) ->
                o.objective != null && o.objective != optimum.third.objective &&
                    (o.objective > optimum.third.objective!!) == optimum.third.maximize
            }
            if (beyond.isEmpty()) return@mapNotNull null
            Disagreement(problem, "${beyond.joinToString { "${it.second} ${it.third.objective}" }} beyond ${optimum.second}'s proven ${optimum.third.objective}")
        }
}
