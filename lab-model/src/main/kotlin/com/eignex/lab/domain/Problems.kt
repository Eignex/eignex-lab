package com.eignex.lab.domain

import kotlinx.serialization.Serializable

/** A portable workload and optional source files for independent reference solving and checking. */
@Serializable
data class Problem(
    val suite: String,
    val problem: String,
    val collection: String = "",
    val family: String = "",
    val format: String = "",
    val category: String = "",
    val model: String? = null,
    val data: String? = null,
    val includeDirs: List<String> = emptyList(),
    val benchCase: com.eignex.lab.bench.BenchCase? = null,
) {
    /** Source identity shared across runtime configurations, seeds and repeats. */
    val referenceKey: ProblemKey get() = ProblemKey.of(this)
}

/** The case at one index of an experiment's commands. */
data class Case(val problem: Int, val arm: Int, val seed: Long?, val repeat: Int = 0)

/**
 * The (collection, problem) a reference row, a feature row and a trend entry are kept under. A bench case's own
 * `problem` attribute names it when it has one, so a case is matched with the references of the instance it ran.
 */
@Serializable
data class ProblemKey(val collection: String, val problem: String) {
    override fun toString() = "$collection/$problem"

    companion object {
        fun of(problem: Problem) = ProblemKey(problem.collection, problem.benchCase?.attributes?.get("problem") ?: problem.problem)
    }
}
