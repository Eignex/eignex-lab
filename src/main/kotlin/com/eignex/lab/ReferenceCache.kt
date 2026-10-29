package com.eignex.lab

/**
 * The reference page's reads, kept between loads: the coverage and conflict tables scan every reference row, and a
 * scoreboard scores its run's every case. Once a reference row changes or a job finishes, a kept read is still served
 * and recomputed on one background thread, so a page load never waits on a recompute it has an answer for. A
 * scoreboard of a run still going is not kept.
 */
internal class ReferenceCache(private val store: Store) : AutoCloseable {
    private class Kept<T>(val version: String, val value: T)

    private class Tables(val coverage: List<ReferenceCoverage>, val conflicts: List<Disagreement>)

    @Volatile private var tables: Kept<Tables>? = null

    private val boards = Lru<String, Kept<ScoreboardView>>(BOARDS)

    private val refreshing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val background = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "reference-refresh").apply { isDaemon = true }
    }

    private fun version() = "${store.references.stamp()}/${store.lastFinished()}"

    private fun later(key: String, work: () -> Unit) {
        if (!refreshing.add(key)) return
        background.execute {
            try {
                work()
            } catch (e: Exception) {
                println("reference refresh of $key failed: ${e.message}")
            } finally {
                refreshing.remove(key)
            }
        }
    }

    private fun computeTables(version: String) = Kept(version, Tables(store.references.coverage(), store.references.conflicts())).also { tables = it }

    /** The tables, served stale while recomputed; computed once, by one caller, when none is kept yet. */
    private fun tables(): Tables {
        val version = version()
        val kept = tables ?: synchronized(cold) { tables ?: computeTables(version) }
        if (kept.version != version) later("tables") { computeTables(version()) }
        return kept.value
    }

    private val cold = Any()

    fun coverage(): List<ReferenceCoverage> = tables().coverage

    fun conflicts(): List<Disagreement> = tables().conflicts

    /**
     * [scoreboardView] of [params], kept per the parameters a scoreboard reads ([SCOREBOARD_PARAMS]) and served stale
     * while it is recomputed: the reference search's own parameters on the same page do not make another board.
     */
    fun scoreboard(params: io.ktor.http.Parameters): ScoreboardView {
        val key = params.entries().filter { it.key in SCOREBOARD_PARAMS }.sortedBy { it.key }
            .joinToString("&") { (k, v) -> "$k=${v.joinToString(",")}" }
        val version = version()
        val kept = boards[key]
        if (kept != null) {
            if (kept.version != version) later("board $key") { board(key, params, version()) }
            return kept.value
        }
        return board(key, params, version)
    }

    private val inputs = Lru<String, Kept<ScoreInputs>>(INPUTS)

    /** A finished run's [ScoreInputs], kept until a reference row changes: a new filter or grouping then only regroups. */
    private fun inputs(job: Job, label: String?): ScoreInputs {
        if (job.status.isActive) return scoreInputs(store, job.id, label)
        val key = "${job.id}/$label/${job.finishedAt}"
        val stamp = store.references.stamp().toString()
        inputs[key]?.takeIf { it.version == stamp }?.let { return it.value }
        return scoreInputs(store, job.id, label).also { inputs[key] = Kept(stamp, it) }
    }

    private fun board(key: String, params: io.ktor.http.Parameters, version: String): ScoreboardView =
        scoreboardView(store, params, ::inputs).also { view ->
            if (view.job?.status?.isActive != true) boards[key] = Kept(version, view)
        }

    /** The default page's reads, computed before anyone asks so the first load after a restart is quick too. */
    fun warm() = later("warm") {
        computeTables(version())
        board("", io.ktor.http.Parameters.Empty, version())
    }

    /** Stop the background recomputes; one under way is interrupted. */
    override fun close() {
        background.shutdownNow()
    }

    private companion object {
        const val BOARDS = 64
        const val INPUTS = 8

        /** The parameters [scoreboardView] reads. */
        val SCOREBOARD_PARAMS = setOf("job", "arm", "by", "scoring", "group", "against") + Dimension.entries.map { it.name.lowercase() }
    }
}

