package com.eignex.lab

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

/** A reference solver's verdict on one problem, as `klause-bench/reference/<solver>.csv` records it. */
@Serializable
data class Reference(
    val solver: String,
    val maximize: Boolean,
    val objective: Double?,
    /** true: a solution; false: proved infeasible; null: undecided within its budget. */
    val feasible: Boolean?,
    val proven: Boolean,
    val elapsedMs: Long,
    val budgetMs: Long,
    /** How the solver ran and its claim was judged, where that decides whether the row still holds: an MPS reference's
     *  build, options and validation rules. A row produced another way replaces this one ([References.replaces]). */
    val version: String = "",
    /** How the solution was checked against the model: `valid`, `repaired`, or why it was not (an MPS reference). */
    val validation: String? = null,
    /** The solver's dual bound, which a proof rests on: one that excludes another solver's solution disputes it. */
    val dualBound: Double? = null,
    /** From before the solver's claims were checked: kept as evidence, never trusted. */
    val stale: Boolean = false,
    /** Which source files the verdict is on, as [References.identity] digests a record's `sourceHashes`: null for an
     *  unbound row, from before the lab kept it, which only counts where the source is unknown too. */
    val sourceIdentity: String? = null,
)

/** One collection's rows from one reference solver. */
@Serializable
data class ReferenceCoverage(
    val collection: String,
    val solver: String,
    val rows: Int,
    val decided: Int,
    val proven: Int,
    val infeasible: Int,
    val updatedAt: Long,
)

/** One lab case of a problem, as its inspection page lists it. */
@Serializable
data class ProblemRun(
    val job: Long,
    val jobName: String,
    val createdAt: Long,
    val arm: String,
    val sha: String,
    val case: Int,
    val seed: Long? = null,
    val repeat: Int = 0,
    val status: Status,
    val record: JsonElement? = null,
)

/** A problem as its inspection page shows it: every reference row and every lab run. */
@Serializable
data class ProblemReport(
    val collection: String,
    val problem: String,
    val references: List<Reference>,
    val runs: List<ProblemRun>,
    /** Every case of the problem, of which [runs] may hold only the newest. */
    val totalRuns: Int = runs.size,
)

/** What a reference verdict can be filtered to, with the condition on a `reference_rows` row that selects it. */
enum class ReferenceVerdict(val label: String, internal val sql: String) {
    OPTIMUM("proven optimum", "proven = 1 AND feasible = 1 AND objective IS NOT NULL"),
    UNPROVEN("solved, not proven", "feasible = 1 AND proven = 0"),
    SATISFIED("satisfied", "feasible = 1 AND objective IS NULL"),
    INFEASIBLE("proven infeasible", "feasible = 0"),
    UNKNOWN("unknown", "feasible IS NULL"),
}

/** Which reference rows to show: every set field must hold; [text] matches the problem or the collection. */
data class ReferenceFilter(
    val text: String? = null,
    val solver: String? = null,
    val collection: String? = null,
    val verdict: ReferenceVerdict? = null,
) {
    val isEmpty: Boolean get() = text == null && solver == null && collection == null && verdict == null
}

/** One arm against the reference, over the problems both have a verdict on. */
@Serializable
data class ReferenceSummary(
    val arm: String,
    /** Problems the arm ran that the reference covers. */
    val covered: Int,
    val bothSolved: Int,
    val armOnly: Int,
    val referenceOnly: Int,
    /** Problems with a proven reference optimum the arm reached. */
    val optimaMatched: Int,
    val provenOptima: Int,
    /** Problems where the arm's objective beats the reference's unproven best. */
    val better: Int,
    /** Mean relative gap to the reference objective where the arm is worse, over problems both found solutions to. */
    val meanGap: Double?,
    /** Problems where the arm's best objective is worse than the reference's. */
    val worse: Int = 0,
)

/** A problem an arm solved to a worse objective than the reference did. */
@Serializable
data class Shortfall(
    val problem: Problem,
    val arm: String,
    val objective: Double,
    val reference: Double,
    val referenceProven: Boolean,
    /** Relative distance to the reference objective. */
    val gap: Double,
)

@Serializable
data class ReferenceComparison(
    val summaries: List<ReferenceSummary>,
    val disagreements: List<Disagreement>,
    /** Every worse incumbent, the largest gap first. */
    val shortfalls: List<Shortfall> = emptyList(),
)

/** The `backend=` that runs each problem's reference solver, whose cases become reference results. */
const val REFERENCE_BACKEND = "reference"

