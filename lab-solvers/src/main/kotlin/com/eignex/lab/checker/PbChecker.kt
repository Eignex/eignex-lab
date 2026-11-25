package com.eignex.lab.checker

import com.eignex.lab.reference.CorpusFiles
import java.io.File

/**
 * VeriPB 3.0.2 (MIT OR Apache-2.0, gitlab.com/MIAOresearch/software/VeriPB). Upstream publishes no binaries, so this
 * repository's `veripb` workflow builds them from the crates.io crate (SHA-256 [CRATE_SHA256]) with `cargo build
 * --locked` and publishes them as the `veripb-v3.0.2` release; the lab downloads the one for this platform into
 * `<dir>/veripb-3.0.2/veripb` and checks it against its pinned SHA-256.
 */
class VeriPb(
    dir: File,
    private val os: String = System.getProperty("os.name"),
    private val arch: String = System.getProperty("os.arch"),
    clock: () -> Long = System::currentTimeMillis,
) {
    private val pinned = PinnedBinary(dir, "VeriPB", "veripb", VERSION, clock)

    /** The pinned release asset and its SHA-256 for this platform, or null when there is none. */
    val artifact: Pair<String, String>? get() = when {
        os == "Linux" && arch in setOf("amd64", "x86_64") -> "veripb-linux-amd64" to LINUX_SHA256
        os == "Mac OS X" && arch in setOf("aarch64", "arm64") -> "veripb-macos-arm64" to MAC_SHA256
        else -> null
    }

    fun binary(): File {
        val (name, pin) = artifact ?: error("no pinned VeriPB $VERSION build for $os $arch")
        return pinned.get("$RELEASE/$name", pin)
    }

    companion object {
        const val VERSION = "3.0.2"
        const val CRATE_SHA256 = "c1b9da430bdf5fd6e7dee94bd920a60c5a5cdb7532ecb18e0918141e2c4e28b9"
        const val RELEASE = "https://github.com/Eignex/eignex-lab/releases/download/veripb-v3.0.2"
        const val LINUX_SHA256 = "c9aa07d224452e2ac99511c1dbb8791d4597e31c74d603b95f2a2af629b684da"
        const val MAC_SHA256 = "dc336efe5bcadd348fd4dc6f0592d828581666f25aa45c8337f54ad15472e9c7"
    }
}

/**
 * OPB and WCNF points, checked by VeriPB against the original source. The proof it is given logs the record's final
 * witness with the `sol` rule and concludes `SAT`, or for an optimisation claim `BOUNDS` with the claimed objective
 * as the upper bound: VeriPB then requires the witness, extended by unit propagation, to be a full assignment that
 * satisfies every constraint, and its objective value to equal the claim exactly. The lower bound is [LOWER], which
 * every objective VeriPB reads implies without a proof, so the verdict says nothing about optimality.
 *
 * VeriPB reads linear OPB and the MaxSAT Evaluation 2022+ WCNF format with `h` lines; WBO, nonlinear OPB and the
 * older `p wcnf` format are unsupported in its own words. A WCNF soft clause with several literals becomes a constraint
 * on a blocking variable the witness does not assign; when the witness satisfies such a clause VeriPB asks for that
 * variable's value, and the check is undecided rather than completed by the lab.
 */
