package com.eignex.lab.reference

import com.eignex.lab.checker.ToolLimits
import com.eignex.lab.checker.ToolProcess
import com.eignex.lab.checker.ToolResourceReceipt
import com.eignex.lab.checker.sha256
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class MiniZincIdentity(val binary: File, val solver: String, val stats: Map<String, String>) {
    companion object {
        fun capture(requested: String, timeoutMs: Long): MiniZincIdentity {
            val binary = executable(System.getenv("LAB_MINIZINC") ?: "minizinc")
            val work = Files.createTempDirectory("minizinc-identity").toFile()
            val started = System.nanoTime()
            val receipts = ArrayList<ToolResourceReceipt>()
            fun query(vararg arguments: String): String {
                val remaining = timeoutMs - (System.nanoTime() - started) / 1_000_000
                check(remaining > 0) { "MiniZinc identity exceeded its preparation allowance" }
                val limit = minOf(remaining, 5_000)
                val run = ToolProcess.run(listOf(binary.path) + arguments, work, limit,
                    limits = ToolLimits((limit + 999) / 1000, limit), inputFiles = listOf(binary))
                check(run.exit == 0 && run.resources?.qualified() == true) { "MiniZinc identity query failed: ${run.tail()}" }
                receipts += checkNotNull(run.resources)
                return run.stdout.trim()
            }
            return try {
                val version = query("--version")
                val configuration = Json.parseToJsonElement(query("--solver-json", requested)) as JsonObject
                val stats = describe(version, sha256(binary), configuration) +
                    ("referenceIdentityResources" to Json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(ToolResourceReceipt.serializer()), receipts))
                MiniZincIdentity(binary, stats.getValue("referenceSolverId"), stats)
            } finally {
                work.deleteRecursively()
            }
        }

        internal fun describe(version: String, compilerHash: String, configuration: JsonObject,
                              hash: (File) -> String = ::sha256): Map<String, String> {
            fun field(name: String) = (configuration[name] as? JsonPrimitive)?.content.orEmpty()
            val id = field("id")
            val solverVersion = field("version")
            require(version.isNotBlank() && id.isNotBlank() && solverVersion.isNotBlank() && solverVersion != "unknown") {
                "MiniZinc and selected reference solver must report their versions"
            }
            val extra = configuration["extraInfo"] as? JsonObject
            val backend = (extra?.get("executable") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: field("executable").takeIf { it.isNotBlank() }
            val backendHash = backend?.let { hash(executable(it)) } ?: compilerHash
            val configHash = sha256(configuration.toString().toByteArray())
            return mapOf(
                "referenceVersion" to "$version|sha256=$compilerHash|solver=$id|version=$solverVersion|sha256=$backendHash|config=$configHash|minizinc-reference-v1",
                "referenceCompilerSha256" to compilerHash,
                "referenceSolverId" to id,
                "referenceSolverVersion" to solverVersion,
                "referenceSolverSha256" to backendHash,
                "referenceSolverConfiguration" to configuration.toString(),
            )
        }

        private fun executable(requested: String): File {
            val file = File(requested).takeIf { it.isAbsolute || it.parent != null }
                ?: System.getenv("PATH").orEmpty().split(File.pathSeparator)
                    .map { File(it, requested) }.firstOrNull { it.isFile && it.canExecute() }
                ?: error("MiniZinc executable not found: $requested")
            return file.canonicalFile.also { check(it.isFile && it.canExecute()) { "not executable: $it" } }
        }
    }
}
