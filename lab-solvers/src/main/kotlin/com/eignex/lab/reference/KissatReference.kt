package com.eignex.lab.reference

import com.eignex.lab.checker.*
import com.eignex.lab.domain.*


/**
 * Single-threaded native kissat reference. Streams CNF using [ClaspReference.ClaspInput]
 * and reads SAT/UNSAT/UNKNOWN status lines. Rows use solver key `kissat`.
 */
object KissatReference {
    /** The kissat executable, from `PATH` by default; override with `LAB_KISSAT=/path/to/kissat`. */
    private val BINARY = System.getenv("LAB_KISSAT") ?: "kissat"

    fun available(): Boolean = NativeReference.available(BINARY)

    fun run(instance: Instance, budget: Budget, binary: String = BINARY): ReferenceResult {
        val started = System.nanoTime()
        val identity = ReferenceIdentity.native(binary, budget.timeoutMillis)
        val file = instance.model
        val input = ClaspReference.ClaspInput.of(Format.DIMACS) { CorpusFiles.open(file).bufferedReader() }
        val remaining = budget.timeoutMillis - (System.nanoTime() - started) / 1_000_000
        check(remaining > 0) { "kissat identity exhausted the reference case budget" }
        val timeoutSec = (remaining / MS_PER_SEC).coerceAtLeast(1)
        // -q: no progress, -n: no witness; the status line is all the reference reads.
        val cmd = listOf(identity.executable, "-q", "-n", "--time=$timeoutSec")
        val (stdout, _) = NativeReference.exec(cmd, remaining, input::writeTo)
        val result = parse(stdout, (System.nanoTime() - started) / 1_000_000, cmd)
        return result.copy(stats = result.stats + identity.stats +
            ("referenceVersion" to "${identity.stats.getValue("referenceVersion")}|-q,-n|kissat-reference-v1"))
    }

    fun parse(stdout: String, elapsedMs: Long, cmd: List<String>): ReferenceResult {
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
