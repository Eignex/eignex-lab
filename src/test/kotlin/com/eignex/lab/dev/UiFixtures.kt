package com.eignex.lab.dev

import com.eignex.lab.analysis.Trend
import com.eignex.lab.analysis.TrendCache
import com.eignex.lab.analysis.TrendData
import com.eignex.lab.analysis.TrendPoints
import com.eignex.lab.domain.*
import com.eignex.lab.bench.*
import com.eignex.lab.execution.BenchSpec
import com.eignex.lab.execution.BenchRuns
import com.eignex.lab.store.*
import com.eignex.lab.util.now
import kotlinx.serialization.json.*
import java.nio.file.Files

/** Small, deterministic records exercise the real queue, comparisons, references, and trend scoring. */
internal class UiFixtures(private val config: Config, private val store: Store, private val clock: () -> Long = ::now) {
    private val themes = listOf("scheduling", "packing", "graph-coloring", "routing", "allocation", "knapsack",
        "bin-packing", "network-flow", "set-covering", "matching", "arithmetic", "combinatorial-design")
    val problems = (0 until themes.size * 8).map { i ->
        val theme = themes[i % themes.size]
        Problem("preview/$theme", "$theme-${(i / themes.size + 1).toString().padStart(2, '0')}")
    }
    val sweep = ExperimentSpec("preview-sweep", problems = listOf(mapOf("suite" to "preview")),
        base = mapOf("timeout" to "60000"), configs = listOf(mapOf("label" to "candidate")),
        host = HostPlacement.AUTO, description = "Synthetic regression history for local UI development.")
    val comparison = sweep.copy(name = "preview-comparison", configs = listOf(mapOf("label" to "baseline"), mapOf("label" to "candidate")),
        description = "Simulated paired comparison. Pause, resume, cancel, retry, or change its priority.")

    fun seed() {
        for (spec in listOf(sweep, sweep.copy(name = "preview-nightly"))) {
            store.schedules.create(spec.name, "main", spec, 3600)
            repeat(10) { revision ->
                val id = create(spec, "${spec.name}@${sha(revision).take(8)}")
                start(id)
                store.plans.casePlan(id).forEach { complete(id, it, revision, "local") }
                store.finish(id, Status.DONE)
                val at = clock() - (10 - revision) * 86_400_000L
                store.executionTransaction { db ->
                    db.sqlUpdate("UPDATE jobs SET created_at = ?, started_at = ?, finished_at = ?, run_ms = ? WHERE id = ?",
                        at - 90_000, at - 80_000, at, 80_000, id)
                }
            }
        }
        val referenceRows = problems.flatMapIndexed { i, problem ->
            listOf("choco", "cp-sat").mapIndexed { solverIndex, solver ->
                problem.referenceKey to Reference(solver, false, null, if ((i + solverIndex) % 7 == 0) null else true,
                    false, 1200L + i * 240, 60_000, version = "local-preview-v1")
            }
        }
        store.references.put(referenceRows, "synthetic-local-fixtures")
        store.references.putFeatures(problems.map { ProblemFeatures(it.collection, it.problem, listOf(it.suite.substringAfter('/'))) })
        seedBench()
        val failed = create(comparison.copy(name = "preview-failed", description = "A simulated infrastructure failure; retry is available."))
        store.finish(failed, Status.FAILED, "Synthetic worker startup failure: use retry to exercise recovery.")
        val cancelled = create(sweep.copy(name = "preview-cancelled"))
        store.requestCancel(cancelled)
        val active = create(comparison)
        val paused = create(comparison.copy(name = "preview-paused", description = "Resume this job to watch its simulated cases progress."))
        store.setPaused(paused, true)
        create(sweep.copy(name = "preview-queued"))
        // Store real computed points before the first page request, including both schedules.
        TrendCache(TrendPoints.of(store)).use { cache ->
            for (schedule in store.schedules.all()) Trend.runs(TrendData.of(store), schedule.name, cache)
        }
        check(store.job(active)?.status == Status.QUEUED)
    }

