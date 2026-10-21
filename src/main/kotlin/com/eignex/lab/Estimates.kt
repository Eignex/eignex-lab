package com.eignex.lab

/** Where an estimate's time a case came from, which the queue page names beside it. */
enum class EstimateSource { MEASURED, HISTORY, TIMEOUT }

/**
 * What an earlier run of the same experiment measured, for a job that has not measured enough of its own: the run's
 * job, the cases it planned, and its mean wall time a case, null when it recorded none.
 */
data class CaseHistory(val jobId: Long, val cases: Int, val meanCaseMs: Double?, val sameExperiment: Boolean = true)

/**
 * A job's remaining work: the [cases] left, the wall time a case is taken to need ([caseMs]), their core-hours, and the
 * hours the local machine would take for them at the job's own concurrency ([localHours]). [source] says where [caseMs]
 * came from; [history] names the run the case count or the time came from, and [guessed] marks a case count guessed
 * from the selections.
 */
data class WorkEstimate(
    val cases: Int,
    val caseMs: Double,
    val coreHours: Double,
    val localHours: Double,
    val source: EstimateSource,
    val history: Long? = null,
    val guessed: Boolean = false,
    val sameExperiment: Boolean = true,
) {
    /** Where the numbers came from, in a few words for the queue page. */
    val note: String
        get() = when (source) {
            EstimateSource.MEASURED -> "measured"
            EstimateSource.HISTORY -> "from run $history" + if (sameExperiment) "" else ", an earlier experiment"
            EstimateSource.TIMEOUT -> when {
                history != null -> "cases from run $history${if (sameExperiment) "" else ", an earlier experiment"}, upper bound"
                guessed -> "guessed, upper bound"
                else -> "upper bound"
            }
        }
}

/**
 * The estimates of the work left in jobs, which the queue page shows and the cloud pressure counts. A job's time a case
 * is its own mean once [MIN_MEASURED] of its cases finished, else the mean of the last finished run of the same
 * experiment ([EstimateHistory]), else each case's whole timeout, the upper bound. A job not planned yet takes its
 * case count from that run too, else the guess from its selections ([NodePool.estimatedCases]).
 */
object Estimates {
    /** Finished cases of its own after which a job's mean case time is trusted over an earlier run's. */
    const val MIN_MEASURED = 10
    private const val MS_PER_HOUR = 3_600_000.0

    /** Cases of [spec] that run at once on a host of [cores] cores: the job's [parallel] within [maxParallel], as long
     *  as its largest case's cores fit beside each other; one case always may run. */
    fun concurrency(spec: ExperimentSpec, parallel: Int, cores: Int, maxParallel: Int = Int.MAX_VALUE): Int {
        val caseCores = Experiments.arms(spec).maxOf { it.cores }
        return minOf(parallel.coerceIn(1, maxParallel.coerceAtLeast(1)), (cores / caseCores).coerceAtLeast(1))
    }

    /** The mean wall time [job], a finished run, measured a case: its finished cases' own times, or else its running
     *  time spread over its cases at the concurrency it ran at on [cores] cores. Null for a job never planned. */
    fun history(job: Job, cores: Int): CaseHistory? {
        val spec = job.experiment?.takeUnless { it.isScript } ?: return null
        if (job.total == 0) return null
        val mean = when {
            job.timed > 0 -> job.timedMs.toDouble() / job.timed
            job.runMs > 0 -> runCatching { job.runMs.toDouble() * concurrency(spec, job.parallel, cores) / job.total }.getOrNull()
            else -> null
        }
        return CaseHistory(job.id, job.total, mean)
    }

    /** [job]'s own mean wall time a case, once [MIN_MEASURED] of its cases finished; null before. */
    fun measuredCaseMs(job: Job): Double? = job.timedMs.toDouble().div(job.timed).takeIf { job.timed >= MIN_MEASURED }

    /** The mean wall time a case of [spec] may take: each arm's whole timeout, the arms taking equal shares. */
    fun timeoutCaseMs(spec: ExperimentSpec): Double = Experiments.arms(spec)
        .map { if (spec.isProfile) Experiments.profileTimeoutSec(it) * 1000.0 else it.timeoutMs.toDouble() }.average()

    /** The cores a case of [spec] holds, on average over its arms, which take equal shares of the cases. */
    private fun meanCores(spec: ExperimentSpec): Double = Experiments.arms(spec).map { it.cores }.average()

    /**
     * The core-hours a case of [job] is taken to need, by the same rule as [estimate]: its measured mean, an earlier
     * run's ([history], asked only when needed), else its timeout ([NodePool.meanCaseCoreHours]).
     */
    fun caseCoreHours(job: Job, history: (Job) -> CaseHistory?): Double {
        val spec = checkNotNull(job.experiment)
        val ms = measuredCaseMs(job) ?: history(job)?.meanCaseMs ?: return NodePool.meanCaseCoreHours(spec)
        return ms * meanCores(spec) / MS_PER_HOUR
    }

