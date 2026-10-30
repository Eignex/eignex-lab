package com.eignex.lab.execution

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

/** Queues a confirmation when a scheduled run's results flip against the schedule's previous run of the same experiment. */
internal class Confirmations(private val local: LocalNode, private val paths: DataPaths, private val store: Store) {
    fun queue(job: Job) {
        val spec = job.experiment
        val series = (job.origin as? JobOrigin.Scheduled)?.schedule ?: return
        if (!spec.isBench) return
        val schedule = store.schedules.all().singleOrNull { it.name == series } ?: return
        val fingerprint = Experiments.fingerprint(spec)
        val history = store.jobs(limit = PREVIOUS_RUNS, name = series)
            .filter { it.id < job.id && it.status == Status.DONE && it.experiment.let(Experiments::fingerprint) == fingerprint }
            .sortedByDescending { it.id }
        val previous = history.firstOrNull() ?: return
        val currentCases = firstArmCases(job.id)
        val previousCases = firstArmCases(previous.id)
        var reference = previous
        var flips = Confirm.flips(previousCases, currentCases)
        history.getOrNull(1)?.let { baseline ->
            val baselineCases = firstArmCases(baseline.id)
            val anchored = Confirm.persistent(baselineCases, previousCases, currentCases)
            if (anchored.persistentObjectiveLost.isNotEmpty()) {
                reference = baseline
                flips = anchored
            }
        }
        val before = reference.sha ?: return
        val after = checkNotNull(store.job(job.id)?.sha)
        if (before == after) return
        if (flips.confirmable.isEmpty()) return
        val confirmation = Confirm.spec(series, spec.copy(priority = schedule.priority), before, after, flips, job.id, reference.id,
            sourceCases = currentCases)
        if (store.jobs(limit = 100, name = confirmation.name).any { it.name == confirmation.name && it.experiment == confirmation }) return
        val id = store.create(confirmation.name, after, emptyList(), confirmation.parallel ?: local.defaultParallel, confirmation.priority, confirmation,
            JobOrigin.Confirmation(series))
        logJob(paths, job.id, "${flips.lost.size} feasibility lost, ${flips.gained.size} gained; " +
            "${flips.proofLost.size} proof lost, ${flips.proofGained.size} gained; " +
            "${flips.objectiveLost.size} objective worse, ${flips.objectiveGained.size} better against job ${reference.id}: queued confirmation job $id")
    }

    private fun firstArmCases(jobId: Long): List<CaseResult> {
        val label = store.plans.arms(jobId).firstOrNull()?.arm?.label ?: return emptyList()
        return store.plans.cases(jobId).filter { it.arm == label }
    }

    private companion object {
        const val PREVIOUS_RUNS = 50
    }
}
