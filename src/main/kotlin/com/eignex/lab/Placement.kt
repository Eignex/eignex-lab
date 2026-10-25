package com.eignex.lab

/**
 * Where a job may run, decided by the lab: ordinary jobs go to the local machine first, then a cloud node
 * ([AwsWorker]). Jobs eligible for the local machine have cores and `parallel` capped to it at submit
 * ([Experiments.capToLocal]); explicit node selectors can instead require a compatible cloud node. Its
 * [ExperimentSpec.host] records the decision for the queue: `lab` for a job only the local machine runs, `auto` for one
 * the local machine and cloud nodes share, each running whole iterations of it, or `aws` for cloud-only work.
 * A planned job a node gives back is placed again ([release]), and whichever host takes
 * it next renders each case's command for itself as it starts it ([Runner.render]).
 */
object Placement {
    /**
     * The jobs that stay on the local machine whatever is free: scheduled runs (`<schedule>@<sha>`) and their
     * confirmations, which the sweeps and the trend compare against each other on one machine; reference runs, which
     * need the local machine's reference solvers; exact problem lists, which only confirmations use; a profile with a
     * whole-run deadline, which the local machine enforces on its own processes; and an analysis job, which reads
     * captures kept on the local machine. [cloudSchedule] says [name] is a run of a schedule that opts in to the cloud
     * ([Placement.cloudSchedule]): such a run may go to either unless another of these reasons holds, and its
     * confirmations still stay.
     */
    fun localOnly(spec: ExperimentSpec, name: String, cloudSchedule: Boolean = false): Boolean = localOnlyReason(spec, name, cloudSchedule) != null

    /** Which of [localOnly]'s reasons keeps [spec] named [name] on the local machine, in a few plain words; null when
     *  none does. */
    fun localOnlyReason(spec: ExperimentSpec, name: String, cloudSchedule: Boolean = false): String? = when {
        Confirm.SUFFIX in name -> "a confirmation"
        '@' in name && !cloudSchedule -> "a scheduled run whose schedule stays local"
        spec.problemList.isNotEmpty() -> "an exact problem list"
        spec.profile?.runTimeoutSec != null -> "a profile with a whole-run deadline"
        spec.isAnalysis -> "an analysis of captures kept on the local machine"
        runCatching { Experiments.arms(spec) }.getOrDefault(emptyList()).any { it.values["backend"] == REFERENCE_BACKEND } ->
            "a reference run, which needs the local machine's reference solvers"
        else -> null
    }

    /** Nomad workers enforce the whole-run deadline and stage exact problem files on every platform. */
    fun nomadLocalOnlyReason(spec: ExperimentSpec, name: String, cloudSchedule: Boolean = false): String? =
        localOnlyReason(spec.copy(problemList = emptyList(), profile = spec.profile?.copy(runTimeoutSec = null)), name, cloudSchedule)

    /** Where a job placed on [host] may run, in the words the pages and the client use: [LOCAL_ONLY] (`lab`),
     *  [LOCAL_OR_CLOUD] (`auto`) or [CLOUD] (`aws`). */
    fun where(host: String?): String = when (host) {
        Experiments.AUTO_HOST -> LOCAL_OR_CLOUD
        Experiments.AWS_HOST -> CLOUD
        else -> LOCAL_ONLY
    }

    /** Whether a queued job on [host] at [priority] is below the cloud's priority floor [minPriority]
     *  ([AwsConfig.nodeMinPriority]): work the local machine could run, for which no node launches and which no node
     *  takes. A job only a node runs never is. */
    fun belowFloor(host: String?, priority: Int, minPriority: Int): Boolean = host != Experiments.AWS_HOST && priority < minPriority

    const val LOCAL_ONLY = "local only"
    const val LOCAL_OR_CLOUD = "local or cloud"
    const val CLOUD = "cloud"

    /**
     * Whether a schedule's saved [spec] opts its runs in to the cloud: its host is `auto`, or `cloud` (`aws`), which
     * still lets the local machine take a run first. `local` (`lab`), the default, keeps them on the local machine.
     * Where a run goes is not what it measures ([Experiments.fingerprint]), so opting in keeps the schedule's trend.
     */
    fun cloudOptIn(spec: ExperimentSpec): Boolean = Experiments.storedHost(spec.host) in setOf(Experiments.AUTO_HOST, Experiments.AWS_HOST)

    /** Whether job [name] is a run (`<schedule>@<sha>`, not a confirmation) of one of [schedules] that opts in to the
     *  cloud ([cloudOptIn]), as its schedule's current spec says. */
    fun cloudSchedule(name: String, schedules: List<Schedule>): Boolean =
        '@' in name && Confirm.SUFFIX !in name &&
            schedules.any { it.name == name.substringBefore('@') && cloudOptIn(it.experiment) }

    /** The host [spec] named [name] is placed on: `lab` when only the local machine may or can run it, `auto`
     *  otherwise, a `JFR measurement` job included, which both measure alike ([CliMeasurements]). */
    fun host(spec: ExperimentSpec, name: String, awsFits: Boolean, cloudSchedule: Boolean = false): String =
        if (localOnly(spec, name, cloudSchedule) || !awsFits) Experiments.LAB_HOST else Experiments.AUTO_HOST

