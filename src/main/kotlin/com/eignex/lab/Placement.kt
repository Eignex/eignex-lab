package com.eignex.lab

/**
 * Where a job may run, decided by the lab rather than whoever submitted it: every job goes Mac first, then an AWS
 * node ([AwsWorker]). Every job fits the Mac, its cores and `parallel` capped to it at submit ([Experiments.capToMac]):
 * AWS is overflow for the Mac's queue, launched on pressure alone. Its [ExperimentSpec.host] records the decision for
 * the queue: `lab` for a job only the Mac runs, `auto` for one whichever is free first takes (`aws`, for one only a
 * node runs, is left to an operator's override); the taker then sets it to where the job went. A planned job a node gives back is
 * placed again ([release]), and whichever host takes it next rewrites its queued commands for itself ([Runner.adopt]).
 */
object Placement {
    /**
     * The jobs that stay on the Mac whatever is free: scheduled runs (`<schedule>@<sha>`) and their confirmations,
     * which the sweeps and the trend compare against each other on one machine; reference runs, which need the Mac's
     * reference solvers; exact problem lists, which only confirmations use; and a profile with a whole-run deadline,
     * which the Mac enforces on its own processes.
     */
    fun macOnly(spec: ExperimentSpec, name: String): Boolean =
        '@' in name || Confirm.SUFFIX in name || spec.problemList.isNotEmpty() || spec.profile?.runTimeoutSec != null ||
            runCatching { Experiments.arms(spec) }.getOrDefault(emptyList()).any { it.values["backend"] == REFERENCE_BACKEND }

    /** The host [spec] named [name] is placed on: `lab` when only the Mac may or can run it, `auto` otherwise, a
     *  `profileCli` job included, which both measure alike ([CliMeasurements]). */
    fun host(spec: ExperimentSpec, name: String, awsFits: Boolean): String =
        if (macOnly(spec, name) || !awsFits) Experiments.LAB_HOST else Experiments.AUTO_HOST

    /** [host] with the lab's own limits: the AWS settings and the sizes within the quota. */
    fun host(spec: ExperimentSpec, name: String, config: Config): String = host(spec, name, awsFits(spec, config.aws))

    /** Whether a node can run [spec]: the lab has AWS settings, and some size within the quota holds its largest case. */
    fun awsFits(spec: ExperimentSpec, aws: AwsConfig?): Boolean {
        if (aws == null) return false
        val arms = runCatching { Experiments.arms(spec) }.getOrDefault(emptyList())
        return arms.isNotEmpty() && aws.sizeFor(arms.maxOf { it.cores }) != null
    }

    /**
     * Place again every queued job by the rules above, run at the runner's start. One that has not been planned is cut
     * down to fit the Mac first ([Experiments.capToMac]): a job queued before the lab capped jobs may ask for more
     * cores than the Mac has, or name a host its submitter chose. A planned one placed on `aws` is [release]d: one a
     * node gave back before nodes released them. Returns the ids moved or cut down.
     */
    fun replaceQueued(store: Store, config: Config): List<Long> =
        store.active(withCommands = false).filter { it.status == Status.QUEUED }.mapNotNull { job ->
            val spec = job.experiment ?: return@mapNotNull null
            if (job.total > 0) return@mapNotNull job.id.takeIf { spec.host == Experiments.AWS_HOST && release(store, config, job.id) }
            val capped = Experiments.capToMac(spec, config.cores, config.maxParallel)
            val host = host(capped, job.name, config)
            if (host == spec.host && capped == spec || !store.place(job.id, host, capped)) return@mapNotNull null
            if (capped.parallel != spec.parallel) capped.parallel?.let { store.setParallel(job.id, it) }
            job.id
        }

    /**
     * Place queued, planned job [jobId] again by the rules above, once a node gave it back: the Mac, first in the lab's
     * order, may then take it over, rewriting its queued commands for itself ([Runner.adopt]), and a node may still
     * when pressure calls for one. False when it stays where it was.
     */
    fun release(store: Store, config: Config, jobId: Long): Boolean {
        val job = store.job(jobId)?.takeIf { it.status == Status.QUEUED } ?: return false
        val spec = job.experiment ?: return false
        val host = host(spec, job.name, config)
        return host != spec.host && store.release(jobId, host)
    }
}
