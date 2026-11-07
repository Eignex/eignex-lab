package com.eignex.lab.analysis

import com.eignex.lab.bench.*
import com.eignex.lab.domain.*
import kotlinx.serialization.Serializable

/** A metric keeps its workload and unit: unlike quantities are never averaged together. */
@Serializable
data class TrendMetric(val caseId: String, val name: String, val unit: String, val direction: MetricDirection, val value: Double)

/** A planned workload retains its theme and outcome even when it has no timing. */
@Serializable
data class BenchmarkWorkload(val caseId: String, val theme: String?, val status: String)

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
)

internal object BenchmarkTrends {
    fun summarize(cases: List<CaseResult>, repository: String): BenchmarkTrend {
        var succeeded = 0; var failed = 0; var skipped = 0; var pending = 0
        val metrics = mutableListOf<TrendMetric>()
        val workloads = mutableListOf<BenchmarkWorkload>()
        for (case in cases) {
            val result = case.record?.let {
                runCatching { BenchProtocol.json.decodeFromJsonElement(BenchResult.serializer(), it) }.getOrNull()
            }?.takeIf { it.caseId == case.problem.benchCase?.id }
            val status = when {
                case.status == Status.DONE && result?.status == BenchStatus.SUCCESS -> {
                    succeeded++
                    metrics += result.metrics.filter { it.value.isFinite() }.map {
                        TrendMetric(result.caseId, it.name, it.unit, it.direction, it.value)
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
            workloads += BenchmarkWorkload(plan?.id ?: case.problem.problem, theme, status)
        }
        return BenchmarkTrend(succeeded, failed, skipped, pending, metrics, workloads = workloads)
    }

    // Historical captures predate theme attributes; their frozen rows still identify the routine.
    private fun koblasTheme(row: String): String? = when (row.substringBefore('+')) {
        "dot", "axpy", "scal", "nrm2", "asum", "iamax", "rot", "swap", "copy", "sum" -> "Dense Level 1"
        "gemv", "symv", "ger", "syr", "syr2", "trsv", "trmv", "ger-window-shrinking" -> "Dense Level 2"
        "gemm", "gemmt", "symm", "syrk", "syr2k", "trsm", "trmm" -> "Dense Level 3"
        "spdot-raw", "spdot-sparse", "spdot-column", "spaxpy-raw", "spscatter-raw", "spgather", "spgather-zero" -> "Sparse Level 1"
        "spgemv", "spsymv", "sptrsv", "sptrmv" -> "Sparse Level 2"
        "spsymm", "spmm", "spsyrk-dense", "sptrsm", "sptrmm" -> "Sparse Level 3"
        else -> null
    }
}
