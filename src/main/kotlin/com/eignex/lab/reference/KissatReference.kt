package com.eignex.lab.reference


/**
 * Single-threaded native kissat reference. Streams CNF using [ClaspReference.ClaspInput]
 * and reads SAT/UNSAT/UNKNOWN status lines. Rows use solver key `kissat`.
 */
internal object KissatReference {
    /** The kissat executable, from `PATH` by default; override with `LAB_KISSAT=/path/to/kissat`. */
    private val BINARY = System.getenv("LAB_KISSAT") ?: "kissat"

    fun available(): Boolean = NativeReference.available(BINARY)

    fun run(instance: Instance, budget: Budget): ReferenceResult {
        val file = instance.model
        val input = ClaspReference.ClaspInput.of(Format.DIMACS) { CorpusFiles.open(file).bufferedReader() }
        val timeoutSec = (budget.timeoutMillis / MS_PER_SEC).coerceAtLeast(1)
        // -q: no progress, -n: no witness; the status line is all the reference reads.
        val cmd = listOf(BINARY, "-q", "-n", "--time=$timeoutSec")
        val (stdout, elapsedMs) = NativeReference.exec(cmd, budget.timeoutMillis, input::writeTo)
        return parse(stdout, elapsedMs, cmd)
    }

    internal fun parse(stdout: String, elapsedMs: Long, cmd: List<String>): ReferenceResult {
        val status = stdout.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("s ") }
        val feasible = when (status) {
            "s SATISFIABLE" -> true
            "s UNSATISFIABLE" -> false
            else -> null
        }
        val timeMs = elapsedMs.takeIf { feasible == true }
        return ReferenceResult(
            feasible = feasible,
            objective = null,
            elapsedMs = elapsedMs,
            timeToBestMs = timeMs,
            timeToFirstFeasibleMs = timeMs,
            proven = feasible != null,
            stats = mapOf("solveTime" to (elapsedMs / MS_PER_SEC.toDouble()).toString(), "maximize" to "false"),
            rawOutput = stdout,
            command = cmd.joinToString(" "),
        )
    }

    private const val MS_PER_SEC = 1000L
}
