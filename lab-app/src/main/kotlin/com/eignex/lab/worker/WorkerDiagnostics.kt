package com.eignex.lab.worker

import com.eignex.lab.domain.AttemptOwner
import com.eignex.lab.domain.safeRelative
import com.eignex.lab.store.ExecutionStore
import com.eignex.lab.store.Store
import com.eignex.lab.util.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.Base64

@Serializable internal data class WorkerDiagnosticFile(val originalBytes: Long, val base64: String)
@Serializable internal data class WorkerDiagnosticFiles(val files: Map<String, WorkerDiagnosticFile>, val limited: Boolean)

/** Failed-attempt evidence has its own immutable directory and never enters result acceptance or projection. */
internal class WorkerDiagnostics(private val store: Store, private val directory: (Long) -> Path) {
    fun retain(owner: AttemptOwner, body: WorkerDiagnosticFiles) {
        val (job, indices) = store.execution.authorizeDeadlineDiagnostics(owner)
        require(body.files.size <= MAX_FILES)
        var total = 0
        val files = body.files.mapValues { (name, file) ->
            safeRelative(name)
            require(name.length <= 1024 && allowed(name, indices)) { "diagnostic file outside the failed row" }
            require(file.base64.length <= (FILE_BYTES + 2) / 3 * 4)
            val bytes = Base64.getDecoder().decode(file.base64)
            require(bytes.size <= FILE_BYTES && file.originalBytes >= bytes.size)
            total = Math.addExact(total, bytes.size); require(total <= TOTAL_BYTES)
            bytes
        }
        val requestSha = ExecutionStore.digest(Json.encodeToString(body))
        val destination = path(directory(job), owner)
        synchronized(fileLockFor(destination)) {
            if (Files.exists(destination)) {
                val previous = Json.parseToJsonElement(Files.readString(destination.resolve("receipt.json"))).jsonObject
                require(previous["requestSha256"]?.jsonPrimitive?.content == requestSha) { "diagnostic retry differs" }
                return
            }
            Files.createDirectories(destination.parent)
            val staging = Files.createTempDirectory(destination.parent, ".diagnostics-")
            try {
                for ((name, bytes) in files) {
                    val target = staging.resolve(name)
                    Files.createDirectories(target.parent); Files.write(target, bytes)
                }
                val receipt = buildJsonObject {
                    put("policy", "failed-attempt-diagnostics-v1"); put("accepted", false); put("reason", "run-timeout")
                    put("session", owner.session); put("iteration", owner.iteration); put("generation", owner.generation)
                    put("allocation", owner.allocation); put("requestSha256", requestSha); put("limited", body.limited)
                    putJsonObject("files") { files.forEach { (name, bytes) ->
                        putJsonObject(name) {
                            put("originalBytes", body.files.getValue(name).originalBytes); put("retainedBytes", bytes.size)
                            put("truncated", bytes.size.toLong() < body.files.getValue(name).originalBytes)
                        }
                    } }
                }
                Files.writeString(staging.resolve("receipt.json"), receipt.toString())
                // Recheck after IO: an expired upload never publishes evidence outside its finalization window.
                store.execution.authorizeDeadlineDiagnostics(owner)
                Files.move(staging, destination, ATOMIC_MOVE)
            } finally { deleteTree(staging) }
        }
    }

    companion object {
        const val FILE_BYTES = 1024 * 1024
        const val TOTAL_BYTES = 16 * 1024 * 1024
        const val WIRE_BYTES = 24 * 1024 * 1024
        const val MAX_FILES = 128
        private const val MAX_SCAN = 4096L

        fun path(root: Path, owner: AttemptOwner): Path {
            require(owner.session.matches(Regex("[A-Za-z0-9._-]{1,128}")))
            require(owner.iteration.matches(Regex("[A-Za-z0-9:._-]{1,128}")) && owner.generation > 0)
            return root.resolve("nomad/diagnostics/${owner.session}/${owner.iteration.replace(':', '-')}-${owner.generation}")
        }

        private fun allowed(name: String, indices: List<Int>): Boolean = name == "resource-observation.json" ||
            indices.any { name in listOf("$it.out", "$it.err", "$it.exit", "$it.termination") || name.startsWith("cases/$it/") }

        /** Snapshot only the row's outputs. No worktrees, credentials, symlinks or unbounded file reads. */
        fun collect(directory: Path, indices: List<Int>, remainingMs: () -> Long): WorkerDiagnosticFiles {
            val files = linkedMapOf<String, WorkerDiagnosticFile>()
            var total = 0
            var scanned = 0L
            var limited = false
            fun add(file: Path) {
                if (remainingMs() <= 0 || total >= TOTAL_BYTES || files.size >= MAX_FILES) { limited = true; return }
                if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) return
                val name = directory.relativize(file).toString().replace('\\', '/')
                if (name in files) return
                if (name.length > 1024) { limited = true; return }
                runCatching {
                    val size = Files.size(file)
                    val bytes = Files.newInputStream(file, NOFOLLOW_LINKS).use { it.readNBytes(minOf(FILE_BYTES, TOTAL_BYTES - total)) }
                    files[name] = WorkerDiagnosticFile(maxOf(size, bytes.size.toLong()), Base64.getEncoder().encodeToString(bytes))
                    total += bytes.size
                    if (bytes.size < size) limited = true
                }.onFailure { limited = true }
            }
            add(directory.resolve("resource-observation.json"))
            // Preserve result and termination evidence before spending the byte budget on bulk output.
            for (index in indices) {
                for (suffix in listOf("exit", "termination")) add(directory.resolve("$index.$suffix"))
                val cases = directory.resolve("cases/$index")
                // Never traverse a symlink in the case's ancestry, either.
                if (Files.isSymbolicLink(directory.resolve("cases")) || Files.isSymbolicLink(cases)) continue
                if (Files.isDirectory(cases, NOFOLLOW_LINKS)) {
                    add(cases.resolve("out/result.json").takeUnless { Files.isSymbolicLink(cases.resolve("out")) } ?: cases)
                    Files.walk(cases, 16).use { paths ->
                        val iterator = paths.iterator()
                        while (iterator.hasNext()) {
                            if (++scanned > MAX_SCAN || remainingMs() <= 0 || files.size >= MAX_FILES || total >= TOTAL_BYTES) {
                                limited = true; break
                            }
                            add(iterator.next())
                        }
                    }
                }
                for (suffix in listOf("out", "err")) add(directory.resolve("$index.$suffix"))
                if (remainingMs() <= 0 || scanned > MAX_SCAN || files.size >= MAX_FILES || total >= TOTAL_BYTES) { limited = true; break }
            }
            return WorkerDiagnosticFiles(files, limited)
        }
    }
}
