package com.eignex.lab.analysis

import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

import kotlinx.serialization.Serializable

/** One suite's share of solved problems in one run. */
@Serializable
data class SuiteShare(
    val problems: Int,
    val solved: Double,
    /** The share with a proof: an optimum proven or infeasibility shown, as the run's own [TrendRun.proven] counts. */
    val proven: Double? = null,
)

/**
 * One run of a schedule, as a point in its trend: shares of the problems it ran with results so far, so a run still
 * going plots what it has. Shares are fractions of problems, a problem's seeds and repeats averaged, which keeps runs
 * over different selections comparable as rates.
 */
@Serializable
data class TrendRun(
    val job: Long,
    val sha: String,
    /** The run's spec and problems ([Experiments.trendFingerprint] of both): runs that share one measured the same thing. */
    val spec: String,
    /** The run's spec alone ([Experiments.trendFingerprint]), which the schedule's current spec is matched by. */
    val experiment: String = "",
    /**
     * Whether the run asked for source-witness validation ([SourceValidation.requested]). The request is part of its
     * [experiment], so checked and unchecked runs never share a line; this says which side of that change a run is on.
     */
    val sourceChecked: Boolean = false,
    /** When the run finished, or was queued while it has not. */
    val at: Long,
    val finished: Boolean,
    val cases: Int,
    val problems: Int,
    val solved: Estimate,
    val proven: Double,
    val unsupported: Int,
    val errors: Int,
    /** Problems where the run contradicts the reference: a proof against its solution, a solution beyond its proven
     *  optimum, a different proven optimum. Each is a soundness bug in one of them. */
    val disagreements: Int = 0,
    /** [disagreements] by the suite of the problem each is on, so a suite's chart marks only its own. */
    val disagreementsBySuite: Map<String, Int> = emptyMap(),
    /** [disagreements] by the themes of the problem each is on ([themes]). */
    val disagreementsByTheme: Map<String, Int> = emptyMap(),
    /** Problems the run solved to a worse objective than the reference. */
    val worse: Int = 0,
    /** Against the run before it of the same experiment: problems lost and gained, and the sign test on them. Null for
     *  a first run. */
    val lost: Int? = null,
    val gained: Int? = null,
    val flipP: Double? = null,
    /** The run's confirmation job, when its flips got one, and the flips its repeats agree with, once it has run. */
    val confirmJob: Long? = null,
    val confirmedLost: Int? = null,
    val confirmedGained: Int? = null,
    val proofLost: Int? = null,
    val proofGained: Int? = null,
    val objectiveLost: Int? = null,
    val objectiveGained: Int? = null,
    val confirmedProofLost: Int? = null,
    val confirmedProofGained: Int? = null,
    val confirmedObjectiveLost: Int? = null,
    val confirmedObjectiveGained: Int? = null,
    /** Mean PAR-2 seconds. */
    val par2: Estimate,
    val suites: Map<String, SuiteShare>,
    /**
     * Solved shares by theme, as the classifier names what a problem asks of solver ([Dimension.THEME]): the themes the
     * sweep set was drawn by, so each holds a fair share of it. A problem of several themes counts in each.
     */
    val themes: Map<String, SuiteShare> = emptyMap(),
    val comparisonSeries: List<String> = emptyList(),
    val checkedPoints: Int = 0,
    val nativeProofClaims: Int = 0,
    val contradictedOptima: Int = 0,
    val unverifiedOptima: Int = 0,
    /** Generic benchmark results, independent of solver feasibility and proof claims. */
    val benchmark: BenchmarkTrend? = null,
)

/**
 * What the trend reads of the lab: a schedule's runs, a run's arms and cases, and the reference rows and features its
 * problems are scored against. The trend computes from these alone ([Trend]); [of] reads them from a store.
 */
