package com.eignex.lab

import kotlinx.serialization.Serializable

/** One suite's share of solved problems in one run. */
@Serializable
data class SuiteShare(val problems: Int, val solved: Double)

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
)

object Trend {
    /**
     * Every run a schedule named [name] queued, oldest first; an experiment of several arms is read by its first. A
     * finished run's point comes from [cache] when it has one: its cases never change, only the reference rows its
     * disagreement and shortfall counts read, so a cached point is recomputed once the rows of one of its problems
     * changed and it is older than [TrendCache.maxReferenceLagMs]. A finished run with no point, one whose setup failed
     * before any case ran, is remembered as such until the job changes. A confirmation's counts are read afresh, since
     * it can end after its run.
     */
    fun runs(
        store: Store,
        name: String,
        cache: TrendCache? = null,
        refreshing: Boolean = false,
        /** Only the runs of this experiment ([Experiments.trendFingerprint]); null for every run. */
        experiment: String? = null,
        /**
         * For a page load: a finished run [cache] has no point for yet is not computed here but added to this, left out,
         * and computed on the cache's background thread, so no load waits on it, however many runs a restart left
         * uncached. Null computes every run.
         */
        missing: MutableList<Long>? = null,
        /** Collects the finished runs known to have no point, which are neither shown nor being computed. */
        pointless: MutableList<Long>? = null,
    ): List<TrendRun> {
        // Filtered by experiment before any case is read: a run's experiment is its spec's alone.
        val jobs = scheduled(store, name)
            .filter { job -> experiment == null || job.experiment.let(Experiments::trendFingerprint) == experiment }
        val confirmations = store.jobs(limit = MAX_RUNS, name = name + Confirm.SUFFIX, withCommands = false)
            .filter { it.name.startsWith("$name${Confirm.SUFFIX}@") }
            .associateBy { it.name }
        val stamp = cache?.sync(store)
        // The previous finished run of each spec: its first arm's cases, loaded only when a run after it is computed.
        var previous: Pair<String, Long>? = null
        val ownCases = HashMap<Long, List<CaseResult>>()
        fun own(job: Long): List<CaseResult> = ownCases.getOrPut(job) {
            val label = store.arms(job).firstOrNull()?.arm?.label
            store.scoringCases(job).filter { it.arm == label }
        }
        var stale = false
        val runs = jobs.mapNotNull { job ->
            val finished = job.status.isTerminal
            // A stale point is still served, and refreshed off the request (see [TrendCache]); a refresh recomputes it.
            // A running one's point too, for [TrendCache.LIVE_MS]: its cases finish one by one, and a page load that
            // finds it older is served it while it is recomputed.
            val version = TrendCache.version(job)
            val cached = cache?.let { if (finished) it.lookup(job.id, version, stamp) else it.lookupLive(job.id) }?.takeIf { it.fresh || !refreshing }
            if (cached?.fresh == false) stale = true
            // Known to have no point: neither shown nor counted as being computed.
            if (cached != null && cached.run == null) {
                pointless?.add(job.id)
                return@mapNotNull null
            }
            if (cached == null && finished && missing != null && cache != null) {
                missing += job.id
                stale = true
                return@mapNotNull null
            }
            val base = cached?.run ?: run {
                val arms = store.arms(job.id)
                val cases = store.scoringCases(job.id)
                ownCases[job.id] = cases.filter { it.arm == arms.firstOrNull()?.arm?.label }
                val keys = cases.map { it.problem.referenceKey }
                val trend = run(job, arms, cases, store.references(keys, sources = References.sources(cases)), store.features(keys.distinct()))
                if (trend == null) {
                    if (finished) cache?.put(job.id, version, stamp, null, emptySet())
                    pointless?.add(job.id)
                    return@mapNotNull null
                }
                val flips = previous?.takeIf { it.first == trend.spec && job.status == Status.DONE }
                    ?.let { Confirm.flips(own(it.second), own(job.id)) }
                trend.copy(lost = flips?.lost?.size, gained = flips?.gained?.size, flipP = flips?.pValue,
                    proofLost = flips?.proofLost?.size, proofGained = flips?.proofGained?.size,
                    objectiveLost = flips?.objectiveLost?.size, objectiveGained = flips?.objectiveGained?.size)
                    .also { if (finished) cache?.put(job.id, version, stamp, it, keys.toSet()) else cache?.putLive(job.id, it) }
            }
            if (job.status == Status.DONE) previous = base.spec to job.id
            val confirmation = confirmations[Confirm.name(name, job.sha ?: job.ref, job.id)]
            val confirmed = confirmation?.takeIf { it.status == Status.DONE }?.let { done ->
                cache?.confirmed(done.id) { Confirm.confirmed(store.scoringCases(done.id)) } ?: Confirm.confirmed(store.scoringCases(done.id))
            }
            base.copy(
                confirmJob = confirmation?.id,
                confirmedLost = confirmed?.lost?.size,
                confirmedGained = confirmed?.gained?.size,
                confirmedProofLost = confirmed?.proofLost?.size,
                confirmedProofGained = confirmed?.proofGained?.size,
                confirmedObjectiveLost = confirmed?.objectiveLost?.size,
                confirmedObjectiveGained = confirmed?.objectiveGained?.size,
            )
        }
        if (stale && cache != null) {
            cache.refreshLater("$name/${experiment.orEmpty()}") { runs(store, name, cache, refreshing = true, experiment = experiment) }
        }
        return runs
    }

