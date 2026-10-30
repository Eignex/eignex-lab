package com.eignex.lab.reference

import com.eignex.lab.checker.*
import com.eignex.lab.domain.*

import java.io.BufferedReader
import java.io.StringWriter
import java.io.Writer
import java.math.BigInteger
import kotlin.math.abs

/**
 * DIMACS and OPB reference adapter for clasp in the [IMAGE] container. Instances stream
 * on stdin; OPB receives a synthesized problem header when absent. Containers have
 * memory limits, a deadline watchdog and label-based cleanup.
 */
object ClaspReference {
    const val IMAGE = "eignex-lab-clasp:latest"

    /** Label on every reference container, so stragglers can be reaped (`docker kill --filter label`). */
    const val CONTAINER_LABEL = "eignex-lab-clasp-ref"

    private val docker = DockerReference(IMAGE, CONTAINER_LABEL, "clasp")

    /** Kill any leftover reference containers from an earlier interrupted run. */
    fun reapStragglers() = docker.reapStragglers()

    /** Whether the clasp image is built (`docker image inspect`). */
    fun imageAvailable(): Boolean = docker.imageAvailable()

    /** Solve [instance] (DIMACS, OPB or WCNF) with clasp under [budget], single-threaded. The instance is streamed
     *  on stdin (OPB and WCNF get their problem line synthesized first). Objective sense is always minimise for
     *  OPB (clasp's only PB mode); DIMACS has none — both report `maximize=false`. */
    fun run(instance: Instance, budget: Budget): ReferenceResult {
        val file = instance.model
        // Streamed, never held: a MaxSAT instance can be gigabytes of text, and several run at once.
        val input = ClaspInput.of(instance.format) { CorpusFiles.open(file).bufferedReader() }
        val timeoutSec = (budget.timeoutMillis / 1000).coerceAtLeast(1)
        val name = docker.containerName()
        val cmd = docker.command(name, arguments = listOf(
            "--time-limit=$timeoutSec",
            "--parallel-mode=1",
            // No models: on a large MaxSAT instance every improving model is gigabytes of `v` lines, and the
            // reference reads only the status and `o` lines.
            "--quiet=2,0",
        ), interactive = true)
        val run = docker.exec(cmd, name, budget.timeoutMillis, input::writeTo)
        return parse(run.stdout, run.elapsedMs, cmd)
    }

    /** clasp's status is a `s <STATUS>` line; a PB optimisation streams incumbents as `o <cost>` lines
     *  (the last is the best/optimum). Map to the [ReferenceResult] the reference sweep scores. */
    fun parse(stdout: String, elapsedMs: Long, cmd: List<String>): ReferenceResult {
        val lines = stdout.lineSequence().map { it.trim() }.toList()
        val status = lines.lastOrNull { it.startsWith("s ") }?.removePrefix("s ")?.trim()
        val bestObjective = lines.filter { it.startsWith("o ") }
            .mapNotNull { ExactObjective.parse(it.removePrefix("o ").trim()) }
            .lastOrNull()
        val feasible = when (status) {
            "OPTIMUM FOUND", "SATISFIABLE" -> true
            "UNSATISFIABLE" -> false
            else -> null
        }
        // clasp is complete, so a decision instance (no `o` lines ⇒ it wasn't optimising) is *decided* by
        // a model: SAT is proven. A `SATISFIABLE` with `o` lines is an optimisation that found an incumbent
        // but timed out before proving optimum.
        val optimising = bestObjective != null
        val proven = when (status) {
            "OPTIMUM FOUND", "UNSATISFIABLE" -> true
            "SATISFIABLE" -> !optimising
            else -> false
        }
        val timeMs = elapsedMs.takeIf { feasible == true }
        return ReferenceResult(
            feasible = feasible,
            objective = bestObjective?.approximate().takeIf { feasible == true },
            exactObjective = bestObjective?.toString().takeIf { feasible == true },
            elapsedMs = elapsedMs,
            timeToBestMs = timeMs,
            timeToFirstFeasibleMs = timeMs,
            proven = proven,
            // Seconds, so [BenchCli.solveReference] derives the proof/first-feasible time uniformly.
            stats = mapOf("solveTime" to (elapsedMs / 1000.0).toString(), "maximize" to "false"),
            rawOutput = stdout,
            command = cmd.joinToString(" "),
        )
    }

    /** Prepend the standard OPB problem line `* #variable= N #constraint= M` when absent (the vendored
     *  instances omit it and clasp then rejects them). N = the highest `xK` index, M = the number of
     *  constraint lines (each terminated by `;`), excluding comments and the `min:`/`max:` objective. */
    fun opbWithProblemLine(text: String): String =
        ClaspInput.of(Format.OPB) { text.reader().buffered() }.text()