    /** [host] with the lab's own limits: the cloud settings and the sizes within the quota. [schedules], the lab's
     *  schedules, say whether a scheduled run's schedule opts in to the cloud ([cloudSchedule]); a submitted job is
     *  placed without them, so its name alone never takes it off the local machine. */
    fun host(spec: ExperimentSpec, name: String, config: Config, schedules: List<Schedule> = emptyList()): String {
        NodeTags.validate(spec.nodeTags)
        val local = NodeTags.matches(spec.nodeTags, config.localNodeTags)
        val cores = runCatching { Experiments.arms(spec).maxOf { it.cores } }.getOrDefault(Int.MAX_VALUE)
        val cloud = cloudCoreLimit(spec, config, cloudSchedule(name, schedules)) >= cores
        val staysLocal = if (config.executionBackend == ExecutionBackendKind.NOMAD)
            nomadLocalOnlyReason(spec, name, cloudSchedule(name, schedules)) != null else localOnly(spec, name, cloudSchedule(name, schedules))
        if (staysLocal) {
            require(local) { "this job stays local, but its node tags do not match the local node" }
            return Experiments.LAB_HOST
        }
        require(local || cloud) { "no configured node matches the required and excluded tags" }
        return if (!local) Experiments.AWS_HOST else if (cloud) Experiments.AUTO_HOST else Experiments.LAB_HOST
    }

    /** Whether a node can run [spec]: the lab has cloud settings, and some size within the quota holds its largest
     *  case. */
    fun awsFits(spec: ExperimentSpec, aws: AwsConfig?): Boolean {
        if (aws == null) return false
        val arms = runCatching { Experiments.arms(spec) }.getOrDefault(emptyList())
        return arms.isNotEmpty() && aws.sizeFor(arms.maxOf { it.cores }, spec.nodeTags, spec.isProfile) != null
    }

    /** Nomad admission uses provider-neutral pool templates; legacy launch shapes remain behind its backend. */
    fun cloudCoreLimit(spec: ExperimentSpec, config: Config, cloudSchedule: Boolean = false): Int =
        if (config.executionBackend == ExecutionBackendKind.NOMAD) {
            requireNotNull(config.nomadQueue) { "Nomad execution requires LAB_NOMAD_QUEUE_SETTINGS" }
                .matching(spec, spec.name, cloudSchedule).filter { it.os == "linux" }
                .maxOfOrNull { it.template.budget.cpuMillis / 1000 } ?: 0
        } else cloudSizes(spec, config).maxOfOrNull { it.cores } ?: 0

    /** Declared launch shapes, or an already-running node carrying a node-specific operator label. */
    fun cloudSizes(spec: ExperimentSpec, config: Config): List<InstanceSize> {
        val aws = config.aws ?: return emptyList()
        val onDemand = NodeTags.onDemand(spec.isProfile, spec.nodeTags)
        val declared = aws.sizes.filter { NodeTags.matches(spec.nodeTags, it.tags(onDemand)) }
        if (declared.isNotEmpty() || spec.nodeTags == null) return declared
        val report = readPoolReport(config)?.takeIf { now() - it.updatedAt <= ManualLaunch.STALE_REPORT_MS } ?: return emptyList()
        return report.nodes.filter { node ->
            (!onDemand || node.market == AwsWorker.ON_DEMAND) && NodeTags.matches(spec.nodeTags, node.tags)
        }.mapNotNull { node -> aws.sizes.firstOrNull { it.type == node.type }?.copy(labels = node.tags) }
    }

    /**
     * Place again every queued job by the rules above, run at the runner's start. One that has not been planned is cut
     * down to fit the local machine first ([Experiments.capToLocal]): a job queued before the lab capped jobs may ask
     * for more cores than the local machine has, or name a host its submitter chose. A scheduled run is placed by its
     * schedule's current spec, so one queued before its schedule opted in to the cloud, or out of it, follows the
     * change. A planned one placed on `aws` is [release]d: one a node gave back before nodes released them. Returns
     * the ids moved or cut down.
     */
    fun replaceQueued(store: Store, config: Config): List<Long> {
        val schedules = store.schedules()
        return store.active(withCommands = false).filter { it.backend == ExecutionBackendKind.LEGACY && it.status == Status.QUEUED }.mapNotNull { job ->
            val spec = job.experiment ?: return@mapNotNull null
            if (job.total > 0) return@mapNotNull job.id.takeIf { spec.host == Experiments.AWS_HOST && release(store, config, job.id) }
            val capped = if (NodeTags.matches(spec.nodeTags, config.localNodeTags)) Experiments.capToLocal(spec, config.cores, config.maxParallel) else spec
            val host = runCatching { host(capped, job.name, config, schedules) }.getOrElse {
                // A node-specific label may only become available after the cloud pool is adopted.
                return@mapNotNull null
            }
            if (host == spec.host && capped == spec || !store.place(job.id, host, capped)) return@mapNotNull null
            if (capped.parallel != spec.parallel) capped.parallel?.let { store.setParallel(job.id, it) }
            job.id
        }
    }

    /**
     * Place queued, planned job [jobId] again by the rules above, once a node gave it back: the local machine, first in
     * the lab's order, may then take it over, rendering each case's command for itself ([Runner.render]), and a node may
     * still when pressure calls for one. False when it stays where it was.
     */
    fun release(store: Store, config: Config, jobId: Long): Boolean {
        val job = store.job(jobId)?.takeIf { it.backend == ExecutionBackendKind.LEGACY && it.status == Status.QUEUED } ?: return false
        val spec = job.experiment ?: return false
        val host = host(spec, job.name, config, store.schedules())
        return host != spec.host && store.release(jobId, host)
    }
}
