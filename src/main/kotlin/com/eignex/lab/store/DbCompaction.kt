package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * How a case record is kept in the database, as against how every reader sees it. A record's build provenance, tens of
 * kilobytes that are the same for every case of one arm at one commit, is kept once in the `provenances` table, keyed
 * by the sha256 of its compact JSON, and the record holds that key as its `buildProvenanceRef`, where the provenance
 * was. A reader of whole records gets the provenance back in its place ([inflate]), so it reads as the record written.
 * The producer record an MPS or SMT check keeps in `stats.sourceValidationProducerRecordJson` is kept cut to its
 * `timeToBestMs`, the one field the lab reads ([Outcome.withdrawn]); the whole one stays in the case's record file in
 * its job directory. Nothing else changes: the final witness, the stats scoring and the checks read, and the source
 * hashes are kept as they are.
 */
internal object StoredRecords {
    const val PROVENANCE = "buildProvenance"
    const val PROVENANCE_REF = "buildProvenanceRef"
    const val PRODUCER_RECORD = "sourceValidationProducerRecordJson"
    private const val TIME_TO_BEST = "timeToBestMs"

    /** A record as stored, and the provenance it refers to as its sha256 and compact JSON, when it has one. */
    data class Compacted(val text: String, val provenance: Pair<String, String>?)

    /**
     * Record [text] as it is to be stored, or null when it is stored as it is: one already compacted, one with neither
     * a provenance nor a producer record to cut, or one that is not a JSON object. Each key keeps its place.
     */
    fun compact(text: String): Compacted? {
        val record = recordOf(text) as? JsonObject ?: return null
        var provenance: Pair<String, String>? = null
        var changed = false
        val stored = LinkedHashMap<String, JsonElement>(record.size)
        for ((key, value) in record) {
            when {
                key == PROVENANCE && value is JsonObject -> {
                    val json = Json.encodeToString(JsonElement.serializer(), value)
                    val sha = sha256(json)
                    provenance = sha to json
                    stored[PROVENANCE_REF] = JsonPrimitive(sha)
                    changed = true
                }
                key == "stats" && value is JsonObject -> {
                    val stats = trimProducer(value)
                    if (stats !== value) changed = true
                    stored[key] = stats
                }
                else -> stored[key] = value
            }
        }
        if (!changed) return null
        return Compacted(Json.encodeToString(JsonElement.serializer(), JsonObject(stored)), provenance)
    }

