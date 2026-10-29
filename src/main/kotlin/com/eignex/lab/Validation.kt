package com.eignex.lab

/**
 * How far a solver case's solution claim was checked against its original source, as its record says. Witness
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
    /**
     * A source check ran and could not decide: unsupported syntax, an incomplete witness, a checker limit or timeout.
     * The solver's claim counts as it reported it, as an unchecked one does ([SourceValidation.rejects]).
     */
    INDETERMINATE("check indeterminate"),
    /** The checker rejected the witness. */
    INVALID("invalid"),
}

/**
 * A check's verdict on a solution claim, as a record's `sourceValidation` or the lab's own check ([com.eignex.lab.checker.LabCheck])
 * states it. A record may carry a word the lab does not know, from a newer or older bench; it reads as no verdict.
 */
enum class SourceVerdict(val word: String) {
    /** The checker accepted the witness against the original source: the solution is valid. */
    VALID("valid"),
    /** The checker rejected the witness, or the claimed objective. */
    INVALID("invalid"),
    /** The checker ran and could not decide. */
    UNKNOWN("unknown"),
    /** The checker could not run: missing tool or source, a download that failed, a crash or a timeout. */
    NOT_RUN("not-run"),
    /** No external checker covers this claim. */
    UNSUPPORTED("unsupported");

    override fun toString() = word

    companion object {
        fun of(word: String?): SourceVerdict? = entries.firstOrNull { it.word == word }
    }
}

/**
 * Source-witness validation, asked for by an arm with `param.source-validation=true`, as every scheduled solver run
 * does ([scheduled]). The lab checks each case itself, with established external checkers ([com.eignex.lab.checker]),
 * and never passes the request on to the bench, which only solves and records the witness ([forBench]).
 */
object SourceValidation {
    const val PARAM = "param.source-validation"

    /**
     * Compressed stdout custody for the bench's MPS checks, which admitted 64 MiB of solver stdout instead of 8 MiB.
     * The lab checks MPS itself from the record's final witness, so it is never forwarded; scheduled runs still carry
     * it ([SCHEDULED]), so their fingerprints stay those of the runs before.
     */
    const val GZIP_PARAM = "param.source-validation-gzip"

    /** The policy of a record whose claims are the solver's own report. */
    const val UNCHECKED_POLICY = "reported-result-v1"

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
        if (spec.bench != null || spec.isProfile || spec.isAnalysis || spec.isScript) return spec
        if (Experiments.arms(spec).any { it.values["backend"] == REFERENCE_BACKEND }) return spec
        val keys = SCHEDULED.keys
        return spec.copy(base = spec.base + SCHEDULED, configs = spec.configs.map { it - keys }, grid = spec.grid - keys)
    }

    /** [spec] without the validation request, wherever it was set: what the solver itself was asked to do. */
    fun unrequested(spec: ExperimentSpec): ExperimentSpec {
        spec.bench?.let { return spec.copy(bench = it.copy(sourceValidation = false)) }
        val keys = SCHEDULED.keys
        return spec.copy(base = spec.base - keys, configs = spec.configs.map { it - keys }, grid = spec.grid - keys,
            bench = spec.bench?.copy(sourceValidation = false))
    }

    /** Whether an arm's [values] ask for source validation. */
    fun requested(values: Map<String, String>): Boolean = values[PARAM]?.toBooleanStrictOrNull() == true

    /** Whether [spec]'s first arm, the one a trend reads, asks for source validation. */
    fun requested(spec: ExperimentSpec): Boolean = spec.bench?.sourceValidation
        ?: (Experiments.arms(spec).firstOrNull()?.let { requested(it.values) } == true)

    /** The coordinator's check policy for a stored case, including repository-owned bench results. */
    fun requested(spec: ExperimentSpec?, values: Map<String, String>): Boolean = spec?.bench?.sourceValidation ?: requested(values)

    /**
     * An arm's [values] as a case passes them to the bench: without the validation request, which the lab consumes
     * itself. The bench then reports the solver's claim as it is (`reported-result-v1`), with the final witness the
     * lab checks.
     */
    fun forBench(values: Map<String, String>): Map<String, String> = values - PARAM - GZIP_PARAM

    /**
     * The one check verdict that changes how a solver result counts: a definite rejection of its witness. Every other
     * outcome of a check, accepted, undecided (`unknown`), not run (`not-run`, a checker that threw included),
     * `unsupported`, or none at all, leaves the solver's reported claim standing as it is: feasibility, proof,
     * objective and time to it, and so whether the case is solved. Such a check only adds a mark ([WitnessCheck]).
     * [Outcome.of] holds to this for the lab's verdicts and the bench's alike, so the trend, confirmation flips, the
     * scoreboard, the stats and comparisons, which all count through it, never move on a check that failed.
     */
    fun rejects(state: SourceVerdict?): Boolean = state == SourceVerdict.INVALID

    /** How far the lab's own verdict, of [state] ([com.eignex.lab.checker.LabCheck]), checked a claim: a check that
     *  could not run, or that no external checker covers, leaves the solver's report standing, unchecked. */
    fun labCheck(state: SourceVerdict?): WitnessCheck = when (state) {
        SourceVerdict.VALID -> WitnessCheck.ACCEPTED
        SourceVerdict.INVALID -> WitnessCheck.INVALID
        SourceVerdict.UNKNOWN -> WitnessCheck.INDETERMINATE
        SourceVerdict.NOT_RUN, SourceVerdict.UNSUPPORTED, null -> WitnessCheck.UNCHECKED
    }

    /**
     * The check behind a record's claims: its `sourceValidation` statistic when the bench checked (`valid`, `invalid`,
     * `unknown`), otherwise unchecked under [UNCHECKED_POLICY] or no policy at all, and indeterminate under a checking
     * policy that left no result. An `unknown` whose [reason] says the checker never ran ([checkerFault]) is unchecked.
     * Only the mark depends on it: any `unknown` keeps the solver's claim, which the bench's check withdrew ([rejects]).
     */
    fun check(sourceValidation: SourceVerdict?, policy: String?, reason: String? = null): WitnessCheck = when (sourceValidation) {
        SourceVerdict.VALID -> WitnessCheck.ACCEPTED
        SourceVerdict.INVALID -> WitnessCheck.INVALID
        SourceVerdict.UNKNOWN -> if (checkerFault(reason)) WitnessCheck.UNCHECKED else WitnessCheck.INDETERMINATE
        SourceVerdict.NOT_RUN, SourceVerdict.UNSUPPORTED, null ->
            if (policy == null || policy == UNCHECKED_POLICY) WitnessCheck.UNCHECKED else WitnessCheck.INDETERMINATE
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
     * Whether an `unknown` validation's [reason] says the source checker never ran or crashed ([CHECKER_FAULTS]). It
     * picks the mark alone: such a case reads `unchecked` with the reason, and a check that ran and could not decide
     * `check indeterminate`. Either way the lab keeps the claim the bench's check withdrew ([rejects], [Outcome.of]).
     */
    fun checkerFault(reason: String?): Boolean = reason != null && CHECKER_FAULTS.any { it in reason }
}
