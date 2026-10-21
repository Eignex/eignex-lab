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
     * The solver's own report: no source check was asked for or performed, as under `reported-result-v1`; one was
     * asked for and the checker could not run ([SourceValidation.checkerFault], a lab check `not-run`); or no external
     * checker covers the claim (a lab check `unsupported`).
     */
    UNCHECKED("unchecked"),
    /** A source check ran and could not decide: unsupported syntax, an incomplete witness, a checker limit or timeout. */
    INDETERMINATE("check indeterminate"),
    /** The checker rejected the witness. */
    INVALID("invalid"),
}

/**
 * Source-witness validation, asked for by an arm with `param.source-validation=true`, as every scheduled Klause run
 * does ([scheduled]). The lab checks the formats in [LAB_FORMATS] itself after each case, with established external
 * checkers ([com.eignex.lab.checker]), and does not pass the request on to the bench for them; the bench still checks
 * the other formats in [FORMATS] it was asked to, until the lab checks them too. The bench fails a case of any other
 * format that asks, so the request reaches only the formats it checks ([forCase]).
 */
object SourceValidation {
    const val PARAM = "param.source-validation"

    /**
     * Compressed stdout custody for the bench's MPS checks, which admitted 64 MiB of solver stdout instead of 8 MiB.
     * The lab checks MPS itself from the record's final witness, so it is never forwarded; scheduled runs still carry
     * it ([SCHEDULED]), so their fingerprints stay those of the runs before.
     */
    const val GZIP_PARAM = "param.source-validation-gzip"

    /** The [Problem.format]s the bench checks, as `klause-bench select` names them. */
    val FORMATS = setOf("MINIZINC", "XCSP3", "MPS", "SMTLIB")

    /** The [Problem.format]s the lab checks itself ([com.eignex.lab.checker.SourceChecker]): the request stays with the
     *  lab for them and never reaches the bench. */
    val LAB_FORMATS = setOf("XCSP3", "MPS")

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
     * lab checks itself ([LAB_FORMATS]) or the bench does not check, and never with [GZIP_PARAM]. The bench then
     * reports the solver's claim as it is (`reported-result-v1`), for the lab to check or to leave unchecked; a problem
     * with no recorded format counts as unchecked.
     */
    fun forCase(values: Map<String, String>, format: String): Map<String, String> = when (format.uppercase()) {
        !in FORMATS, in LAB_FORMATS -> values - PARAM - GZIP_PARAM
        else -> values - GZIP_PARAM
    }

    /** How far the lab's own verdict, of [state] ([com.eignex.lab.checker.LabCheck]), checked a claim: a check that
     *  could not run, or that no external checker covers, leaves the solver's report standing, unchecked. */
    fun labCheck(state: String?): WitnessCheck = when (state) {
        "valid" -> WitnessCheck.ACCEPTED
        "invalid" -> WitnessCheck.INVALID
        "unknown" -> WitnessCheck.INDETERMINATE
        else -> WitnessCheck.UNCHECKED
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
     * MIPLIB solchecker`, from a bench built without `-Pbench.mpsSolchecker`) or failing its own integrity checks, a checker
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
}
