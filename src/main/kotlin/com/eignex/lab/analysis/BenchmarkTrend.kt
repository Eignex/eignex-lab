package com.eignex.lab.analysis

import com.eignex.lab.bench.*
import com.eignex.lab.domain.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import com.eignex.lab.store.ExecutionStore

/** A metric keeps its workload and unit: unlike quantities are never averaged together. */
@Serializable
data class TrendMetric(val caseId: String, val name: String, val unit: String, val direction: MetricDirection, val value: Double,
    val engine: String? = null, val provider: String? = null, val kernel: String? = null)

/** A planned workload retains its theme and outcome even when it has no timing. */
@Serializable
data class BenchmarkWorkload(val caseId: String, val theme: String?, val status: String,
    val workload: String = caseId, val threshold: Double? = null, val controls: String? = null,
    val engines: Map<String, String> = emptyMap(), val instrumented: Boolean = false)

/** A qualified change keeps its workload visible without selecting an individual chart. */
@Serializable
data class BenchmarkChange(val caseId: String, val kind: String, val metric: String?, val confirmation: String)

@Serializable
data class BenchmarkChanges(val failed: Int, val recovered: Int, val regressed: Int, val improved: Int) {
    companion object {
        fun of(flips: Flips) = BenchmarkChanges(flips.benchmarkFailed.size, flips.benchmarkRecovered.size,
            flips.metricLost.size, flips.metricGained.size)
    }
}

@Serializable
data class BenchmarkTrend(
    val succeeded: Int,
    val failed: Int,
    val skipped: Int,
    val pending: Int,
    val metrics: List<TrendMetric>,
    val changes: BenchmarkChanges? = null,
    val confirmed: BenchmarkChanges? = null,
    val workloads: List<BenchmarkWorkload> = emptyList(),
    val baselineJob: Long? = null,
    val transitions: List<BenchmarkChange> = emptyList(),
)

internal object BenchmarkTrends {
    fun transitions(flips: Flips, confirmed: Flips?, confirmationStatus: Status?, confirmationCases: List<CaseResult>): List<BenchmarkChange> {
        fun events(changes: Flips): List<BenchmarkChange> {
            fun correctness(kind: String, problems: List<Problem>) = problems.mapNotNull { p ->
                p.benchCase?.let { BenchmarkChange(it.id, kind, null, "unconfirmed") }
            }
            fun timing(kind: String, metrics: List<MetricChange>, problems: List<Problem>) =
                if (metrics.isNotEmpty()) metrics.mapNotNull { m ->
                    m.problem.benchCase?.let { BenchmarkChange(it.id, kind, m.metric, "unconfirmed") }
                } else problems.mapNotNull { p -> p.benchCase?.let {
                    BenchmarkChange(it.id, kind, it.attributes["regressionMetric"], "unconfirmed")
                } }
            return correctness("failed", changes.benchmarkFailed) + correctness("recovered", changes.benchmarkRecovered) +
                timing("regressed", changes.metricRegressions, changes.metricLost) +
                timing("improved", changes.metricImprovements, changes.metricGained)
        }
        val agreed = confirmed?.let(::events).orEmpty().map { Triple(it.kind, it.caseId, it.metric) }.toSet()
        val selected = confirmationCases.mapNotNull { it.problem.benchCase?.id }.toSet()
        return events(flips).map { event ->
            val confirmation = when {
                Triple(event.kind, event.caseId, event.metric) in agreed -> "confirmed"
                confirmationStatus == null -> "unconfirmed"
                !confirmationStatus.isTerminal -> "pending"
                event.caseId !in selected -> "not selected"
                confirmationStatus != Status.DONE -> "inconclusive"
                else -> "not confirmed"
            }
            event.copy(confirmation = confirmation)
        }
    }

    fun summarize(cases: List<CaseResult>, repository: String): BenchmarkTrend {
        var succeeded = 0; var failed = 0; var skipped = 0; var pending = 0
        val metrics = mutableListOf<TrendMetric>()
        val workloads = mutableListOf<BenchmarkWorkload>()
        for (case in cases) {
            val result = case.record?.let {
                runCatching { BenchProtocol.json.decodeFromJsonElement(BenchResult.serializer(), it) }.getOrNull()
            }?.takeIf { it.caseId == case.problem.benchCase?.id }
            val attribution = result?.details?.get("metricAttribution") as? JsonObject
            val status = when {
                case.status == Status.DONE && result?.status == BenchStatus.SUCCESS -> {
                    succeeded++
                    metrics += result.metrics.filter { it.value.isFinite() }.map {
                        val source = attribution?.get(it.name) as? JsonObject
                        fun field(key: String) = (source?.get(key) as? JsonPrimitive)?.contentOrNull
                        TrendMetric(result.caseId, it.name, it.unit, it.direction, it.value,
                            engine = field("engine") ?: if (repository == "Eignex/koblas" && it.name == "median_ns") "jvm-default" else null, provider = field("provider"), kernel = field("kernel"))
                    }
                    "success"
                }
                result?.status == BenchStatus.SKIPPED -> { skipped++; "skipped" }
                case.status.isTerminal -> { failed++; "failed" }
                else -> { pending++; "pending" }
            }
            val plan = case.problem.benchCase
            val theme = plan?.attributes?.get("theme") ?: if (repository == "Eignex/koblas")
                koblasTheme(plan?.parameters?.get("row") ?: plan?.id.orEmpty().substringAfterLast('/')) else null
            val controls = (result?.details?.get("benchmarkControls") as? JsonObject)?.takeIf { it.isNotEmpty() }
            val engines = (result?.details?.get("engineStatuses") as? JsonObject)?.mapNotNull { (engine, value) ->
                (value as? JsonPrimitive)?.contentOrNull?.let { engine to it }
            }?.toMap().orEmpty()
            workloads += BenchmarkWorkload(plan?.id ?: case.problem.problem, theme, status,
                workload = plan?.parameters?.get("row") ?: plan?.id ?: case.problem.problem,
                threshold = plan?.attributes?.get("regressionThreshold")?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it < 1 },
                controls = controls?.let { ExecutionStore.digest(it.toString()) }, engines = engines, instrumented = case.instrumented)
        }
        return BenchmarkTrend(succeeded, failed, skipped, pending, metrics, workloads = workloads)
    }

    // Historical captures predate theme attributes; their frozen rows still identify the routine.
    private fun koblasTheme(row: String): String? = when (row.substringBefore('+')) {
        "dot", "axpy", "scal", "nrm2", "asum", "iamax", "rot", "swap", "copy", "sum", "copy-overlap", "axpy-views" -> "Dense Level 1"
        "gemv", "symv", "ger", "syr", "syr2", "trsv", "trmv", "ger-window-shrinking" -> "Dense Level 2"
        "gemm", "gemmt", "symm", "syrk", "syr2k", "trsm", "trmm" -> "Dense Level 3"
        "spdot-raw", "spdot-sparse", "spdot-column", "spaxpy-raw", "spscatter-raw", "spgather", "spgather-zero", "spdot-view", "spbuild-triplets", "spbuild-duplicates", "spdot-slice", "spaxpy-slice", "spscatter-slice" -> "Sparse Level 1"
        "spgemv", "spsymv", "sptrsv", "sptrmv" -> "Sparse Level 2"
        "spsymm", "spmm", "spmm-right", "spsyrk-dense", "sptrsm", "sptrmm" -> "Sparse Level 3"
        else -> null
    }
}
