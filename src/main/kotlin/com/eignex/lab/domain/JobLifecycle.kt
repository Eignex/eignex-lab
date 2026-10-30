package com.eignex.lab.domain

import com.eignex.lab.util.*

/**
 * The moves a job's [Status] may make, and nothing else: queued work starts, pauses back to the queue, or ends; an
 * ended job only goes back to the queue by a retry of one that failed or was cancelled, and a finished one never
 * changes. The database enforces the same table ([Schema]), so no write path, in any process, can move a job
 * outside it.
 */
object JobLifecycle {
    val moves: Map<Status, Set<Status>> = mapOf(
        Status.QUEUED to setOf(Status.RUNNING, Status.DONE, Status.FAILED, Status.CANCELLED),
        Status.RUNNING to setOf(Status.QUEUED, Status.DONE, Status.FAILED, Status.CANCELLED),
        Status.FAILED to setOf(Status.QUEUED),
        Status.CANCELLED to setOf(Status.QUEUED),
        Status.DONE to emptySet(),
    )

    fun allows(from: Status, to: Status): Boolean = from == to || to in moves.getValue(from)

    /** The table as an SQL condition on a trigger's `OLD.status` and `NEW.status`: true for an allowed move. */
    internal val SQL: String = movesSql(moves)
}

/**
 * The moves a case's command ([Command.status]) may make: queued work starts, goes back to the queue or ends, and an
 * ended one only goes back to the queue, when a retry or an invalidated attempt queues it again. A late report can
 * no longer turn a cancelled case into a finished one, nor a finished one into another result. The database
 * enforces it ([Schema]).
 */
object CommandLifecycle {
    val moves: Map<Status, Set<Status>> = mapOf(
        Status.QUEUED to setOf(Status.RUNNING, Status.DONE, Status.FAILED, Status.CANCELLED),
        Status.RUNNING to setOf(Status.QUEUED, Status.DONE, Status.FAILED, Status.CANCELLED),
        Status.DONE to setOf(Status.QUEUED),
        Status.FAILED to setOf(Status.QUEUED),
        Status.CANCELLED to setOf(Status.QUEUED),
    )

    fun allows(from: Status, to: Status): Boolean = from == to || to in moves.getValue(from)

    internal val SQL: String = movesSql(moves)
}

private fun movesSql(moves: Map<Status, Set<Status>>): String =
    (Status.entries.map { "(OLD.status = '${it.name}' AND NEW.status = '${it.name}')" } +
        moves.flatMap { (from, tos) -> tos.map { "(OLD.status = '${from.name}' AND NEW.status = '${it.name}')" } })
        .joinToString(" OR ")
