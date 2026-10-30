package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** What an experiment was planned into (`arms`, `problems`, `cases`) and what its cases recorded, provenances kept once. */
class PlanRepository internal constructor(db: Database, lock: Any) : Repository(db, lock) {
    internal fun planRows(jobId: Long, arms: List<PlannedArm>, problems: List<Problem>, cases: List<Case>, commands: List<CaseCommand>) {
        arms.forEachIndexed { index, planned ->
            update(
                "INSERT INTO arms (job_id, idx, label, arm, sha) VALUES (?, ?, ?, ?, ?)",
                jobId, index, planned.arm.label, Json.encodeToString(planned.arm), planned.sha,
            )
        }
        connection.prepareStatement("INSERT INTO problems (job_id, idx, problem) VALUES (?, ?, ?)").use { statement ->
            problems.forEachIndexed { index, problem ->
                statement.setLong(1, jobId)
                statement.setInt(2, index)
                statement.setString(3, Json.encodeToString(problem))
                statement.addBatch()
            }
            statement.executeBatch()
        }
        connection.prepareStatement(
            "INSERT INTO cases (job_id, idx, problem_idx, arm_idx, seed, repeat) VALUES (?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            cases.forEachIndexed { index, case ->
                statement.setLong(1, jobId)
                statement.setInt(2, index)
                statement.setInt(3, case.problem)
                statement.setInt(4, case.arm)
                statement.setObject(5, case.seed)
                statement.setInt(6, case.repeat)
                statement.addBatch()
            }
            statement.executeBatch()
        }
        insertCommands(jobId, commands)
    }

    fun arms(jobId: Long): List<PlannedArm> = reading {
        connection.prepareStatement(
            "SELECT arm, sha FROM arms WHERE job_id = ? ORDER BY idx",
        ).use { statement ->
            statement.setLong(1, jobId)
            statement.executeQuery().use { rows ->
                generateSequence { if (rows.next()) PlannedArm(Json.decodeFromString(rows.getString(1)), rows.getString(2)) else null }
                    .toList()
            }
        }
    }

    /** The problem and arm of case [index] of [jobId]. */
    fun caseOf(jobId: Long, index: Int): Pair<Problem, Arm>? = reading {
        connection.prepareStatement(
            """SELECT p.problem, a.arm FROM cases c
               JOIN problems p ON p.job_id = c.job_id AND p.idx = c.problem_idx
               JOIN arms a ON a.job_id = c.job_id AND a.idx = c.arm_idx
               WHERE c.job_id = ? AND c.idx = ?""",
        ).use { statement ->
            statement.setLong(1, jobId)
            statement.setInt(2, index)
            statement.executeQuery().use { r ->
                if (r.next()) Json.decodeFromString<Problem>(r.getString(1)) to Json.decodeFromString<Arm>(r.getString(2)) else null
            }
        }
    }

    /** The solver commit case [index] of [jobId] ran: the one its arm was planned at. */
    fun caseSha(jobId: Long, index: Int): String? = reading {
        connection.prepareStatement(
            """SELECT a.sha FROM cases c JOIN arms a ON a.job_id = c.job_id AND a.idx = c.arm_idx
               WHERE c.job_id = ? AND c.idx = ?""",
        ).use { statement ->
            statement.setLong(1, jobId)
            statement.setInt(2, index)
            statement.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
        }
    }

    /** [record] as it is stored ([StoredRecords.compact]), its provenance kept once in `provenances`. In a transaction. */
    internal fun storedRecord(record: String): String {
        val compacted = StoredRecords.compact(record) ?: return record
        compacted.provenance?.let { (sha, json) -> keepProvenance(sha, json) }
        return compacted.text
    }

    /** Case [index]'s whole record, final witness and provenance and all: what a source check reads. */
    fun caseRecordText(jobId: Long, index: Int): String? =
