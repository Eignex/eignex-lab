package com.eignex.lab.reference

import java.io.File
import java.nio.file.Files

/**
 * MPS reference adapter for the native HiGHS binary, with explicit [OPTIONS], single-threaded
 * solving and a wall-clock limit. Native solution claims and assignments are retained unchecked.
 * Rows use solver key `highs`.
 */
object HighsReference {
    /** The highs executable, from `PATH` by default; override with `LAB_HIGHS=/path/to/highs`. */
    private val BINARY = System.getenv("LAB_HIGHS") ?: "highs"

    /** HiGHS's defaults, passed explicitly: part of the cache identity and of every row's record. */
    internal val OPTIONS = linkedMapOf(
        "mip_feasibility_tolerance" to "1e-06",
        "primal_feasibility_tolerance" to "1e-07",
        "dual_feasibility_tolerance" to "1e-07",
        "mip_rel_gap" to "0.0001",
        "mip_abs_gap" to "1e-06",
    )

    /** The build, as `highs --version` prints it; a binary that will not say refuses to solve rather than cache under a guess. */
    private val version: String by lazy {
        NativeReference.exec(listOf(BINARY, "--version"), VERSION_WAIT_MS).first.lineSequence().first().trim().also {
            check(it.isNotBlank() && it.lowercase() !in setOf("unknown", "null", "unavailable")) { "$BINARY did not report its version" }
        }
    }

    /** What a cached result depends on: the build, options and native result interpretation. */
    fun identity(build: String = version): String = "$build|${options()}|${MpsReference.VERSION}"

    private fun options() = OPTIONS.entries.joinToString(",") { "${it.key}=${it.value}" }

    fun run(instance: Instance, budget: Budget): ReferenceResult =
        CorpusFiles.withPlainFile(instance.model) { file ->
            attempt(file, MpsReference.maximize(file.readText()), budget.timeoutMillis)
        }

    private fun attempt(file: File, maximize: Boolean, timeoutMs: Long): ReferenceResult {
        val dir = Files.createTempDirectory("eignex-lab-highs").toFile()
        try {
            val solution = File(dir, "solution.sol")
            val options = File(dir, "options.txt")
            val settings = OPTIONS + ("log_file" to File(dir, "highs.log").absolutePath)
            options.writeText(settings.entries.joinToString("\n", postfix = "\n") { "${it.key} = ${it.value}" })
            val cmd = listOf(
                BINARY,
                "--model_file", file.absolutePath,
                "--options_file", options.absolutePath,
                "--solution_file", solution.absolutePath,
                "--time_limit", "${timeoutMs / MS_PER_SEC}",
                "--parallel", "off",
            )
            val (stdout, elapsedMs) = NativeReference.exec(cmd, timeoutMs)
            val claim = parseClaim(stdout, solution.takeIf { it.isFile }?.readText())
            return MpsReference.result(claim, maximize, elapsedMs, stdout, cmd.joinToString(" "),
                identity(), options(), reported(stdout))
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * A MIP's summary has `Status <status>`, `Primal bound`, `Dual bound` and `Gap`; an LP's has `Model status :
     * <status>` and `Objective value :`, an optimal LP's objective being its own dual bound. The solution comes from
     * the solution file's `# Columns` section, absent when HiGHS wrote none.
     */
    fun parseClaim(stdout: String, solution: String?): MpsReference.Claim {
        val lines = stdout.lineSequence().map { it.trim() }.toList()
        fun field(name: String) = lines.lastOrNull { Regex("""^$name\s*:?\s+\S""").containsMatchIn(it) }
            ?.replace(Regex("""^$name\s*:?\s+"""), "")?.trim()
        fun number(name: String) = field(name)?.substringBefore(' ')?.toDoubleOrNull()?.takeIf { it.isFinite() }
        val text = status(stdout).orEmpty()
        val status = when {
            text.startsWith("optimal") -> MpsReference.Status.OPTIMAL
            text.startsWith("infeasible") -> MpsReference.Status.INFEASIBLE
            "limit" in text || "interrupt" in text -> MpsReference.Status.LIMIT
            else -> MpsReference.Status.UNKNOWN
        }
        val primal = number("Primal bound") ?: number("Objective value")
        val lp = field("Model status") != null && field("Primal bound") == null
        val dual = number("Dual bound") ?: primal.takeIf { lp && status == MpsReference.Status.OPTIMAL }
        val gap = field("Gap")?.substringBefore('%')?.trim()?.toDoubleOrNull()?.div(PERCENT)
        return MpsReference.Claim(status, primal, dual, gap, solution?.let(::solutionValues))
    }

    private fun status(stdout: String): String? = stdout.lineSequence().map { it.trim() }
        .lastOrNull { Regex("""^(Model status|Status)\s*:?\s+\S""").containsMatchIn(it) }
        ?.replace(Regex("""^(Model status|Status)\s*:?\s+"""), "")?.trim()?.lowercase()

    private fun reported(stdout: String): Map<String, String> = buildMap {
        status(stdout)?.let { put("solverStatus", it) }
        stdout.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("Solution status") }
            ?.removePrefix("Solution status")?.trim()?.let { put("solutionStatus", it) }
    }

    /** The primal values a raw-style solution file lists under `# Columns`, by name; null when it holds none. */
    internal fun solutionValues(solution: String): Map<String, Double>? {
        val lines = solution.lines()
        val primal = lines.indexOfFirst { it.startsWith("# Primal solution values") }
        if (primal < 0 || lines.getOrNull(primal + 1)?.trim().equals("None", ignoreCase = true)) return null
        val columns = (primal until lines.size).firstOrNull { lines[it].startsWith("# Columns") } ?: return null
        val count = lines[columns].removePrefix("# Columns").trim().toIntOrNull() ?: return null
        return (columns + 1..columns + count).mapNotNull { lines.getOrNull(it) }.associate { line ->
            line.substringBeforeLast(' ') to line.substringAfterLast(' ').toDouble()
        }
    }

    private const val MS_PER_SEC = 1000.0
    private const val PERCENT = 100.0
    private const val VERSION_WAIT_MS = 10_000L

}