internal class ScoreboardView(
    /** The latest finished run of each schedule, the run picker's choices. */
    val runs: List<Job>,
    val job: Job?,
    val arms: List<String>,
    val arm: String?,
    val by: Dimension,
    val filters: Map<Dimension, String>,
    val scoring: Scoring,
    /** Every value each dimension takes over the run's problems, for the filters. */
    val options: Map<Dimension, List<String>>,
    val board: Board?,
    /** A cell's problems, when one was opened: its group and reference solver, and the problems worst first. */
    val cell: Pair<String, String>?,
    val problems: List<ScoreProblem>,
    /** The arm's solver solution claims by how far each was checked against its source ([WitnessCheck]). */
    val witnesses: Map<WitnessCheck, Int> = emptyMap(),
)

/** The scoreboard for [params]: `job` (default the latest solver run), `arm`, `by`, a filter per dimension
 *  named by it, and `group` with `against` to open a cell. */
/** What a scoreboard of one arm of a run reads: its cases, and their problems' reference rows and features. */
internal class ScoreInputs(
    val cases: List<CaseResult>,
    val references: Map<ProblemKey, List<Reference>>,
    val features: Map<ProblemKey, ProblemFeatures>,
)

internal fun scoreInputs(store: Store, job: Long, label: String?): ScoreInputs {
    val cases = store.plans.scoringCases(job).filter { it.arm == label }
    val keys = cases.map { it.problem.referenceKey }.distinct()
    return ScoreInputs(cases, References.matching(store.references.current(keys), cases), store.references.features(keys))
}

internal fun scoreboardView(
    store: Store,
    params: io.ktor.http.Parameters,
    inputs: (Job, String?) -> ScoreInputs = { job, label -> scoreInputs(store, job.id, label) },
): ScoreboardView {
    val schedules = store.schedules.all().filter { schedule -> Experiments.arms(schedule.experiment).none { it.values["backend"] == REFERENCE_BACKEND } }
    val runs = schedules.mapNotNull { schedule ->
        store.jobs(limit = 20, name = schedule.name, withCommands = false).firstOrNull { it.origin == JobOrigin.Scheduled(schedule.name) && it.status == Status.DONE }
    }
    val job = params["job"]?.toLongOrNull()?.let(store::job)
        ?: runs.firstOrNull()
    val by = params["by"]?.let { v -> Dimension.entries.firstOrNull { it.name.equals(v, ignoreCase = true) } } ?: Dimension.CLASS
    val filters = Dimension.entries.mapNotNull { d -> params[d.name.lowercase()]?.takeIf { it.isNotBlank() }?.let { d to it } }.toMap()
    val scoring = if (params["scoring"].equals("incomplete", ignoreCase = true)) Scoring.INCOMPLETE else Scoring.COMPLETE
    if (job == null) return ScoreboardView(runs, null, emptyList(), null, by, filters, scoring, emptyMap(), null, null, emptyList())
    val planned = store.plans.arms(job.id)
    val label = params["arm"]?.takeIf { a -> planned.any { it.arm.label == a } } ?: planned.firstOrNull()?.arm?.label
    val arm = planned.firstOrNull { it.arm.label == label }?.arm
        ?: return ScoreboardView(runs, job, emptyList(), null, by, filters, scoring, emptyMap(), null, null, emptyList())
    val (cases, references, features) = inputs(job, label).let { Triple(it.cases, it.references, it.features) }
    val problems = cases.map { it.problem }.distinct()
    val profiles = Scoreboard.profiles(cases, features)
    val options = Dimension.entries.associateWith { d ->
        problems.flatMap { p -> Scoreboard.values(d, p, features[p.referenceKey], references[p.referenceKey].orEmpty(), profiles[p]) }
            .distinct().sorted()
    }
    val board = Scoreboard.board(cases, arm, references, features, by, filters, scoring)
    val cell = params["against"]?.let { against -> (params["group"] ?: "all") to against }
    val opened = cell?.let { (group, against) -> Scoreboard.problems(cases, arm, references, features, by, group, against, filters, scoring) }.orEmpty()
    val witnesses = cases.mapNotNull { Outcome.of(it.record) }.filter { it.claimedSolution }.mapNotNull { it.witness }
        .groupingBy { it }.eachCount()
    return ScoreboardView(runs, job, planned.map { it.arm.label }, label, by, filters, scoring, options, board, cell, opened, witnesses)
}
