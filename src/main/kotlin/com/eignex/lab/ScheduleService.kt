package com.eignex.lab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Creating and changing schedules: the checks a scheduled experiment passes before the lab reruns it on every commit. */
class ScheduleService internal constructor(private val services: ApiServices) {
    private val config get() = services.config
    private val store get() = services.store

    /** Create the schedule [input] describes; its id. A malformed one is refused as an [IllegalArgumentException]. */
    suspend fun create(input: JsonObject): Long {
        val spec = Json.decodeFromJsonElement(ScheduleSpec.serializer(), scheduleInput(input))
        require(spec.name.isNotBlank() && spec.ref.isNotBlank() && !spec.ref.startsWith("-")) { "name and ref are required" }
        Experiments.requirePlainName(spec.name)
        require(spec.intervalSec >= MIN_SCHEDULE_SEC) { "intervalSec must be at least $MIN_SCHEDULE_SEC" }
        validate(spec.experiment)
        services.experiments.requireRef(spec.ref, Repositories.source(spec.experiment.repository), services.trust(spec.experiment.repository))
        return store.schedules.create(spec.name, spec.ref, spec.experiment, spec.intervalSec)
    }

    /** Change schedule [id] as [input] says ([ScheduleUpdate]). */
    fun update(id: Long, input: JsonObject): Action {
        val spec = Json.decodeFromJsonElement(ScheduleUpdate.serializer(), scheduleInput(input))
        require(spec.intervalSec == null || spec.intervalSec >= MIN_SCHEDULE_SEC) { "intervalSec must be at least $MIN_SCHEDULE_SEC" }
        require(spec.nextCheckInSec == null || spec.nextCheckInSec >= 0) { "nextCheckInSec must not be negative" }
        spec.experiment?.let(::validate)
        val nextCheckAt = spec.nextCheckInSec?.let { System.currentTimeMillis() + it * 1000 }
        return if (store.schedules.update(id, spec.experiment, spec.intervalSec, nextCheckAt)) Action.Done("updated") else Action.Missing("no such schedule")
    }

    /** The runner's schedule thread finds it due within seconds; a push hook or relay can call this instead of waiting. */
    fun checkNow(id: Long): Action = if (store.schedules.checkNow(id)) Action.Accepted("checking") else Action.Missing("no such schedule")

    /** A run now, even at a commit the schedule already ran: what a rerun of an unmoved branch needs. */
    fun launchNow(id: Long): Action = if (store.schedules.launchNow(id)) Action.Accepted("launching") else Action.Missing("no such schedule")

    fun delete(id: Long): Action = if (store.schedules.delete(id)) Action.Done("deleted") else Action.Missing("no such schedule")

    private fun validate(experiment: ExperimentSpec) {
        requireDescription(experiment)
        val local = NodeTags.matches(experiment.nodeTags, config.localNodeTags)
        val pinned = !Placement.cloudOptIn(experiment) || Placement.localOnly(experiment, JobOrigin.Submitted(experiment.name))
        require(if (pinned) local else Placement.cloudCoreLimit(experiment, config, cloudSchedule = true) >= Experiments.arms(experiment).maxOf { it.cores }) {
            "the schedule's tags match no allowed worker; omit tags for auto placement or select configured capabilities"
        }
        val coreLimit = services.placement.coreLimit(experiment)
        Experiments.validate(experiment, if (local) config.maxParallel else coreLimit, coreLimit)
        require(Experiments.arms(experiment).none { "ref" in it.values }) {
            "a scheduled experiment runs every arm at the schedule's ref; drop ref from its configs"
        }
    }

    private companion object {
        const val MIN_SCHEDULE_SEC = 60L
    }
}
