package com.eignex.lab

/** Timeout bounds for display estimates; these never launch or retire a worker. */
object WorkEstimates {
    fun caseCoreHours(arm: Arm, profile: Boolean): Double {
        val ms = if (profile) Experiments.profileTimeoutSec(arm) * MS_PER_SEC else arm.timeoutMs
        return ms * arm.cores / MS_PER_HOUR
    }

    /** The mean core-hours of one case of [spec]: the arms alternate within a problem, so each takes an equal share. */
    fun meanCaseCoreHours(spec: ExperimentSpec): Double {
        val arms = Experiments.arms(spec)
        return arms.sumOf { caseCoreHours(it, spec.isProfile) } / arms.size
    }

    /**
     * Cases [spec] is expected to run before it is planned, when the selection is not known yet: per selection its
     * `max`, else its `per-family` over [ASSUMED_FAMILIES] families but at most [ASSUMED_OPEN_SELECTION] (a
     * `per-family` of 1000000 means "all of it", not millions), else [ASSUMED_SELECTION]; a selection naming one
     * problem (`name`) and an exact problem list count as they are. Times the arms, seeds and repeats. A profile job runs one case.
     */
    fun estimatedCases(spec: ExperimentSpec): Int {
        if (spec.isProfile) return 1
        val problems = spec.problemList.size.takeIf { it > 0 } ?: spec.problems.sumOf { selection ->
            if ("name" in selection) return@sumOf 1
            val max = selection["max"]?.toIntOrNull()
            val perFamily = selection["per-family"]?.toIntOrNull()?.times(ASSUMED_FAMILIES)
            max?.let { minOf(it, perFamily ?: it) } ?: perFamily?.let { minOf(it, ASSUMED_OPEN_SELECTION) } ?: ASSUMED_SELECTION
        }
        return problems * Experiments.arms(spec).size * maxOf(1, spec.seeds.size) * spec.repeats
    }

    /** Core-hours left of a queued job by each case's whole timeout: its queued cases when it was planned
     *  ([queuedCases]), its estimate otherwise. The upper bound, which [Estimates] fall back on. */
    fun coreHours(spec: ExperimentSpec, queuedCases: Int?): Double =
        (queuedCases ?: estimatedCases(spec)) * meanCaseCoreHours(spec)

    private const val MS_PER_SEC = 1000L
    private const val MS_PER_HOUR = 3_600_000.0
    private const val ASSUMED_FAMILIES = 10
    private const val ASSUMED_OPEN_SELECTION = 200
    private const val ASSUMED_SELECTION = 20
}
