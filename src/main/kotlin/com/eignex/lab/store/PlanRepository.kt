package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What an experiment was planned into (`arms`, `problems`, `cases`) and what its cases recorded, provenances kept once. */
class PlanRepository internal constructor(db: Database, lock: Any) : Repository(db, lock) {
    internal fun planRows(jobId: Long, arms: List<PlannedArm>, problems: List<Problem>, cases: List<Case>, commands: List<CaseCommand>) {
        arms.forEachIndexed { index, planned ->
            update(
                "INSERT INTO arms (job_id, idx, label, arm, sha) VALUES (?, ?, ?, ?, ?)",
                jobId, index, planned.arm.label, Json.encodeToString(planned.arm), planned.sha,
            )
        }
        connection.sqlBatch(
            "INSERT INTO problems (job_id, idx, problem) VALUES (?, ?, ?)",
            problems.mapIndexed { index, problem -> listOf(jobId, index, Json.encodeToString(problem)) },
        )
        connection.sqlBatch(
            "INSERT INTO cases (job_id, idx, problem_idx, arm_idx, seed, repeat) VALUES (?, ?, ?, ?, ?, ?)",
            cases.mapIndexed { index, case -> listOf(jobId, index, case.problem, case.arm, case.seed, case.repeat) },
        )
        insertCommands(jobId, commands)
    }

    fun arms(jobId: Long): List<PlannedArm> = reading {
        rows("SELECT arm, sha FROM arms WHERE job_id = ? ORDER BY idx", jobId) { r ->
            PlannedArm(Json.decodeFromString(r.getString(1)), r.getString(2))
        }
    }

    /** The problem and arm of case [index] of [jobId]. */
    fun caseOf(jobId: Long, index: Int): Pair<Problem, Arm>? = reading {
        rows(
            """SELECT p.problem, a.arm FROM cases c
               JOIN problems p ON p.job_id = c.job_id AND p.idx = c.problem_idx
               JOIN arms a ON a.job_id = c.job_id AND a.idx = c.arm_idx
               WHERE c.job_id = ? AND c.idx = ?""",
            jobId, index,
        ) { r -> Json.decodeFromString<Problem>(r.getString(1)) to Json.decodeFromString<Arm>(r.getString(2)) }.firstOrNull()
    }

    /** The solver commit case [index] of [jobId] ran: the one its arm was planned at. */
    fun caseSha(jobId: Long, index: Int): String? = reading {
        rows(
            """SELECT a.sha FROM cases c JOIN arms a ON a.job_id = c.job_id AND a.idx = c.arm_idx
               WHERE c.job_id = ? AND c.idx = ?""",
            jobId, index,
        ) { it.getString(1) }.firstOrNull()
    }

    /** [record] as it is stored ([StoredRecords.compact]), its provenance kept once in `provenances`. In a transaction. */
    internal fun storedRecord(record: String): String {
        val compacted = StoredRecords.compact(record) ?: return record
        compacted.provenance?.let { (sha, json) -> keepProvenance(sha, json) }
        return compacted.text
    }

    /** Case [index]'s whole record, final witness and provenance and all: what a source check reads. */
    fun caseRecordText(jobId: Long, index: Int): String? = synchronized(lock) {
        rows("SELECT record FROM cases WHERE job_id = ? AND idx = ?", jobId, index) { it.getString(1) }.firstOrNull()?.let { text ->
            if (StoredRecords.PROVENANCE_REF !in text) text
            else StoredRecords.inflate(recordOf(text), ::provenance)?.toString() ?: text
        }
    }

    /** The build provenance kept under [sha], parsed, its shared parts back in place; the most recently read are kept
     *  ([PROVENANCES]). */
    private fun provenance(sha: String): JsonElement? = provenances[sha]
        ?: rows("SELECT provenance FROM provenances WHERE sha = ?", sha) { recordOf(it.getString(1)) }.firstOrNull()
            ?.let(::joinParts)?.also { provenances[sha] = it }

    /** [provenance] with each `<key>PartRef` replaced by the part it names, in its place; a missing part stays a reference. */
    private fun joinParts(provenance: JsonElement): JsonElement {
        if (provenance !is JsonObject || provenance.keys.none { it.endsWith(PART_REF) }) return provenance
        val joined = LinkedHashMap<String, JsonElement>(provenance.size)
        for ((key, value) in provenance) {
            val part = if (key.endsWith(PART_REF)) (value as? JsonPrimitive)?.content?.let(::part) else null
            if (part != null) joined[key.removeSuffix(PART_REF)] = part else joined[key] = value
        }
        return JsonObject(joined)
    }