interface TrendData {
    /** The newest runs of [series] ([JobOrigin.series]), at most [limit], without their commands. */
    fun runs(series: String, limit: Int): List<Job>
    fun arms(job: Long): List<PlannedArm>
    /** A run's cases, each record cut to what scoring reads ([PlanRepository.scoringCases]). */
    fun scoringCases(job: Long): List<CaseResult>
    /** A run's source-check revision, which a recheck advances so cached points and flips are recomputed. */
    fun caseRevision(job: Long): Long = 0
    fun references(keys: Collection<ProblemKey>, sources: Map<ProblemKey, String?>): Map<ProblemKey, Reference>
    fun features(keys: Collection<ProblemKey>): Map<ProblemKey, ProblemFeatures>
    fun schedules(): List<Schedule>
    /** When the reference rows last changed, and what changed since a stamp ([ReferenceRepository.stamp]). */
    fun referenceStamp(): Long?
    fun referencesChangedSince(stamp: Long): Map<ProblemKey, Long>

    companion object {
        /** Problems whose checked points [of] keeps between runs; each is a handful of small points. */
        private const val MAX_EVIDENCE = 1024

        /** The trend's data as [store] keeps it. */
        fun of(store: Store): TrendData = object : TrendData {
            // Every run of a schedule claims optima on the same problems; one retained-evidence read per problem serves
            // them all until another run finishes or a source check changes, instead of a full scan per run.
            // Only the compact checked points are kept: retained runs with their witnesses can be tens of megabytes each.
            private val evidence = Lru<Triple<String, String, Long>, OptimumClaims.Evidence>(MAX_EVIDENCE)
            override fun runs(series: String, limit: Int) = store.jobs(limit = limit, name = series, withCommands = false)
            override fun arms(job: Long) = store.plans.arms(job)
            override fun scoringCases(job: Long): List<CaseResult> {
                val stamp = (store.lastFinished() ?: 0) + store.plans.checkRevision()
                return OptimumClaims.qualify(store, store.plans.scoringCases(job)) { claim ->
                    val key = claim.problem.referenceKey
                    evidence.getOrPut(Triple(key.collection, key.problem, stamp)) { OptimumClaims.retained(store, claim) }
                }
            }
            // A run's own cases and the lab's source checks. Evidence later jobs retain reaches stored points through
            // their maximum age ([TrendCache.maxAgeMs]) instead of invalidating every point whenever any job finishes.
            override fun caseRevision(job: Long) = store.plans.caseRevision(job) + store.plans.checkRevision()
            override fun references(keys: Collection<ProblemKey>, sources: Map<ProblemKey, String?>) = store.references.matching(keys, sources = sources)
            override fun features(keys: Collection<ProblemKey>) = store.references.features(keys)
            override fun schedules() = store.schedules.all()
            override fun referenceStamp() = store.references.stamp()
            override fun referencesChangedSince(stamp: Long) = store.references.changedSince(stamp)
        }
    }
}

