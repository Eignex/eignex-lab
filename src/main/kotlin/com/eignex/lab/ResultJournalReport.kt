package com.eignex.lab

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*

@Serializable data class ResultJournalReport(val generatedAt: Long, val status: ResultJournal.Status, val ageMs: Long = 0) {
    val stale: Boolean get() = ageMs > 15_000
}

internal fun readResultJournalReport(config: Config): ResultJournalReport? = runCatching {
    val path = config.dataDir.resolve("nomad/result-journal-status.json")
    require(Files.size(path) <= 8192)
    Json.decodeFromString<ResultJournalReport>(Files.readString(path)).let { it.copy(ageMs = (now() - it.generatedAt).coerceAtLeast(0)) }
}.getOrNull()

/** Disposable status, written atomically on the internal volume; acknowledged results have separate durable packs. */
internal fun writeResultJournalReport(path: Path, journal: ResultJournal) {
    val temp = Files.createTempFile(path.parent, ".journal-status-", ".tmp")
    try {
        Files.writeString(temp, Json.encodeToString(ResultJournalReport(now(), journal.status())))
        Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temp) }
}

internal fun ResultJournal.Status.metrics(): String = """
lab_result_journal_pending_batches $batches
lab_result_journal_bytes $bytes
lab_result_journal_limit_bytes $limit
lab_result_journal_oldest_age_seconds ${oldestAgeMs / 1000.0}
lab_result_journal_drain_errors_total $drainErrors
lab_result_journal_drain_failed ${if (lastError == null) 0 else 1}
""".trimIndent() + "\n"
