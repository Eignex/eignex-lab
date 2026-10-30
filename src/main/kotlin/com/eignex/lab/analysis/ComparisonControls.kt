package com.eignex.lab.analysis

import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.eignex.lab.worker.ComparisonExecution

/** Qualification of revision comparisons; missing historical controls remain unavailable. */
internal object ComparisonControls {
    fun seed(case: CaseResult): Long? = case.seed ?: text(case.record as? JsonObject, "seed")?.toLongOrNull()

    fun block(case: CaseResult): Triple<Long?, Int, Int> = Triple(seed(case),
        case.problem.benchCase?.attributes?.get("repeat")?.toIntOrNull() ?: 0, case.repeat)

    fun problem(case: CaseResult): Pair<String, String> = case.problem.suite to
        (case.problem.benchCase?.attributes?.get("problem") ?: case.problem.problem)

    fun mismatch(a: CaseResult, b: CaseResult): String? {
        val left = a.record as? JsonObject ?: return "missing record"
        val right = b.record as? JsonObject ?: return "missing record"
        val controlled = "comparisonControls" in left || "comparisonControls" in right ||
            text(left, "solver") == "klause" || text(right, "solver") == "klause"
        if (!controlled) return null
        val x = left["comparisonControls"] as? JsonObject ?: return "historical controls unavailable"
        val y = right["comparisonControls"] as? JsonObject ?: return "historical controls unavailable"
        if (text(x, "policy") != "matched-source-v1" || text(y, "policy") != "matched-source-v1") return "unknown comparison policy"
        if (REQUIRED.any { text(x, it).isNullOrBlank() || text(y, it).isNullOrBlank() }) return "incomplete controls"
        if (x != y) return "runtime, source dependency, seed or allowance migration"
        val execution = ComparisonExecution.frame(left) ?: return "physical execution controls unavailable"
        val otherExecution = ComparisonExecution.frame(right) ?: return "physical execution controls unavailable"
        if (execution != otherExecution) return "physical execution migration"
        if (checkerPolicy(left) != checkerPolicy(right)) return "source checking policy migration"
        if (seed(a)?.toString() != text(x, "solverSeed") || seed(b)?.toString() != text(y, "solverSeed")) return "inconsistent seed"
        if (text(left, "seed") != text(x, "solverSeed") || text(right, "seed") != text(y, "solverSeed")) return "inconsistent recorded seed"
        for ((recordKey, controlKey) in listOf("budgetMs" to "solverBudgetMs", "processors" to "cpus")) {
            if (text(left, recordKey) != text(x, controlKey) || text(right, recordKey) != text(y, controlKey)) return "inconsistent effective control"
        }
        val hashes = left["sourceHashes"] as? JsonObject
        if (hashes.isNullOrEmpty() || hashes != right["sourceHashes"]) return "source identity unavailable or changed"
        if (a.problem.format == "MINIZINC" || b.problem.format == "MINIZINC") {
            if (text(x, "compilerFingerprint").isNullOrBlank()) return "compiler identity unavailable"
            val compiled = left["compiledInputHashes"] as? JsonObject ?: return "compiled identity unavailable"
            val other = right["compiledInputHashes"] as? JsonObject ?: return "compiled identity unavailable"
            if (listOf("flatzinc", "outputMapping", "flatzincContentV1").any { text(compiled, it).isNullOrBlank() ||
                    text(other, it).isNullOrBlank() }) return "compiled identity unavailable"
            if (listOf("outputMapping", "flatzincContentV1").any { compiled[it] != other[it] }) return "compiled artifact migration"
        }
        return null
    }

    fun series(case: CaseResult): String {
        val record = case.record as? JsonObject
        val controls = record?.get("comparisonControls") as? JsonObject
        return listOfNotNull(text(controls, "series"), text(controls, "runtimeFingerprint"), text(controls, "compilerFingerprint"),
            text(controls, "harnessFingerprint") ?: "harness-unqualified", text(controls, "libraryFingerprint") ?: "libraries-unqualified")
            .takeIf { text(controls, "policy") == "matched-source-v1" }
            ?.plus(record?.let(ComparisonExecution::series) ?: "execution-unqualified")
            ?.plus(record?.let(::checkerPolicy) ?: "checking-unrequested")?.joinToString(":") ?: "historical-unqualified"
    }

    private fun checkerPolicy(record: JsonObject): String? = text(record["labCheck"] as? JsonObject, "policy")

    private fun text(value: JsonObject?, key: String) = (value?.get(key) as? JsonPrimitive)?.content?.takeUnless { it == "null" }
    private val REQUIRED = setOf("series", "solverSeed", "compilerSeed", "solverBudgetMs", "compilerTimeoutSeconds",
        "caseTimeoutSeconds", "cpus", "memoryMiB", "heap-mib", "gc", "debug", "measurement", "exact",
        "complete-assignment", "deadlinePolicy", "runtimeFingerprint", "harnessFingerprint", "libraryFingerprint", "inputDigest")
}