object Trend {
    /**
     * Every run a schedule named [name] queued, oldest first; an experiment of several arms is read by its first. A
     * finished run's point comes from [cache] when its source-check revision matches. Reference rows can also change
     * its disagreement and shortfall counts, so a cached point is recomputed once the rows of one of its problems
     * changed and it is older than [TrendCache.maxReferenceLagMs]. A finished run with no point, one whose setup failed
     * before any case ran, is remembered as such until the job changes. A confirmation's counts are part of its run's
     * point, recomputed when the confirmation ends after it. This scores runs: page loads read [stored] instead.
     */
    fun runs(
        data: TrendData,
        name: String,
        cache: TrendCache? = null,
        refreshing: Boolean = false,
        /** Only the runs of this experiment ([Experiments.trendFingerprint]); null for every run. */
        experiment: String? = null,
    ): List<TrendRun> {
        // Filtered by experiment before any case is read: a run's experiment is its spec's alone.
        val jobs = scheduled(data, name, experiment)
        val confirmations = confirmations(data, name)
        val stamp = cache?.sync(data)
        // Compare only matching runtime/control series even when the chart joins the same experiment and problem set.
        val previous = HashMap<String, Long>()
        val ownCases = HashMap<Long, List<CaseResult>>()
        fun own(job: Long): List<CaseResult> = ownCases.getOrPut(job) {
            val label = data.arms(job).firstOrNull()?.arm?.label
            data.scoringCases(job).filter { it.arm == label }
        }
        var stale = false
        val runs = jobs.mapNotNull { job ->
            val finished = job.status.isTerminal
            // A stale point is still served, and refreshed off the request (see [TrendCache]); a refresh recomputes it.
            // A running one's point too, for [TrendCache.LIVE_MS]: its cases finish one by one. The confirmation of a
            // run is part of its point, so one that ends later makes the point another version.
            val confirmation = confirmations[Confirm.name(name, job.sha ?: job.ref, job.id)]
            val version = TrendCache.version(job, data.caseRevision(job.id), confirmation,
                confirmation?.takeIf { it.status == Status.DONE }?.let { data.caseRevision(it.id) } ?: 0)
            val cached = cache?.lookup(job.id, version, stamp, live = !finished)?.takeIf { it.fresh || !refreshing }
            if (cached?.fresh == false) stale = true
            // Known to have no point: neither shown nor counted as being computed.
            if (cached != null && cached.run == null) return@mapNotNull null
            val point = cached?.run ?: run {
                val arms = data.arms(job.id)
                val cases = data.scoringCases(job.id)
                ownCases[job.id] = cases.filter { it.arm == arms.firstOrNull()?.arm?.label }
                val keys = cases.map { it.problem.referenceKey }
                val trend = run(job, arms, cases, data.references(keys, References.sources(cases)), data.features(keys.distinct()))
                if (trend == null) {
                    cache?.put(job.id, version, stamp, null, emptySet())
                    return@mapNotNull null
                }
                val flips = previous[comparisonKey(trend)]?.takeIf { job.status == Status.DONE || trend.benchmark != null && job.status == Status.FAILED }
                    ?.let { Confirm.flips(own(it), own(job.id)) }
                val confirmed = confirmation?.takeIf { it.status == Status.DONE }?.let { Confirm.confirmed(data.scoringCases(it.id)) }
                trend.copy(lost = flips?.lost?.size, gained = flips?.gained?.size, flipP = flips?.pValue,
                    proofLost = flips?.proofLost?.size, proofGained = flips?.proofGained?.size,
                    objectiveLost = flips?.objectiveLost?.size, objectiveGained = flips?.objectiveGained?.size,
                    confirmJob = confirmation?.id,
                    confirmedLost = confirmed?.lost?.size,
                    confirmedGained = confirmed?.gained?.size,
                    confirmedProofLost = confirmed?.proofLost?.size,
                    confirmedProofGained = confirmed?.proofGained?.size,
                    confirmedObjectiveLost = confirmed?.objectiveLost?.size,
                    confirmedObjectiveGained = confirmed?.objectiveGained?.size,
                    benchmark = trend.benchmark?.copy(changes = flips?.let(BenchmarkChanges::of),
                        confirmed = confirmed?.let(BenchmarkChanges::of)))
                    .also { cache?.put(job.id, version, stamp, it, keys.toSet()) }
            }
            if (job.status == Status.DONE || point.benchmark != null && job.status == Status.FAILED) previous[comparisonKey(point)] = job.id
            point
        }
        if (stale && cache != null) {
            cache.refreshLater("$name/${experiment.orEmpty()}") { runs(data, name, cache, refreshing = true, experiment = experiment) }
        }
        return runs
    }