    /**
     * [text] in the classic `p wcnf` form clasp reads. The MaxSAT Evaluation format since 2022 has no problem
     * line and marks hard clauses with `h`; they become clauses at the top weight, one more than every soft
     * weight together, so no set of soft clauses can outweigh one hard clause. A classic file passes unchanged.
     */
    fun wcnfWithProblemLine(text: String): String =
        ClaspInput.of(Format.WCNF) { text.reader().buffered() }.text()

    /**
     * An instance as clasp reads it, written in two passes over [open]: the first works out the problem line a
     * file without one needs, the second streams the body through, so the instance is never held in memory.
     */
    class ClaspInput private constructor(
        private val open: () -> BufferedReader,
        private val header: String?,
        /** A line that ends the instance: it and everything after it stay out. */
        private val end: (String) -> Boolean = { false },
        private val line: (String) -> String?,
    ) {
        fun writeTo(out: Writer) {
            if (header != null) {
                out.write(header)
                out.write("\n")
            }
            open().useLines { lines ->
                for (raw in lines) {
                    if (end(raw)) break
                    val written = line(raw) ?: continue
                    out.write(written)
                    out.write("\n")
                }
            }
        }

        fun text(): String = StringWriter().also(::writeTo).toString()

        companion object {
            fun of(format: Format, open: () -> BufferedReader): ClaspInput = when (format) {
                // The PB24/PB25 normalizer adds `#equal=` and `intsize=` to the problem line, which clasp 3.3
                // refuses as a parse error on line 1; it reads the line without them.
                Format.OPB -> ClaspInput(open, opbHeader(open)) { line ->
                    if (PROBLEM_LINE.containsMatchIn(line)) line.replace(UNREAD_FIELDS, "") else line
                }

                Format.WCNF -> wcnf(open)

                // SATLIB's random-3SAT files close with a `%` line and then a lone `0`, which clasp reads as an
                // empty clause, making every one of them unsatisfiable. `%` ends the formula.
                else -> ClaspInput(open, null, end = { it.trim() == "%" }) { it }
            }

            private fun opbHeader(open: () -> BufferedReader): String? = open().useLines { lines ->
                var nVar = 0
                var nCon = 0
                for (line in lines) {
                    if (PROBLEM_LINE.containsMatchIn(line)) return@useLines null
                    VARIABLE.findAll(line).forEach { nVar = maxOf(nVar, it.groupValues[1].toInt()) }
                    val t = line.trim()
                    if (t.endsWith(";") && !t.startsWith("*") && !t.startsWith("min:") && !t.startsWith("max:")) nCon++
                }
                "* #variable= $nVar #constraint= $nCon"
            }

            private fun wcnf(open: () -> BufferedReader): ClaspInput {
                var classic = false
                var maxVar = 0
                var clauses = 0
                var softTotal = BigInteger.ZERO
                open().useLines { lines ->
                    for (raw in lines) {
                        if (WCNF_PROBLEM_LINE.containsMatchIn(raw)) {
                            classic = true
                            break
                        }
                        val clause = raw.trim()
                        if (clause.isEmpty() || clause.startsWith("c")) continue
                        clauses++
                        val tokens = clause.split(WHITESPACE)
                        if (tokens[0] != "h") softTotal += tokens[0].toBigInteger()
                        for (literal in tokens.drop(1)) maxVar = maxOf(maxVar, abs(literal.toInt()))
                    }
                }
                if (classic) return ClaspInput(open, null) { it }
                val top = softTotal + BigInteger.ONE
                return ClaspInput(open, "p wcnf $maxVar $clauses $top") { raw ->
                    val clause = raw.trim()
                    when {
                        clause.isEmpty() || clause.startsWith("c") -> null
                        clause.startsWith("h ") -> "$top ${clause.removePrefix("h ")}"
                        else -> clause
                    }
                }
            }
        }
    }

    private val WCNF_PROBLEM_LINE = Regex("""(?m)^\s*p\s+wcnf\b""")

    private val PROBLEM_LINE = Regex("""(?m)^\s*\*\s*#variable=""")

    /** Problem-line fields the PB competition normalizer writes and clasp does not read. */
    private val UNREAD_FIELDS = Regex("""\s+(#equal|intsize)=\s*\d+""")
    private val VARIABLE = Regex("""x(\d+)""")
    private val WHITESPACE = Regex("""\s+""")
}
