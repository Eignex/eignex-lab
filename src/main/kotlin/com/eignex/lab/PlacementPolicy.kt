package com.eignex.lab

/**
 * Where the lab places a job, by one rule wherever a job is placed: when it is submitted, when its tags change, and
 * when it is retried. [schedules] are the lab's schedules, whose opt-in lets their runs go to the cloud.
 */
class PlacementPolicy(private val config: Config, private val schedules: () -> List<Schedule>) {
    /**
     * The host [spec] goes to as a job of [origin] ([Placement.host]). A [checked] placement is refused, as an
     * [IllegalArgumentException], when it may go to the cloud and no configured cloud worker fits its cases.
     */
    fun resolve(spec: ExperimentSpec, origin: JobOrigin, checked: Boolean = true): HostPlacement {
        val host = Placement.host(spec, origin, config, if (origin is JobOrigin.Submitted) emptyList() else schedules())
        if (checked) requireFits(spec.copy(host = host))
        return host
    }

    /** A job that may run on a cloud node needs the lab's cloud settings, runs repository-owned work, and fits a node. */
    fun requireFits(spec: ExperimentSpec) {
        require(spec.host in setOf(Experiments.LAB_HOST, Experiments.AWS_HOST, Experiments.AUTO_HOST)) {
            "host must be local, cloud or auto (or lab or aws, their older names)"
        }
        if (spec.host == Experiments.LAB_HOST) return
        require(Placement.cloudCoreLimit(spec, config) >= Experiments.arms(spec).maxOf { it.cores }) {
            "no configured Nomad cloud worker fits its cases"
        }
    }

    /** The most cores a case of [spec] can have where it may run: the local machine's when its tags match it. */
    fun coreLimit(spec: ExperimentSpec): Int =
        if (NodeTags.matches(spec.nodeTags, config.localNodeTags)) config.cores else Placement.cloudCoreLimit(spec, config)

    companion object {
        /** What placing a job on [host] means, for the submitter. */
        fun note(host: HostPlacement): String = when (host) {
            HostPlacement.CLOUD -> "placed on the cloud: a cloud node runs it"
            HostPlacement.AUTO -> "placed by the lab: the local machine when it is free, else a cloud node"
            HostPlacement.LOCAL -> "placed locally"
        }
    }
}