    /**
     * What a page load shows: every run's stored point ([TrendCache.stored]), read in one query beside the run list and
     * its confirmations, with nothing scored or checked on the request, however old the runs. A run with no stored point
     * yet is added to [missing], one stored as having none to [pointless]. When any is missing or its stored point is
     * past its age or another finish state ([TrendCache.expired]), the schedule is recomputed ([runs]) on the cache's
     * background thread, which also takes in reference and source-check changes.
     */
    fun stored(
        data: TrendData,
        name: String,
        cache: TrendCache,
        experiment: String? = null,
        missing: MutableList<Long>? = null,
        pointless: MutableList<Long>? = null,
    ): List<TrendRun> {
        val jobs = scheduled(data, name, experiment)
        val confirmations = confirmations(data, name)
        val points = cache.stored(jobs.map { it.id })
        var stale = false
        val runs = jobs.mapNotNull { job ->
            val point = points[job.id]
            if (point == null) {
                missing?.add(job.id)
                stale = true
                return@mapNotNull null
            }
            val expired = cache.expired(job, point, confirmations[Confirm.name(name, job.sha ?: job.ref, job.id)])
            if (expired) stale = true
            // An old empty point may become a benchmark point after an upgrade. Active jobs may also have no
            // planned cases yet. Keep the page polling both instead of treating them as permanently pointless.
            if (point.run == null && (expired || job.status.isActive)) {
                missing?.add(job.id)
                return@mapNotNull null
            }
            point.run ?: run {
                pointless?.add(job.id)
                null
            }
        }
        if (stale) cache.refreshLater("$name/${experiment.orEmpty()}") { runs(data, name, cache, refreshing = true, experiment = experiment) }
        return runs
    }

    private fun confirmations(data: TrendData, name: String): Map<String, Job> = data.runs(name + Confirm.SUFFIX, MAX_RUNS)
        .filter { it.name.startsWith("$name${Confirm.SUFFIX}@") }
        .associateBy { it.name }

    /** Statistical comparisons retain their stricter controls independently of visual chart grouping. */
    private fun comparisonKey(run: TrendRun): String = run.spec +
        if (run.comparisonSeries == listOf("historical-unqualified")) "" else ":" + ExecutionStore.digest(run.comparisonSeries.joinToString("\n"))

    /**
     * Every schedule's runs computed into [cache] before anyone asks, so the first trend load after a restart is quick:
     * first each schedule's current experiment, the page's default, then every run of it, which "show them" reads. The
     * background thread takes them in that order, so the older runs wait behind every current view.
     */
    fun warm(data: TrendData, cache: TrendCache) {
        val schedules = data.schedules()
        for (schedule in schedules) {
            val experiment = Experiments.trendFingerprint(schedule.experiment)
            cache.refreshLater("${schedule.name}/$experiment") { runs(data, schedule.name, cache, experiment = experiment) }
        }
        for (schedule in schedules) cache.refreshLater("${schedule.name}/") { runs(data, schedule.name, cache) }
    }

    /** Every run a schedule named [name] queued and did not cancel, oldest first, none of them read. */
    fun scheduled(data: TrendData, name: String, experiment: String? = null): List<Job> = data.runs(name, MAX_RUNS)
        .filter { it.origin == JobOrigin.Scheduled(name) && it.status != Status.CANCELLED }
        .filter { job -> experiment == null || job.experiment.let(Experiments::trendFingerprint) == experiment }
        .sortedBy { it.id }

