package com.eignex.lab

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the classifier says about a problem's source: its themes (`scheduling`, `mip`, …), structure and logic. */
@Serializable
data class ProblemFeatures(
    val collection: String,
    val problem: String,
    val themes: List<String> = emptyList(),
    val structure: String? = null,
    val logic: String? = null,
)

/**
 * The MiniZinc Challenge's two scoring classes. [COMPLETE] (its free, fixed, parallel and open classes) ranks a proof
 * above an unproven result and splits an equal one by time; [INCOMPLETE] (its local-search class) ignores proofs,
 * compares solutions and objectives alone, and splits an equal result evenly.
 */
enum class Scoring { COMPLETE, INCOMPLETE }

/** A way to split problems into groups on the scoreboard, and to filter them. */
enum class Dimension(val label: String) {
    /** klause's own profile of the model it built ([profile]): its problem class. */
    CLASS("class"),
    /** The profile's cross-cutting traits: resource or placement constraints, values past 32 bits. */
    TRAIT("trait"),
    /** The source-text classifier's themes, which match the named sets. */
    THEME("theme"),
    FORMAT("format"),
    KIND("kind"),
    SUITE("suite"),
    DIFFICULTY("difficulty"),
}

/** One arm's Borda points against one reference over a group's problems, and the reference's points against it. */
@Serializable
data class ScoreCell(val klause: Double, val reference: Double, val problems: Int) {
    /** klause's share of the points awarded; null when neither side scored. */
    val share: Double? get() = (klause + reference).takeIf { it > 0 }?.let { klause / it }
}

/** One group's row: a cell per reference solver, [BEST] the strongest trusted verdict of any. */
@Serializable
data class ScoreRow(val group: String, val problems: Int, val cells: Map<String, ScoreCell>)

/** One problem against one reference, for a cell's drill-down. */
@Serializable
data class ScoreProblem(
    val problem: Problem,
    val solver: String,
    val klause: Double,
    val reference: Double,
    /** Whether time split an equal result: the run had the reference's budget on one core. */
    val timed: Boolean,
)

@Serializable
data class Board(val solvers: List<String>, val rows: List<ScoreRow>, val total: ScoreRow)

/**
 * An arm against every reference solver by the MiniZinc Challenge's pairwise Borda rule ([Compare.points]), one row per
 * group of problems. A run whose budget and single core match a reference's has equal results split by time; any other
 * run is compared on quality alone, an equal result splitting the point, since its times measure something else.
 * Proofs another solver contradicts are set aside first ([References.trusted]), and only reference rows on the source
 * files the arm's runs used count ([References.matching]).
 */
object Scoreboard {
    const val BEST = "best"

