package com.eignex.lab.web

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.nomad.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import com.eignex.lab.worker.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.io.File

/** What an action on a job or schedule came to, with what to tell the caller. */
sealed interface Action {
    val message: String

    /** Done now. */
    data class Done(override val message: String) : Action

    /** Taken up: the runner or the coordinator does it shortly. */
    data class Accepted(override val message: String) : Action

    /** There is no such job or schedule. */
    data class Missing(override val message: String) : Action

    /** The job or schedule is in a state that does not allow it. */
    data class Refused(override val message: String) : Action
}

/**
 * What an operator does to a job: cancel, retry, delete, retag and set its parallelism, priority, description and
 * pause. Each is one store transaction ([JobControl]); the files a cancel or delete leaves behind are removed after it,
 * off the request's thread.
 */
class JobService internal constructor(private val services: ApiServices) {
    private val config get() = services.config
    private val store get() = services.store

    suspend fun cancel(id: Long): Action = when (withContext(Dispatchers.IO) { store.requestCancel(id) }) {
        // A queued job may have been set up before it yielded; the runner will not take it again to clean up.
        CancelOutcome.CANCELLED -> {
            removeTrees(worktrees(id))
            Action.Done("cancelled before it ran again")
        }
        CancelOutcome.REQUESTED -> Action.Accepted("cancel requested")
        CancelOutcome.FINISHED -> Action.Refused("job already finished")
        CancelOutcome.MISSING -> Action.Missing("no such job")
    }

    /**
     * Queue a failed or cancelled Nomad job again. It may go anywhere again, wherever it was taken last time: an
     * unplanned one is placed afresh ([PlacementPolicy]); a planned one's commands are rendered for the host that runs
     * each case ([Runner.render]).
     */
    suspend fun retry(id: Long): Action = withContext(Dispatchers.IO) {
        if (!store.retry(id)) return@withContext Action.Refused("only a failed or cancelled Nomad job can be retried; resubmit historical experiments")
        store.job(id)?.takeIf { !it.planned }?.let { job -> store.place(id, services.placement.resolve(job.experiment, job.origin, checked = false)) }
        Action.Done("queued again")
    }

    /** Forget an ended job ([Store.deleteJob]); its directory goes too, and any worktree a failed setup left. */
    suspend fun delete(id: Long): Action {
        if (!withContext(Dispatchers.IO) { store.deleteJob(id) }) return Action.Refused("no such job, it has not ended, or its Nomad stop is pending")
        removeTrees(worktrees(id) + config.jobDir(id).toFile())
        return Action.Done("deleted")
    }

    /** Move a queued job that has not been planned or admitted to the workers [tags] select; `[]` restores auto placement. */
    suspend fun retag(id: Long, tags: JsonElement?): Action = withContext(Dispatchers.IO) {
        val selector = inputTags(tags)
        val job = store.job(id) ?: return@withContext Action.Missing("no such experiment")
        val experiment = job.experiment.copy(nodeTags = selector.takeIf { NodeTags.labels(it).isNotEmpty() })
        val host = services.placement.resolve(experiment, job.origin)
        if (store.place(id, host, experiment)) Action.Done("tags updated: ${PlacementPolicy.note(host)}")
        else Action.Refused("only a queued job that has not been planned or admitted can change tags")
    }

    /** Set job [id]'s vCPUs within the limit a submission has: the local machine's cores, or a cloud node's for one
     *  that is cloud-only. */
    fun setVcpus(id: Long, vcpus: Int): Action {
        val job = store.job(id) ?: return Action.Missing("no such job")
        val limit = if (job.experiment.host == Experiments.AWS_HOST) services.placement.coreLimit(job.experiment) else config.cores
        require(vcpus in 1..limit) { "vcpus must be between 1 and $limit" }
        return if (store.setVcpus(id, vcpus)) Action.Done("vcpus $vcpus") else Action.Missing("no such job")
    }

    fun setPriority(id: Long, priority: Int): Action =
        if (store.setPriority(id, priority)) Action.Done("priority $priority") else Action.Missing("no such job")

    fun describe(id: Long, description: String): Action {
        require(description.isNotBlank()) { "description must not be blank" }
        return if (store.setDescription(id, description.trim())) Action.Done("described") else Action.Missing("no such experiment")
    }

    /** A running job stops starting commands and goes back to the queue once the running ones end; [paused] false resumes it. */
    fun setPaused(id: Long, paused: Boolean): Action =
        if (store.setPaused(id, paused)) Action.Done(if (paused) "paused" else "resumed") else Action.Refused("no unfinished job")

    /** The worktrees job [id] built, its own and those of its other commits; the mirror forgets them at its next prune. */
    private fun worktrees(id: Long): List<File> =
        config.worktree(id).toFile().parentFile.listFiles { f -> f.name == "$id" || f.name.startsWith("$id@") }.orEmpty().toList()

    private suspend fun removeTrees(trees: List<File>) = withContext(Dispatchers.IO) { trees.forEach { deleteTree(it.toPath()) } }
}
