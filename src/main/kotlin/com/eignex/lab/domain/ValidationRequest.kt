package com.eignex.lab.domain

/**
 * An experiment's request for source-witness validation (`param.source-validation=true`), as every scheduled solver run
 * makes it ([scheduled]). The lab consumes the request itself; what it decides is the analysis layer's SourceValidation.
 */
object ValidationRequest {
    const val PARAM = "param.source-validation"

    /**
     * Compressed stdout custody for the bench's MPS checks, which admitted 64 MiB of solver stdout instead of 8 MiB.
     * The lab checks MPS itself from the record's final witness, so it is never forwarded; scheduled runs still carry
     * it ([SCHEDULED]), so their fingerprints stay those of the runs before.
     */
    const val GZIP_PARAM = "param.source-validation-gzip"

    /** What every scheduled solver run carries in its base. */
    val SCHEDULED: Map<String, String> = mapOf(PARAM to "true", GZIP_PARAM to "true")

    /**
     * [spec] as a schedule runs it: a solver experiment with [SCHEDULED] in its base, overriding any config or grid
     * value of those keys, so every run and confirmation of a schedule asks for source validation whatever spec it was
     * saved with. A reference run, a profile and an analysis are left as they are. The request is part of the
     * [Experiments.fingerprint], so a checked run is never confirmed against an unchecked one; the trend leaves it out
     * ([Experiments.trendFingerprint]), since the check changes nothing the solver does, and marks each run instead.
     * Repository-owned bench schedules preserve their explicit [BenchSpec.sourceValidation] policy instead of
     * adding solver runtime options to the lab-owned base. Applying it twice changes nothing.
     */
    fun scheduled(spec: ExperimentSpec): ExperimentSpec {
        if (spec.work != Work.Recipe) return spec
        if (Experiments.arms(spec).any { it.values["backend"] == REFERENCE_BACKEND }) return spec
        val keys = SCHEDULED.keys
        return spec.copy(base = spec.base + SCHEDULED, configs = spec.configs.map { it - keys }, grid = spec.grid - keys)
    }

    /** [spec] without the validation request, wherever it was set: what the solver itself was asked to do. */
    fun unrequested(spec: ExperimentSpec): ExperimentSpec {
        spec.work.bench?.let { return spec.withWork(Work.Bench(it.copy(sourceValidation = false))) }
        val keys = SCHEDULED.keys
        return spec.copy(base = spec.base - keys, configs = spec.configs.map { it - keys }, grid = spec.grid - keys)
    }

    /** Whether an arm's [values] ask for source validation. */
    fun requested(values: Map<String, String>): Boolean = values[PARAM]?.toBooleanStrictOrNull() == true

    /** Whether [spec]'s first arm, the one a trend reads, asks for source validation. */
    fun requested(spec: ExperimentSpec): Boolean = spec.work.bench?.sourceValidation
        ?: (Experiments.arms(spec).firstOrNull()?.let { requested(it.values) } == true)

    /** The coordinator's check policy for a stored case, including repository-owned bench results. */
    fun requested(spec: ExperimentSpec?, values: Map<String, String>): Boolean = spec?.work?.bench?.sourceValidation ?: requested(values)
}
