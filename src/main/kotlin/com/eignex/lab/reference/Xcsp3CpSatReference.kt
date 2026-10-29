package com.eignex.lab.reference

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * XCSP3 reference adapter for CPMpy and OR-Tools cp-sat in the [IMAGE] container.
 * Returns a [ReferenceResult] scored like any other reference.
 */
internal object Xcsp3CpSatReference {
    const val IMAGE = "eignex-lab-xcsp3-cpsat:source-preserving-v2"
    const val VERSION = "xcsp3-source-preserving-v2"

    /** Label on every reference container, so stragglers can be reaped (`docker kill --filter label`). */
    const val CONTAINER_LABEL = "eignex-lab-xcsp3-ref"

    private const val DOCKER_INSPECT_WAIT_MS = 5_000L

    private val json = Json { ignoreUnknownKeys = true }

    private val docker = DockerReference(IMAGE, CONTAINER_LABEL, "xcsp3 cp-sat")

    /** The container's one-line JSON verdict (`images/xcsp3-cpsat/solve.py` in eignex-lab). */
    @Serializable
    private data class Verdict(
        val exit: String,
        val version: String = "",
        val runtime: Double? = null,
        val objective: Double? = null,
        val maximize: Boolean? = null,
        val error: String? = null,
        val exactObjective: String? = null,
        val seed: Long? = null,
        val workers: Int? = null,
        val cpModelPresolve: Boolean? = null,
    )

    /** Kill any leftover reference containers from an earlier interrupted run. */
    fun reapStragglers() = docker.reapStragglers()

    /** Whether the converter image is built (`docker image inspect`). */
    fun imageAvailable(): Boolean = docker.imageAvailable()

    private val image: String by lazy {
        val p = ProcessBuilder("docker", "image", "inspect", "--format", "{{.Id}}", IMAGE)
            .redirectErrorStream(true).start()
        val id = p.inputStream.bufferedReader().readText().trim()
        check(p.waitFor(DOCKER_INSPECT_WAIT_MS, TimeUnit.MILLISECONDS) && p.exitValue() == 0) {
            "xcsp3 cp-sat: cannot identify $IMAGE"
        }
        id
    }

    fun identity(build: String = image): String = "$IMAGE@$build|$VERSION"

    /** Solve [instance]'s XCSP3 `.xml` with cp-sat in the container under [budget], pinning cp-sat to
     *  [workers] search workers (the sweep parallelizes across instances, so 1 keeps a container from
     *  fanning out to every core). The objective sense (`maximize`), unknowable without parsing the
     *  model, is carried back in `stats["maximize"]`. A compressed instance is mounted as a decompressed
     *  staged copy, since the container reads a plain file. */
    fun run(instance: Instance, budget: Budget, workers: Int, seed: Long): ReferenceResult =
        CorpusFiles.withPlainFile(instance.model) { run(it, budget, workers, seed) }

    private fun run(xml: File, budget: Budget, workers: Int, seed: Long): ReferenceResult {
        val timeoutSec = (budget.timeoutMillis / 1000).coerceAtLeast(1)
        val name = docker.containerName()
        val cmd = docker.command(name, arguments = listOf(
            "/in/${xml.name}",
            timeoutSec.toString(),
            workers.toString(),
            seed.toString(),
        ), cpus = workers, mounts = listOf("${xml.parentFile.absolutePath}:/in:ro"))
        val run = docker.exec(cmd, name, budget.timeoutMillis)
        return parse(cmd, run.stdout, identity(), run.stderr, run.exit, run.timedOut).copy(elapsedMs = run.elapsedMs)
    }

    internal fun parse(
        cmd: List<String>,
        stdout: String,
        identity: String,
        error: String = "",
        exit: Int? = 0,
        timedOut: Boolean = false,
    ): ReferenceResult {
        val v = stdout.lineSequence().lastOrNull { exit == 0 && !timedOut && it.trimStart().startsWith("{") }
            ?.let { json.decodeFromString<Verdict>(it) }
        check(v == null || v.version == VERSION) {
            "xcsp3 cp-sat: incompatible importer '${v?.version}'; rebuild $IMAGE from eignex-lab images/xcsp3-cpsat"
        }
        val result = when {
            exit != 0 || timedOut -> undecided(cmd, stdout, "container exit=$exit, watchdog=$timedOut ($error)")
            v == null -> undecided(cmd, stdout, "no JSON verdict ($error)")
            v.exit == "ERROR" -> undecided(cmd, stdout, v.error ?: "unknown")
            v.exit in setOf("OPTIMAL", "FEASIBLE", "UNSATISFIABLE") && v.cpModelPresolve != true ->
                undecided(cmd, stdout, "source-preserving native settings are missing")
            v.exit in setOf("OPTIMAL", "FEASIBLE") && Witness.xcsp3(stdout) == null ->
                undecided(cmd, stdout, "complete source witness cannot be retained")
            v.exit in setOf("OPTIMAL", "FEASIBLE") && v.maximize != null &&
                v.exactObjective?.toBigIntegerOrNull() == null ->
                undecided(cmd, stdout, "exact integer objective is missing")
            else -> decided(cmd, stdout, v)
        }
        return result.copy(stats = result.stats + mapOf("referenceVersion" to identity,
            "referenceExit" to exit.toString(), "referenceTimeout" to timedOut.toString(), "referenceStderr" to error))
    }

    private fun decided(cmd: List<String>, stdout: String, v: Verdict): ReferenceResult {
        val feasible = when (v.exit) {
            "OPTIMAL", "FEASIBLE" -> true
            "UNSATISFIABLE" -> false
            else -> null
        }
        val timeMs = ((v.runtime ?: 0.0) * 1000).toLong()
        return ReferenceResult(
            feasible = feasible,
            objective = v.objective,
            timeToBestMs = timeMs.takeIf { feasible == true },
            timeToFirstFeasibleMs = timeMs.takeIf { feasible == true },
            proven = v.exit == "OPTIMAL" || v.exit == "UNSATISFIABLE",
            stats = mapOf("solveTime" to (v.runtime?.toString() ?: "0"), "maximize" to v.maximize.toString()) +
                listOfNotNull(v.seed?.let { "referenceSeed" to "$it" }, v.workers?.let { "referenceWorkers" to "$it" },
                    v.cpModelPresolve?.let { "cpModelPresolve" to "$it" }).toMap(),
            rawOutput = stdout,
            command = cmd.joinToString(" "),
            exactObjective = v.exactObjective,
        )
    }

    // A container that ran but gave no verdict, from a parse failure, an unsupported constraint or an OOM kill,
    // fails the same way on every run. It is a result, so the bench caches it instead of solving it again.
    private fun undecided(cmd: List<String>, stdout: String, error: String) = ReferenceResult(
        feasible = null,
        objective = null,
        timeToBestMs = null,
        proven = false,
        stats = mapOf("error" to "xcsp3 cp-sat: $error"),
        rawOutput = stdout,
        command = cmd.joinToString(" "),
    )
}
