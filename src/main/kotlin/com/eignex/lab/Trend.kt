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
    /** The run's spec and problems ([Experiments.fingerprint] of both): runs that share one measured the same thing. */
    val spec: String,
    /** The run's spec alone ([Experiments.fingerprint]), which the schedule's current spec is matched by. */
    val experiment: String = "",
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
)

object Trend {
    /**
     * Every run a schedule named [name] queued, oldest first; an experiment of several arms is read by its first. A
     * finished run's point comes from [cache] when it has one: its cases never change, only the reference rows its
     * disagreement and shortfall counts read, so a cached point is recomputed once those rows changed and it is older
     * than [TrendCache.maxReferenceLagMs]. A confirmation's counts are read afresh, since it can end after its run.
     */
    fun runs(
        store: Store,
        name: String,
        cache: TrendCache? = null,
        refreshing: Boolean = false,
        /** Only the runs of this experiment ([Experiments.fingerprint]); null for every run. */
        experiment: String? = null,
    ): List<TrendRun> {
        // Filtered by experiment before any case is read: a run's experiment is its spec's alone.
        val jobs = scheduled(store, name)
            .filter { job -> experiment == null || job.experiment?.let(Experiments::fingerprint) == experiment }
        val confirmations = store.jobs(limit = MAX_RUNS, name = name + Confirm.SUFFIX, withCommands = false)
            .filter { it.name.startsWith("$name${Confirm.SUFFIX}@") }
            .associateBy { it.name }
        val stamp = cache?.let { store.referencesStamp() }
        // The previous finished run of each spec: its first arm's cases, loaded only when a run after it is computed.
        var previous: Pair<String, Long>? = null
        val ownCases = HashMap<Long, List<CaseResult>>()
        fun own(job: Long): List<CaseResult> = ownCases.getOrPut(job) {
            val label = store.arms(job).firstOrNull()?.arm?.label
            store.scoringCases(job).filter { it.arm == label }
        }
        var stale = false
        val runs = jobs.mapNotNull { job ->
            val finished = job.status !in ACTIVE
            // A stale point is still served, and refreshed off the request (see [TrendCache]); a refresh recomputes it.
            // A running one's point too, for [TrendCache.LIVE_MS]: its cases finish one by one, and a page load that
            // finds it older is served it while it is recomputed.
            val cached = cache?.let { if (finished) it.lookup(job.id, stamp) else it.lookupLive(job.id) }?.takeIf { it.fresh || !refreshing }
            if (cached?.fresh == false) stale = true
            val base = cached?.run ?: run {
                val arms = store.arms(job.id)
                val cases = store.scoringCases(job.id)
                ownCases[job.id] = cases.filter { it.arm == arms.firstOrNull()?.arm?.label }
                val trend = run(job, arms, cases, store.references(cases.map { it.problem.collection to it.problem.problem }))
                    ?: return@mapNotNull null
                val flips = previous?.takeIf { it.first == trend.spec && job.status == Status.DONE }
                    ?.let { Confirm.flips(own(it.second), own(job.id)) }
                trend.copy(lost = flips?.lost?.size, gained = flips?.gained?.size, flipP = flips?.pValue,
                    proofLost = flips?.proofLost?.size, proofGained = flips?.proofGained?.size,
                    objectiveLost = flips?.objectiveLost?.size, objectiveGained = flips?.objectiveGained?.size)
                    .also { if (finished) cache?.put(job.id, stamp, it) else cache?.putLive(job.id, it) }
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

    /** Every run a schedule named [name] queued and did not cancel, oldest first, none of them read. */
    fun scheduled(store: Store, name: String): List<Job> = store.jobs(limit = MAX_RUNS, name = name, withCommands = false)
        .filter { it.experiment != null && it.name.startsWith("$name@") && it.status != Status.CANCELLED }
        .sortedBy { it.id }

    fun run(
        job: Job,
        arms: List<PlannedArm>,
        cases: List<CaseResult>,
        references: Map<Pair<String, String>, Reference> = emptyMap(),
    ): TrendRun? {
        val label = arms.firstOrNull()?.arm?.label ?: return null
        val own = cases.filter { it.arm == label }
        val stats = Stats.of(listOf(label), own).arms.single()
        if (stats.problems == 0) return null
        val summary = Compare.compare(listOf(label), own).arms.single()
        val n = stats.problems.toDouble()
        val suites = own.mapNotNull { case -> Outcome.of(case.record)?.let { case.problem to it } }
            .groupBy({ it.first }, { it.second })
            .entries.groupBy { it.key.suite.ifEmpty { "(none)" } }
            .mapValues { (_, problems) ->
                SuiteShare(problems.size, problems.map { (_, runs) -> runs.map { if (it.rank > 0) 1.0 else 0.0 }.average() }.average())
            }.toSortedMap()
        val comparison = References.compare(listOf(label), own, references)
        return TrendRun(
            job = job.id,
            sha = job.sha ?: job.ref,
            spec = job.experiment?.let { Experiments.fingerprint(it, own.map { case -> case.problem }.distinct()) }.orEmpty(),
            experiment = job.experiment?.let(Experiments::fingerprint).orEmpty(),
            at = job.finishedAt ?: job.createdAt,
            finished = job.status !in ACTIVE,
            cases = own.size,
            problems = stats.problems,
            solved = Estimate(stats.solved.value / n, stats.solved.low / n, stats.solved.high / n),
            proven = summary.proven / n,
            unsupported = summary.unsupported,
            errors = summary.errors,
            disagreements = comparison.disagreements.size,
            disagreementsBySuite = comparison.disagreements.groupingBy { it.problem.suite.ifEmpty { "(none)" } }.eachCount(),
            worse = comparison.shortfalls.size,
            par2 = stats.par2,
            suites = suites,
        )
    }

    private const val MAX_RUNS = 500
}

/**
 * Finished runs' trend points, kept between requests: a schedule's trend reads every run it has, and recomputing a
 * hundred finished runs on a page load took seconds. A point whose reference rows changed since it was computed, and
 * which is older than [maxReferenceLagMs], is stale: still served, and recomputed off the request on one background
 * thread, so no page load waits on a recompute and a schedule is refreshed once however many loads find it stale.
 */
class TrendCache(val maxReferenceLagMs: Long = DEFAULT_REFERENCE_LAG_MS, private val clock: () -> Long = System::currentTimeMillis) {
    /** A cached point and whether it is fresh. */
    class Cached(val run: TrendRun, val fresh: Boolean)

    private class Entry(val stamp: Long?, val at: Long, val run: TrendRun)

    private val entries = java.util.concurrent.ConcurrentHashMap<Long, Entry>()
    private val refreshing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val background = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "trend-refresh").apply { isDaemon = true }
    }

    /** [job]'s point, fresh unless the reference rows changed since ([stamp]) and it is older than [maxReferenceLagMs]. */
    fun lookup(job: Long, stamp: Long?): Cached? {
        val entry = entries[job] ?: return null
        return Cached(entry.run, entry.stamp == stamp || clock() - entry.at <= maxReferenceLagMs)
    }

    fun put(job: Long, stamp: Long?, run: TrendRun) {
        entries[job] = Entry(stamp, clock(), run)
    }

    private val live = java.util.concurrent.ConcurrentHashMap<Long, Entry>()

    /** A running [job]'s point, fresh for [LIVE_MS]. */
    fun lookupLive(job: Long): Cached? = live[job]?.let { Cached(it.run, clock() - it.at <= LIVE_MS) }

    fun putLive(job: Long, run: TrendRun) {
        live[job] = Entry(null, clock(), run)
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
        private const val DEFAULT_REFERENCE_LAG_MS = 10 * 60_000L
        /** How long a running job's point is served before it is recomputed. */
        const val LIVE_MS = 20_000L
    }
}