class PbChecker(
    override val format: String,
    private val veripb: () -> File,
    private val timeoutMs: Long = TIMEOUT_MS,
) : FormatChecker {
    override val policy = when (format) {
        "OPB" -> Policies.OPB
        "WCNF" -> Policies.WCNF
        else -> throw IllegalArgumentException("VeriPB checks OPB and WCNF, not $format")
    }
    override val checker = "veripb"

    override fun check(case: CheckCase, work: File): Verdict {
        val solution = solution(case.witness, format)
            ?: return Verdict(LabCheck.INVALID, "the witness is not a ${if (format == "OPB") "PB" else "MaxSAT"} competition `v` assignment",
                VeriPb.VERSION)
        val bound = if (case.optimize) {
            val claimed = case.claimedObjective?.toBigIntegerOrNull()
                ?: return Verdict(LabCheck.UNKNOWN, "the record holds no integral claimed objective to check", VeriPb.VERSION)
            // VeriPB minimises: a `max:` objective is read negated.
            if (case.field("maximize") == "true") claimed.negate() else claimed
        } else null
        val conclusion = if (bound == null) "conclusion SAT;" else "conclusion BOUNDS $LOWER $bound;"
        val proof = File(work, "point.pbp").apply {
            writeText("pseudo-Boolean proof version 3.0\nsol $solution;\noutput NONE;\n$conclusion\nend pseudo-Boolean proof;\n")
        }
        val binary = veripb()
        val sha = sha256(binary)
        val parser = if (format == "OPB") "--opb" else "--wcnf"
        var plain = ""
        val run = CorpusFiles.withPlainFile(case.sources.model) { source ->
            plain = source.path
            ToolProcess.run(listOf(binary.path, parser, source.path, proof.path), work, timeoutMs, env = mapOf("NO_COLOR" to "1"),
                limits = ToolLimits(CPU_SECONDS, timeoutMs), inputFiles = listOf(source, proof))
        }
        val diagnostic = "command: ${binary.path} $parser <plain copy of ${case.sources.model.path}> point.pbp " +
            "(`sol` with the witness, then `$conclusion`)\nexit ${run.exit ?: "none (killed)"} after ${run.elapsedMs} ms; output: ${run.tail()}"
        val inputs = listOf(CheckedInput(proof.canonicalPath, sha256(proof), proof.readText()))
        fun verdict(state: String, reason: String) = Verdict(state, reason, VeriPb.VERSION, sha,
            diagnostic = diagnostic, resourceReceipts = listOfNotNull(run.resources), inputs = inputs)
        val expected = if (bound == null) "s VERIFIED SATISFIABLE" else "s VERIFIED BOUNDS $LOWER <= obj <= $bound"
        val output = run.stdout.lines().map { it.trim() }
        val error = run.stderr.trim()
        return when {
            run.timedOut -> verdict(LabCheck.NOT_RUN, "VeriPB did not finish within ${timeoutMs / 1000} s")
            run.resources?.qualified() != true -> verdict(LabCheck.NOT_RUN, "VeriPB's resource envelope was incomplete or exceeded")
            run.exit == 0 && expected in output && output.none { it.startsWith("Warning") } ->
                verdict(LabCheck.VALID, if (bound == null) "VeriPB accepted the assignment" else "VeriPB accepted the assignment with objective $bound")
            run.exit == 1 && SOURCE_ERRORS.any { error.startsWith(it.replace("<source>", plain)) } ->
                verdict(LabCheck.UNSUPPORTED, "VeriPB $VERSION cannot read this source: ${error.removePrefix("Error: ").take(REASON_CAP)}")
            run.exit == 1 && error.startsWith("Error: Verification error at ${proof.path}:") && REJECTIONS.any { it in error } ->
                verdict(LabCheck.INVALID, "VeriPB rejected the point: ${cause(error)}")
            else -> verdict(LabCheck.UNKNOWN, "VeriPB could not decide (exit ${run.exit}): ${cause(error).ifEmpty { run.tail() }}")
        }
    }

    private fun cause(error: String): String = error.substringAfter("Caused by:", error).trim()

    companion object {
        const val VERSION = VeriPb.VERSION
        const val CPU_SECONDS = 60L
        const val TIMEOUT_MS = 65_000L

        /** A lower bound every objective implies: below the sum of any real coefficients. */
        val LOWER = "-1" + "0".repeat(100)

        /** VeriPB's own words for a source its parsers cannot read, `<source>` its path. */
        private val SOURCE_ERRORS = listOf("Error: Unexpected token starting at <source>:", "Error: The file did not contain a header")

        /** VeriPB's own words for a witness that is not a solution, or whose objective is not the claimed one. */
        private val REJECTIONS = listOf(
            "The given solution is conflicting with constraint",
            "The propagated assignment does not satisfy the constraint",
            "The given solution is conflicting in itself",
            "mismatches the best recorded upper bound",
        )

        private val NAME = Regex("[A-Za-z][A-Za-z0-9_\\[\\]{}^-]+")
        private val BITS = Regex("[01]+")
        private val LITERAL = Regex("-?[1-9][0-9]*")
        private val SPACE = Regex("\\s+")

        /**
         * The `v` lines of [witness] as VeriPB literals separated by spaces, or null when they are not an assignment in
         * [format]'s competition output: OPB names, negated by `-` (or `~`); for WCNF one bit string whose character `i`
         * is variable `i` (MaxSAT Evaluation 2024), or signed variable numbers with an optional final `0`.
         */
        fun solution(witness: String, format: String): String? {
            val tokens = mutableListOf<String>()
            for (line in witness.lineSequence()) {
                val text = line.trim()
                if (text.isEmpty()) continue
                if (text != "v" && !(text.startsWith("v") && text[1].isWhitespace())) return null
                text.substring(1).trim().split(SPACE).filterTo(tokens) { it.isNotEmpty() }
            }
            if (tokens.isEmpty()) return null
            val out = StringBuilder()
            fun add(negated: Boolean, name: String) {
                if (out.isNotEmpty()) out.append(' ')
                if (negated) out.append('~')
                out.append(name)
            }
            when (format) {
                "OPB" -> for (token in tokens) {
                    val negated = token.startsWith('-') || token.startsWith('~')
                    val name = if (negated) token.substring(1) else token
                    if (!NAME.matches(name)) return null
                    add(negated, name)
                }
                "WCNF" -> if (tokens.size == 1 && BITS.matches(tokens[0])) {
                    tokens[0].forEachIndexed { i, bit -> add(bit == '0', "x${i + 1}") }
                } else {
                    for ((i, token) in tokens.withIndex()) {
                        if (token == "0" && i == tokens.lastIndex) break
                        if (!LITERAL.matches(token)) return null
                        add(token.startsWith('-'), "x${token.removePrefix("-")}")
                    }
                }
                else -> return null
            }
            return out.toString().ifEmpty { null }
        }
    }
}
