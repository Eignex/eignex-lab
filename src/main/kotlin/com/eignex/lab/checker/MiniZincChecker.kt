package com.eignex.lab.checker

import com.eignex.lab.reference.CorpusFiles
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.math.BigDecimal

/** A MiniZinc installation the lab checks with: the `minizinc` driver, its version, and the solver's. */
data class MiniZincTool(val executable: File, val version: String, val sha256: String)

/**
 * Finds `minizinc` ([LAB_MINIZINC], else on `PATH`: on the Mac Homebrew's link into the MiniZincIDE bundle) with
 * [solver] registered, once; throws, saying why, when there is none.
 */
class MiniZincInstall(private val solver: String = MiniZincChecker.SOLVER, private val env: (String) -> String? = System::getenv) {
    private val found by lazy { runCatching { locate() } }

    fun tool(): MiniZincTool = found.getOrThrow()

    private fun locate(): MiniZincTool {
        val named = env("LAB_MINIZINC")?.takeIf { it.isNotBlank() }?.let(::File)
        val executable = named ?: env("PATH").orEmpty().split(File.pathSeparator).map { File(it, "minizinc") }
            .firstOrNull { it.canExecute() } ?: error("minizinc is not installed (not on PATH, LAB_MINIZINC unset)")
        val real = executable.toPath().toRealPath().toFile()
        fun ask(vararg args: String): String {
            val dir = kotlin.io.path.createTempDirectory("minizinc-").toFile()
            try {
                val run = ToolProcess.run(listOf(executable.path) + args, dir, PROBE_MS)
                check(run.exit == 0) { "minizinc ${args.joinToString(" ")} failed (exit ${run.exit}): ${run.tail()}" }
                return run.stdout
            } finally {
                dir.deleteRecursively()
            }
        }
        val version = Regex("version ([0-9][^,\\s]*)").find(ask("--version"))?.groupValues?.get(1) ?: "unknown"
        val solverLine = ask("--solvers").lineSequence().firstOrNull { line ->
            line.contains("org.$solver.$solver") || line.trim().startsWith(solver, ignoreCase = true)
        } ?: error("minizinc $version has no $solver solver registered")
        val solverVersion = Regex("^\\s*\\S+ ([0-9][^ ]*)").find(solverLine)?.groupValues?.get(1) ?: "unknown"
        return MiniZincTool(executable, "minizinc $version, $solver $solverVersion", sha256(real))
    }

    private companion object {
        const val PROBE_MS = 30_000L
    }
}

/**
 * MiniZinc integer witnesses, checked by MiniZinc itself with Gecode: the original model and data, with the witness, the
 * final DZN solution the record kept, as one more data file, so every output variable is fixed to its claimed value.
 * Gecode then completes it or proves it cannot be: a solution is a valid witness and `=====UNSATISFIABLE=====` an
 * invalid one. An optimisation claim's objective is compared exactly with `_objective`: a completion reaching it is
 * valid, a search that ends with every completion worse than the claim is invalid, anything else undecided. Floats
 * have no external exact checker: a witness with Klause's exact or coordinate float comments, a float approximation, or
 * a model with float variables is unsupported.
 */
class MiniZincChecker(private val install: () -> MiniZincTool, private val timeoutMs: Long = TIMEOUT_MS) : FormatChecker {
    override val format = "MINIZINC"
    override val policy = Policies.MINIZINC
    override val checker = "minizinc --solver $SOLVER"

