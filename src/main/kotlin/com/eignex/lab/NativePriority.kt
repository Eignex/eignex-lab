package com.eignex.lab

/** Native admission and iteration claims honor priority; pinned work wins equal-priority ties. */
internal class NativePriority(private val store: Store) {
    fun allows(id: Long): Boolean {
        val job = store.job(id) ?: return false
        if (job.experiment == null) return false
        val schedules = store.schedules()
        fun reserved(job: Job): Boolean = job.experiment?.let {
            Placement.localOnly(it, job.name, Placement.cloudSchedule(job.name, schedules))
        } == true
        return !store.active(withCommands = false).any {
            it.id != id && !it.paused && !it.cancelRequested && it.experiment != null &&
                it.experiment.host != Experiments.AWS_HOST &&
                (it.priority > job.priority || it.priority == job.priority && reserved(it) && !reserved(job))
        }
    }
}