    fun run(
        job: Job,
        arms: List<PlannedArm>,
        cases: List<CaseResult>,
        references: Map<ProblemKey, Reference> = emptyMap(),
        /** The classifier's features of the run's problems, which place a problem solver did not profile in a class. */
        features: Map<ProblemKey, ProblemFeatures> = emptyMap(),
    ): TrendRun? {
        val label = arms.firstOrNull()?.arm?.label ?: return null
        val raw = cases.filter { it.arm == label }
        if (BenchTransitions.enabled(raw)) {
            val benchmark = BenchmarkTrends.summarize(raw)
            val zero = Estimate(0.0, 0.0, 0.0)
            return TrendRun(job.id, job.sha ?: job.ref,
                Experiments.trendFingerprint(job.experiment, raw.map { it.problem }.distinct()),
                experiment = Experiments.trendFingerprint(job.experiment), at = job.finishedAt ?: job.createdAt,
                finished = job.status.isTerminal, cases = raw.size, problems = raw.map { it.problem }.distinct().size,
                solved = zero, proven = 0.0, unsupported = 0, errors = benchmark.failed, par2 = zero, suites = emptyMap(),
                comparisonSeries = raw.mapNotNull { (it.record as? kotlinx.serialization.json.JsonObject)
                    ?.get("details")?.let { details -> (details as? kotlinx.serialization.json.JsonObject)?.get("benchmarkControls") }
                    ?.let { controls -> "benchmark:" + ExecutionStore.digest(controls.toString()) } }.distinct().sorted(),
                benchmark = benchmark)
        }
        val own = OptimumClaims.local(raw.map { case ->
            if ((case.record as? kotlinx.serialization.json.JsonObject)?.get("comparisonControls") == null) case
            else case.copy(problem = case.problem.copy(problem = ComparisonControls.problem(case).second, benchCase = null))
        })
        val stats = Stats.of(listOf(label), own).arms.single()
        if (stats.problems == 0) return null
        val summary = Compare.compare(listOf(label), own).arms.single()
        val n = stats.problems.toDouble()
        val outcomes = own.mapNotNull { case -> Outcome.of(case.record)?.let { case.problem to it } }.groupBy({ it.first }, { it.second })
        fun shares(group: (Problem) -> List<String>) = outcomes.entries.flatMap { e -> group(e.key).map { it to e } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, problems) ->
                SuiteShare(problems.size, problems.map { (_, runs) -> runs.map { if (it.rank > 0) 1.0 else 0.0 }.average() }.average(),
                    problems.map { (_, runs) -> runs.map { if (!it.error && (it.proven || it.feasible == false)) 1.0 else 0.0 }.average() }.average())
            }.toSortedMap()
        val themesOf = outcomes.keys.associate { p ->
            (p.referenceKey) to Scoreboard.values(Dimension.THEME, p, features[p.referenceKey], emptyList())
        }
        val suites = shares { listOf(it.suite.ifEmpty { "(none)" }) }
        val themes = shares { themesOf.getValue(it.referenceKey) }
        val comparison = References.compare(listOf(label), own, references)
        val controlSeries = own.map(ComparisonControls::series).distinct().sorted()
        return TrendRun(
            job = job.id,
            sha = job.sha ?: job.ref,
            // Runtime/harness receipts describe the point; they do not redraw the requested problem set.
            spec = job.experiment.let { Experiments.trendFingerprint(it, raw.map { case -> case.problem }.distinct()) },
            experiment = job.experiment.let(Experiments::trendFingerprint).orEmpty(),
            sourceChecked = job.experiment.let { SourceValidation.requested(it) } == true,
            at = job.finishedAt ?: job.createdAt,
            finished = job.status.isTerminal,
            cases = own.size,
            problems = stats.problems,
            solved = Estimate(stats.solved.value / n, stats.solved.low / n, stats.solved.high / n),
            proven = summary.proven / n,
            unsupported = summary.unsupported,
            errors = summary.errors,
            disagreements = comparison.disagreements.size,
            disagreementsBySuite = comparison.disagreements.groupingBy { it.problem.suite.ifEmpty { "(none)" } }.eachCount(),
            disagreementsByTheme = comparison.disagreements.flatMap { themesOf[it.problem.referenceKey].orEmpty() }
                .groupingBy { it }.eachCount(),
            worse = comparison.shortfalls.size,
            par2 = stats.par2,
            suites = suites,
            themes = themes,
            comparisonSeries = controlSeries,
            checkedPoints = summary.witnesses[WitnessCheck.ACCEPTED] ?: 0,
            nativeProofClaims = summary.nativeProofClaims,
            contradictedOptima = summary.contradictedOptima,
            unverifiedOptima = summary.unverifiedOptima,
        )
    }

    private const val MAX_RUNS = 500
}

/**
 * Every run's trend point, one stored row per run ([TrendPoints]): a page load only reads them ([Trend.stored]), and every
 * point is computed on one background thread. A running job's point is recomputed after [LIVE_MS]. A point whose problems' reference rows changed since
 * it was computed, and which is older than [maxReferenceLagMs], is stale: still served, and recomputed off the request on
 * one background thread, so no page load waits on a recompute and a schedule is refreshed once however many loads find it
 * stale. A change to one problem's rows leaves every point that did not run it fresh.
 */
