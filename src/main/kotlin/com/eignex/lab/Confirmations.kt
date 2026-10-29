package com.eignex.lab

/** Queues a confirmation when a scheduled run's results flip against the schedule's previous run of the same experiment. */
internal class Confirmations(private val config: Config, private val store: Store) {
    fun queue(job: Job) {
        val spec = job.experiment
        val series = (job.origin as? JobOrigin.Scheduled)?.schedule ?: return
        if (spec.bench == null) return
        val schedule = store.schedules.all().singleOrNull { it.name == series } ?: return
        val fingerprint = Experiments.fingerprint(spec)
        val previous = store.jobs(limit = PREVIOUS_RUNS, name = series)
            .filter { it.id < job.id && it.status == Status.DONE && it.experiment.let(Experiments::fingerprint) == fingerprint }
            .maxByOrNull { it.id } ?: return
        val before = previous.sha ?: return
        val after = checkNotNull(store.job(job.id)?.sha)
        if (before == after) return
        val flips = Confirm.flips(firstArmCases(previous.id), firstArmCases(job.id))
        if (flips.confirmable.isEmpty()) return
        val confirmation = Confirm.spec(series, spec.copy(priority = schedule.priority), before, after, flips, job.id, previous.id)
        if (store.jobs(limit = 100, name = confirmation.name).any { it.name == confirmation.name && it.experiment == confirmation }) return
        val id = store.create(confirmation.name, after, emptyList(), confirmation.parallel ?: config.defaultParallel, confirmation.priority, confirmation,
            JobOrigin.Confirmation(series))
        logJob(config, job.id, "${flips.lost.size} feasibility lost, ${flips.gained.size} gained; " +
            "${flips.proofLost.size} proof lost, ${flips.proofGained.size} gained; " +
            "${flips.objectiveLost.size} objective worse, ${flips.objectiveGained.size} better against job ${previous.id}: queued confirmation job $id")
    }

    private fun firstArmCases(jobId: Long): List<CaseResult> {
        val label = store.plans.arms(jobId).firstOrNull()?.arm?.label ?: return emptyList()
        return store.plans.cases(jobId).filter { it.arm == label }
    }

    private companion object {
        const val PREVIOUS_RUNS = 50
    }
}
