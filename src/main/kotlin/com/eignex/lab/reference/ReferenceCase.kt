package com.eignex.lab.reference

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant

/**
 * One reference case: a reference solver on one instance, written to [out] as the record a lab case keeps
 * (`<problem>.json`, in the solver-v1 record schema, which [com.eignex.lab.References.of] reads), its raw
 * output (`<problem>.out`), and the solution a solver returned (`<problem>.sol`). A failing run is an `ERROR` record
 * with why under `stats`, so the case still ends with a record.
 */
internal class ReferenceCase(
    private val instance: Instance,
    private val budget: Budget,
    private val solver: String?,
    private val processors: Int,
    private val seed: Long,
    private val free: Boolean,
    private val out: File,
) {
    fun run(solve: () -> ReferenceSolve.Run = { ReferenceSolve.run(instance, budget, solver, processors, seed, free) }):
        JsonObject {
        out.mkdirs()
        val name = instance.name.replace('/', '_')
        val timestamp = Instant.now().toString()
        var hashes: Map<String, String> = emptyMap()
        val record = runCatching {
            hashes = CorpusFiles.sourceHashes(instance)
            val run = solve()
            check(hashes == CorpusFiles.sourceHashes(instance)) { "reference source changed during solve" }
            val r = run.result
            r.assignment?.let { File(out, "$name.sol").writeText(it) }
            File(out, "$name.out").writeText(r.rawOutput)
            record(
                solverId = run.solver, timestamp = timestamp, hashes = hashes,
                kind = if (run.optimize) "optimize" else "satisfy", maximize = run.maximize, result = r,
                finalWitness = if (r.feasible == true) Witness.final(instance.format, r.rawOutput) else null,
                outputSha256 = sha256(r.rawOutput.toByteArray()),
            )
        }.getOrElse { failure ->
            System.err.println("reference ERROR [${instance.name}]: ${failure.message ?: failure::class.simpleName}")
            val failed = ReferenceResult(
                feasible = null, objective = null, timeToBestMs = null, proven = false,
                stats = mapOf("loadError" to reason(failure)) + failureStats(failure),
                rawOutput = "", command = "ERROR",
            )
            record(
                solverId = solver ?: runCatching { ReferenceSolve.solverIdFor(instance.format) }.getOrDefault("unknown"),
                timestamp = timestamp, hashes = hashes, kind = kindOnFailure(), maximize = false, result = failed,
                finalWitness = null, outputSha256 = null,
            )
        }
        File(out, "$name.json").writeText(record.toString())
        return record
    }

    private fun record(
        solverId: String,
        timestamp: String,
        hashes: Map<String, String>,
        kind: String,
        maximize: Boolean,
        result: ReferenceResult,
        finalWitness: String?,
        outputSha256: String?,
    ): JsonObject = buildJsonObject {
        put("problem", instance.name)
        put("solver", solverId)
        put("engine", JsonNull)
        put("processors", processors)
        put("search", if (free) "free" else "fixed")
        put("seed", seed)
        put("budgetMs", budget.timeoutMillis)
        put("kind", kind)
        put("maximize", maximize)
        put("feasible", result.feasible)
        put("objective", result.objective)
        put("timeToBestMs", result.timeToBestMs)
        put("timeToFirstFeasibleMs", result.timeToFirstFeasibleMs)
        put("proven", result.proven)
        put("stats", JsonObject(result.stats.mapValues { JsonPrimitive(it.value) }))
        put("attribution", buildJsonArray { })
        put("timestamp", timestamp)
        put("command", result.command)
        put("validationPolicy", REPORTED_RESULT_POLICY)
        put("elapsedMs", result.elapsedMs)
        put("sourceHashes", JsonObject(hashes.mapValues { JsonPrimitive(it.value) }))
        put("finalWitness", finalWitness)
        put("sourceOutputSha256", outputSha256)
        put("exactObjective", result.exactObjective)
    }

    /** The kind of a run that failed before a solver could say: optimisation for the formats that always are. */
    private fun kindOnFailure(): String = when (instance.format) {
        Format.MPS, Format.OPB, Format.WCNF -> "optimize"
        Format.MINIZINC -> if (runCatching { MiniZincReference.solveKind(instance).first }.getOrDefault(false)) {
            "optimize"
        } else {
            "satisfy"
        }
        else -> "satisfy"
    }

    private fun reason(failure: Throwable): String =
        (failure.message ?: failure::class.simpleName.orEmpty()).lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() }.joinToString(" / ").take(REASON_CAP)

    /** The failure and its causes, each class and message under its own key, as the suite records them. */
    private fun failureStats(failure: Throwable): Map<String, String> = buildMap {
        put("errorClass", failure::class.qualifiedName ?: failure::class.toString())
        failure.message?.let { put("errorMessage", it.take(REASON_CAP)) }
        generateSequence(failure.cause) { it.cause }.take(MAX_CAUSES).forEachIndexed { index, cause ->
            put("errorCause${index + 1}Class", cause::class.qualifiedName ?: cause::class.toString())
            cause.message?.let { put("errorCause${index + 1}Message", it.take(REASON_CAP)) }
        }
    }

    companion object {
        /** A reference verdict is the solver's own claim; the lab judges it against the other references. */
        const val REPORTED_RESULT_POLICY = "reported-result-v1"
        private const val REASON_CAP = 500
        private const val MAX_CAUSES = 4
    }
}
