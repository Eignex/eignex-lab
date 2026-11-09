package com.eignex.lab.reference

import com.eignex.lab.checker.ToolProcess
import com.eignex.lab.checker.sha256
import java.io.File
import java.nio.file.Files

/** The executable or immutable image used by a reference solve, with its captured build identity. */
data class ReferenceIdentity(val executable: String, val stats: Map<String, String>) {
    companion object {
        fun native(requested: String, timeoutMs: Long): ReferenceIdentity {
            val started = System.nanoTime()
            val binary = executable(requested)
            val hash = sha256(binary)
            val version = query(listOf(binary.path, "--version"), remaining(started, timeoutMs)).trim()
            check(version.isNotBlank() && version.lowercase() !in setOf("unknown", "null", "unavailable")) {
                "$requested did not report its version"
            }
            check(hash == sha256(binary)) { "$requested changed during identity capture" }
            remaining(started, timeoutMs)
            return ReferenceIdentity(binary.path, mapOf(
                "referenceVersion" to "$version|sha256=$hash",
                "referenceSolverVersion" to version,
                "referenceSolverSha256" to hash,
                "referenceBinary" to binary.path,
            ))
        }

        fun image(requested: String, timeoutMs: Long, docker: String = "docker"): ReferenceIdentity {
            val id = query(listOf(docker, "image", "inspect", "--format", "{{.Id}}", requested), timeoutMs).trim()
            check(id.matches(Regex("sha256:[a-f0-9]{64}"))) { "$requested did not resolve to an immutable image" }
            return ReferenceIdentity(id, mapOf(
                "referenceVersion" to "$requested@$id",
                "referenceImageId" to id,
            ))
        }

        internal fun executable(requested: String): File {
            val binary = File(requested).takeIf { it.isAbsolute || it.parent != null }
                ?: System.getenv("PATH").orEmpty().split(File.pathSeparator)
                    .map { File(it, requested) }.firstOrNull { it.isFile && it.canExecute() }
                ?: error("reference executable not found: $requested")
            return binary.canonicalFile.also { check(it.isFile && it.canExecute()) { "not executable: $it" } }
        }

        private fun query(command: List<String>, timeoutMs: Long): String {
            check(timeoutMs > 0) { "reference identity exhausted the case budget" }
            val work = Files.createTempDirectory("reference-identity").toFile()
            return try {
                val run = ToolProcess.run(command, work, minOf(timeoutMs, 5_000))
                check(run.exit == 0 && !run.timedOut) { "reference identity query failed: ${run.tail()}" }
                run.stdout
            } finally {
                work.deleteRecursively()
            }
        }

        private fun remaining(started: Long, timeoutMs: Long): Long =
            (timeoutMs - (System.nanoTime() - started) / 1_000_000).also {
                check(it > 0) { "reference identity exhausted the case budget" }
            }
    }
}