class TrendCache(
    /** Where finished runs' points are kept: one stored row per run in production ([TrendPoints.of]). */
    private val points: TrendPoints = TrendPoints.memory(),
    val maxReferenceLagMs: Long = DEFAULT_REFERENCE_LAG_MS,
    /** How long a stored point is served before it is recomputed off the request, which picks up evidence later runs
     *  retained (a contradicted optimum) without recomputing every point whenever any job finishes. */
    val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    constructor(maxReferenceLagMs: Long, clock: () -> Long) : this(TrendPoints.memory(), maxReferenceLagMs, DEFAULT_MAX_AGE_MS, clock)

    /** A cached point, null for a run known to have none, and whether it is fresh. */
    class Cached(val run: TrendRun?, val fresh: Boolean)

    private val refreshing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val background = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "trend-refresh").apply { isDaemon = true }
    }

    /** The reference stamp last synced, and when each problem's rows last changed since the first sync. */
    private var synced = false
    /** Past [MAX_CHANGED] changed problems, every point computed against an earlier stamp than this is stale. */
    @Volatile private var everyPointBefore: Long? = null
    private var lastStamp: Long? = null
    private val changed = java.util.concurrent.ConcurrentHashMap<ProblemKey, Long>()

    /**
     * Takes in the reference stamp [stamp] ([Store.referencesStamp]): when it moved since the last sync, the problems
     * whose rows changed since then ([changedSince]) are noted, which tells [lookup] which points they made stale.
     * Returns [stamp], the one to [put] a point computed now with.
     */
    @Synchronized
    fun sync(stamp: Long?, changedSince: (Long) -> Map<ProblemKey, Long>): Long? {
        if (synced && stamp != null && stamp != lastStamp) {
            for ((problem, at) in changedSince(lastStamp ?: Long.MIN_VALUE)) changed.merge(problem, at, ::maxOf)
            // Past this many changed problems, every point is recomputed instead of tracking them all.
            if (changed.size > MAX_CHANGED) {
                changed.clear()
                everyPointBefore = stamp
            }
        }
        synced = true
        lastStamp = stamp
        return stamp
    }

    fun sync(data: TrendData): Long? = sync(data.referenceStamp(), data::referencesChangedSince)

    /**
     * [job]'s point as cached for this [version] of the job ([TrendCache.version]), none for another: fresh unless the
     * rows of one of its problems changed after it was computed ([stamp]) and it is older than [maxReferenceLagMs].
     */
    fun lookup(job: Long, version: String, stamp: Long?, live: Boolean = false): Cached? {
        val entry = points.load(listOf(job))[job]?.takeIf { it.version == version } ?: return null
        val age = clock() - entry.at
        if (live) return Cached(entry.run, age <= LIVE_MS)
        val moved = entry.stamp != stamp && (everyPointBefore?.let { before -> entry.stamp == null || entry.stamp < before } == true ||
            entry.problems.any { problem -> changed[problem]?.let { at -> entry.stamp == null || at > entry.stamp } == true })
        return Cached(entry.run, (!moved || age <= maxReferenceLagMs) && age <= maxAgeMs)
    }

    /** Stores [job]'s point, or null for a run with none, computed against reference [stamp] from [problems]' rows. */
    fun put(job: Long, version: String, stamp: Long?, run: TrendRun?, problems: Set<ProblemKey>) {
        points.save(job, TrendPoints.Point(version, stamp, clock(), run, problems))
    }

    /** The stored points of [jobs], whatever their version or age: what a page load shows ([Trend.stored]). */
    fun stored(jobs: Collection<Long>): Map<Long, TrendPoints.Point> = points.load(jobs)

    /**
     * Whether [point], stored for [job], is due a recompute that a page load can tell without reading anything more:
     * the job or its [confirmation] reached another finish state, or the point is past its age ([maxAgeMs], or
     * [LIVE_MS] for a running job). Source-check and reference changes are found by the background recompute.
     */
    fun expired(job: Job, point: TrendPoints.Point, confirmation: Job?): Boolean {
        val parts = point.version.split('/')
        val age = clock() - point.at
        return parts.getOrNull(0) != job.status.toString() || parts.getOrNull(1) != job.finishedAt.toString() || POINT_FORMAT !in parts ||
            parts.filter { it.startsWith(CONFIRMATION) } != listOfNotNull(confirmation?.let(::confirmationPart)) ||
            age > if (job.status.isTerminal) maxAgeMs else LIVE_MS
    }

    /** Run [refresh] of [key] on the background thread, unless a refresh of it is already waiting or running. */
    fun refreshLater(key: String, refresh: () -> Unit) {
        if (!refreshing.add(key)) return
        background.execute {
            try {
                refresh()
            } catch (e: Exception) {
                println("trend refresh of $key failed: ${e.message}")
            } finally {
                refreshing.remove(key)
            }
        }
    }

    /** Stop the background refreshes; one under way is interrupted. */
    override fun close() {
        background.shutdownNow()
    }

    companion object {
        /** The changed problems tracked before every point is recomputed instead. */
        private const val MAX_CHANGED = 100_000

        /** A retry changes the job's finish state; a source recheck changes its case revision. A run's confirmation is
         *  part of its point, so its finish state and case revision are part of the version too. */
        fun version(job: Job, revision: Long = 0, confirmation: Job? = null, confirmationRevision: Long = 0): String =
            "${job.status}/${job.finishedAt}/$POINT_FORMAT" + (if (revision == 0L) "" else "/checks-$revision") +
                (confirmation?.let { "/" + confirmationPart(it) + if (confirmationRevision == 0L) "" else "/confirmed-checks-$confirmationRevision" } ?: "")

        private const val CONFIRMATION = "confirmation-"
        /** What a stored point holds; a point stored in an earlier format is recomputed (here: proven per theme). */
        private const val POINT_FORMAT = "format-3"
        private fun confirmationPart(confirmation: Job) = "$CONFIRMATION${confirmation.id}-${confirmation.status}-${confirmation.finishedAt}"

        private const val DEFAULT_REFERENCE_LAG_MS = 10 * 60_000L
        private const val DEFAULT_MAX_AGE_MS = 6 * 60 * 60_000L
        /** How long a running job's point is served before it is recomputed. */
        const val LIVE_MS = 20_000L
    }
}

