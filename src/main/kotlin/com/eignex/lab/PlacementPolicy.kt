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

    /** [Placement.requireFits] on this lab. */
    fun requireFits(spec: ExperimentSpec) = Placement.requireFits(spec, config)

    /** [Placement.coreLimit] on this lab. */
    fun coreLimit(spec: ExperimentSpec): Int = Placement.coreLimit(spec, config)

    companion object {
        /** What placing a job on [host] means, for the submitter. */
        fun note(host: HostPlacement): String = when (host) {
            HostPlacement.CLOUD -> "placed on the cloud: a cloud node runs it"
            HostPlacement.AUTO -> "placed by the lab: the local machine when it is free, else a cloud node"
            HostPlacement.LOCAL -> "placed locally"
        }
    }
}
