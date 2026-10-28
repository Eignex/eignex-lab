package com.eignex.lab.reference

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A MiniZinc model solved by a registered `minizinc --solver` (cp-sat by default, also chuffed and the rest). The model
 * goes to `minizinc` as it is, with its data file and its collection's include directories; the DZN output stream gives
 * the incumbents (`----------`), the proof (`==========`) and infeasibility (`=====UNSATISFIABLE=====`).
 */
internal object MiniZincReference {
    /** Fixes the instance a random-data model compiles to, so results with another seed are not mixed in. */
    const val RANDOM_SEED = 1

    private const val SOLUTION_SEPARATOR = "----------"
    private const val SEARCH_COMPLETE = "=========="
    private const val UNSATISFIABLE = "=====UNSATISFIABLE====="
    private const val OBJECTIVE_KEY = "objective ="
    private const val MODEL_OBJECTIVE_KEY = "_objective"
    private const val STAT_PREFIX = "%%%mzn-stat:"
    private const val GRACE_MS = 30_000L
    private const val REGISTRY_WAIT_MS = 10_000L
    private const val STDERR_CAP = 2000

    /** The solver ids `minizinc --solvers` lists, lowercased. */
    private val registered: Set<String> by lazy {
        runCatching {
            val proc = ProcessBuilder("minizinc", "--solvers").redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor(REGISTRY_WAIT_MS, TimeUnit.MILLISECONDS)
            Regex("""\(([^)]*)\)""").findAll(out)
                .flatMap { it.groupValues[1].split(',') }
                .map { it.trim().lowercase() }
                .toSet()
        }.getOrElse { emptySet() }
    }

    fun available(solver: String): Boolean = solver.lowercase() in registered

    fun command(instance: Instance, solver: String, budget: Budget, processors: Int, free: Boolean): List<String> {
        val (optimize, _) = solveKind(instance)
        return buildList {
            addAll(listOf("minizinc", "--solver", solver, "--time-limit", budget.timeoutMillis.toString()))
            addAll(listOf("--output-mode", "dzn", "--random-seed", RANDOM_SEED.toString(), "-s"))
            if (optimize) addAll(listOf("-a", "--output-objective"))
            if (free) add("-f")
            // Pinned even at 1: cp-sat otherwise starts a worker per core, and cases run side by side would each
            // start a full pool, so their times would say more about the load than about the instance.
            addAll(listOf("-p", processors.toString()))
            for (dir in instance.includeDirs) addAll(listOf("-I", dir.absolutePath))
            add(instance.model.absolutePath)
            instance.data?.let { add(it.absolutePath) }
        }
    }

    fun run(instance: Instance, solver: String, budget: Budget, processors: Int, free: Boolean): ReferenceResult {
        val startNanos = System.nanoTime()
        val identity = MiniZincIdentity.capture(solver, budget.timeoutMillis)
        val remaining = budget.timeoutMillis - (System.nanoTime() - startNanos) / 1_000_000
        check(remaining > 0) { "MiniZinc identity exhausted the reference case budget" }
        val cmd = command(instance, solver, Budget(remaining), processors, free)
            .let { listOf(identity.binary.path) + it.drop(1) }
        val process = ProcessBuilder(cmd).start()
        process.outputStream.close()
        // A solve that ignores its time limit must not hang the case; the lab's own case timeout is the backstop.
        val killed = AtomicBoolean(false)
        Thread {
            runCatching {
                if (!process.waitFor(budget.timeoutMillis * 2 + 20_000, TimeUnit.MILLISECONDS)) {
                    killed.set(true)
                    process.destroyForcibly()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        val stderr = StringBuilder()
        val drain = Thread { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }.apply {
            isDaemon = true
            start()
        }
        val parsed = parse(process.inputStream.bufferedReader().lineSequence(), startNanos)
        if (!process.waitFor(GRACE_MS, TimeUnit.MILLISECONDS)) {
            killed.set(true)
            process.destroyForcibly()
            process.waitFor()
        }
        drain.join(GRACE_MS)
        val exit = process.exitValue()
        val stats = LinkedHashMap(parsed.stats + identity.stats)
        if (exit != 0 && parsed.feasible == null) {
            check(killed.get()) { "minizinc failed (exit $exit): ${stderr.toString().take(STDERR_CAP)}" }
            stats["killed"] = "hard-timeout"
        }
        stats["exit"] = exit.toString()
        val (_, maximize) = solveKind(instance)
        stats["maximize"] = maximize.toString()
        return parsed.copy(
            stats = stats,
            elapsedMs = (System.nanoTime() - startNanos) / 1_000_000,
            command = cmd.joinToString(" "),
        )
    }

    /** The verdict a MiniZinc DZN output stream reports, stamping incumbent times against [startNanos]. */
    internal fun parse(lines: Sequence<String>, startNanos: Long = System.nanoTime()): ReferenceResult {
        val raw = StringBuilder()
        val stats = LinkedHashMap<String, String>()
        var exact: ExactObjective? = null
        var timeToBestMs: Long? = null
        var timeToFirstFeasibleMs: Long? = null
        var proven = false
        var unsat = false
        var anySolution = false
        for (rawLine in lines) {
            raw.appendLine(rawLine)
            val line = rawLine.trim()
            when {
                line == SOLUTION_SEPARATOR -> {
                    anySolution = true
                    val elapsed = (System.nanoTime() - startNanos) / 1_000_000
                    timeToBestMs = elapsed
                    if (timeToFirstFeasibleMs == null) timeToFirstFeasibleMs = elapsed
                }
                line == SEARCH_COMPLETE -> proven = true
                // MiniZinc prints it only once the search has completed: a proof of infeasibility.
                line == UNSATISFIABLE -> {
                    unsat = true
                    proven = true
                }
                line.startsWith(STAT_PREFIX) -> line.removePrefix(STAT_PREFIX).trim().split('=', limit = 2)
                    .takeIf { it.size == 2 }?.let { stats[it[0].trim()] = it[1].trim() }
                line.startsWith(OBJECTIVE_KEY) || line.startsWith(MODEL_OBJECTIVE_KEY) ->
                    ExactObjective.parse(line.substringAfter('=').trim().removeSuffix(";").trim())?.let { exact = it }
            }
        }
        return ReferenceResult(
            feasible = when {
                anySolution -> true
                unsat -> false
                else -> null
            },
            objective = exact?.approximate().takeIf { anySolution },
            exactObjective = exact?.toString().takeIf { anySolution },
            timeToBestMs = timeToBestMs,
            timeToFirstFeasibleMs = timeToFirstFeasibleMs,
            proven = proven,
            stats = stats,
            rawOutput = raw.toString(),
            command = "",
        )
    }

    /** `(optimize, maximize)` from the model's last `solve` item, comments stripped, so no model needs compiling. A
     *  model whose solve item is not in the top `.mzn` is treated as satisfaction: feasibility only, no `-a`. */
    fun solveKind(instance: Instance): Pair<Boolean, Boolean> {
        val stripped = CorpusFiles.readText(instance.model)
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("%[^\n]*"), " ")
        val keyword = Regex("""\bsolve\b[^;]*?\b(satisfy|minimize|maximize)\b""", RegexOption.DOT_MATCHES_ALL)
            .findAll(stripped).lastOrNull()?.groupValues?.get(1)
        return when (keyword) {
            "maximize" -> true to true
            "minimize" -> true to false
            else -> false to false
        }
    }
}
