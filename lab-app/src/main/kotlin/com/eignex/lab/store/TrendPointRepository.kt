package com.eignex.lab.store

import com.eignex.lab.domain.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A finished run's stored trend point ([trend_points]): the point as computed, or none for a run known to have no point,
 * with what it was computed from. [run] is the serialized point, which the analysis layer reads back.
 */
data class StoredTrendPoint(val version: String, val referenceStamp: Long?, val computedAt: Long, val run: String?,
                            val problems: Set<ProblemKey>)

/** Finished runs' trend points, one row per job, written once a run is scored and read by every trend page load. */
class TrendPointRepository internal constructor(db: Database, lock: Any) : Repository(db, lock) {
    private val keys = ListSerializer(ProblemKey.serializer())

    fun load(jobs: Collection<Long>): Map<Long, StoredTrendPoint> {
        if (jobs.isEmpty()) return emptyMap()
        return reading {
            jobs.distinct().chunked(500).flatMap { chunk ->
                connection.prepareStatement("SELECT * FROM trend_points WHERE job_id IN (${chunk.joinToString(",") { "?" }})").use { statement ->
                    chunk.forEachIndexed { index, job -> statement.setLong(index + 1, job) }
                    statement.executeQuery().use { r ->
                        generateSequence {
                            if (!r.next()) return@generateSequence null
                            r.getLong("job_id") to StoredTrendPoint(r.getString("version"),
                                r.getLong("reference_stamp").takeUnless { r.wasNull() }, r.getLong("computed_at"),
                                r.getString("run"), Json.decodeFromString(keys, r.getString("problems")).toSet())
                        }.toList()
                    }
                }
            }.toMap()
        }
    }

    fun save(job: Long, point: StoredTrendPoint) {
        synchronized(lock) { update("INSERT INTO trend_points (job_id, version, reference_stamp, computed_at, run, problems) VALUES (?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT(job_id) DO UPDATE SET version = excluded.version, reference_stamp = excluded.reference_stamp, " +
            "computed_at = excluded.computed_at, run = excluded.run, problems = excluded.problems",
            job, point.version, point.referenceStamp, point.computedAt, point.run,
            Json.encodeToString(keys, point.problems.sortedWith(compareBy({ it.collection }, { it.problem })))) }
    }
}
