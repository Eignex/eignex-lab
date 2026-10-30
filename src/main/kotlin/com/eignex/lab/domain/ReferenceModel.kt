package com.eignex.lab.domain

import com.eignex.lab.util.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import com.eignex.lab.analysis.ReferenceVerdict

/** A reference solver's verdict on one problem, as a reference arm's case records it ([References.of]). */
@Serializable
data class Reference(
    val solver: String,
    val maximize: Boolean,
    val objective: Double?,
    /** true: a solution; false: proved infeasible; null: undecided within its budget. */
    val feasible: Boolean?,
    val proven: Boolean,
    val elapsedMs: Long,
    val budgetMs: Long,
    /** How the solver ran and its claim was judged, where that decides whether the row still holds: an MPS reference's
     *  build, options and validation rules. A row produced another way replaces this one ([References.replaces]). */
    val version: String = "",
    /** How the solution was checked against the model: `valid`, `repaired`, or why it was not (an MPS reference). */
    val validation: String? = null,
    /** The solver's dual bound, which a proof rests on: one that excludes another solver's solution disputes it. */
    val dualBound: Double? = null,
    /** From before the solver's claims were checked: kept as evidence, never trusted. */
    val stale: Boolean = false,
    /** Which source files the verdict is on, as [References.identity] digests a record's `sourceHashes`: null for an
     *  unbound row, from before the lab kept it, which only counts where the source is unknown too. */
    val sourceIdentity: String? = null,
) {
    init {
        requireConsistent(feasible, objective, proven)
    }

    /** What the row decided ([Decision]). */
    val decision: Decision get() = Decision.of(feasible, objective, proven)
}

/**
 * What a solve decided, whatever produced it: nothing within its budget, that the problem has no solution, or a
 * solution, with its objective when it has one and whether it is proven optimal. A record's `feasible`, `objective` and
 * `proven` can say two of these at once; a [Reference] or an [com.eignex.lab.analysis.Outcome] cannot
 * ([requireConsistent]), so its verdict is always one of them.
 */
sealed interface Decision {
    data object Undecided : Decision

    /** Proved to have no solution: proven by what it is, with no objective. */
    data object Infeasible : Decision

    data class Feasible(val objective: Double?, val proven: Boolean) : Decision

    companion object {
        fun of(feasible: Boolean?, objective: Double?, proven: Boolean): Decision = when (feasible) {
            null -> Undecided
            false -> Infeasible
            true -> Feasible(objective, proven)
        }
    }
}

/** An undecided solve proves nothing, and an infeasible one has no objective. */
internal fun requireConsistent(feasible: Boolean?, objective: Double?, proven: Boolean) {
    require(feasible != null || !proven) { "an undecided result cannot be proven" }
    require(feasible != false || objective == null) { "an infeasible result has no objective" }
}

/** One collection's rows from one reference solver. */
@Serializable
data class ReferenceCoverage(
    val collection: String,
    val solver: String,
    val rows: Int,
    val decided: Int,
    val proven: Int,
    val infeasible: Int,
    val updatedAt: Long,
)

/** One lab case of a problem, as its inspection page lists it. */
@Serializable
data class ProblemRun(
    val job: Long,
    val jobName: String,
    val createdAt: Long,
    val arm: String,
    val sha: String,
    val case: Int,
    val seed: Long? = null,
    val repeat: Int = 0,
    val status: Status,
    val record: JsonElement? = null,
)

/** Which reference rows to show: every set field must hold; [text] matches the problem or the collection. */
data class ReferenceFilter(
    val text: String? = null,
    val solver: String? = null,
    val collection: String? = null,
    val verdict: ReferenceVerdict? = null,
) {
    val isEmpty: Boolean get() = text == null && solver == null && collection == null && verdict == null
}

/** Arms that contradict each other on one problem, which at least one of them got wrong. */
@Serializable
data class Disagreement(val problem: Problem, val reason: String)

/** What the classifier says about a problem's source: its themes (`scheduling`, `mip`, …), structure and logic. */
@Serializable
data class ProblemFeatures(
    val collection: String,
    val problem: String,
    val themes: List<String> = emptyList(),
    val structure: String? = null,
    val logic: String? = null,
)
