package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/*
 * What is done to a job across its own tables and its execution ([ExecutionStore]), each in one write transaction:
 * the store's job tables know nothing of the execution tables, and the execution sees a job only through the store.
 */

/** Cancel job [jobId]: one Nomad runs is cancelled with its execution; a legacy one queued is cancelled, a running one asked to stop. */
fun Store.requestCancel(jobId: Long): CancelOutcome = executionTransaction { db ->
    val job = job(jobId) ?: return@executionTransaction CancelOutcome.MISSING
    if (job.backend == Backend.NOMAD && job.status.isActive) {
        execution.cancelIn(db, jobId, now())
        return@executionTransaction CancelOutcome.CANCELLED
    }
    when (job.status) {
        Status.QUEUED -> {
            db.sqlUpdate("UPDATE jobs SET status = ?, cancel_requested = 1, finished_at = ? WHERE id = ?", Status.CANCELLED.name, now(), jobId)
            db.sqlUpdate("UPDATE commands SET status = ? WHERE job_id = ? AND status = ?", Status.CANCELLED.name, jobId, Status.QUEUED.name)
            CancelOutcome.CANCELLED
        }
        Status.RUNNING -> {
            db.sqlUpdate("UPDATE jobs SET cancel_requested = 1 WHERE id = ?", jobId)
            CancelOutcome.REQUESTED
        }
        else -> CancelOutcome.FINISHED
    }
}

/**
 * Put a failed or cancelled job back in the queue: its error is cleared, its unfinished cases are queued again
 * and its finished ones kept, and its whole-run limit counts again from now. Its worktree is gone, so the runner sets it up afresh, planning it only if it never
 * was. False when there is no such job or it did not fail or get cancelled.
 */
fun Store.retry(jobId: Long): Boolean = executionTransaction { db ->
    val reopened = db.sqlUpdate(
        // A retry grants the whole-run limit afresh; run_ms itself keeps the job's full cost.
        "UPDATE jobs SET status = ?, error = NULL, finished_at = NULL, cancel_requested = 0, paused = 0, running_since = NULL, deadline_from_ms = run_ms " +
            "WHERE id = ? AND backend = 'nomad' AND status IN (?, ?)",
        Status.QUEUED.name, jobId, Status.FAILED.name, Status.CANCELLED.name,
    ) == 1
    if (reopened) {
        execution.retryIn(db, jobId, now())
        db.sqlUpdate(
            "UPDATE commands SET status = ?, started_at = NULL WHERE job_id = ? AND status IN (?, ?)",
            Status.QUEUED.name, jobId, Status.CANCELLED.name, Status.RUNNING.name,
        )
    }
    reopened
}

/**
 * Place a queued experiment that has not been planned on [host] (`lab`, `aws` or `auto`): what [Placement] decides,
 * or an operator updates its tags. [placed], when given, is the replacement spec. Admission freezes the recipe
 * before source publication ([ExecutionStore.started]), so checking it in the same write transaction also fences a
 * concurrent coordinator in another process.
 */
fun Store.place(jobId: Long, host: HostPlacement, placed: ExperimentSpec? = null): Boolean = executionTransaction { db ->
    val job = job(jobId) ?: return@executionTransaction false
    val spec = placed ?: job.experiment
    if (job.status != Status.QUEUED || job.planned || execution.started(db, jobId)) return@executionTransaction false
    val moved = spec.copy(host = host)
    db.sqlUpdate("UPDATE jobs SET experiment = ?, host = ? WHERE id = ? AND status = 'QUEUED' AND NOT EXISTS (SELECT 1 FROM commands WHERE job_id = ?)",
        Json.encodeToString(moved), moved.host.stored, jobId, jobId) == 1
}

/**
 * Forget an ended job: its row and, by the database's own cascade ([Schema.JOB_TABLES]), everything recorded for it.
 * False when there is no such job, it has not ended, since the runner may still be writing it, or a session of it may
 * still run ([ExecutionStore.unstopped]).
 */
fun Store.deleteJob(id: Long): Boolean = executionTransaction { db ->
    !execution.unstopped(db, id) &&
        db.sqlUpdate("DELETE FROM jobs WHERE id = ? AND status NOT IN (?, ?)", id, Status.QUEUED.name, Status.RUNNING.name) == 1
}