synchronized(lock) {
                connection.prepareStatement("SELECT record FROM cases WHERE job_id = ? AND idx = ?").use { statement ->
                statement.setLong(1, jobId)
                statement.setInt(2, index)
                statement.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
            }?.let { text ->
                if (StoredRecords.PROVENANCE_REF !in text) text
                else StoredRecords.inflate(recordOf(text), ::provenance)?.toString() ?: text
            }
    }

    /** The build provenance kept under [sha], parsed; the most recently read are kept ([PROVENANCES]). */
    private fun provenance(sha: String): JsonElement? = provenances[sha] ?: connection.prepareStatement(
        "SELECT provenance FROM provenances WHERE sha = ?",
    ).use { statement ->
        statement.setString(1, sha)
        statement.executeQuery().use { r -> if (r.next()) recordOf(r.getString(1)) else null }
    }?.also { provenances[sha] = it }

    private val provenances = object : LinkedHashMap<String, JsonElement>(PROVENANCES, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JsonElement>) = size > PROVENANCES
    }

    /**
     * Keep the lab's verdict on case [index]'s solution claim ([com.eignex.lab.checker.LabCheck]), as JSON, replacing
     * any earlier one. Every reader of the case sees it as its record's `labCheck` ([withLabCheck]).
     */
    fun caseCheck(jobId: Long, index: Int, check: String) {
        synchronized(lock) { update("UPDATE cases SET lab_check = ? WHERE job_id = ? AND idx = ?", check, jobId, index) }
        synchronized(scoring) { scoring.remove(jobId) }
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
     * cases being final until it is queued again, which changes its finish time.
     */
    fun scoringCases(jobId: Long): List<CaseResult> {
        val (status, finishedAt) = jobState(jobId) ?: return emptyList()
        val final = !status.isActive
        if (final) synchronized(scoring) { scoring[jobId]?.takeIf { it.first == finishedAt }?.let { return it.second } }
        val cases = casesWith(SCORING_RECORD, jobId)
        if (final) synchronized(scoring) { scoring[jobId] = finishedAt to cases }
        return cases
    }

    private val scoring = object : LinkedHashMap<Long, Pair<Long?, List<CaseResult>>>(SCORING_JOBS, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Pair<Long?, List<CaseResult>>>) = size > SCORING_JOBS
    }

    private fun jobState(jobId: Long): Pair<Status, Long?>? =
synchronized(lock) {
                connection.prepareStatement("SELECT status, finished_at FROM jobs WHERE id = ?").use { statement ->
                statement.setLong(1, jobId)
                statement.executeQuery().use { r -> if (r.next()) Status.valueOf(r.getString(1)) to r.longOrNull("finished_at") else null }
            }
    }

    /** The cases of [jobId] with [record] as each one's record; a [whole] one gets its provenance back ([StoredRecords.inflate]). */
    private fun casesWith(record: String, jobId: Long, from: Int = 0, limit: Int = -1, whole: Boolean = false): List<CaseResult> = synchronized(lock) {
        connection.prepareStatement(
            """SELECT c.idx, m.status, p.problem, a.label, c.seed, $record AS record, c.repeat, $INSTRUMENTED, c.lab_check, $CASE_HOST, $LAB_DEBUG, c.stdout FROM cases c
               JOIN commands m ON m.job_id = c.job_id AND m.idx = c.idx
               JOIN problems p ON p.job_id = c.job_id AND p.idx = c.problem_idx
               JOIN arms a ON a.job_id = c.job_id AND a.idx = c.arm_idx
               JOIN jobs j ON j.id = c.job_id
               WHERE c.job_id = ? AND c.idx >= ? ORDER BY c.idx LIMIT ?""",
        ).use { statement ->
            statement.setLong(1, jobId)
            statement.setInt(2, from)
            statement.setInt(3, limit)
            statement.executeQuery().use { rows ->
                generateSequence {
                    if (!rows.next()) return@generateSequence null
                    CaseResult(
                        rows.getInt(1), Status.valueOf(rows.getString(2)), Json.decodeFromString(rows.getString(3)),
                        rows.getString(4), rows.longOrNull("seed"),
                        withLabCheck(rows.getString(6)?.let(::recordOf)?.let { if (whole) StoredRecords.inflate(it, ::provenance) else it },
                            rows.getString(9)),
                        rows.getInt(7), rows.getInt(8) != 0, rows.getString(10)?.let(RecordedHost::of), rows.getObject(11)?.let { rows.getInt(11) != 0 },
                        rows.getString(12)?.let { Json.decodeFromString(ListSerializer(CaseFile.serializer()), it) }.orEmpty(),
                    )
                }.toList()
            }
        }
    }

    internal fun keepProvenance(sha: String, json: String) =
        update("INSERT OR IGNORE INTO provenances (sha, provenance) VALUES (?, ?)", sha, json)

    internal fun insertCommands(jobId: Long, commands: List<CaseCommand>) = connection.prepareStatement(
        "INSERT INTO commands (job_id, idx, cmd, timeout_sec, status, cores) VALUES (?, ?, ?, ?, ?, ?)",
    ).use { statement ->
        commands.forEachIndexed { index, command ->
            statement.setLong(1, jobId)
            statement.setInt(2, index)
            statement.setString(3, command.cmd)
            statement.setLong(4, command.timeoutSec)
            statement.setString(5, Status.QUEUED.name)
            statement.setInt(6, command.cores)
            statement.addBatch()
        }
        statement.executeBatch()
    }
}
