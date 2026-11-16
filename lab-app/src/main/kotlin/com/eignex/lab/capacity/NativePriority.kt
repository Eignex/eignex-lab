package com.eignex.lab.capacity

import com.eignex.lab.analysis.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

/**
 * Recheck native work between iterations, as well as at admission and replacement. Another job comes first when it
 * ranks higher and still wants the native worker: its work waits, and is not already running on cloud workers. A job
 * preparing on a cloud node, or whose rows cloud workers are running, would leave the local machine idle for nothing
 * if it held lower-ranked work off; once it waits again (prepared, or its cloud workers gone), it comes first again,
 * and native work yields between rows.
 */
internal class NativePriority(private val store: Store) {
    fun allows(id: Long, tags: Set<String>? = null): Boolean {
        val active = store.active(withCommands = false)
        val job = active.firstOrNull { it.id == id } ?: store.jobsByIds(listOf(id)).singleOrNull() ?: return false
        val schedules = store.schedules.all()
        fun reserved(job: Job): Boolean = Placement.localOnly(job.experiment, job.origin, Placement.cloudSchedule(job.origin, schedules))
        val pinned = reserved(job)
        val cloud by lazy { store.execution.cloudWork() }
        return !active.any {
            val spec = it.experiment
            it.id != id && !it.paused && !it.cancelRequested &&
                spec.host != Experiments.AWS_HOST && (tags == null || NodeTags.matches(spec.nodeTags, tags)) &&
                (it.priority > job.priority || it.priority == job.priority && !pinned && reserved(it)) &&
                when {
                    spec.isAnalysis -> true
                    !it.setupDone -> it.id !in cloud.first
                    else -> it.id !in cloud.second && store.unclaimed(it.id) > 0
                }
        }
    }
}
