package com.eignex.lab

/**
 * How far a Klause case's solution claim was checked against its original source, as its record says. Witness
 * checking speaks for the solution alone: an accepted witness is a valid solution, and says nothing of a proof of
 * optimality or infeasibility, which no witness check verifies.
 */
enum class WitnessCheck(val word: String) {
    /** The final witness was accepted against the original source: the solution is valid. */
    ACCEPTED("checked"),
    /**
     * The solver's own report: no source check was asked for or performed, as under `reported-result-v1`, or one was
     * asked for and the checker could not run ([SourceValidation.checkerFault]).
     */
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
     * saved with. A reference run, a profile and an analysis are left as they are. The request is part of the
     * [Experiments.fingerprint], so a checked run is never confirmed against an unchecked one; the trend leaves it out
     * ([Experiments.trendFingerprint]), since the check changes nothing the solver does, and marks each run instead.
     * Applying it twice changes nothing.
     */
    fun scheduled(spec: ExperimentSpec): ExperimentSpec {
        if (spec.isProfile || spec.isAnalysis || spec.isScript) return spec
        if (Experiments.arms(spec).any { it.values["backend"] == REFERENCE_BACKEND }) return spec
        val keys = SCHEDULED.keys
        return spec.copy(base = spec.base + SCHEDULED, configs = spec.configs.map { it - keys }, grid = spec.grid - keys)
    }

    /** [spec] without the validation request, wherever it was set: what the solver itself was asked to do. */
    fun unrequested(spec: ExperimentSpec): ExperimentSpec {
        val keys = SCHEDULED.keys
        return spec.copy(base = spec.base - keys, configs = spec.configs.map { it - keys }, grid = spec.grid - keys)
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
     * policy that left no result. An `unknown` whose [reason] says the checker never ran ([checkerFault]) is unchecked.
     */
    fun check(sourceValidation: String?, policy: String?, reason: String? = null): WitnessCheck = when (sourceValidation) {
        "valid" -> WitnessCheck.ACCEPTED
        "invalid" -> WitnessCheck.INVALID
        "unknown" -> if (checkerFault(reason)) WitnessCheck.UNCHECKED else WitnessCheck.INDETERMINATE
        else -> if (policy == null || policy == UNCHECKED_POLICY) WitnessCheck.UNCHECKED else WitnessCheck.INDETERMINATE
    }

    /**
     * The parts of an `unknown` validation's reason the bench writes when the checker itself could not run or crashed,
     * rather than when it ran and could not decide: the MIPLIB solchecker missing from the bench jar (`missing packaged
     * MIPLIB solchecker`, from a bench built without [MPS_CHECKER_BUILD]) or failing its own integrity checks, a checker
     * executable that would not start (Java's `Cannot run program`), and an XCSP3 checker process that exited without a
     * verdict. The record has no structured field for this, so the reason decides. `source checking failed:` alone is no
     * sign: the bench writes it before an unsupported MPS input or a number the checker cannot read, which is a check
     * that ran and could not decide.
     */
    private val CHECKER_FAULTS = listOf(
        "missing packaged ",
        "packaged checker exceeds limit",
        "checker executable changed or cleanup failed",
        "Cannot run program ",
        "source checker failed: ",
        "malformed source checker result",
    )

    /**
     * Whether an `unknown` validation's [reason] says the source checker never ran or crashed ([CHECKER_FAULTS]). Such
     * a case is the solver's own report, unchecked: the lab keeps the claim the check withdrew ([Outcome.of]) and shows
     * the reason, where a check that ran and could not decide stays indeterminate.
     */
    fun checkerFault(reason: String?): Boolean = reason != null && CHECKER_FAULTS.any { it in reason }

    /**
     * The Gradle property that packages the MIPLIB solchecker into the bench jar. Since klause b417cf18b packaging is
     * opt-in, and a bench built without it ends every MPS check `unknown` with `missing packaged MIPLIB solchecker`.
     * Commits from before the property packaged the checker unconditionally, and Gradle ignores a project property no
     * build script reads.
     */
    const val MPS_CHECKER_BUILD = "-Pbench.mpsSolchecker"
}