    private fun part(sha: String): JsonElement? = parts[sha]
        ?: rows("SELECT part FROM provenance_parts WHERE sha = ?", sha) { recordOf(it.getString(1)) }.firstOrNull()
            ?.also { parts[sha] = it }

    private val parts = object : LinkedHashMap<String, JsonElement>(PROVENANCES, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonElement>) = size > PROVENANCES
    }

    /**
     * [json] as a provenance is stored: its [SHARED_PARTS] kept once each in `provenance_parts` under their sha256 and
     * named as `<key>PartRef` in their place. In a transaction. A provenance without them is stored as it is.
     */
    private fun splitParts(json: String): String {
        val parsed = recordOf(json) as? JsonObject ?: return json
        if (SHARED_PARTS.none { parsed[it] is JsonObject || parsed[it] is JsonArray }) return json
        val stored = LinkedHashMap<String, JsonElement>(parsed.size)
        for ((key, value) in parsed) {
            if (key in SHARED_PARTS && (value is JsonObject || value is JsonArray)) {
                val text = Json.encodeToString(JsonElement.serializer(), value)
                val sha = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                update("INSERT OR IGNORE INTO provenance_parts (sha, part) VALUES (?, ?)", sha, text)
                stored[key + PART_REF] = JsonPrimitive(sha)
            } else stored[key] = value
        }
        return Json.encodeToString(JsonElement.serializer(), JsonObject(stored))
    }

    /**
     * Split at most [batch] provenances stored whole, after rowid [after] ([splitParts]); returns the last rowid looked
     * at, null once none is left. Call again with it until null.
     */
    fun splitProvenances(after: Long, batch: Int = 50): Long? = synchronized(lock) {
        transaction {
            val stored = rows("SELECT rowid, provenance FROM provenances WHERE rowid > ? ORDER BY rowid LIMIT ?", after, batch) { r ->
                r.getLong(1) to r.getString(2)
            }
            for ((rowid, text) in stored) {
                val split = splitParts(text)
                if (split != text) update("UPDATE provenances SET provenance = ? WHERE rowid = ?", split, rowid)
            }
            stored.lastOrNull()?.first
        }
    }

    private val provenances = object : LinkedHashMap<String, JsonElement>(PROVENANCES, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonElement>) = size > PROVENANCES
    }

    /**
     * Keep the lab's verdict on case [index]'s solution claim ([com.eignex.lab.checker.LabCheck]), as JSON, replacing
     * any earlier one. Every reader of the case sees it as its record's `labCheck` ([withLabCheck]).
     */
    fun caseCheck(jobId: Long, index: Int, check: String) {
        val updated = synchronized(lock) { update("UPDATE cases SET lab_check = ? WHERE job_id = ? AND idx = ?", check, jobId, index) }
        if (updated != 0) {
            synchronized(scoring) { scoring.remove(jobId) }
        }
    }

    internal fun caseRevision(jobId: Long): Long = checkRevision(jobId)

    /** A changed point check can contradict a claim in another job. */
    internal fun checkRevision(): Long = checkRevision(0)

    private fun checkRevision(jobId: Long): Long = reading {
        rows("SELECT revision FROM check_revisions WHERE job_id = ?", jobId) { it.getLong(1) }.firstOrNull() ?: 0
    }

    /** [cases] without their records: what rendering a case's command for the host that runs it reads ([CasePlan]). */
    fun casePlan(jobId: Long): List<CaseResult> = reading {
        casesWith("NULL", jobId)
    }

    /** Every case of an experiment in command order, with its problem, arm, status and record. */
    fun cases(jobId: Long): List<CaseResult> = synchronized(lock) {
        casesWith("c.record", jobId, whole = true)
    }

    /**
     * At most [limit] of [cases], whole, from case index [from] on. A large job's records, final witnesses included,
     * can run to megabytes each, so an export reads them a page at a time instead of all at once.
     */
    fun cases(jobId: Long, from: Int, limit: Int): List<CaseResult> = synchronized(lock) {
        casesWith("c.record", jobId, from, limit, whole = true)
    }

    /**
     * [cases] with each record cut to what scoring and the trend read: its stats only [SCORING_STATS], and none of its
     * build provenance, final witness or attribution. A record runs to tens of kilobytes, nearly all of it provenance,
     * so SQLite cuts it before it is parsed here. A finished job's are kept between calls ([SCORING_JOBS] of them), its
     * cases being final until a recheck changes their revision or a retry changes the job's finish time.
     */
    fun scoringCases(jobId: Long): List<CaseResult> {
        val (status, finishedAt) = jobState(jobId) ?: return emptyList()
        val final = !status.isActive
        val revision = caseRevision(jobId)
        if (final) synchronized(scoring) {
            scoring[jobId]?.takeIf { it.first == finishedAt && it.second == revision }?.let { return it.third }
        }
        val cases = casesWith(SCORING_RECORD, jobId)
        if (final && caseRevision(jobId) == revision) synchronized(scoring) { scoring[jobId] = Triple(finishedAt, revision, cases) }
        return cases
    }

    private val scoring = object : LinkedHashMap<Long, Triple<Long?, Long, List<CaseResult>>>(SCORING_JOBS, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Triple<Long?, Long, List<CaseResult>>>) = size > SCORING_JOBS
    }

    private fun jobState(jobId: Long): Pair<Status, Long?>? = synchronized(lock) {
        rows("SELECT status, finished_at FROM jobs WHERE id = ?", jobId) { r ->
            Status.valueOf(r.getString(1)) to r.longOrNull("finished_at")
        }.firstOrNull()
    }

    /** The cases of [jobId] with [record] as each one's record; a [whole] one gets its provenance back ([StoredRecords.inflate]). */
    private fun casesWith(record: String, jobId: Long, from: Int = 0, limit: Int = -1, whole: Boolean = false): List<CaseResult> = synchronized(lock) {
        rows(
            """WITH accepted AS (
                 SELECT i.value AS idx, json_array(t.session_id, t.allocation_id, t.iteration_id, t.generation) AS attempt,
                        row_number() OVER (PARTITION BY i.value ORDER BY t.generation DESC) AS rn
                 FROM iteration_attempts t, json_each(t.indices) i
                 WHERE t.job_id = ? AND t.state = 'committed'
               )
               SELECT c.idx, m.status, p.problem, a.label, c.seed, $record AS record, c.repeat, $INSTRUMENTED, c.lab_check, $CASE_HOST, $LAB_DEBUG, c.stdout, accepted.attempt FROM cases c
               JOIN commands m ON m.job_id = c.job_id AND m.idx = c.idx
               JOIN problems p ON p.job_id = c.job_id AND p.idx = c.problem_idx
               JOIN arms a ON a.job_id = c.job_id AND a.idx = c.arm_idx
               JOIN jobs j ON j.id = c.job_id
               LEFT JOIN accepted ON accepted.idx = c.idx AND accepted.rn = 1
               WHERE c.job_id = ? AND c.idx >= ? ORDER BY c.idx LIMIT ?""",
            jobId, jobId, from, limit,
        ) { rows ->
            CaseResult(
                rows.getInt(1), Status.valueOf(rows.getString(2)), Json.decodeFromString(rows.getString(3)),
                rows.getString(4), rows.longOrNull("seed"),
                withLabCheck(rows.getString(6)?.let(::recordOf)?.let { if (whole) StoredRecords.inflate(it, ::provenance) else it },
                    rows.getString(9)),
                rows.getInt(7), rows.getInt(8) != 0, rows.getString(10)?.let(RecordedHost::of), rows.getObject(11)?.let { rows.getInt(11) != 0 },
                rows.getString(12)?.let { Json.decodeFromString(ListSerializer(CaseFile.serializer()), it) }.orEmpty(),
                if (record == "NULL") null else CaseOrigin(jobId, rows.getString(13)),
            )
        }
    }

    /** Keep provenance [json] under [sha], the sha256 of [json] itself, its shared parts kept once ([splitParts]). */
    internal fun keepProvenance(sha: String, json: String) =
        update("INSERT OR IGNORE INTO provenances (sha, provenance) VALUES (?, ?)", sha, splitParts(json))

    internal fun insertCommands(jobId: Long, commands: List<CaseCommand>) = connection.sqlBatch(
        "INSERT INTO commands (job_id, idx, cmd, timeout_sec, status, cores) VALUES (?, ?, ?, ?, ?, ?)",
        commands.mapIndexed { index, command -> listOf(jobId, index, command.cmd, command.timeoutSec, Status.QUEUED.name, command.cores) },
    )
}

/** Provenance keys whose values repeat across thousands of provenances, kept once each ([PlanRepository.keepProvenance]). */
private val SHARED_PARTS = setOf("harness", "runtime")
private const val PART_REF = "PartRef"
