package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/* Fixtures that write past runs straight into a store; production records cases through [ExecutionStore]. */

/** Keep the native solver stdout files case [index] of [jobId] left ([CaseResult.stdout]), replacing any earlier ones. */
internal fun Store.caseStdout(jobId: Long, index: Int, files: List<CaseFile>): Int = executionTransaction {
    it.sqlUpdate("UPDATE cases SET stdout = ? WHERE job_id = ? AND idx = ?",
        if (files.isEmpty()) null else Json.encodeToString(ListSerializer(CaseFile.serializer()), files), jobId, index)
}

/**
 * Turn records an earlier job left into a finished experiment: a new job of [name] at [sha], whose cases are [records],
 * each already done with its record kept, so the experiment views compare them like any other.
 */
internal fun Store.importExperiment(
    name: String,
    ref: String,
    sha: String,
    spec: ExperimentSpec,
    arms: List<PlannedArm>,
    problems: List<Problem>,
    records: List<Pair<Case, String>>,
    sources: List<String>,
): Long = executionTransaction { db ->
    val at = now()
    val columns = JobColumns.of(spec)
    val origin = JobOrigin.of(name)
    val id = db.sqlInsert(
        "INSERT INTO jobs (name, ref, sha, status, setup_done, created_at, started_at, finished_at, experiment, backend, host, analysis, bench, measurement, " +
            "origin, series) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, 'nomad', ?, ?, ?, ?, ?, ?)",
        name, ref, sha, Status.DONE.name, at, at, at, Json.encodeToString(spec), columns.host, columns.analysis, columns.bench,
        columns.measurement, origin.kind, origin.series,
    )
    plans.planRows(id, arms, problems, records.map { it.first }, sources.map { CaseCommand(it, 0L) })
    db.sqlUpdate("UPDATE commands SET status = ? WHERE job_id = ?", Status.DONE.name, id)
    records.forEachIndexed { index, (_, record) ->
        db.sqlUpdate("UPDATE cases SET record = ? WHERE job_id = ? AND idx = ?", plans.storedRecord(record), id, index)
    }
    id
}