/** Where [TrendCache] keeps finished runs' points: one row per run in the lab's database, or a map for tests. */
interface TrendPoints {
    class Point(val version: String, val stamp: Long?, val at: Long, val run: TrendRun?, val problems: Set<ProblemKey>)

    fun load(jobs: Collection<Long>): Map<Long, Point>
    fun save(job: Long, point: Point)

    companion object {
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        /** Points in [store]'s `trend_points`, one row per run, kept across restarts. A row that no longer decodes
         *  (an older point format) reads as absent and is recomputed. */
        fun of(store: Store): TrendPoints = object : TrendPoints {
            override fun load(jobs: Collection<Long>): Map<Long, Point> = store.trendPoints.load(jobs).mapNotNull { (job, row) ->
                val run = row.run?.let { runCatching { json.decodeFromString(TrendRun.serializer(), it) }.getOrNull() ?: return@mapNotNull null }
                job to Point(row.version, row.referenceStamp, row.computedAt, run, row.problems)
            }.toMap()
            override fun save(job: Long, point: Point) = store.trendPoints.save(job,
                StoredTrendPoint(point.version, point.stamp, point.at, point.run?.let { json.encodeToString(TrendRun.serializer(), it) }, point.problems))
        }

        fun memory(): TrendPoints = object : TrendPoints {
            private val points = java.util.concurrent.ConcurrentHashMap<Long, Point>()
            override fun load(jobs: Collection<Long>) = jobs.mapNotNull { job -> points[job]?.let { job to it } }.toMap()
            override fun save(job: Long, point: Point) { points[job] = point }
        }
    }
}
