package com.eignex.lab

/**
 * How far a Klause case's solution claim was checked against its original source, as its record says. Witness
 * checking speaks for the solution alone: an accepted witness is a valid solution, and says nothing of a proof of
 * optimality or infeasibility, which no witness check verifies.
 */
enum class WitnessCheck(val word: String) {
    /** The final witness was accepted against the original source: the solution is valid. */
    ACCEPTED("checked"),
    /** The solver's own report: no source check was asked for or performed, as under `reported-result-v1`. */
    UNCHECKED("unchecked"),
    /** A source check ran and could not decide: unsupported syntax, an incomplete witness, a checker limit or timeout. */
    INDETERMINATE("check indeterminate"),
    /** The checker rejected the witness. */
    INVALID("invalid"),
}

/**
 * The independent source-witness validation klause-bench performs when an arm asks for it with
 * `param.source-validation=true`: MiniZinc integer, XCSP3, MPS and SMT-LIB sources, each under its own validation
 * policy. Every scheduled Klause run asks for it ([scheduled]); the lab passes the request only to cases whose format
 * the bench checks ([forCase]), since the bench fails a case of any other format that asks.
 */
object SourceValidation {
    const val PARAM = "param.source-validation"

    /**
     * Compressed stdout custody for MPS checks: the plain policy leaves a point unchecked once the solver's stdout,
     * which holds every incumbent it printed, passes 8 MiB; this one admits 64 MiB decoded. The bench accepts it only
     * on a Klause MPS case that also asks for [PARAM].
     */
    const val GZIP_PARAM = "param.source-validation-gzip"

    /** The [Problem.format]s the bench checks, as `klause-bench select` names them. */
    val FORMATS = setOf("MINIZINC", "XCSP3", "MPS", "SMTLIB")
    private const val GZIP_FORMAT = "MPS"

    /** The policy of a record whose claims are the solver's own report. */
    const val UNCHECKED_POLICY = "reported-result-v1"

    /** What every scheduled Klause run carries in its base. */
    val SCHEDULED: Map<String, String> = mapOf(PARAM to "true", GZIP_PARAM to "true")

    /**
     * [spec] as a schedule runs it: a Klause experiment with [SCHEDULED] in its base, overriding any config or grid
     * value of those keys, so every run and confirmation of a schedule asks for source validation whatever spec it was
     * saved with. A reference run, a profile and an analysis are left as they are. The request is measured: it is part
     * of the [Experiments.fingerprint], so the first checked run of a schedule starts a new experiment in its trend and
     * is never compared with an unchecked one. Applying it twice changes nothing.
     */
    fun scheduled(spec: ExperimentSpec): ExperimentSpec {
        if (spec.isProfile || spec.isAnalysis || spec.isScript) return spec
        if (Experiments.arms(spec).any { it.values["backend"] == REFERENCE_BACKEND }) return spec
        val keys = SCHEDULED.keys
        return spec.copy(base = spec.base + SCHEDULED, configs = spec.configs.map { it - keys }, grid = spec.grid - keys)
    }

    /** Whether an arm's [values] ask for source validation. */
    fun requested(values: Map<String, String>): Boolean = values[PARAM]?.toBooleanStrictOrNull() == true

    /** Whether [spec]'s first arm, the one a trend reads, asks for source validation. */
    fun requested(spec: ExperimentSpec): Boolean = Experiments.arms(spec).firstOrNull()?.let { requested(it.values) } == true

    /**
     * An arm's [values] as a case of [format] passes them to the bench: without the validation keys for a format the
     * bench does not check, and without [GZIP_PARAM] except on MPS. Such a case runs unchecked, as its record's
     * `reported-result-v1` says, instead of failing; a problem with no recorded format counts as unchecked.
     */
    fun forCase(values: Map<String, String>, format: String): Map<String, String> = when (format.uppercase()) {
        !in FORMATS -> values - PARAM - GZIP_PARAM
        GZIP_FORMAT -> values
        else -> values - GZIP_PARAM
    }

    /**
     * The check behind a record's claims: its `sourceValidation` statistic when the bench checked (`valid`, `invalid`,
     * `unknown`), otherwise unchecked under [UNCHECKED_POLICY] or no policy at all, and indeterminate under a checking
     * policy that left no result.
     */
    fun check(sourceValidation: String?, policy: String?): WitnessCheck = when (sourceValidation) {
        "valid" -> WitnessCheck.ACCEPTED
        "invalid" -> WitnessCheck.INVALID
        "unknown" -> WitnessCheck.INDETERMINATE
        else -> if (policy == null || policy == UNCHECKED_POLICY) WitnessCheck.UNCHECKED else WitnessCheck.INDETERMINATE
    }
}
