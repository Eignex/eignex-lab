package com.eignex.lab.domain

import com.eignex.lab.util.*
import com.eignex.lab.analysis.REFERENCE_BACKEND
import com.eignex.lab.nomad.NomadBatchJob
import com.eignex.lab.nomad.NomadPlatform

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
        val benchConfirmation = spec.isBench && confirmation
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
        config.nomadQueue?.matching(spec, JobOrigin.Submitted(spec.name), cloudSchedule)?.filter { !it.native }
            ?.maxOfOrNull { it.template.budget.cpuMillis / 1000 } ?: 0

    /** The most cores a case of [spec] can have where it may run: the local machine's when its tags match it. */
    fun coreLimit(spec: ExperimentSpec, config: Config): Int =
        if (NodeTags.matches(spec.nodeTags, config.localNodeTags)) config.cores else cloudCoreLimit(spec, config)

    /** A job that may run on a cloud node needs the lab's cloud settings, runs repository-owned work, and fits a node. */
    fun requireFits(spec: ExperimentSpec, config: Config) {
        require(spec.host in setOf(Experiments.LAB_HOST, Experiments.AWS_HOST, Experiments.AUTO_HOST)) {
            "host must be local, cloud or auto (or lab or aws, their older names)"
        }
        if (spec.host == Experiments.LAB_HOST) return
        require(cloudCoreLimit(spec, config) >= Experiments.arms(spec).maxOf { it.cores }) { "no configured Nomad cloud worker fits its cases" }
    }

    /**
     * Why a job of [origin] placed as [spec] may not run on a worker of [platform], null when it may: a job that stays
     * local ([localOnlyReason]) never runs on a cloud one, and a cloud-only one never on the local machine.
     */
    fun refusal(platform: NomadPlatform, spec: ExperimentSpec, origin: JobOrigin, cloudSchedule: Boolean): String? = when (platform) {
        NomadPlatform.LINUX -> localOnlyReason(spec, origin, cloudSchedule)
            ?: "experiment is placed on the local host".takeIf { spec.host == Experiments.LAB_HOST }
        NomadPlatform.MAC -> "experiment is cloud-only".takeIf { spec.host == Experiments.AWS_HOST }
    }

    /** Whether the local machine may take [spec]'s work: it is not cloud-only, and its tags match [localTags]. */
    fun localEligible(spec: ExperimentSpec, localTags: Set<String>): Boolean =
        spec.host != Experiments.AWS_HOST && NodeTags.matches(spec.nodeTags, localTags)

    /**
     * Admit a session of [manifest] on a worker of [template], or fail saying why. A native one fits the local machine
     * ([cores], [maxParallel]); a cloud one runs privately networked in its node class's pool ([ClusterSettings.poolOf])
     * and fits it, the pool offering the tags and the market the experiment asks for.
     */
    fun admit(cluster: ClusterSettings, manifest: WorkManifest, template: NomadBatchJob, cores: Int, maxParallel: Int) {
        if (template.platform == NomadPlatform.MAC) {
            require(manifest.budget.cpuMillis <= cores * 1000 && manifest.budget.parallel <= maxParallel)
            return
        }
        require(template.networkMode == null && template.statusHostVolume == "lab-status") { "cloud workers require private container networking and read-only interruption status" }
        val pool = requireNotNull(cluster.poolOf(template)) { "cloud pool is not configured" }
        require(manifest.budget.cpuMillis <= pool.cpuMillis && manifest.budget.memoryMiB <= pool.availableMemoryMiB && template.cpuCapacity.logicalCpus == pool.vcpus)
        require(NodeTags.matches(manifest.experiment.nodeTags, pool.capabilities))
        require(pool.market == NodeTags.market(manifest.experiment.nodeTags))
    }
}