    /**
     * Every schedule's runs computed into [cache] before anyone asks, so the first trend load after a restart is quick:
     * first each schedule's current experiment, the page's default, then every run of it, which "show them" reads. The
     * background thread takes them in that order, so the older runs wait behind every current view.
     */
    fun warm(store: Store, cache: TrendCache) {
        val schedules = store.schedules()
        for (schedule in schedules) {
            val experiment = Experiments.trendFingerprint(schedule.experiment)
            cache.refreshLater("${schedule.name}/$experiment") { runs(store, schedule.name, cache, experiment = experiment) }
        }
        for (schedule in schedules) cache.refreshLater("${schedule.name}/") { runs(store, schedule.name, cache) }
    }

    /** Every run a schedule named [name] queued and did not cancel, oldest first, none of them read. */
    fun scheduled(store: Store, name: String): List<Job> = store.jobs(limit = MAX_RUNS, name = name, withCommands = false)
        .filter { it.name.startsWith("$name@") && it.status != Status.CANCELLED }
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
        val own = cases.filter { it.arm == label }
        val stats = Stats.of(listOf(label), own).arms.single()
        if (stats.problems == 0) return null
        val summary = Compare.compare(listOf(label), own).arms.single()
        val n = stats.problems.toDouble()
        val outcomes = own.mapNotNull { case -> Outcome.of(case.record)?.let { case.problem to it } }.groupBy({ it.first }, { it.second })
        fun shares(group: (Problem) -> List<String>) = outcomes.entries.flatMap { e -> group(e.key).map { it to e } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, problems) ->
                SuiteShare(problems.size, problems.map { (_, runs) -> runs.map { if (it.rank > 0) 1.0 else 0.0 }.average() }.average())
            }.toSortedMap()
        val themesOf = outcomes.keys.associate { p ->
            (p.referenceKey) to Scoreboard.values(Dimension.THEME, p, features[p.referenceKey], emptyList())
        }
        val suites = shares { listOf(it.suite.ifEmpty { "(none)" }) }
        val themes = shares { themesOf.getValue(it.referenceKey) }
        val comparison = References.compare(listOf(label), own, references)
        return TrendRun(
            job = job.id,
            sha = job.sha ?: job.ref,
            spec = job.experiment.let { Experiments.trendFingerprint(it, own.map { case -> case.problem }.distinct()) }.orEmpty(),
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
        )
    }

    private const val MAX_RUNS = 500
}

/**
 * Finished runs' trend points, kept between requests and only in memory: a schedule's trend reads every run it has, and
 * recomputing a hundred finished runs on a page load took seconds. A point whose problems' reference rows changed since
 * it was computed, and which is older than [maxReferenceLagMs], is stale: still served, and recomputed off the request on
 * one background thread, so no page load waits on a recompute and a schedule is refreshed once however many loads find it
 * stale. A change to one problem's rows leaves every point that did not run it fresh.
 */
class TrendCache(val maxReferenceLagMs: Long = DEFAULT_REFERENCE_LAG_MS, private val clock: () -> Long = System::currentTimeMillis) {
    /** A cached point, null for a run known to have none, and whether it is fresh. */
    class Cached(val run: TrendRun?, val fresh: Boolean)

    private class Entry(val version: String, val stamp: Long?, val at: Long, val run: TrendRun?, val problems: Set<ProblemKey>)

    private val entries = java.util.concurrent.ConcurrentHashMap<Long, Entry>()
    private val refreshing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val background = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "trend-refresh").apply { isDaemon = true }
    }

    /** The reference stamp last synced, and when each problem's rows last changed since the first sync. */
    private var synced = false
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
        }
        synced = true
        lastStamp = stamp
        return stamp
    }

    fun sync(store: Store): Long? = sync(store.referencesStamp(), store::referencesChangedSince)

    /**
     * [job]'s point as cached for this [version] of the job ([TrendCache.version]), none for another: fresh unless the
     * rows of one of its problems changed after it was computed ([stamp]) and it is older than [maxReferenceLagMs].
     */
    fun lookup(job: Long, version: String, stamp: Long?): Cached? {
        val entry = entries[job]?.takeIf { it.version == version } ?: return null
        val moved = entry.stamp != stamp && entry.problems.any { problem ->
            changed[problem]?.let { at -> entry.stamp == null || at > entry.stamp } == true
        }
        return Cached(entry.run, !moved || clock() - entry.at <= maxReferenceLagMs)
    }

    /** Caches [job]'s point, or null for a run with none, computed against reference [stamp] from [problems]' rows. */
    fun put(job: Long, version: String, stamp: Long?, run: TrendRun?, problems: Set<ProblemKey>) {
        entries[job] = Entry(version, stamp, clock(), run, problems)
    }

    private val live = java.util.concurrent.ConcurrentHashMap<Long, Entry>()

    /** A running [job]'s point, fresh for [LIVE_MS]. */
    fun lookupLive(job: Long): Cached? = live[job]?.let { Cached(it.run, clock() - it.at <= LIVE_MS) }

    fun putLive(job: Long, run: TrendRun) {
        live[job] = Entry("", null, clock(), run, emptySet())
    }

    private val confirmations = java.util.concurrent.ConcurrentHashMap<Long, Flips>()

    /** A finished confirmation's flips, which never change once it is done: computed once, by [compute]. */
    fun confirmed(job: Long, compute: () -> Flips): Flips = confirmations.getOrPut(job, compute)

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

    companion object {
        /** What a finished job's cached point is valid for: a retry changes its status or when it finished. */
        fun version(job: Job): String = "${job.status}/${job.finishedAt}"

        private const val DEFAULT_REFERENCE_LAG_MS = 10 * 60_000L
        /** How long a running job's point is served before it is recomputed. */
        const val LIVE_MS = 20_000L
    }
}
