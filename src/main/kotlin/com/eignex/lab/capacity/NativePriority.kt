package com.eignex.lab.capacity

import com.eignex.lab.analysis.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

/** Recheck native work between iterations, as well as at admission and replacement. */
internal class NativePriority(private val store: Store) {
    fun allows(id: Long, tags: Set<String>? = null): Boolean {
        val job = store.job(id) ?: return false
        val schedules = store.schedules.all()
        fun reserved(job: Job): Boolean = Placement.localOnly(job.experiment, job.origin, Placement.cloudSchedule(job.origin, schedules))
        val pinned = reserved(job)
        return !store.active(withCommands = false).any {
            val spec = it.experiment
            it.id != id && !it.paused && !it.cancelRequested &&
                spec.host != Experiments.AWS_HOST && (tags == null || NodeTags.matches(spec.nodeTags, tags)) &&
                (it.priority > job.priority || it.priority == job.priority && !pinned && reserved(it)) &&
                (!it.setupDone || spec.isAnalysis || store.unclaimed(it.id) > 0)
        }
    }
}