    override fun check(case: CheckCase, work: File): Verdict {
        val stats = case.record["stats"] as? JsonObject
        if (case.witness.lineSequence().any { line -> FLOAT_COMMENTS.any { line.startsWith(it) } } ||
            (stats?.get("floatApproximation") as? JsonPrimitive)?.content == "true" ||
            FLOAT_VAR.containsMatchIn(stripComments(CorpusFiles.readText(case.sources.model)))
        ) {
            return Verdict(LabCheck.UNSUPPORTED, FLOATS)
        }
        if (INTRODUCED.containsMatchIn(case.witness)) return Verdict(LabCheck.UNSUPPORTED, INTRODUCED_REASON)
        val tool = install()
        fun verdict(state: String, reason: String) = Verdict(state, reason, tool.version, tool.sha256)
        val claimed = if (case.optimize) {
            case.claimedObjective?.toBigDecimalOrNull() ?: return verdict(LabCheck.UNKNOWN, "the record holds no claimed objective to check")
        } else null
        val witness = File(work, "witness.dzn").apply { writeText(dzn(case.witness)) }
        val run = CorpusFiles.withPlainFile(case.sources.model) { model ->
            val data = case.sources.data
            if (data == null) solve(tool, model, null, witness, case, work)
            else CorpusFiles.withPlainFile(data) { solve(tool, model, it, witness, case, work) }
        }
        if (run.timedOut) return verdict(LabCheck.NOT_RUN, "minizinc did not finish within ${timeoutMs / 1000} s")
        val out = run.stdout
        if ("=====UNSATISFIABLE=====" in out) {
            return verdict(LabCheck.INVALID, "the model has no solution with the witness's output variables fixed (=====UNSATISFIABLE=====)")
        }
        val solutions = out.split("----------").dropLast(1)
        if (solutions.isEmpty() && run.exit != 0) {
            // A witness MiniZinc cannot read as data for the model, such as integers for an enum-typed array, is
            // unsupported; any other failure (a solver that would not start) is a check that did not run.
            val unreadable = UNREADABLE.any { it in run.stdout || it in run.stderr }
            return if (unreadable) verdict(LabCheck.UNSUPPORTED, "minizinc could not read the witness with the model (exit ${run.exit}): ${errors(run)}")
            else verdict(LabCheck.NOT_RUN, "minizinc failed (exit ${run.exit}): ${run.tail()}")
        }
        if (solutions.isEmpty()) return verdict(LabCheck.UNKNOWN, "minizinc found no completion and no refutation: ${run.tail()}")
        if (claimed == null) return verdict(LabCheck.VALID, "Gecode completed the model with the witness's output variables fixed")
        val objectives = solutions.mapNotNull { OBJECTIVE.find(it)?.groupValues?.get(1)?.toBigDecimalOrNull() }
        if (objectives.isEmpty()) return verdict(LabCheck.UNKNOWN, "minizinc printed no _objective to compare with the claimed $claimed")
        if (objectives.any { it.compareTo(claimed) == 0 }) {
            return verdict(LabCheck.VALID, "Gecode completed the model with the witness's output variables fixed, objective $claimed")
        }
        val maximize = (case.record["maximize"] as? JsonPrimitive)?.content == "true"
        val best = objectives.last()
        val complete = "==========" in out
        if (complete && (if (maximize) best < claimed else best > claimed)) {
            return verdict(LabCheck.INVALID, "the claimed objective $claimed is out of reach: the best completion of the witness has $best")
        }
        return verdict(LabCheck.UNKNOWN, "the witness completes, but no completion found has the claimed objective $claimed (last $best)")
    }

    private fun solve(tool: MiniZincTool, model: File, data: File?, witness: File, case: CheckCase, work: File): ToolRun {
        val command = buildList {
            addAll(listOf(tool.executable.path, "--solver", SOLVER, "--allow-multiple-assignments", "--output-mode", "dzn",
                "--output-objective", "-a", "--time-limit", timeoutMs.toString()))
            // The source's own directory too, for a model staged out of it from a compressed copy.
            (case.sources.includeDirs + case.sources.model.absoluteFile.parentFile).distinct().forEach { addAll(listOf("-I", it.path)) }
            add(model.path)
            data?.let { add(it.path) }
            add(witness.path)
        }
        // MiniZinc's own limit stops the search; this one, a little later, stops a compiler that will not.
        return ToolProcess.run(command, work, timeoutMs + GRACE_MS)
    }

    companion object {
        const val SOLVER = "gecode"
        const val TIMEOUT_MS = 60_000L
        private const val GRACE_MS = 15_000L
        const val FLOATS = "no external exact checker for floats"
        private val FLOAT_COMMENTS = listOf("% klause-exact:", "% klause-coordinate:", "% klause-float-")
        private val FLOAT_VAR = Regex("""\bvar\s+(?:opt\s+)?(?:float\b|-?[0-9]+\.[0-9]+\s*\.\.)""")
        private val OBJECTIVE = Regex("""(?m)^\s*_objective\s*=\s*([^;]+);""")
        private val COMMENT = Regex("""%[^\n]*|/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

        private val INTRODUCED = Regex("""\bX_INTRODUCED_[0-9]+_""")
        /** What MiniZinc says of data it cannot read for a model. */
        private val UNREADABLE = listOf("type error", "syntax error")
        const val INTRODUCED_REASON = "the witness names FlatZinc-introduced variables (X_INTRODUCED_*), which only Klause's own " +
            "compilation maps back to the model; no external checker reads them"

        private fun stripComments(model: String) = model.replace(COMMENT, "")

        /** MiniZinc's error lines, without the scratch paths they name. */
        private fun errors(run: ToolRun): String = (run.stdout + "\n" + run.stderr).lineSequence().map { it.trim() }
            .filter { it.startsWith("Error") || it.startsWith("MiniZinc:") }.toList().ifEmpty { listOf(run.tail()) }
            .joinToString(" / ").take(REASON_CAP)

        /** The witness as a data file: without comments and without `_objective`, which the model does not declare. */
        fun dzn(witness: String): String = witness.lineSequence()
            .filterNot { it.trimStart().startsWith("%") || OBJECTIVE_LINE.matches(it) }
            .joinToString("\n")
            .trim() + "\n"

        private val OBJECTIVE_LINE = Regex("""\s*_objective\s*=\s*[^;]*;\s*""")
    }
}
