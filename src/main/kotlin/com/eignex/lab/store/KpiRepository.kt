package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset

/** [median] and [p90] of [count] durations in milliseconds. */
@Serializable
data class Spread(val count: Int, val median: Long, val p90: Long) {
    companion object {
        fun of(values: List<Long>): Spread? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            fun at(q: Double) = sorted[((sorted.size - 1) * q).toInt()]
            return Spread(sorted.size, at(0.5), at(0.9))
        }
    }
}

@Serializable
data class CacheCounts(val hits: Int = 0, val misses: Int = 0, val bypasses: Int = 0)

/**
 * Throughput of the jobs that ended on [day] (UTC): how many finished or failed and why ([failures], by the error's
 * leading cause), the cases they completed, how long they queued before starting ([queueWaitMs]) and how long their
 * accepted preparation took ([preparationMs]). [preparationAttempts] counts every preparation attempt, so attempts
 * above [preparationsAccepted] are replacements. [caches] sums the accepted preparations' cache observations.
 */
@Serializable
data class KpiDay(val day: String, val jobsDone: Int, val jobsFailed: Int, val jobsCancelled: Int, val failures: Map<String, Int>,
                  val casesCompleted: Int, val queueWaitMs: Spread?, val preparationMs: Spread?, val preparationAttempts: Int,
                  val preparationsAccepted: Int, val caches: Map<String, CacheCounts>)

@Serializable
data class KpiReport(val since: Long, val days: List<KpiDay>)

/** Throughput read from what the lab already records for ended jobs; nothing is collected for it. */
class KpiRepository internal constructor(db: Database, lock: Any) : Repository(db, lock) {
    private class Ended(val id: Long, val status: String, val createdAt: Long, val startedAt: Long?, val finishedAt: Long, val error: String?)
    private class Preparation(val jobId: Long, val state: String, val startedAt: Long, val finishedAt: Long?, val caches: String?)

    fun report(days: Int, at: Long = now()): KpiReport = reading {
        require(days in 1..90) { "days must be 1 to 90" }
        val since = Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).toLocalDate().minusDays(days - 1L)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val jobs = rows("SELECT id, status, created_at, started_at, finished_at, error FROM jobs WHERE finished_at >= ? AND status IN ('DONE', 'FAILED', 'CANCELLED')", since) {
            Ended(it.getLong("id"), it.getString("status"), it.getLong("created_at"), it.longOrNull("started_at"), it.getLong("finished_at"), it.getString("error"))
        }
        val cases = mutableMapOf<Long, Int>()
        val preparations = mutableMapOf<Long, MutableList<Preparation>>()
        for (chunk in jobs.map { it.id }.chunked(500)) {
            val marks = chunk.joinToString(",") { "?" }
            rows("SELECT job_id, COUNT(*) FROM cases WHERE job_id IN ($marks) AND record IS NOT NULL GROUP BY job_id", *chunk.toTypedArray()) {
                cases[it.getLong(1)] = it.getInt(2)
            }
            rows("SELECT job_id, state, started_at, finished_at, preparation_caches FROM iteration_attempts WHERE job_id IN ($marks) AND iteration_id = 'preparation'",
                *chunk.toTypedArray()) {
                Preparation(it.getLong(1), it.getString(2), it.getLong(3), it.longOrNull("finished_at"), it.getString(5))
            }.forEach { preparations.getOrPut(it.jobId) { mutableListOf() }.add(it) }
        }
        val byDay = jobs.groupBy { Instant.ofEpochMilli(it.finishedAt).atZone(ZoneOffset.UTC).toLocalDate().toString() }
        KpiReport(since, byDay.toSortedMap().map { (day, ended) ->
            val attempts = ended.flatMap { preparations[it.id].orEmpty() }
            val accepted = attempts.filter { it.state == AttemptState.COMMITTED.stored && it.finishedAt != null }
            val caches = mutableMapOf<String, CacheCounts>()
            for (attempt in accepted) {
                val observed = attempt.caches?.let { runCatching { Json.decodeFromString<Map<String, PreparationCacheEvent>>(it) }.getOrNull() }.orEmpty()
                for ((name, event) in observed) {
                    val sum = caches[name] ?: CacheCounts()
                    caches[name] = CacheCounts(sum.hits + event.hits, sum.misses + event.misses, sum.bypasses + event.bypasses)
                }
            }
            KpiDay(day, ended.count { it.status == Status.DONE.name }, ended.count { it.status == Status.FAILED.name },
                ended.count { it.status == Status.CANCELLED.name },
                ended.filter { it.status == Status.FAILED.name }.groupingBy { failureCause(it.error) }.eachCount().toSortedMap(),
                ended.sumOf { cases[it.id] ?: 0 },
                Spread.of(ended.mapNotNull { job -> job.startedAt?.let { it - job.createdAt }?.takeIf { it >= 0 } }),
                Spread.of(accepted.map { it.finishedAt!! - it.startedAt }.filter { it >= 0 }),
                attempts.size, accepted.size, caches.toSortedMap())
        })
    }

    companion object {
        /** The error's leading cause, such as `source-build` or `Nomad preparation stopped after 3 worker exits`. */
        internal fun failureCause(error: String?): String {
            val first = error?.lineSequence()?.firstOrNull()?.trim().orEmpty()
            if (first.isEmpty()) return "unknown"
            return first.substringBefore(':').replace(Regex("""\b[0-9a-f]{7,}\b|\(.*"""), "").trim().take(120).ifEmpty { "unknown" }
        }
    }
}
