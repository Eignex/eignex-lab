package com.eignex.lab.execution

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import com.eignex.lab.worker.PreparationResult

internal data class ReplaySource(val job: Long, val preparation: PreparationResult, val label: String)

internal fun replaySources(store: Store, spec: ExperimentSpec, arms: List<PlannedArm>): Map<String, ReplaySource> {
    val bench = spec.work.bench ?: return emptyMap()
    if (bench.replayJobs.isEmpty()) return emptyMap()
    require(bench.replayJobs.keys == arms.map { it.arm.label }.toSet()) { "replay must pin every arm" }
    require(bench.frozenCases.isNotEmpty()) { "replay needs frozen workload inputs" }
    return arms.associate { arm ->
        val job = requireNotNull(bench.replayJobs[arm.arm.label])
        val original = requireNotNull(store.job(job)) { "replay job is unavailable" }
        require(original.status in listOf(Status.DONE, Status.FAILED) && original.experiment.repository == spec.repository) {
            "replay requires a completed or failed job in the same repository"
        }
        val originalBench = requireNotNull(original.experiment.work.bench)
        val request = BenchRuns.request(bench, arm.arm)
        val originalArm = requireNotNull(store.plans.arms(job).firstOrNull {
            it.sha == arm.sha && originalBench.manifest == bench.manifest &&
                BenchRuns.request(originalBench, it.arm) == request
        }) {
            "replay cannot change the original revision, entry point or runtime request"
        }
        val preparation = requireNotNull(store.execution.acceptedPreparation(job)) {
            "replay preparation was not retained"
        }
        require(preparation.bundles.all { it.build == null })
        BenchRuns.align(bench.frozenCases, preparation.armPlans[originalArm.arm.label] ?: requireNotNull(preparation.benchPlan))
        arm.arm.label to ReplaySource(job, preparation, originalArm.arm.label)
    }
}

internal fun benchReplay(sources: Map<String, ReplaySource>, spec: BenchSpec): BenchReplay? {
    val first = sources.values.firstOrNull() ?: return null
    val descriptor = requireNotNull(first.preparation.benchDescriptor)
    require(sources.values.all { it.preparation.benchDescriptor?.id == descriptor.id })
    return BenchReplay(sources.mapValues { (_, source) ->
        BenchRuns.align(spec.frozenCases, source.preparation.armPlans[source.label] ?: requireNotNull(source.preparation.benchPlan))
    }, descriptor, first.preparation.corpus, first.preparation.sharedInputs)
}
