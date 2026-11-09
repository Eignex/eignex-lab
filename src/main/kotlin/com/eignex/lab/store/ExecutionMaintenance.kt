package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Batched rewrites of stored execution rows into their current form, which the runner drives a batch at a time. */
class ExecutionMaintenance internal constructor(private val store: Store) {

    /**
     * Split at most [batch] committed preparations stored whole, after rowid [after] ([splitResult]); returns the last
     * rowid looked at, null once none is left. Call again with it until null.
     */
    fun splitPreparationResults(after: Long, batch: Int = 10): Long? = store.executionTransaction { db ->
        val rows = db.sqlRows("SELECT rowid, result FROM iteration_attempts WHERE rowid > ? AND iteration_id = ? AND state = 'committed' ORDER BY rowid LIMIT ?",
            after, ExecutionStore.PREPARATION, batch) { it.getLong(1) to it.getString(2) }
        for ((rowid, text) in rows) {
            val split = text?.let { db.splitResult(it) } ?: continue
            if (split != text) db.sqlUpdate("UPDATE iteration_attempts SET result = ? WHERE rowid = ?", split, rowid)
        }
        rows.lastOrNull()?.first
    }

    /**
     * Split at most [batch] session manifests stored whole, after rowid [after] ([splitManifest]); returns the last
     * rowid looked at, null once none is left. Call again with it until null.
     */
    fun splitManifests(after: Long, batch: Int = 5): Long? = store.executionTransaction { db ->
        val rows = db.sqlRows("SELECT rowid, manifest FROM execution_manifests WHERE rowid > ? ORDER BY rowid LIMIT ?", after, batch) {
            it.getLong(1) to it.getString(2)
        }
        for ((rowid, text) in rows) {
            val split = db.splitManifest(text)
            if (split != text) db.sqlUpdate("UPDATE execution_manifests SET manifest = ? WHERE rowid = ?", split, rowid)
        }
        rows.lastOrNull()?.first
    }

    /** Drop the manifest parts no kept manifest names any more; how many went. */
    fun dropUnusedManifestParts(): Int = store.executionTransaction { db ->
        db.sqlUpdate("""DELETE FROM manifest_parts WHERE sha NOT IN (SELECT value FROM execution_manifests m, json_each(m.manifest)
            WHERE json_valid(m.manifest) AND key LIKE '%$MANIFEST_PART_REF')
            AND sha NOT IN (SELECT value FROM iteration_attempts a, json_each(a.result, '$.preparation')
            WHERE a.iteration_id = '${ExecutionStore.PREPARATION}' AND a.state = 'committed' AND json_valid(a.result) AND key LIKE '%$MANIFEST_PART_REF')""")
    }

    /**
     * Drop the manifests, often megabytes each, of stopped sessions of jobs done before [doneBefore], at most [batch]
     * sessions; how many went. Only a done job: a failed or cancelled one may be retried, and a retry replaces its
     * sessions from their manifests. A small batch keeps each write short; call again until it returns 0.
     */
    /** Move at most [batch] manifests still in their session rows to `execution_manifests`; how many moved. Call again
     *  until it returns 0. */
    fun moveManifests(batch: Int = 5): Int = store.executionTransaction { db ->
        val ids = db.sqlRows("SELECT id FROM execution_sessions WHERE manifest IS NOT NULL LIMIT ?", batch) { it.getString(1) }
        for (id in ids) {
            db.sqlRows("SELECT manifest FROM execution_sessions WHERE id = ?", id) { it.getString(1) }.singleOrNull()?.let { manifest ->
                db.sqlUpdate("INSERT OR IGNORE INTO execution_manifests (session_id, manifest) VALUES (?, ?)", id, db.splitManifest(manifest))
            }
            db.sqlUpdate("UPDATE execution_sessions SET manifest = NULL WHERE id = ?", id)
        }
        ids.size
    }

    fun dropDoneManifests(doneBefore: Long, batch: Int = 5): Int = store.executionTransaction { db ->
        db.sqlUpdate("""DELETE FROM execution_manifests WHERE session_id IN (
            SELECT s.id FROM execution_manifests m JOIN execution_sessions s ON s.id = m.session_id JOIN jobs j ON j.id = s.job_id
            WHERE s.desired = 'stop' AND j.status = 'DONE' AND j.finished_at < ? LIMIT ?)""", doneBefore, batch)
    }

    /**
     * Slim at most [batch] committed attempts that still carry their case records, after rowid [after]: each keeps its
     * result without the records, already in `cases`, and the digest of the whole result it was committed with. Returns
     * the last rowid looked at, null once none is left. Call again with it until null.
     */
    fun slimAcceptedResults(after: Long, batch: Int = 20): Long? = store.executionTransaction { db ->
        val rows = db.sqlRows("SELECT rowid, result FROM iteration_attempts WHERE rowid > ? AND state = 'committed' AND result_sha256 IS NULL ORDER BY rowid LIMIT ?",
            after, batch) { it.getLong(1) to it.getString(2) }
        for ((rowid, text) in rows) {
            val slimmed = text?.let { runCatching { Json.encodeToString(ExecutionStore.slim(Json.decodeFromString<IterationResult>(it))) }.getOrNull() } ?: text
            db.sqlUpdate("UPDATE iteration_attempts SET result = ?, result_sha256 = ? WHERE rowid = ?", slimmed, text?.let(ExecutionStore::digest), rowid)
        }
        rows.lastOrNull()?.first
    }
}
