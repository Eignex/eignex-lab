package com.eignex.lab.reference

import com.eignex.lab.checker.Mps
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import com.eignex.lab.checker.ObjectiveSense as ObjectiveDirection

/**
 * MPS reference adapter for SCIP in the [IMAGE] container. Streams the model through
 * `/dev/stdin` and drives `read`/`optimize`/`quit` in batch mode. Containers have
 * memory limits, a deadline watchdog and label-based cleanup. Rows use `reference/scip.csv`.
 */
internal object ScipReference {
    const val IMAGE = "eignex-lab-scip:latest"

    /** Label on every reference container, so stragglers can be reaped (`docker kill --filter label`). */
    const val CONTAINER_LABEL = "eignex-lab-scip-ref"

    /** SCIP's own memory limit in MB, in addition to the shared Docker ceiling. */
    private const val MEMORY_LIMIT_MB = "6000"
    private const val DOCKER_INSPECT_WAIT_MS = 5_000L

    /** Threshold below SCIP's `infinity` (1e20); a reported bound at or beyond it means SCIP has none. */
    private const val SCIP_INFINITY = 1e19

    private val docker = DockerReference(IMAGE, CONTAINER_LABEL, "scip")

    /** Kill any leftover reference containers from an earlier interrupted run. */
    fun reapStragglers() = docker.reapStragglers()

    /** Whether the SCIP image is built (`docker image inspect`). */
    fun imageAvailable(): Boolean = docker.imageAvailable()

    /** SCIP's tolerances and gap limit, its defaults: recorded with every row and part of its cache identity. */
    internal const val OPTIONS = "numerics/feastol=1e-06,numerics/epsilon=1e-09,limits/gap=0"

    /** The image a result came from, by id, so a rebuilt SCIP never replays an older build's results. */
    private val image: String by lazy {
        runCatching {
            val p = ProcessBuilder(
                "docker",
                "image",
                "inspect",
                "--format",
                "{{.Id}}",
                IMAGE,
            ).redirectErrorStream(true).start()
            val id = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(DOCKER_INSPECT_WAIT_MS, TimeUnit.MILLISECONDS)
            id.takeIf { p.exitValue() == 0 }
        }.getOrNull() ?: "unknown"
    }

    /** What a cached result depends on: the image, the options and the validation rules. */
    fun identity(build: String = image): String = "$IMAGE@$build|$OPTIONS|${MpsWitness.VERSION}"

    /** Solve [instance] (an MPS instance) with SCIP under [budget], single-threaded. The instance is piped on
     *  stdin and read as MPS; SCIP's objective sense (from the model's `OBJSENSE`) orients the reported
     *  bound. Its solution is displayed after the solve and checked against the model ([MpsWitness]), a repair
     *  going through [HighsReference.repair]. */
    fun run(instance: Instance, budget: Budget): ReferenceResult {
        val text = CorpusFiles.readText(instance.model)
        val model = runCatching { Mps.parse(text) }.getOrNull()
        // MPS default is minimise; an `OBJSENSE MAXIMIZE` flips it. SCIP reports the bound in this
        // orientation, so record it for the entry (and virtual-best comparison).
        val maximize = model?.sense == ObjectiveDirection.MAXIMIZE
        val timeoutSec = (budget.timeoutMillis / 1000).coerceAtLeast(1)
        val name = docker.containerName()
        val cmd = docker.command(name, arguments = listOf(
            "-c", "set limits time $timeoutSec",
            "-c", "set limits memory $MEMORY_LIMIT_MB",
            "-c", "read /dev/stdin mps",
            "-c", "optimize",
            "-c", "display solution",
            "-c", "quit",
        ), interactive = true)
        val run = docker.exec(cmd, name, budget.timeoutMillis) { it.write(text) }
        val stdout = run.stdout
        val claim = parseClaim(stdout)
        val attempt = MpsAttempt(
            label = "default",
            stdout = stdout,
            elapsedMs = run.elapsedMs,
            command = cmd.joinToString(" "),
            claim = claim,
            verdict = MpsReference.judge(model, claim, HighsReference.repair.takeIf { HighsReference.available() }),
            reported = lines(
                stdout,
            ).lastOrNull {
                it.startsWith(
                    "SCIP Status",
                )
            }?.let { mapOf("solverStatus" to it.substringAfter(':').trim()) }.orEmpty(),
        )
        return MpsReference.result(listOf(attempt), model, maximize, identity(), OPTIONS)
    }

    /**
     * SCIP's `optimize` summary reports `SCIP Status : … [optimal solution found] / [infeasible] / …`, a `Primal Bound`
     * (at or beyond infinity when it has none), a `Dual Bound` and a `Gap`; `display solution` then lists the
     * solution's nonzero values after `objective value:`, or says `no solution available`.
     */
    internal fun parseClaim(stdout: String): MpsWitness.Claim {
        val lines = lines(stdout)
        val status = lines.lastOrNull { it.startsWith("SCIP Status") }.orEmpty()
        fun bound(name: String) = lines.lastOrNull { it.startsWith(name) }
            ?.substringAfter(":")?.trim()?.substringBefore(' ')?.toDoubleOrNull()?.takeIf { abs(it) < SCIP_INFINITY }
        val start = lines.indexOfLast { it.startsWith("objective value:") }
        val assignment = if (start < 0) {
            null
        } else {
            val values = lines.drop(start + 1).map { SOLUTION_LINE.matchEntire(it) }.takeWhile { it != null }
            values.filterNotNull().associate { match ->
                val (name, value) = match.destructured
                name to value.toDouble()
            }
        }
        return MpsWitness.Claim(
            status = when {
                "optimal solution found" in status -> MpsWitness.Status.OPTIMAL
                "[infeasible]" in status -> MpsWitness.Status.INFEASIBLE
                "limit reached" in status || "interrupted" in status -> MpsWitness.Status.LIMIT
                else -> MpsWitness.Status.UNKNOWN
            },
            primal = bound("Primal Bound"),
            dual = bound("Dual Bound"),
            gap = lines.lastOrNull { it.startsWith("Gap") }?.substringAfter(":")?.trim()?.substringBefore(' ')
                ?.toDoubleOrNull()?.div(PERCENT),
            assignment = assignment,
        )
    }

    private fun lines(stdout: String) = stdout.lineSequence().map { it.trim() }.toList()

    /** One value `display solution` lists: the variable, its value, and its objective coefficient. */
    private val SOLUTION_LINE = Regex("""(\S+)\s+(\S+)\s+\(obj:[^)]*\)""")
    private const val PERCENT = 100.0
}