    /**
     * klause's profile of a model, as its `profile` statistic reports it: `<class> optimizing=… wide=… scheduling=…`,
     * the class one of Sat, PseudoBoolean, FiniteCp, MixedInteger, Continuous and Open. A [derived] profile is the lab's
     * guess for a run that reported none, from how klause solved it or the source's logic; its traits are unknown.
     */
    data class Profile(val problemClass: String, val wide: Boolean, val scheduling: Boolean, val derived: Boolean = false) {
        companion object {
            fun of(text: String?): Profile? {
                val words = text?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: return null
                val flags = words.drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }
                return words.firstOrNull()?.let { Profile(it, flags["wide"] == "true", flags["scheduling"] == "true") }
            }
        }
    }

    /**
     * Each problem's profile, from the first of its runs that reports one. klause profiles the model only on its main
     * pipelines: a run on another (the polynomial theory solver for nonlinear reals, an early refutation) or one that
     * failed to load reports none, and gets a [Profile.derived] class from its pipeline or [features]' logic instead.
     */
    fun profiles(cases: List<CaseResult>, features: Map<Pair<String, String>, ProblemFeatures> = emptyMap()): Map<Problem, Profile> =
        cases.groupBy { it.problem }.mapNotNull { (problem, runs) ->
            val stats = runs.map { ((it.record as? JsonObject)?.get("stats") as? JsonObject).orEmpty() }
            fun stat(name: String) = stats.firstNotNullOfOrNull { (it[name] as? JsonPrimitive)?.content }
            val profile = stat("profile")?.let(Profile::of)
                ?: derivedClass(stat("solvePipeline"), features[problem.collection to problem.problem]?.logic, stat("loadError") != null)
                    ?.let { Profile(it, wide = false, scheduling = false, derived = true) }
            profile?.let { problem to it }
        }.toMap()

    /** The class of a run klause did not profile: by its logic, else by its pipeline, else that it did not load. */
    private fun derivedClass(pipeline: String?, logic: String?, loadFailed: Boolean): String? =
        LOGIC_CLASSES[logic?.uppercase()] ?: PIPELINE_CLASSES[pipeline] ?: LOAD_FAILED.takeIf { loadFailed }

    /** Each problem of [cases] with its runs' outcomes. */
    private fun outcomes(cases: List<CaseResult>): Map<Problem, List<Outcome>> =
        cases.mapNotNull { case -> Outcome.of(case.record)?.let { case.problem to it } }.groupBy({ it.first }, { it.second })

    /** The values [problem] takes in [dimension]: several themes or traits at once, one value otherwise. */
    fun values(
        dimension: Dimension,
        problem: Problem,
        features: ProblemFeatures?,
        references: List<Reference>,
        profile: Profile? = null,
    ): List<String> = when (dimension) {
        Dimension.CLASS -> listOf(profile?.problemClass?.let { CLASS_LABELS[it] ?: it } ?: UNPROFILED)
        Dimension.TRAIT -> profile?.takeUnless { it.derived }?.let { p -> listOfNotNull("scheduling".takeIf { p.scheduling }, "wide domains".takeIf { p.wide }).ifEmpty { listOf("neither") } }
            ?: listOf(UNKNOWN)
        Dimension.THEME -> features?.themes?.takeIf { it.isNotEmpty() } ?: listOf(UNCLASSIFIED)
        Dimension.FORMAT -> listOf(problem.format.ifEmpty { UNKNOWN }.lowercase())
        Dimension.KIND -> listOf(problem.category.ifEmpty { UNKNOWN }.lowercase())
        Dimension.SUITE -> listOf(problem.suite.ifEmpty { problem.collection })
        Dimension.DIFFICULTY -> listOf(difficulty(references))
    }

    /** How long the strongest trusted reference took: what the reference needed to decide it. */
    fun difficulty(references: List<Reference>): String {
        val best = strongest(References.trusted(references)) ?: return "no reference"
        return when {
            best.feasible == null -> "undecided by reference"
            best.elapsedMs < SECOND -> "under 1 s"
            best.elapsedMs < TEN_SECONDS -> "1-10 s"
            else -> "10 s or more"
        }
    }

    fun board(
        cases: List<CaseResult>,
        arm: Arm,
        references: Map<Pair<String, String>, List<Reference>>,
        features: Map<Pair<String, String>, ProblemFeatures>,
        groupBy: Dimension,
        filters: Map<Dimension, String> = emptyMap(),
        scoring: Scoring = Scoring.COMPLETE,
    ): Board {
        val matched = References.matching(references, cases)
        val scored = scoredProblems(cases, arm, matched, features, filters, scoring)
        val profiles = profiles(cases, features)
        val named = scored.flatMap { it.second.keys }.filter { it != BEST }.distinct().sorted()
        val solvers = listOfNotNull(BEST.takeIf { scored.any { BEST in it.second } }) + named
        fun row(group: String, members: List<Pair<Problem, Map<String, ScoreProblem>>>) = ScoreRow(
            group,
            members.size,
            solvers.associateWith { solver ->
                val points = members.mapNotNull { it.second[solver] }
                ScoreCell(points.sumOf { it.klause }, points.sumOf { it.reference }, points.size)
            },
        )
        val groups = scored.flatMap { entry ->
            val key = entry.first.collection to entry.first.problem
            values(groupBy, entry.first, features[key], matched[key].orEmpty(), profiles[entry.first]).map { it to entry }
        }.groupBy({ it.first }, { it.second })
        return Board(
            solvers,
            groups.map { (group, members) -> row(group, members) }.sortedWith(compareBy<ScoreRow> { it.group in CATCH_ALL }.thenByDescending { it.problems }),
            row("all", scored),
        )
    }

    /** The problems of a cell: [solver] against the arm over the problems in [group] of [groupBy], worst for klause first. */
    fun problems(
        cases: List<CaseResult>,
        arm: Arm,
        references: Map<Pair<String, String>, List<Reference>>,
        features: Map<Pair<String, String>, ProblemFeatures>,
        groupBy: Dimension,
        group: String?,
        solver: String,
        filters: Map<Dimension, String> = emptyMap(),
        scoring: Scoring = Scoring.COMPLETE,
    ): List<ScoreProblem> {
        val profiles = profiles(cases, features)
        val matched = References.matching(references, cases)
        return scoredProblems(cases, arm, matched, features, filters, scoring)
            .filter { (problem, _) ->
                val key = problem.collection to problem.problem
                group == null || group == "all" || group in values(groupBy, problem, features[key], matched[key].orEmpty(), profiles[problem])
            }
            .mapNotNull { it.second[solver] }
            .sortedBy { it.klause - it.reference }
    }

    /** Each problem that passes [filters] and has a reference, with its points against each solver and the best. */
    private fun scoredProblems(
        cases: List<CaseResult>,
        arm: Arm,
        references: Map<Pair<String, String>, List<Reference>>,
        features: Map<Pair<String, String>, ProblemFeatures>,
        filters: Map<Dimension, String>,
        scoring: Scoring,
    ): List<Pair<Problem, Map<String, ScoreProblem>>> {
        val profiles = profiles(cases, features)
        return outcomes(cases).mapNotNull { (problem, runs) ->
            val key = problem.collection to problem.problem
            val rows = References.trusted(references[key].orEmpty())
            if (rows.isEmpty()) return@mapNotNull null
            val passes = filters.all { (dimension, value) ->
                value in values(dimension, problem, features[key], references[key].orEmpty(), profiles[problem])
            }
            if (!passes) return@mapNotNull null
            val bySolver = rows.associateBy { it.solver }.mapValues { (solver, row) -> score(problem, solver, runs, row, arm, scoring) }
            val best = strongest(rows)?.let { score(problem, BEST, runs, it, arm, scoring) }
            problem to (bySolver + listOfNotNull(best?.let { BEST to it }))
        }
    }

    /** The arm's [runs] of [problem] against [reference]: each side's points averaged over the runs. */
    private fun score(problem: Problem, solver: String, runs: List<Outcome>, reference: Reference, arm: Arm, scoring: Scoring): ScoreProblem {
        val timed = scoring == Scoring.COMPLETE && arm.timeoutMs == reference.budgetMs && arm.cores == 1
        val theirs = runs.map { mine ->
            Outcome(
                optimize = mine.optimize,
                maximize = reference.maximize,
                feasible = reference.feasible,
                objective = reference.objective,
                proven = reference.proven,
                timeToBestMs = reference.elapsedMs,
                budgetMs = reference.budgetMs,
                error = false,
            )
        }
        val pairs = runs.zip(theirs)
        return ScoreProblem(
            problem = problem,
            solver = solver,
            klause = pairs.map { (mine, ref) -> points(mine, ref, timed, scoring) }.average(),
            reference = pairs.map { (mine, ref) -> points(ref, mine, timed, scoring) }.average(),
            timed = timed,
        )
    }

    /**
     * Points [a] earns against [b] by the MiniZinc Challenge rule of [scoring], as klause-bench's `compare_records.py`
     * scores it: solved (a solution or an infeasibility) over unsolved; under [Scoring.COMPLETE] a proof (an optimum,
     * an infeasibility, a solved satisfaction problem) over none; then the better objective, within [References.same]
     * since a reference prints its objective with noise. An equal result splits by time when the two were [timed] alike
     * under [Scoring.COMPLETE], evenly otherwise; two unsolved score nothing.
     */
    internal fun points(a: Outcome, b: Outcome, timed: Boolean, scoring: Scoring = Scoring.COMPLETE): Double {
        val solvedA = a.rank > 0
        val solvedB = b.rank > 0
        if (solvedA != solvedB) return if (solvedA) 1.0 else 0.0
        if (!solvedA) return 0.0
        if (scoring == Scoring.COMPLETE && a.rank != b.rank) return if (a.rank > b.rank) 1.0 else 0.0
        val (oa, ob) = a.objective to b.objective
        if (a.optimize && oa != null && ob != null && !References.same(oa, ob)) return if ((oa > ob) == a.maximize) 1.0 else 0.0
        if (!timed || scoring == Scoring.INCOMPLETE) return HALF
        val close = kotlin.math.abs(a.timeMs - b.timeMs) <= maxOf(Compare.TIE_MS, (Compare.TIE_SHARE * maxOf(a.timeMs, b.timeMs)).toLong())
        return if (close) HALF else b.timeMs.toDouble() / (a.timeMs + b.timeMs)
    }

    private fun strongest(rows: List<Reference>): Reference? = rows.reduceOrNull { a, b -> if (References.stronger(b, a)) b else a }

    private const val UNCLASSIFIED = "unclassified"
    private const val UNPROFILED = "unknown class"
    private const val UNKNOWN = "unknown"
    private const val LOAD_FAILED = "did not load"
    /** klause's class names as the scoreboard shows them. */
    private val CLASS_LABELS = mapOf(
        "Sat" to "SAT", "PseudoBoolean" to "pseudo-Boolean", "FiniteCp" to "finite-domain CP", "MixedInteger" to "mixed integer",
        "Continuous" to "continuous", "Open" to "open-domain integer", "Nonlinear" to "nonlinear real",
    )
    /** The class an SMT logic's problems take when klause did not profile them. */
    private val LOGIC_CLASSES = mapOf(
        "QF_NRA" to "Nonlinear", "QF_LRA" to "Continuous", "QF_RDL" to "Continuous",
        "QF_LIA" to "Open", "QF_IDL" to "Open", "QF_LIRA" to "Open",
    )
    /** The class klause's solve pipeline implies, for a run it did not profile. */
    private val PIPELINE_CLASSES = mapOf("polynomial-theory" to "Nonlinear", "open-theory" to "Open", "finite-cp" to "FiniteCp")
    /** Groups that hold what could not be placed: listed after the real ones. */
    private val CATCH_ALL = setOf(UNPROFILED, UNCLASSIFIED, UNKNOWN, LOAD_FAILED, "no reference", "undecided by reference")
    private const val SECOND = 1_000L
    private const val TEN_SECONDS = 10_000L
    private const val HALF = 0.5
}
