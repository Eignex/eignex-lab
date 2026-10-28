package com.eignex.lab.checker

import com.eignex.lab.Problem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files

/** One case as an adapter checks it: its full record, its original source files, and the final witness it kept. */
data class CheckCase(val record: JsonObject, val sources: SourceFiles, val witness: String,
                     val miniZincFrame: MiniZincFrame? = null) {
    fun field(name: String): String? = (record[name] as? JsonPrimitive)?.takeUnless { it.content == "null" }?.content
    val optimize: Boolean get() = field("kind") == "optimize"

    /** The claimed objective as exact text: the record's `exactObjective`, else an integral `objective`. */
    val claimedObjective: String? get() = field("exactObjective")
        ?: (record["stats"] as? JsonObject)?.let { (it["reportedExactObjective"] as? JsonPrimitive)?.content?.takeUnless { v -> v == "null" } }
        ?: field("objective")?.toBigDecimalOrNull()?.takeIf { it.stripTrailingZeros().scale() <= 0 }?.toBigInteger()?.toString()
}

/**
 * An adapter's verdict, with the checker version and binary it ran. A verdict another checker reached in the adapter's
 * place names that checker's [policy] and [checker]. [diagnostic]
 * is what the checker was asked and said, kept short.
 */
data class Verdict(
    val state: String,
    val reason: String,
    val version: String? = null,
    val sha256: String? = null,
    val policy: String? = null,
    val checker: String? = null,
    val diagnostic: String? = null,
    val resourceReceipts: List<ToolResourceReceipt> = emptyList(),
    val inputs: List<CheckedInput> = emptyList(),
)

/** One assignment or proof checker for a source format. Full solvers must never be used as checkers. */
interface FormatChecker {
    val format: String
    val policy: String
    /** The tool, for [LabCheck.checker]. */
    val checker: String

    /** Check [case] with scratch directory [work], which is deleted afterwards. Anything thrown is a check that could
     *  not run ([LabCheck.NOT_RUN]), with what was thrown as the reason; a checker that hangs past its time is too. */
    fun check(case: CheckCase, work: File): Verdict
}

/**
 * The dispatcher: picks the [adapters] entry for a case's format, finds its original source in the lab's corpus
 * ([locator]), and runs it over the final witness the case's record kept. Only a Klause record that claims a solution
 * is checked; anything else has no witness to check and gets no verdict. Every scratch file goes under [workRoot] and
 * is deleted when the check ends, whatever it decided. Whatever goes wrong while checking, here or in an adapter, is a
 * verdict of [LabCheck.NOT_RUN] with the reason, never a failure: the case keeps its record and status, and a check
 * that did not run leaves the solver's claim counting as it was reported ([com.eignex.lab.SourceValidation.rejects]).
 */
class SourceChecker(
    private val adapters: List<FormatChecker>,
    private val locator: SourceLocator,
    private val workRoot: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The formats the lab checks itself. */
    val formats: Set<String> get() = adapters.map { it.format }.toSet()

    fun covers(format: String): Boolean = adapters.any { it.format.equals(format, ignoreCase = true) }

    /** The lab's verdict on [record], a case of [problem] run at klause [revision]; null when there is no Klause
     *  solution claim to check or no checker for its format. */
    fun check(problem: Problem, record: JsonObject, revision: Revision? = null,
              miniZincFrame: MiniZincFrame? = null): LabCheck? {
        val adapter = adapters.firstOrNull { it.format.equals(problem.format, ignoreCase = true) } ?: return null
        if ((record["solver"] as? JsonPrimitive)?.content != KLAUSE) return null
        val stats = record["stats"] as? JsonObject
        val claimed = (record["feasible"] as? JsonPrimitive)?.content == "true" ||
            (stats?.get("reportedFeasible") as? JsonPrimitive)?.content == "true"
        if (!claimed) return null
        val started = clock()
        val witness = (record["finalWitness"] as? JsonPrimitive)?.takeUnless { it.content == "null" }?.content
        val hashes = (record["sourceHashes"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.content.orEmpty() }
            ?.filterValues { it.isNotEmpty() }.orEmpty()
        fun done(verdict: Verdict) = LabCheck(
            policy = verdict.policy ?: adapter.policy, state = verdict.state, reason = verdict.reason.take(REASON_CAP),
            checker = verdict.checker ?: adapter.checker, checkerVersion = verdict.version, checkerSha256 = verdict.sha256,
            witnessSha256 = witness?.let { sha256(it.toByteArray()) }, sourceSha256 = hashes, checkedAt = started,
            elapsedMs = clock() - started, diagnostic = verdict.diagnostic?.take(DIAGNOSTIC_CAP),
            resourceReceipts = verdict.resourceReceipts,
            inputs = verdict.inputs,
        )
        return try {
            run(adapter, problem, record, witness, hashes, revision, miniZincFrame)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Throwable) {
            if (e is VirtualMachineError && e !is StackOverflowError) throw e
            Verdict(LabCheck.NOT_RUN, "the checker could not run: ${e.message ?: e::class.simpleName}")
        }.let(::done)
    }

    /** [adapter]'s verdict on [record]'s [witness], in a scratch directory deleted afterwards with any source read
     *  into it. */
    private fun run(adapter: FormatChecker, problem: Problem, record: JsonObject, witness: String?, hashes: Map<String, String>,
                    revision: Revision?, miniZincFrame: MiniZincFrame?): Verdict {
        if (witness.isNullOrBlank()) {
            return Verdict(LabCheck.NOT_RUN, "the record kept no final witness (the bench keeps one of at most 8 MiB)")
        }
        workRoot.mkdirs()
        val work = Files.createTempDirectory(workRoot.toPath(), "check-").toFile()
        return try {
            val sources = locator.locate(problem, hashes, revision, work).getOrElse {
                return Verdict(LabCheck.NOT_RUN, "original source not found on the lab: ${it.message}")
            }
            adapter.check(CheckCase(record, sources, witness, miniZincFrame), work)
        } finally {
            work.deleteRecursively()
        }
    }

    companion object {
        const val KLAUSE = "klause"
    }
}
