package com.eignex.lab

/**
 * Where a job may run, decided by the lab: ordinary jobs go to the local machine first, then a cloud node
 * (Nomad workers). Jobs eligible for the local machine have cores and `parallel` capped to it at submit
 * ([Experiments.capToLocal]); explicit node selectors can instead require a compatible cloud node. Its
 * [ExperimentSpec.host] records the decision for the queue: `lab` for a job only the local machine runs, `auto` for one
 * the local machine and cloud nodes share, each running whole iterations of it, or `aws` for cloud-only work.
 * Nomad replacements retain the frozen recipe and render case commands for their own worker platform.
 */
object Placement {
    const val LOCAL_RESERVED = "local-only placement is reserved for sweeps and reference runs; use auto placement"

    /** Ordinary jobs may borrow idle native capacity, but cannot pin themselves to it. */
    fun requireAutomatic(spec: ExperimentSpec, origin: JobOrigin) {
        if (localOnly(spec, origin)) return
        require("local" !in spec.nodeTags?.require.orEmpty() && "cloud" !in spec.nodeTags?.exclude.orEmpty()) { LOCAL_RESERVED }
    }

    /**
     * The jobs that stay on the local machine whatever is free: scheduled runs (`<schedule>@<sha>`) and their
     * confirmations, which the sweeps and the trend compare against each other on one machine; reference runs, which
     * need the local machine's reference solvers; and an analysis job, which reads
     * captures kept on the local machine. [cloudSchedule] says [name] is a run of a schedule that opts in to the cloud
     * ([Placement.cloudSchedule]): such a run may go to either unless another of these reasons holds, and its
     * legacy confirmations still stay. Repository bench confirmations retain their original placement and platform.
     */
    fun localOnly(spec: ExperimentSpec, origin: JobOrigin, cloudSchedule: Boolean = false): Boolean = localOnlyReason(spec, origin, cloudSchedule) != null

    /** [localOnly] for a job known only by its [name] ([JobOrigin.of]). */
    fun localOnly(spec: ExperimentSpec, name: String, cloudSchedule: Boolean = false): Boolean = localOnly(spec, JobOrigin.of(name), cloudSchedule)

    /** Which of [localOnly]'s reasons keeps [spec] named [name] on the local machine, in a few plain words; null when
     *  none does. */
    fun localOnlyReason(spec: ExperimentSpec, origin: JobOrigin, cloudSchedule: Boolean = false): String? {
        val confirmation = origin is JobOrigin.Confirmation
        val benchConfirmation = spec.bench != null && confirmation
        val pinnedLocal = spec.host == Experiments.LAB_HOST ||
            "local" in spec.nodeTags?.require.orEmpty() || "cloud" in spec.nodeTags?.exclude.orEmpty()
        return when {
            confirmation && (!benchConfirmation || pinnedLocal) -> "a confirmation"
            origin is JobOrigin.Scheduled && !cloudSchedule -> "a scheduled run whose schedule stays local"
            spec.isAnalysis -> "an analysis of captures kept on the local machine"
            runCatching { Experiments.arms(spec) }.getOrDefault(emptyList()).any { it.values["backend"] == REFERENCE_BACKEND } ->
                "a reference run, which needs the local machine's reference solvers"
            else -> null
        }
    }

    /** Where a job placed on [host] may run, in the words the pages and the client use: [LOCAL_ONLY] (`lab`),
     *  [LOCAL_OR_CLOUD] (`auto`) or [CLOUD] (`aws`). */
    fun where(host: HostPlacement?): String = when (host) {
        HostPlacement.AUTO -> LOCAL_OR_CLOUD
        HostPlacement.CLOUD -> CLOUD
        HostPlacement.LOCAL, null -> LOCAL_ONLY
    }

    const val LOCAL_ONLY = "local only"
    const val LOCAL_OR_CLOUD = "local or cloud"
    const val CLOUD = "cloud"

    /**
     * Whether a schedule's saved [spec] opts its runs in to the cloud: its host is `auto`, or `cloud` (`aws`), which
     * still lets the local machine take a run first. `local` (`lab`), the default, keeps them on the local machine.
     * Where a run goes is not what it measures ([Experiments.fingerprint]), so opting in keeps the schedule's trend.
     */
    fun cloudOptIn(spec: ExperimentSpec): Boolean = spec.host in setOf(Experiments.AUTO_HOST, Experiments.AWS_HOST)

    /** Whether job [name] is a run (`<schedule>@<sha>`, not a confirmation) of one of [schedules] that opts in to the
     *  cloud ([cloudOptIn]), as its schedule's current spec says. */
    fun cloudSchedule(origin: JobOrigin, schedules: List<Schedule>): Boolean =
        origin is JobOrigin.Scheduled && schedules.any { it.name == origin.schedule && cloudOptIn(it.experiment) }

    /** [host] with the lab's own limits: the cloud settings and the sizes within the quota. [schedules], the lab's
     *  schedules, say whether a scheduled run's schedule opts in to the cloud ([cloudSchedule]); a submitted job is
     *  placed without them, so its name alone never takes it off the local machine. */
    fun host(spec: ExperimentSpec, origin: JobOrigin, config: Config, schedules: List<Schedule> = emptyList()): HostPlacement {
        NodeTags.validate(spec.nodeTags)
        requireAutomatic(spec, origin)
        val local = NodeTags.matches(spec.nodeTags, config.localNodeTags)
        val cores = runCatching { Experiments.arms(spec).maxOf { it.cores } }.getOrDefault(Int.MAX_VALUE)
        val cloud = cloudCoreLimit(spec, config, cloudSchedule(origin, schedules)) >= cores
        val staysLocal = localOnly(spec, origin, cloudSchedule(origin, schedules))
        if (staysLocal) {
            require(local) { "this job stays local, but its node tags do not match the local node" }
            return Experiments.LAB_HOST
        }
        require(cloud) { "$LOCAL_RESERVED; no compatible cloud worker is configured for these node tags and resources" }
        return if (local) Experiments.AUTO_HOST else Experiments.AWS_HOST
    }

    /** [host] for a job known only by its [name] ([JobOrigin.of]). */
    fun host(spec: ExperimentSpec, name: String, config: Config, schedules: List<Schedule> = emptyList()): HostPlacement =
        host(spec, JobOrigin.of(name), config, schedules)

    /** Largest compatible provider-neutral cloud budget. */
    fun cloudCoreLimit(spec: ExperimentSpec, config: Config, cloudSchedule: Boolean = false): Int =
        config.nomadQueue?.matching(spec, JobOrigin.Submitted(spec.name), cloudSchedule)?.filter { it.os == "linux" }
            ?.maxOfOrNull { it.template.budget.cpuMillis / 1000 } ?: 0
}
