package com.eignex.lab.checker

import com.eignex.lab.reference.CorpusFiles
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Dolmen 0.10 (BSD-2-Clause, github.com/Gbury/dolmen), the pinned release binary for this platform, downloaded once
 * into `<dir>/dolmen-0.10/dolmen` and checked against its SHA-256. The release's macOS file is named `amd64` but is an
 * arm64 Mach-O, which is what the Mac runs. A download that fails is not tried again for [RETRY_MS].
 */
class Dolmen(
    private val dir: File,
    private val os: String = System.getProperty("os.name"),
    private val arch: String = System.getProperty("os.arch"),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var failure: Pair<Long, String>? = null

    /** The pinned release asset and its SHA-256 for this platform, or null when there is none. */
    val artifact: Pair<String, String>? get() = when {
        os == "Linux" && arch in setOf("amd64", "x86_64") -> "dolmen-linux-amd64" to LINUX_SHA256
        os == "Mac OS X" && arch in setOf("aarch64", "arm64") -> "dolmen-macos-amd64" to MAC_SHA256
        else -> null
    }

    @Synchronized
    fun binary(): File {
        val binary = File(dir, "dolmen-$VERSION/dolmen")
        val (name, pin) = artifact ?: error("no pinned Dolmen $VERSION release for $os $arch")
        if (binary.canExecute() && sha256(binary) == pin) return binary
        failure?.let { (at, why) -> if (clock() - at < RETRY_MS) error(why) }
        try {
            binary.parentFile.mkdirs()
            val partial = File.createTempFile("dolmen-", ".part", binary.parentFile)
            try {
                URI("$RELEASE/$name").toURL().openConnection().apply { connectTimeout = 30_000; readTimeout = 30_000 }
                    .getInputStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
                val sha = sha256(partial)
                check(sha == pin) { "SHA-256 $sha of $name differs from the pin $pin" }
                check(partial.setExecutable(true, true)) { "the downloaded Dolmen cannot be made executable" }
                Files.move(partial.toPath(), binary.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                partial.delete()
            }
            failure = null
            return binary
        } catch (e: Exception) {
            val why = "Dolmen $VERSION could not be downloaded: ${e.message}"
            failure = clock() to why
            error(why)
        }
    }

    companion object {
        const val VERSION = "0.10"
        const val RELEASE = "https://github.com/Gbury/dolmen/releases/download/v0.10"
        const val LINUX_SHA256 = "bab3e9a1007d5bf6fe5323195e98f95c812fbb108b95a9e02a693ebc4014b675"
        const val MAC_SHA256 = "2141ed2e479f4a0fd97723f34f42cae66156a8c5a20d29da919b12b373da47a1"
        const val RETRY_MS = 10 * 60_000L
    }
}

/**
 * SMT-LIB witnesses, checked by Dolmen's model checking: the original problem with the response `sat` and the model
 * the record kept (its last `( … )` block) on standard input, `--check-model=true --strict=false --time=30s
 * --size=4G`. Exit 0 with no output is a valid model, exit 6 with `E:bad-model` an invalid one, anything else
 * undecided. Only a satisfiable claim without an objective has a model to check: an optimisation claim is unsupported,
 * and an unsat claim has no witness at all; UNSAT claims are compared with stored reference verdicts instead.
 *
 * Dolmen cannot read algebraic numbers, Z3's `(root-obj p i)`, and leaves such a model undecided (`E:unbound-id`). Only
 * then, when the model has one, [algebraic] decides it in Dolmen's place ([Z3AlgebraicCheck]), under its own policy; a
 * Z3 that cannot be found keeps Dolmen's verdict, saying so.
 */
class SmtChecker(
    private val dolmen: () -> File,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val algebraic: Z3AlgebraicCheck? = null,
) : FormatChecker {
    override val format = "SMTLIB"
    override val policy = Policies.SMTLIB
    override val checker = "dolmen"

    override fun check(case: CheckCase, work: File): Verdict {
        if (case.optimize || case.field("objective") != null || case.field("exactObjective") != null) {
            return Verdict(LabCheck.UNSUPPORTED, "Dolmen checks a satisfiable claim's model, not an objective; " +
                "UNSAT claims are compared with stored reference verdicts")
        }
        val dolmen = dolmen(case, work)
        if (dolmen.state != LabCheck.UNKNOWN || algebraic == null || !Z3AlgebraicCheck.algebraic(case.witness)) return dolmen
        return try {
            algebraic.check(case, work, dolmen)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            dolmen.copy(reason = "${dolmen.reason}; the model has algebraic numbers and Z3 could not run: ${e.message}")
        }
    }

    private fun dolmen(case: CheckCase, work: File): Verdict {
        val binary = dolmen()
        val sha = sha256(binary)
        val response = File(work, "response.rsmt2").apply { writeText("sat\n${case.witness}\n") }
        val args = listOf("--check-model=true", "--strict=false", "--time=30s", "--size=4G", "--report-style=minimal", "--warn=-all")
        val run = CorpusFiles.withPlainFile(case.sources.model) { source ->
            ToolProcess.run(listOf(binary.path) + args + source.path, work, timeoutMs, stdin = response,
                limits = ToolLimits(30, timeoutMs), inputFiles = listOf(source))
        }
        val diagnostic = "command: ${binary.path} ${args.joinToString(" ")} <plain copy of ${case.sources.model.path}> " +
            "< response.rsmt2 (`sat`, then the model)\nexit ${run.exit ?: "none (killed)"} after ${run.elapsedMs} ms; output: ${run.tail()}"
        fun verdict(state: String, reason: String) = Verdict(state, reason, Dolmen.VERSION, sha,
            diagnostic = diagnostic, resourceReceipts = listOfNotNull(run.resources))
        return when {
            run.timedOut -> verdict(LabCheck.NOT_RUN, "Dolmen did not finish within ${timeoutMs / 1000} s")
            run.resources?.qualified() != true -> verdict(LabCheck.NOT_RUN, "Dolmen's resource envelope was incomplete or exceeded")
            run.exit == 0 && run.stdout.isBlank() && run.stderr.isBlank() -> verdict(LabCheck.VALID, "Dolmen accepted the model")
            run.exit == 6 && run.stderr.trim() == "E:bad-model" -> verdict(LabCheck.INVALID, "Dolmen rejected the model (E:bad-model)")
            else -> verdict(LabCheck.UNKNOWN, "Dolmen could not decide (exit ${run.exit}): ${run.tail()}")
        }
    }

    companion object {
        /** Dolmen's own `--time=30s`, and room to start and stop. */
        const val TIMEOUT_MS = 35_000L
    }
}
