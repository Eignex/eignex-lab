package com.eignex.lab.reference

import kotlin.math.abs
import kotlin.math.max

/** Native MPS result reporting shared by SCIP and HiGHS; assignments are retained without evaluation or repair. */
object MpsReference {
    const val VERSION = "mps-native-report-1"

    enum class Status { OPTIMAL, INFEASIBLE, LIMIT, UNKNOWN }

    data class Claim(val status: Status, val primal: Double?, val dual: Double?, val gap: Double?,
                     val assignment: Map<String, Double>?)

    /** The objective orientation declared in the MPS header, before any model rows. */
    fun maximize(source: String): Boolean {
        val header = source.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('*') }
            .takeWhile { it != "ROWS" }.toList()
        val at = header.indexOfFirst { it.substringBefore(' ').substringBefore('\t') == "OBJSENSE" }
        if (at < 0) return false
        val sense = header[at].removePrefix("OBJSENSE").trim().ifEmpty { header.getOrNull(at + 1).orEmpty() }
        return sense in setOf("MAX", "MAXIMIZE")
    }

    fun result(claim: Claim, maximize: Boolean, elapsedMs: Long, stdout: String, command: String,
               identity: String, options: String, reported: Map<String, String>): ReferenceResult {
        val infeasible = claim.status == Status.INFEASIBLE
        val feasible = when {
            infeasible -> false
            claim.primal != null && claim.status in setOf(Status.OPTIMAL, Status.LIMIT) -> true
            else -> null
        }
        // A solver's tolerance-limited optimal status can retain a nonzero reported primal/dual gap.
        val closed = claim.primal != null && claim.dual != null &&
            abs(claim.primal - claim.dual) <= max(1e-6, 1e-9 * max(abs(claim.primal), abs(claim.dual)))
        val stats = buildMap {
            putAll(reported)
            put("solveTime", (elapsedMs / 1000.0).toString())
            put("maximize", maximize.toString())
            put("referenceVersion", identity)
            put("options", options)
            put("claimedStatus", claim.status.name.lowercase())
            put("validation", "unchecked")
            claim.primal?.let { put("claimedPrimal", it.toString()) }
            claim.dual?.let { put("dualBound", it.toString()) }
            claim.gap?.let { put("gap", it.toString()) }
            if (claim.status == Status.OPTIMAL && !closed) put("proof", "gap-limited: reported primal and dual bounds differ")
        }
        return ReferenceResult(feasible = feasible, objective = claim.primal.takeIf { feasible == true },
            proven = infeasible || (feasible == true && claim.status == Status.OPTIMAL && closed),
            elapsedMs = elapsedMs, timeToBestMs = elapsedMs.takeIf { feasible == true },
            timeToFirstFeasibleMs = elapsedMs.takeIf { feasible == true }, stats = stats,
            rawOutput = stdout, command = command,
            assignment = claim.assignment?.entries?.joinToString("\n", postfix = "\n") { "${it.key} ${it.value}" })
    }
}
