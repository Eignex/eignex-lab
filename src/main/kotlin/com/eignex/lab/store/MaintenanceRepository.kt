package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/** Keeping the database small: compacting stored case records ([StoredRecords]) and VACUUM. */
class MaintenanceRepository internal constructor(private val db: Database, lock: Any, private val plans: PlanRepository) : Repository(db, lock) {
    internal val vacuumMarker: Path get() = db.vacuumMarker

    /** The compaction's progress ([DbCompaction]): where it is, or a fresh state before it started. */
    fun compaction(): Compaction = synchronized(lock) {
        connection.prepareStatement("SELECT state FROM compaction WHERE id = 0").use { statement ->
            statement.executeQuery().use { r -> if (r.next()) Json.decodeFromString<Compaction>(r.getString(1)) else Compaction() }
        }
    }

    private fun saveCompaction(state: Compaction) = update(
        "INSERT INTO compaction (id, state) VALUES (0, ?) ON CONFLICT (id) DO UPDATE SET state = excluded.state",
        Json.encodeToString(state),
    )

    /** Start the compaction: count the cases it has to go over. */
    fun startCompaction(): Compaction = synchronized(lock) {
        transaction {
            val total = connection.prepareStatement("SELECT count(*) FROM cases WHERE record IS NOT NULL").use { s ->
                s.executeQuery().use { r -> r.next(); r.getInt(1) }
            }
            compaction().copy(total = total, startedAt = now()).also(::saveCompaction)
        }
    }

    /**
     * Compact the next [limit] stored case records after the compaction's cursor ([StoredRecords.compact]) and move the
     * cursor past them, in one transaction. The records are read and compacted outside the store's lock, and a record
     * that changed meanwhile is left as its writer stored it, compacted. Once a batch finds fewer than [limit], the
     * compaction is done: every record written since it started was written compacted.
     */
    fun compactBatch(limit: Int): Compaction {
        val cursor = compaction().cursor
        val rows = synchronized(lock) {
            connection.prepareStatement(
                "SELECT rowid, record FROM cases WHERE rowid > ? AND record IS NOT NULL ORDER BY rowid LIMIT ?",
            ).use { s ->
                s.setLong(1, cursor)
                s.setInt(2, limit)
                s.executeQuery().use { r -> generateSequence { if (r.next()) r.getLong(1) to r.getString(2) else null }.toList() }
            }
        }
        val compacted = rows.map { (rowid, text) -> Triple(rowid, text, StoredRecords.compact(text)) }
        return synchronized(lock) {
            transaction {
                var state = compaction()
                for ((rowid, text, record) in compacted) {
                    if (record == null) continue
                    record.provenance?.let { (sha, json) -> plans.keepProvenance(sha, json) }
                    if (update("UPDATE cases SET record = ? WHERE rowid = ? AND record = ?", record.text, rowid, text) == 1) {
                        state = state.copy(changed = state.changed + 1, bytesBefore = state.bytesBefore + utf8Length(text),
                            bytesAfter = state.bytesAfter + utf8Length(record.text))
                    }
                }
                state = state.copy(cursor = rows.lastOrNull()?.first ?: state.cursor, scanned = state.scanned + rows.size,
                    migratedAt = if (rows.size < limit) now() else null)
                saveCompaction(state)
                state
            }
        }
    }

    private fun utf8Length(text: String): Long = text.toByteArray(Charsets.UTF_8).size.toLong()

    /** Keep what the last vacuum did, or why it was skipped. */
    fun vacuumOutcome(outcome: String) = synchronized(lock) {
        transaction { saveCompaction(compaction().copy(vacuum = outcome, vacuumAt = now())) }
    }

    /** The database's size and free pages as SQLite counts them, its kept provenances and the compaction's progress. */
    fun database(): DatabaseReport = synchronized(lock) {
        fun pragma(name: String) = connection.createStatement().use { s -> s.executeQuery("PRAGMA $name").use { r -> r.next(); r.getLong(1) } }
        val provenances = connection.prepareStatement("SELECT count(*) FROM provenances").use { s -> s.executeQuery().use { r -> r.next(); r.getInt(1) } }
        return DatabaseReport(pragma("page_size"), pragma("page_count"), pragma("freelist_count"), provenances, compaction())
    }

    /**
     * VACUUM the database, giving its free pages back to the disk, then checkpoint the write-ahead log it went through
     * and cut it back. It holds the write lock throughout: this store's other callers wait on its lock, and every other
     * process's writes wait while [vacuumMarker] is there ([waitWhileBusy]). Readers in other processes go on reading.
     */
    fun vacuum() = synchronized(lock) {
        Files.writeString(vacuumMarker, "${now()}\n")
        try {
            connection.createStatement().use { s ->
                s.execute("VACUUM")
                s.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            }
        } finally {
            Files.deleteIfExists(vacuumMarker)
        }
    }
}
