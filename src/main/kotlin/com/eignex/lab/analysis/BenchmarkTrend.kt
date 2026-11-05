package com.eignex.lab.analysis

import com.eignex.lab.bench.*
import com.eignex.lab.domain.*
import kotlinx.serialization.Serializable

/** A metric keeps its workload and unit: unlike quantities are never averaged together. */
@Serializable
data class TrendMetric(val caseId: String, val name: String, val unit: String, val direction: MetricDirection, val value: Double)

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
)

internal object BenchmarkTrends {
    fun summarize(cases: List<CaseResult>): BenchmarkTrend {
        var succeeded = 0; var failed = 0; var skipped = 0; var pending = 0
        val metrics = mutableListOf<TrendMetric>()
        for (case in cases) {
            val result = case.record?.let {
                runCatching { BenchProtocol.json.decodeFromJsonElement(BenchResult.serializer(), it) }.getOrNull()
            }?.takeIf { it.caseId == case.problem.benchCase?.id }
            when {
                case.status == Status.DONE && result?.status == BenchStatus.SUCCESS -> {
                    succeeded++
                    metrics += result.metrics.filter { it.value.isFinite() }.map {
                        TrendMetric(result.caseId, it.name, it.unit, it.direction, it.value)
                    }
                }
                result?.status == BenchStatus.SKIPPED -> skipped++
                case.status.isTerminal -> failed++
                else -> pending++
            }
        }
        return BenchmarkTrend(succeeded, failed, skipped, pending, metrics)
    }
}