/** How reference results merge and how an experiment measures up to them. */
object References {
    /**
     * A reference arm's case record as a reference row, timed as `bench reference` times its rows: a proof by its solve
     * time, an unproven solution by its time to the first one, anything else by the whole budget. Null for a record
     * of a run that never solved: an error, or a problem that did not load.
     */
    fun of(record: JsonElement): Reference? {
        val fields = record as? JsonObject ?: return null
        fun field(name: String) = (fields[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }
        val command = field("command")?.content
        if (command == "ERROR" || command == "LOAD") return null
        val solver = field("solver")?.content ?: return null
        val budgetMs = field("budgetMs")?.longOrNull ?: return null
        val feasible = field("feasible")?.booleanOrNull
        val proven = field("proven")?.booleanOrNull ?: false
        val stats = fields["stats"] as? JsonObject
        fun stat(name: String) = (stats?.get(name) as? JsonPrimitive)?.content
        if (stat("referenceError") != null) return null
        val solveMs = stat("solveTime")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
        // Without a solve time, as when MiniZinc refutes a model while flattening, the solve's wall-clock time.
        val wallMs = field("elapsedMs")?.longOrNull?.takeIf { it >= 0 }
        val elapsedMs = when {
            proven -> solveMs ?: wallMs ?: budgetMs
            feasible == true -> field("timeToFirstFeasibleMs")?.longOrNull ?: solveMs ?: wallMs ?: budgetMs
            else -> budgetMs
        }
        return Reference(
            solver = solver,
            maximize = field("maximize")?.booleanOrNull ?: false,
            objective = field("objective")?.doubleOrNull,
            feasible = feasible,
            proven = proven,
            elapsedMs = elapsedMs,
            budgetMs = budgetMs,
            version = stat("referenceVersion").orEmpty(),
            validation = stat("validation"),
            dualBound = stat("dualBound")?.toDoubleOrNull(),
            stale = stale(solver, stat("referenceVersion").orEmpty()),
            sourceIdentity = identity(record),
        )
    }

    /**
     * The identity of the source files a case [record] ran on: a digest of its `sourceHashes` (the model's and the
     * data's SHA-256), sorted by name. Null when the record has none, as from a bench too old to record them. A problem
     * name can come to mean another model or data file, so a verdict only speaks for runs on the same identity.
     */
    fun identity(record: JsonElement?): String? {
        val hashes = (record as? JsonObject)?.get("sourceHashes") as? JsonObject ?: return null
        if (hashes.isEmpty()) return null
        val canonical = hashes.entries.sortedBy { it.key }
            .joinToString("\n") { (name, hash) -> "$name=${(hash as? JsonPrimitive)?.content ?: hash}" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** What [sources] gives a problem whose cases ran on different source files: no reference row matches it. */
    const val MIXED_SOURCES = "mixed"

    /**
     * The source identity of each problem of [cases], keyed by (collection, problem): the one its cases ran on, null
     * when none recorded one (an unbound run, which only unbound reference rows match), or [MIXED_SOURCES] when they
     * disagree. A case that recorded none beside others that did, such as one that failed early, does not count.
     */
    fun sources(cases: List<CaseResult>): Map<Pair<String, String>, String?> =
        cases.groupBy { it.problem.collection to it.problem.problem }.mapValues { (_, group) ->
            val identities = group.mapNotNull { identity(it.record) }.distinct()
            if (identities.size > 1) MIXED_SOURCES else identities.firstOrNull()
        }

    /** The rows of each problem of [cases] that speak for the source its cases ran on ([sources], [applicable]). */
    fun matching(rows: Map<Pair<String, String>, List<Reference>>, cases: List<CaseResult>): Map<Pair<String, String>, List<Reference>> {
        val sources = sources(cases)
        return rows.mapValues { (key, list) -> applicable(list, sources[key]) }.filterValues { it.isNotEmpty() }
    }

    /**
     * The rows of one problem that speak for a run on [source]. A row bound to that source does. An unbound row, from
     * before sources were kept, does too unless a row bound to another source shows the name has meant other files,
     * when it could be from either and is left out. A run on [MIXED_SOURCES] matches nothing, and a run that recorded
     * no source takes the unbound rows, else the bound ones.
     */
    fun applicable(rows: List<Reference>, source: String?): List<Reference> {
        if (source == MIXED_SOURCES) return emptyList()
        val (bound, unbound) = rows.partition { it.sourceIdentity != null }
        if (source == null) return unbound.ifEmpty { bound }
        val (same, other) = bound.partition { it.sourceIdentity == source }
        return if (other.isEmpty()) same + unbound else same
    }

    /** The solvers whose rows from before [Reference.version] was kept are stale: MPS references, whose solutions
     *  were not checked against the model. */
    val UNCHECKED_SOLVERS = listOf("scip", "highs")

    internal const val XCSP_POLICY = "xcsp3-source-preserving-v2"
    private val xcspIdentity = Regex("klause-xcsp3-cpsat:[^@|]+@sha256:[0-9a-f]{64}\\|$XCSP_POLICY")

    internal fun stale(solver: String, version: String, collection: String = ""): Boolean =
        solver in UNCHECKED_SOLVERS && version.isEmpty() ||
            solver == "cp-sat" && (collection.startsWith("xcsp3-") || version.contains("xcsp3-")) &&
            !xcspIdentity.matches(version)

    /** Whether [incoming] takes the place of the same solver's [stored] row: a current row always over a stale one and
     *  never the other way, then a row bound to other source files, since the problem's name now means those (an
     *  unbound row never displaces a bound one), then one produced another way, so a proof the old way judged wrongly
     *  never outlives its correction, then any verdict at least as strong. */
    fun replaces(incoming: Reference, stored: Reference): Boolean = when {
        incoming.stale != stored.stale -> stored.stale
        incoming.sourceIdentity != stored.sourceIdentity -> incoming.sourceIdentity != null
        incoming.version != stored.version -> true
        // An equally strong verdict refreshes the row, so a rerun corrects how it was timed or recorded.
        else -> incoming != stored && !stronger(stored, incoming)
    }

    /**
     * [rows] of one problem as far as they can be trusted. A proof another solver's solution contradicts is set aside,
     * whichever solver made it: an infeasibility proof against a solution becomes undecided, and an optimum beaten by
     * a better solution, or whose dual bound excludes one, keeps its solution but loses its proof. The rows themselves
     * stay as they were, as evidence of the contradiction ([conflicts]). Only a row on the same source can dispute one.
     */
    fun trusted(rows: List<Reference>): List<Reference> = rows.map { a ->
        val disputed = a.proven && rows.any { b ->
            val y = b.objective
            b.solver != a.solver && b.sourceIdentity == a.sourceIdentity && b.feasible == true && (
                a.feasible == false ||
                    y != null && a.objective != null && beyond(y, a.objective, a.maximize) ||
                    y != null && a.dualBound != null && beyond(y, a.dualBound, a.maximize)
                )
        }
        when {
            !disputed -> a
            a.feasible == false -> a.copy(feasible = null, proven = false)
            else -> a.copy(proven = false)
        }
    }

    /** Whether [a] is the better verdict on a problem than [b]: decided over undecided, proven over unproven,
     *  then the better objective, then the bigger budget, which makes an equal verdict the more telling one. */
    internal fun stronger(a: Reference, b: Reference): Boolean {
        if ((a.feasible != null) != (b.feasible != null)) return a.feasible != null
        if (a.proven != b.proven) return a.proven
        val (x, y) = a.objective to b.objective
        if (x != y) return x != null && (y == null || if (a.maximize) x > y else x < y)
        return a.budgetMs > b.budgetMs
    }


    /**
     * Each arm's cases against [references]. An arm's runs of a problem (seeds, repeats) count by their best: solved
     * if any run solved, its best objective. Speed is left out on purpose: the reference ran under its own budget.
     * A reference on other source files than the arm's runs of a problem ([sources]) does not cover it.
     */
    fun compare(labels: List<String>, cases: List<CaseResult>, references: Map<Pair<String, String>, Reference>): ReferenceComparison {
        val disagreements = ArrayList<Disagreement>()
        val shortfalls = ArrayList<Shortfall>()
        val summaries = labels.map { label ->
            val own = cases.filter { it.arm == label }
            val sources = sources(own)
            val byProblem = own
                .mapNotNull { case -> Outcome.of(case.record)?.let { case.problem to it } }
                .groupBy({ it.first }, { it.second })
            var covered = 0
            var bothSolved = 0
            var armOnly = 0
            var referenceOnly = 0
            var optimaMatched = 0
            var provenOptima = 0
            var better = 0
            val gaps = ArrayList<Double>()
            for ((problem, runs) in byProblem) {
                val key = problem.collection to problem.problem
                val reference = references[key]?.takeIf { applicable(listOf(it), sources[key]).isNotEmpty() } ?: continue
                covered++
                val solved = runs.any { it.rank > 0 }
                val refSolved = reference.feasible != null
                when {
                    solved && refSolved -> bothSolved++
                    solved -> armOnly++
                    refSolved -> referenceOnly++
                }
                val maximize = reference.maximize
                val objectives = runs.mapNotNull { it.objective }
                val best = if (maximize) objectives.maxOrNull() else objectives.minOrNull()
                val refObjective = reference.objective
                if (reference.proven && refObjective != null) {
                    provenOptima++
                    if (best != null && same(best, refObjective)) optimaMatched++
                }
                if (best != null && refObjective != null) {
                    val beats = better(best, refObjective, maximize)
                    if (beats && !reference.proven) better++
                    if (!beats && !same(best, refObjective)) {
                        val gap = abs(best - refObjective) / maxOf(abs(refObjective), 1.0)
                        gaps += gap
                        shortfalls += Shortfall(problem, label, best, refObjective, reference.proven, gap)
                    }
                    if (beats && reference.proven) {
                        disagreements += Disagreement(problem, "$label $best beyond ${reference.solver}'s proven optimum $refObjective")
                    }
                }
                if (runs.any { it.feasible == false && !it.error } && reference.feasible == true) {
                    disagreements += Disagreement(problem, "$label proves infeasible, ${reference.solver} solved it")
                }
                if (runs.any { it.feasible == true } && reference.feasible == false && reference.proven) {
                    disagreements += Disagreement(problem, "$label solved it, ${reference.solver} proved it infeasible")
                }
                val armOptimum = runs.firstOrNull { it.proven && it.feasible == true && it.objective != null }?.objective
                if (armOptimum != null && reference.proven && refObjective != null && !same(armOptimum, refObjective)) {
                    disagreements += Disagreement(problem, "$label proves optimum $armOptimum, ${reference.solver} proves $refObjective")
                }
            }
            ReferenceSummary(label, covered, bothSolved, armOnly, referenceOnly, optimaMatched, provenOptima, better,
                gaps.takeIf { it.isNotEmpty() }?.average() ?: if (bothSolved > 0) 0.0 else null, worse = gaps.size)
        }
        return ReferenceComparison(summaries, disagreements.distinctBy { it.problem to it.reason }, shortfalls.sortedByDescending { it.gap })
    }

    /**
     * How the verdicts of several reference solvers on one problem contradict each other: one proving infeasible what
     * another solved, two proven optima that differ, or a solution beyond another solver's proven optimum. Any of
     * them means one of the solvers is wrong, so neither verdict is to be trusted until it is settled. Rows on different
     * source files cannot contradict each other: they answer different problems under one name.
     */
    fun conflicts(rows: List<Reference>): List<String> = buildList {
        for (a in rows) {
            for (b in rows.filter { it.solver != a.solver && it.sourceIdentity == a.sourceIdentity }) {
                if (a.feasible == false && a.proven && b.feasible == true) add("${a.solver} proves infeasible, ${b.solver} found a solution")
                val (x, y) = a.objective to b.objective
                if (a.solver < b.solver && a.proven && b.proven && x != null && y != null && !same(x, y)) {
                    add("${a.solver} proves optimum ${fmt(x)}, ${b.solver} proves ${fmt(y)}")
                }
                if (a.proven && x != null && !b.proven && y != null && beyond(y, x, a.maximize)) {
                    add("${b.solver}'s solution ${fmt(y)} beats ${a.solver}'s proven optimum ${fmt(x)}")
                }
                val bound = a.dualBound
                if (a.proven && bound != null && y != null && (x == null || !beyond(y, x, a.maximize)) && beyond(y, bound, a.maximize)) {
                    add("${b.solver}'s solution ${fmt(y)} lies beyond ${a.solver}'s dual bound ${fmt(bound)}")
                }
            }
        }
    }.distinct()

    private fun fmt(x: Double) = if (x == Math.rint(x) && abs(x) < 1e15) x.toLong().toString() else x.toString()

    /**
     * Whether two objectives are the same optimum: within a relative [REL_TOLERANCE] (an absolute [ABS_TOLERANCE] near
     * zero). Solvers print floating-point objectives with noise (1479.99999999 for 1480), and a MIP solver calls a
     * solution optimal once its bound is within a relative gap of it, 1e-4 by default in HiGHS, so two proofs of one
     * optimum can differ by that much.
     */
    fun same(a: Double, b: Double): Boolean = abs(a - b) <= maxOf(ABS_TOLERANCE, REL_TOLERANCE * maxOf(abs(a), abs(b)))

    /** Whether [x] is better than [than] by more than [same] allows, in the [maximize] sense. */
    fun better(x: Double, than: Double, maximize: Boolean): Boolean = !same(x, than) && if (maximize) x > than else x < than

    /**
     * Whether a solution [x] lies beyond a proven [bound] in the [maximize] sense, by more than a solution checker's
     * tolerance: what refutes a proof. Tighter than [same], which allows for a solver's gap tolerance, since a proof
     * that stopped within its gap is no longer a proof (an MPS reference records it unproven), and a solution only
     * 0.01% past a dual bound still refutes it.
     */
    fun beyond(x: Double, bound: Double, maximize: Boolean): Boolean {
        val margin = maxOf(ABS_TOLERANCE, PROOF_TOLERANCE * maxOf(abs(x), abs(bound)))
        return if (maximize) x > bound + margin else x < bound - margin
    }

    private const val PROOF_TOLERANCE = 1e-6
    private const val REL_TOLERANCE = 1e-4
    private const val ABS_TOLERANCE = 1e-6

    private fun abs(x: Double) = kotlin.math.abs(x)
}