    /** A dense solver bench mirrors real job pages: many fingerprints, long paths, metrics and files. */
    private fun seedBench() {
        val spec = ExperimentSpec("preview-bench", bench = BenchSpec("bench.json"),
            configs = listOf("root-off", "schedule-only", "bounded-root", "dense-unit").map { mapOf("label" to it) },
            description = "Synthetic solver benchmark with mixed build provenance, paired results, metrics and case artifacts.")
        val id = store.create(spec.name, "main", emptyList(), vcpus = 4, experiment = spec)
        val arms = Experiments.arms(spec).map { PlannedArm(it, sha(20)) }
        val problems = (0 until 36).map { i -> BenchRuns.problem(BenchCase(
            "pb-comp/normalized-PB25/DEC-LIN/scheduling/normalized-workload-${i.toString().padStart(2, '0')}",
            attributes = mapOf("recordSchema" to "solver-v1"))) }
        val plan = Experiments.cases(problems.size, arms.size, listOf(1, 3))
        store.plan(id, arms, problems, plan, plan.map { "preview solve benchmark" to 60L })
        store.setup(id, arms.first().sha)
        start(id)
        for (case in store.plans.casePlan(id)) {
            val elapsed = 900.0 + (case.index * 331 % 12000)
            val record = buildJsonObject {
                put("kind", "satisfy"); put("budgetMs", 60000)
                put("buildFingerprint", sha(case.index % 32)); put("validationPolicy", "source-v1")
                if (case.index % 13 != 0) { put("feasible", true); put("timeToBestMs", elapsed) }
            }
            val artifacts = listOf("report/solve-record.json", "report/cli.stdout", "report/cli.stderr", "runtime.json", "solve.log")
            val result = BenchResult(case.problem.benchCase!!.id,
                metrics = listOf(BenchMetric("elapsed", elapsed, "ms", MetricDirection.LOWER),
                    BenchMetric("time-to-best", elapsed * 0.9, "ms", MetricDirection.LOWER)),
                artifacts = artifacts.map(::BenchArtifact), details = record)
            store.commandStarted(id, case.index, "local")
            store.caseRecord(id, case.index, BenchRuns.record(BenchProtocol.json.encodeToString(BenchResult.serializer(), result), case.problem.benchCase!!, 0))
            store.commandFinished(id, case.index, 0)
            val dir = config.createJobDir(id).resolve("cases/${case.index}/out")
            artifacts.forEach { path ->
                Files.createDirectories(dir.resolve(path).parent)
                Files.writeString(dir.resolve(path), "Synthetic local preview artifact.\n$record\n")
            }
        }
        store.finish(id, Status.DONE)
    }

    fun create(spec: ExperimentSpec, name: String = spec.name): Long {
        val id = store.create(name, "main", emptyList(), vcpus = 4, experiment = spec)
        val arms = Experiments.arms(spec).map { PlannedArm(it, sha(id.toInt())) }
        val cases = Experiments.cases(problems.size, arms.size, emptyList())
        store.plan(id, arms, problems, cases, cases.map { "preview solve ${problems[it.problem].problem} --arm ${arms[it.arm].arm.label}" to 60L })
        store.setup(id, arms.first().sha)
        val dir = config.createJobDir(id)
        Files.writeString(dir.resolve("job.log"), "Synthetic local UI job $id. No worker or solver is executed.\n")
        return id
    }

    fun start(id: Long) = store.executionTransaction { db ->
        db.sqlUpdate("UPDATE jobs SET status = 'RUNNING', started_at = COALESCE(started_at, ?), running_since = ? WHERE id = ? AND status = 'QUEUED'",
            clock(), clock(), id)
    }

    fun complete(job: Long, case: CaseResult, revision: Int, host: String) {
        val i = problems.indexOf(case.problem).coerceAtLeast(0)
        // Improving, regressing, plateauing and step-change themes make the chart grid useful to inspect.
        val progress = when (i % themes.size % 4) {
            0 -> revision
            1 -> 9 - revision
            2 -> revision.coerceAtMost(5)
            else -> if (revision < 5) 3 else 8
        }
        val solved = (i * 37 + if (case.arm == "candidate") 1 else 0) % problems.size < 40 + progress * 5
        val record = buildJsonObject {
            put("kind", "satisfy"); put("budgetMs", 60_000)
            put("buildFingerprint", "synthetic-preview"); put("validationPolicy", "reported-v1")
            if (solved) { put("feasible", true); put("timeToBestMs", 1200 + (i * 733 + revision * 431) % 8000) }
        }
        store.commandStarted(job, case.index, host)
        store.caseRecord(job, case.index, record.toString())
        store.commandFinished(job, case.index, 0)
        val dir = config.createJobDir(job)
        Files.writeString(dir.resolve("${case.index}.out"), "Simulated ${case.arm}: ${case.problem.problem}\n$record\n")
        Files.writeString(dir.resolve("${case.index}.err"), "")
    }

    private fun sha(revision: Int): String = java.security.MessageDigest.getInstance("SHA-1")
        .digest("preview-$revision".toByteArray()).joinToString("") { "%02x".format(it) }
}
