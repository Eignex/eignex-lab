package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.eignex.lab.analysis.SourceValidation

/** Schedules (`schedules`): experiments queued again whenever their ref moves ([Schedules]). */
class ScheduleRepository internal constructor(db: Database, lock: Any) : Repository(db, lock) {
    /** Set how many of [jobId]'s commands may run at once; false when there is no such job. */
    fun create(name: String, ref: String, experiment: ExperimentSpec, intervalSec: Long): Long = synchronized(lock) {
        connection.prepareStatement(
            "INSERT INTO schedules (name, ref, parallel, priority, interval_sec, experiment) VALUES (?, ?, ?, ?, ?, ?)",
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use {
            it.setString(1, name)
            it.setString(2, ref)
            it.setInt(3, experiment.vcpus ?: 0)
            it.setInt(4, experiment.priority)
            it.setLong(5, intervalSec)
            it.setString(6, Json.encodeToString(experiment))
            it.executeUpdate()
            it.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
        }
    }

    fun all(): List<Schedule> = synchronized(lock) {
        connection.prepareStatement("SELECT * FROM schedules ORDER BY id").use { statement ->
            statement.executeQuery().use { rows ->
                generateSequence {
                    if (!rows.next()) return@generateSequence null
                    // Parallel and priority are the experiment's own; the columns copy them for older readers.
                    val experiment = storedExperiment(rows.getString("experiment"))
                    Schedule(
                        rows.getLong("id"), rows.getString("name"), rows.getString("ref"), experiment,
                        experiment.vcpus ?: 0, experiment.priority,
                        rows.getLong("interval_sec"), rows.getString("last_sha"), rows.longOrNull("last_job"),
                        rows.longOrNull("checked_at"), rows.getInt("launch") == 1,
                    )
                }.toList()
            }
        }
    }

    /**
     * Change schedule [id]: a new [experiment]; a new [intervalSec]; and [nextCheckAt], when its next check is due
     * (epoch ms). Unset fields stay. An experiment that measures something else ([Experiments.fingerprint]) forgets the
     * last commit, so the next check queues it at the ref's commit; one that only changes how it runs, where it may run
     * ([Placement.cloudOptIn]) or how it is described is saved, and its next run follows it, but queues no extra run.
     * A schedule keeps its phase otherwise, so staggered schedules stay apart. False when there is no such schedule.
     */
    fun update(id: Long, experiment: ExperimentSpec?, intervalSec: Long?, nextCheckAt: Long? = null): Boolean = synchronized(lock) {
        transaction {
            val old = all().firstOrNull { it.id == id }?.experiment
            val found = update("UPDATE schedules SET interval_sec = COALESCE(?, interval_sec) WHERE id = ?", intervalSec, id) == 1
            if (found && experiment != null) {
                // Compared as the schedule runs them ([SourceValidation.scheduled]), so saving the validation request a
                // schedule already gets queues no extra run.
                val changed = old == null ||
                    Experiments.fingerprint(SourceValidation.scheduled(old)) != Experiments.fingerprint(SourceValidation.scheduled(experiment))
                update(
                    "UPDATE schedules SET experiment = ?, parallel = ?, priority = ?" + (if (changed) ", last_sha = NULL" else "") + " WHERE id = ?",
                    Json.encodeToString(experiment), experiment.vcpus ?: 0, experiment.priority, id,
                )
            }
            // A schedule is due one interval after its last check, so its next check sets the last one back by an interval.
            if (found && nextCheckAt != null) update("UPDATE schedules SET checked_at = ? - interval_sec * 1000 WHERE id = ?", nextCheckAt, id)
            found
        }
    }

    fun delete(id: Long): Boolean = synchronized(lock) {
        update("DELETE FROM schedules WHERE id = ?", id) == 1
    }

    /** Make schedule [id] due, so the runner checks it at once; false when there is no such schedule. */
    fun checkNow(id: Long): Boolean = synchronized(lock) {
        update("UPDATE schedules SET checked_at = NULL WHERE id = ?", id) == 1
    }

    /**
     * Ask schedule [id] for a run now, at its ref's commit whether or not that commit ran; false when there is none.
     * Its regular checks keep their times, so schedules staggered apart stay apart after a manual launch.
     */
    fun launchNow(id: Long): Boolean = synchronized(lock) {
        update("UPDATE schedules SET launch = 1 WHERE id = ?", id) == 1
    }

    fun checked(id: Long, at: Long) = synchronized(lock) {
        update("UPDATE schedules SET checked_at = ? WHERE id = ?", at, id)
    }

    fun ran(id: Long, sha: String, jobId: Long) =
synchronized(lock) {
                update("UPDATE schedules SET last_sha = ?, last_job = ?, launch = 0 WHERE id = ?", sha, jobId, id)
    }
}
