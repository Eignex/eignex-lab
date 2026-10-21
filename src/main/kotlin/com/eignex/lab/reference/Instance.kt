package com.eignex.lab.reference

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale

/** The source formats a reference solver reads, named as `klause-bench select` prints them. */
enum class Format { MINIZINC, XCSP3, DIMACS, OPB, WCNF, SMTLIB, MPS }

/**
 * One problem as `klause-bench select files=true` resolves it: its [model] file (plain or zstd-compressed), the
 * MiniZinc [data] file, and the [includeDirs] its collection ships for `minizinc -I`.
 */
data class Instance(
    val name: String,
    val format: Format,
    val model: File,
    val data: File? = null,
    val includeDirs: List<File> = emptyList(),
)

/** How long a reference solver may run. */
data class Budget(val timeoutMillis: Long)

/**
 * One reference solve: verdict, objective, proof, timings, the solver's stats and its raw output. [assignment] is the
 * solution a solver returned as `name value` lines, kept beside the record so the verdict can be checked again.
 */
data class ReferenceResult(
    val feasible: Boolean?,
    val objective: Double?,
    val timeToBestMs: Long?,
    val timeToFirstFeasibleMs: Long? = null,
    val proven: Boolean,
    val stats: Map<String, String>,
    val rawOutput: String,
    val command: String,
    val assignment: String? = null,
    /** Subprocess wall-clock duration, including launch and output consumption; null when unavailable. */
    val elapsedMs: Long? = null,
    val exactObjective: String? = null,
)

/**
 * Corpus files, plain or zstd-compressed (`.zst`). Decompression goes through the system `zstd`, as it does in the
 * bench that fetched them, so a compressed and a plain copy read and hash alike.
 */
object CorpusFiles {
    private const val SUFFIX = ".zst"
    private const val BUFFER_BYTES = 1 shl 16

    fun isCompressed(file: File): Boolean = file.name.endsWith(SUFFIX)

    fun open(file: File): InputStream = if (isCompressed(file)) {
        val process = ProcessBuilder("zstd", "-dcq", file.absolutePath)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.close()
        process.inputStream
    } else {
        file.inputStream()
    }

    fun readText(file: File): String = open(file).bufferedReader().use { it.readText() }

    /** Run [block] on a plain copy of [file] for a solver that needs a real path; a compressed one is staged and
     *  deleted afterwards. Staging stays under `$HOME`, which a colima Docker VM mounts, and outside the corpus,
     *  whose collections the bench walks to discover instances. */
    fun <T> withPlainFile(file: File, block: (File) -> T): T {
        if (!isCompressed(file)) return block(file)
        val staging = File(System.getProperty("user.home"), ".cache/klause-lab/plain").apply { mkdirs() }
        val dir = Files.createTempDirectory(staging.toPath(), "plain-").toFile()
        try {
            val plain = File(dir, file.name.removeSuffix(SUFFIX))
            open(file).use { input -> plain.outputStream().use { input.copyTo(it) } }
            return block(plain)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** SHA-256 of the plain bytes of [file]. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        open(file).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hex(digest.digest())
    }

    /** The `model` and `data` digests a lab record binds its verdict to, as klause-bench computes them. */
    fun sourceHashes(instance: Instance): Map<String, String> = buildMap {
        put("model", sha256(instance.model))
        instance.data?.let { put("data", sha256(it)) }
    }
}

fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(Locale.ROOT, it) }

/** The `solveTime` stat in milliseconds, when a solver reported a usable one. */
fun solveTimeMs(stats: Map<String, String>): Long? = stats["solveTime"]?.toDoubleOrNull()
    ?.takeIf { it.isFinite() && it >= 0 }?.let { (it * 1000).toLong() }
