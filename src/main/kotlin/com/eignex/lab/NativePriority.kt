package com.eignex.lab

/** Recheck native work between iterations, as well as at admission and replacement. */
internal class NativePriority(private val store: Store) {
    fun allows(id: Long, tags: Set<String>? = null): Boolean {
        val job = store.job(id) ?: return false
        if (job.experiment == null) return false
        val schedules = store.schedules()
        fun reserved(job: Job): Boolean = job.experiment?.let {
            Placement.localOnly(it, job.name, Placement.cloudSchedule(job.name, schedules))
        } == true
        val pinned = reserved(job)
        return !store.active(withCommands = false).any {
            val spec = it.experiment
            it.id != id && !it.paused && !it.cancelRequested && spec != null &&
                spec.host != Experiments.AWS_HOST && (tags == null || NodeTags.matches(spec.nodeTags, tags)) &&
                (it.priority > job.priority || it.priority == job.priority && !pinned && reserved(it)) &&
                (!it.setupDone || spec.isAnalysis || store.unclaimed(it.id) > 0)
        }
    }
}