    /** [stats] with its producer record cut to its time to the best solution, or [stats] itself when there is nothing
     *  to cut: no producer record, one already cut, or one that is not a JSON object. */
    private fun trimProducer(stats: JsonObject): JsonObject {
        val text = (stats[PRODUCER_RECORD] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return stats
        val producer = recordOf(text) as? JsonObject ?: return stats
        val trimmed = Json.encodeToString(JsonElement.serializer(), JsonObject(producer.filterKeys { it == TIME_TO_BEST }))
        if (trimmed == text) return stats
        return JsonObject(stats + (PRODUCER_RECORD to JsonPrimitive(trimmed)))
    }

    /** [record] with its provenance back in place of its `buildProvenanceRef`, by [provenance]; as it is when it has no
     *  reference or the provenance is not found. */
    fun inflate(record: JsonElement?, provenance: (String) -> JsonElement?): JsonElement? {
        if (record !is JsonObject) return record
        val sha = (record[PROVENANCE_REF] as? JsonPrimitive)?.content ?: return record
        val value = provenance(sha) ?: return record
        val inflated = LinkedHashMap<String, JsonElement>(record.size)
        for ((key, element) in record) if (key == PROVENANCE_REF) inflated[PROVENANCE] = value else inflated[key] = element
        return JsonObject(inflated)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/**
 * How far the one-time compaction of the stored case records has come ([DbCompaction]), kept in the database so that a
 * restarted runner resumes it and the API's disk page shows it. [cursor] is the last case row done, by rowid.
 */
@Serializable
data class Compaction(
    val cursor: Long = 0,
    /** Cases with a record when the compaction started. */
    val total: Int? = null,
    val scanned: Int = 0,
    val changed: Int = 0,
    /** The changed records' size before and after, in bytes. */
    val bytesBefore: Long = 0,
    val bytesAfter: Long = 0,
    val startedAt: Long? = null,
    /** When every record had been compacted; null while it runs. */
    val migratedAt: Long? = null,
    /** What the last vacuum did or why it was skipped, and when. */
    val vacuum: String? = null,
    val vacuumAt: Long? = null,
)

/** The database's file as SQLite counts it, its kept provenances and the compaction's progress, for the disk page. */
@Serializable
data class DatabaseReport(
    val pageSize: Long,
    val pageCount: Long,
    val freelistCount: Long,
    val provenances: Int,
    val compaction: Compaction,
) {
    val bytes: Long get() = pageSize * pageCount
    val freeBytes: Long get() = pageSize * freelistCount

    /** About what the file comes to once vacuumed: its pages in use. */
    val usedBytes: Long get() = pageSize * (pageCount - freelistCount)
}

/**
 * Compacts the case records stored before [StoredRecords] did ([Store.compactBatch]), [BATCH] cases a transaction with
 * a pause between, so the runner and the API stay responsive and the write-ahead log small. It resumes where it was
 * after a restart, and doing a batch again changes nothing. Once every record is done it gives the space back with a
 * VACUUM ([maybeVacuum]), and keeps looking every [VACUUM_CHECK_MIN] minutes for space worth giving back.
 */
class DbCompaction(
    private val store: Store,
    private val freeBytes: () -> Long,
    /** Whether a job is running: a vacuum waits for none to be. */
    private val busy: () -> Boolean,
    private val log: (String) -> Unit = ::println,
    private val pauseMs: Long = PAUSE_MS,
    private val vacuumMinFreeBytes: Long = VACUUM_MIN_FREE_BYTES,
) {
    /** Compact every record not yet done; returns the state once all are. */
    fun migrate(): Compaction {
        var state = store.maintenance.compaction()
        if (state.migratedAt != null) return state
        if (state.startedAt == null) {
            state = store.maintenance.startCompaction()
            log("compacting ${state.total} case records, $BATCH a transaction")
        } else {
            log("resuming the compaction of the case records after ${state.scanned} of ${state.total}")
        }
        var batches = 0
        while (state.migratedAt == null) {
            state = store.maintenance.compactBatch(BATCH)
            if (++batches % LOG_EVERY == 0 || state.migratedAt != null) {
                log("compacted ${state.scanned} of ${state.total} case records: ${state.changed} changed, " +
                    "${(state.bytesBefore - state.bytesAfter) / BYTES_PER_MB} MB smaller")
            }
            if (state.migratedAt == null && pauseMs > 0) Thread.sleep(pauseMs)
        }
        return state
    }

    /**
     * VACUUM the database when it has at least [vacuumMinFreeBytes] of free pages, no job runs and the disk has
     * twice the vacuumed size free: SQLite builds the vacuumed copy in a temporary file and, in WAL mode, writes it
     * through the write-ahead log back into the database, each about the size of the result. Returns whether it ran.
     */
    fun maybeVacuum(): Boolean {
        val database = store.maintenance.database()
        if (database.freeBytes < vacuumMinFreeBytes) return false
        val needed = database.usedBytes * VACUUM_SPACE_FACTOR
        val free = freeBytes()
        if (free < needed) {
            val why = "skipped: ${free / BYTES_PER_MB} MB free on disk, ${needed / BYTES_PER_MB} MB needed"
            if (store.maintenance.compaction().vacuum != why) log("vacuum $why")
            store.maintenance.vacuumOutcome(why)
            return false
        }
        if (busy()) return false
        log("vacuuming the database: ${database.bytes / BYTES_PER_MB} MB, ${database.freeBytes / BYTES_PER_MB} MB of it free")
        val started = System.currentTimeMillis()
        val outcome = runCatching { store.maintenance.vacuum() }.fold(
            { "${database.bytes / BYTES_PER_MB} MB → ${store.maintenance.database().bytes / BYTES_PER_MB} MB in ${(System.currentTimeMillis() - started) / MS_PER_S} s" },
            { "failed: ${it.message}" },
        )
        log("vacuum $outcome")
        store.maintenance.vacuumOutcome(outcome)
        return true
    }

    /** [migrate] and then [maybeVacuum] now and every [VACUUM_CHECK_MIN] minutes, on a daemon thread. */
    fun start() {
        Thread({
            runCatching { migrate() }.onFailure { log("compaction failed: ${it.message}"); return@Thread }
            while (true) {
                runCatching { maybeVacuum() }.onFailure { log("vacuum: ${it.message}") }
                Thread.sleep(VACUUM_CHECK_MIN * MS_PER_MIN)
            }
        }, "compact-db").apply { isDaemon = true }.start()
    }

    internal companion object {
        const val BATCH = 500
        const val PAUSE_MS = 1000L
        const val LOG_EVERY = 20
        const val VACUUM_CHECK_MIN = 15L
        const val VACUUM_MIN_FREE_BYTES = 1L shl 30
        const val VACUUM_SPACE_FACTOR = 2
        private const val MS_PER_S = 1000L
        private const val MS_PER_MIN = 60_000L
    }
}
