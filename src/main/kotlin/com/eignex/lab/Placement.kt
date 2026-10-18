package com.eignex.lab

/**
 * Where a job may run, decided by the lab rather than whoever submitted it: every job goes Mac first, then an AWS
 * node ([AwsWorker]). Its [ExperimentSpec.host] records the decision for the queue: `lab` for a job only the Mac runs,
 * `aws` for one only a node runs, `auto` for one whichever is free first takes; the taker then sets it to where the
 * job went, since a planned job's commands name paths on the host that planned it.
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

    /** The host [spec] named [name] is placed on: `lab` when only the Mac may or can run it, `aws` when only a node can
     *  (a `profileCli` job, or one the Mac has too few cores or too little memory for), `auto` otherwise. */
    fun host(spec: ExperimentSpec, name: String, macFits: Boolean, awsFits: Boolean): String = when {
        macOnly(spec, name) || !awsFits -> Experiments.LAB_HOST
        spec.profileCli || !macFits -> Experiments.AWS_HOST
        else -> Experiments.AUTO_HOST
    }

    /** [host] with the lab's own limits: the Mac's cores and parallel cases, and the AWS sizes within the quota. */
    fun host(spec: ExperimentSpec, name: String, config: Config): String =
        host(spec, name, macFits(spec, config), awsFits(spec, config.aws))

    /** Whether the Mac can run [spec]: its largest case fits the Mac's cores, and its `parallel` the Mac's limit. */
    fun macFits(spec: ExperimentSpec, config: Config): Boolean = !spec.profileCli &&
        runCatching { Experiments.arms(spec) }.getOrDefault(emptyList()).all { it.cores <= config.cores } &&
        (spec.parallel == null || spec.parallel <= config.maxParallel)

    /** Whether a node can run [spec]: the lab has AWS settings, and some size within the quota holds its largest case. */
    fun awsFits(spec: ExperimentSpec, aws: AwsConfig?): Boolean {
        if (aws == null) return false
        val arms = runCatching { Experiments.arms(spec) }.getOrDefault(emptyList())
        return arms.isNotEmpty() && aws.sizeFor(arms.maxOf { it.cores }) != null
    }

    /**
     * Place again every queued job that has not been planned, by the rules above: a job queued before the lab placed
     * jobs itself may name a host its submitter chose. A planned one keeps its host. Returns the ids moved.
     */
    fun replaceQueued(store: Store, config: Config): List<Long> =
        store.active(withCommands = false).filter { it.status == Status.QUEUED && it.total == 0 }.mapNotNull { job ->
            val spec = job.experiment ?: return@mapNotNull null
            val host = host(spec, job.name, config)
            job.id.takeIf { host != spec.host && store.place(job.id, host) }
        }
}