    /**
     * The work left in [job] on a local machine of [cores] cores, null for a job that is not an experiment. The cases
     * left are a queued job's cases no host has started, a running one's unfinished cases, an unplanned one's earlier
     * run's or guessed count. [history] is asked only for a job that has not measured [MIN_MEASURED] cases of its own.
     */
    fun estimate(job: Job, history: (Job) -> CaseHistory?, cores: Int, maxParallel: Int = Int.MAX_VALUE): WorkEstimate? {
        val spec = job.experiment?.takeUnless { it.isScript } ?: return null
        return runCatching {
            val measured = measuredCaseMs(job)
            val earlier = if (measured == null) history(job) else null
            val planned = job.total > 0
            val cases = when {
                spec.isProfile && !planned -> 1
                !planned -> earlier?.cases ?: NodePool.estimatedCases(spec)
                job.status == Status.RUNNING -> job.total - job.done
                else -> job.total - job.done - job.running
            }.coerceAtLeast(0)
            val fromEarlier = earlier?.takeIf { !planned && !spec.isProfile || it.meanCaseMs != null }
            val guessed = !planned && fromEarlier == null && !exact(spec)
            val concurrent = concurrency(spec, job.parallel, cores, maxParallel)
            val (ms, source) = when {
                measured != null -> measured to EstimateSource.MEASURED
                earlier?.meanCaseMs != null -> earlier.meanCaseMs to EstimateSource.HISTORY
                else -> timeoutCaseMs(spec) to EstimateSource.TIMEOUT
            }
            val coreHours = if (source == EstimateSource.TIMEOUT) cases * NodePool.meanCaseCoreHours(spec) else cases * ms * meanCores(spec) / MS_PER_HOUR
            WorkEstimate(cases, ms, coreHours, cases * ms / concurrent / MS_PER_HOUR, source, fromEarlier?.jobId, guessed,
                fromEarlier?.sameExperiment ?: true)
        }.getOrNull()
    }

    /**
     * The core-hours queued [job] adds to the cloud pressure: its [estimate], from measured or earlier runs' case times
     * when there are any, so the pool reacts to the work a queue really holds. Each case's whole timeout
     * ([NodePool.coreHours]) is the fallback when the estimate cannot be made.
     */
    fun pressureCoreHours(job: Job, history: (Job) -> CaseHistory?, cores: Int): Double =
        estimate(job, history, cores)?.coreHours
            ?: NodePool.coreHours(checkNotNull(job.experiment), job.total.takeIf { it > 0 }?.let { it - job.done - job.running })

    /** Whether [spec]'s cases are known before it is planned: an exact problem list, or every selection one problem. */
    private fun exact(spec: ExperimentSpec): Boolean =
        spec.isProfile || spec.problemList.isNotEmpty() || spec.problems.isNotEmpty() && spec.problems.all { "name" in it }
}

/**
 * The finished runs the estimates learn from ([Estimates]), read from the store at most once every [ttlMs]: the newest
 * [limit] experiments that ended done, each by its experiment's fingerprint with its ref and placement left out
 * ([Experiments.fingerprint]). A run, once done, does not change, so each is read once; a refresh reads only the
 * runs that finished since. [cores] are the local machine's, for a run that recorded only its running time.
 */
class EstimateHistory(
    private val store: Store,
    private val cores: Int,
    private val ttlMs: Long = 60_000,
    private val limit: Int = 300,
    private val clock: () -> Long = ::now,
) {
    private class Run(val key: String?, val series: String, val history: CaseHistory?)

    private val runs = HashMap<Long, Run>()
    /** The runs with a history by key, newest first. */
    private var byKey: Map<String, List<Run>> = emptyMap()
    private var refreshedAt: Long? = null

    /** Per schedule, its newest done run whatever its experiment, with when it was read. */
    private val bySeries = HashMap<String, Pair<Long, CaseHistory?>>()

    /**
     * The run [job]'s estimate learns from: the newest done run of the same experiment, a run of the same schedule
     * (or the same name) before any other. A scheduled run (`<schedule>@<sha>`) whose experiment changed since its
     * schedule last ran falls back to that last run, marked as another experiment: an edit to a schedule seldom
     * changes its size much, and a guess from its selections is far worse. Null when there is none, or the store
     * cannot be read.
     */
    @Synchronized
    fun lookup(job: Job): CaseHistory? {
        val spec = job.experiment?.takeUnless { it.isScript } ?: return null
        runCatching { refresh() }.onFailure { println("estimates: history not read: ${it.message}") }
        val series = job.name.substringBefore('@')
        key(spec)?.let { byKey[it] }?.let { matches -> return (matches.firstOrNull { it.series == series } ?: matches.first()).history }
        if ('@' !in job.name) return null
        return runCatching { seriesRun(series) }.getOrNull()
    }

    private fun seriesRun(series: String): CaseHistory? {
        val at = clock()
        bySeries[series]?.takeIf { at - it.first < ttlMs }?.let { return it.second }
        val history = store.lastDoneOfSeries(series)?.let { id ->
            (runs[id]?.history ?: store.jobsByIds(listOf(id)).firstOrNull()?.let { Estimates.history(it, cores) })
                ?.copy(sameExperiment = false)
        }
        bySeries[series] = at to history
        return history
    }

    private fun refresh() {
        val at = clock()
        if (refreshedAt?.let { at - it < ttlMs } == true) return
        refreshedAt = at
        val ids = store.doneExperimentIds(limit)
        val fresh = ids.filter { it !in runs }
        for (job in store.jobsByIds(fresh)) {
            runs[job.id] = Run(job.experiment?.let(::key), job.name.substringBefore('@'), Estimates.history(job, cores))
        }
        runs.keys.retainAll(ids.toSet())
        byKey = ids.mapNotNull { id -> runs[id]?.takeIf { it.key != null && it.history != null } }.groupBy { it.key!! }
    }

    /** An experiment's fingerprint, which leaves out where it was placed to run, which taking a job changes. */
    private fun key(spec: ExperimentSpec): String? = runCatching { Experiments.fingerprint(spec) }.getOrNull()
}
