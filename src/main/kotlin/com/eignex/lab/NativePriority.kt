package com.eignex.lab

/** Pinned work can reserve native capacity ahead of auto work at the same or lower priority. */
internal class NativePriority(private val store: Store) {
    fun allows(id: Long): Boolean {
        val job = store.job(id) ?: return false
        if (job.experiment == null) return false
        val schedules = store.schedules()
        fun reserved(job: Job): Boolean = job.experiment?.let {
            Placement.localOnly(it, job.name, Placement.cloudSchedule(job.name, schedules))
        } == true
        if (reserved(job)) return true
        return !store.active(withCommands = false).any {
            !it.paused && !it.cancelRequested && it.priority >= job.priority && reserved(it)
        }
    }
}
