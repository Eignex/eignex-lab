package com.eignex.lab

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.Properties

/**
 * The job queue, persisted in SQLite so that neither a crashed runner nor a reboot loses queued or finished work.
 * Every state change is one transaction; WAL mode lets the API read while the runner writes.
 */
class Store(file: Path, private val busyTimeoutMs: Long = BUSY_TIMEOUT_MS) {
    /** Present while a [vacuum] runs: a write in any process waits for it to end instead of failing after
     *  [busyTimeoutMs] ([waitWhileBusy]). */
    internal val vacuumMarker: Path = file.resolveSibling("${file.fileName}-vacuum")

    // IMMEDIATE takes the write lock at BEGIN. A deferred transaction that reads and then writes cannot wait for a
    // lock the other process holds, and fails with SQLITE_BUSY instead of honouring the busy timeout.
    private val connection: Connection = DriverManager.getConnection(
        "jdbc:sqlite:$file",
        Properties().apply { setProperty("transaction_mode", "IMMEDIATE") },
    ).apply {
        org.sqlite.BusyHandler.setHandler(this, waitWhileBusy())
        createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("PRAGMA synchronous=FULL")
            // A checkpointed write-ahead log is cut back to this, so one large write, a vacuum's, does not keep its size.
            it.execute("PRAGMA journal_size_limit=$WAL_LIMIT_BYTES")
            it.execute(
                """CREATE TABLE IF NOT EXISTS jobs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, ref TEXT NOT NULL, sha TEXT,
                    status TEXT NOT NULL, cancel_requested INTEGER NOT NULL DEFAULT 0,
                    setup_done INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, started_at INTEGER,
                    finished_at INTEGER, error TEXT, parallel INTEGER NOT NULL DEFAULT 1,
                    priority INTEGER NOT NULL DEFAULT 0, paused INTEGER NOT NULL DEFAULT 0, experiment TEXT,
                    run_ms INTEGER NOT NULL DEFAULT 0, running_since INTEGER)""",
            )
            it.execute(
                """CREATE TABLE IF NOT EXISTS commands (
                    job_id INTEGER NOT NULL, idx INTEGER NOT NULL, cmd TEXT NOT NULL, timeout_sec INTEGER NOT NULL,
                    status TEXT NOT NULL, exit_code INTEGER, started_at INTEGER, finished_at INTEGER,
                    cores INTEGER NOT NULL DEFAULT 1, phase TEXT, PRIMARY KEY (job_id, idx))""",
            )
            it.execute(
                """CREATE TABLE IF NOT EXISTS schedules (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, ref TEXT NOT NULL,
                    parallel INTEGER NOT NULL, priority INTEGER NOT NULL, interval_sec INTEGER NOT NULL,
                    last_sha TEXT, last_job INTEGER, checked_at INTEGER, experiment TEXT NOT NULL)""",
            )
            it.execute(
                """CREATE TABLE IF NOT EXISTS arms (
                    job_id INTEGER NOT NULL, idx INTEGER NOT NULL, label TEXT NOT NULL, arm TEXT NOT NULL,
                    sha TEXT NOT NULL, PRIMARY KEY (job_id, idx))""",
            )
            it.execute(
                """CREATE TABLE IF NOT EXISTS problems (
                    job_id INTEGER NOT NULL, idx INTEGER NOT NULL, problem TEXT NOT NULL, PRIMARY KEY (job_id, idx))""",
            )
            it.execute(
                """CREATE TABLE IF NOT EXISTS cases (
                    job_id INTEGER NOT NULL, idx INTEGER NOT NULL, problem_idx INTEGER NOT NULL,
                    arm_idx INTEGER NOT NULL, seed INTEGER, record TEXT, repeat INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (job_id, idx))""",
            )
            // Each distinct build provenance once, which the case records refer to by its sha256 ([StoredRecords]).
            it.execute("CREATE TABLE IF NOT EXISTS provenances (sha TEXT PRIMARY KEY, provenance TEXT NOT NULL)")
            // The one-time compaction's progress ([DbCompaction]), one row.
            it.execute("CREATE TABLE IF NOT EXISTS compaction (id INTEGER PRIMARY KEY CHECK (id = 0), state TEXT NOT NULL)")
            it.execute(
                """CREATE TABLE IF NOT EXISTS reference_rows (
                    collection TEXT NOT NULL, problem TEXT NOT NULL, solver TEXT NOT NULL, maximize INTEGER NOT NULL,
                    objective REAL, feasible INTEGER, proven INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL,
                    budget_ms INTEGER NOT NULL, source TEXT NOT NULL, updated_at INTEGER NOT NULL,
                    version TEXT NOT NULL DEFAULT '', validation TEXT, dual_bound REAL, stale INTEGER NOT NULL DEFAULT 0,
                    source_identity TEXT, PRIMARY KEY (collection, problem, solver))""",
            )
            it.execute(
                """CREATE TABLE IF NOT EXISTS problem_features (
                    collection TEXT NOT NULL, problem TEXT NOT NULL, themes TEXT NOT NULL, structure TEXT, logic TEXT,
                    PRIMARY KEY (collection, problem))""",
            )
            // Downloads' holds on jobs' files ([Hold]): here, not in the API's memory, so the runner's trim sees them and
            // a restart keeps them.
            it.execute(
                """CREATE TABLE IF NOT EXISTS holds (
                    id TEXT PRIMARY KEY, job_id INTEGER NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)""",
            )
            // [referencesStamp] reads the newest row on every reference and trend page load.
            it.execute("CREATE INDEX IF NOT EXISTS reference_rows_updated ON reference_rows (updated_at)")
            // [problemRuns] finds one problem's cases across every job; without this it reads every problem row.
            it.execute(
                "CREATE INDEX IF NOT EXISTS problems_identity ON problems " +
                    "(json_extract(problem, '$.collection'), json_extract(problem, '$.problem'))",
            )
            val jobColumns = it.executeQuery("PRAGMA table_info(jobs)").use { r -> generateSequence { if (r.next()) r.getString("name") else null }.toSet() }
            if ("local" !in jobColumns) {
                it.execute("ALTER TABLE jobs ADD COLUMN local INTEGER NOT NULL DEFAULT 0")
                // The job a runner from before the column ran on the local machine.
                it.execute("UPDATE jobs SET local = 1 WHERE status = 'RUNNING' AND $HOST = 'lab'")
            }
            if ("backend" !in jobColumns) it.execute("ALTER TABLE jobs ADD COLUMN backend TEXT NOT NULL DEFAULT 'legacy'")
            ExecutionStore.migrate(this)
            // A queued job whose cancel was requested, which a runner from before requeues left: it is cancelled.
            it.executeUpdate("UPDATE commands SET status = 'CANCELLED' WHERE status IN ('QUEUED', 'RUNNING') AND job_id IN " +
                "(SELECT id FROM jobs WHERE status = 'QUEUED' AND cancel_requested = 1)")
            it.executeUpdate("UPDATE jobs SET status = 'CANCELLED', finished_at = COALESCE(finished_at, ${now()}), local = 0, paused = 0 " +
                "WHERE status = 'QUEUED' AND cancel_requested = 1")
            val scheduleColumns = it.executeQuery("PRAGMA table_info(schedules)").use { r -> generateSequence { if (r.next()) r.getString("name") else null }.toSet() }
            if ("launch" !in scheduleColumns) it.execute("ALTER TABLE schedules ADD COLUMN launch INTEGER NOT NULL DEFAULT 0")
            val commandColumns = it.executeQuery("PRAGMA table_info(commands)").use { r -> generateSequence { if (r.next()) r.getString("name") else null }.toSet() }
            if ("phase" !in commandColumns) it.execute("ALTER TABLE commands ADD COLUMN phase TEXT")
            // The machine that holds or ran each case ([Command.host]).
            if ("host" !in commandColumns) it.execute("ALTER TABLE commands ADD COLUMN host TEXT")
            // The lab's own verdict on a case's solution claim ([com.eignex.lab.checker.LabCheck]), beside its record.
            val caseColumns = it.executeQuery("PRAGMA table_info(cases)").use { r -> generateSequence { if (r.next()) r.getString("name") else null }.toSet() }
            if ("lab_check" !in caseColumns) it.execute("ALTER TABLE cases ADD COLUMN lab_check TEXT")
            // The native solver stdout each case left, with its hashes ([CaseResult.stdout]), as JSON.
            if ("stdout" !in caseColumns) it.execute("ALTER TABLE cases ADD COLUMN stdout TEXT")
            val columns = it.executeQuery("PRAGMA table_info(reference_rows)").use { r -> generateSequence { if (r.next()) r.getString("name") else null }.toSet() }
            if ("version" !in columns) {
                it.execute("ALTER TABLE reference_rows ADD COLUMN version TEXT NOT NULL DEFAULT ''")
                it.execute("ALTER TABLE reference_rows ADD COLUMN validation TEXT")
                it.execute("ALTER TABLE reference_rows ADD COLUMN dual_bound REAL")
                it.execute("ALTER TABLE reference_rows ADD COLUMN stale INTEGER NOT NULL DEFAULT 0")
                // The MPS references' rows from before their claims were checked against the model: kept as evidence,
                // no longer trusted, and replaced by the next run of each solver ([References.stale]).
                it.execute("UPDATE reference_rows SET stale = 1 WHERE solver IN ${References.UNCHECKED_SOLVERS.joinToString(",", "(", ")") { s -> "'$s'" }}")
            }
            // The rows from before the source files were kept stay unbound ([Reference.sourceIdentity]).
            if ("source_identity" !in columns) it.execute("ALTER TABLE reference_rows ADD COLUMN source_identity TEXT")
        }
        retireReferences(this)
    }

    /**
     * SQLite's busy handler: retry a locked database for [busyTimeoutMs], as `busy_timeout` would, and for as long as
     * a [vacuum] runs, which holds the write lock for minutes, as told by [vacuumMarker]. A marker older than
     * [VACUUM_MAX_MS] is left over from a vacuum that died and is ignored.
     */
    private fun waitWhileBusy() = object : org.sqlite.BusyHandler() {
        private var since = 0L

        override fun callback(tries: Int): Int {
            val now = System.currentTimeMillis()
            if (tries == 0) since = now
            val vacuuming = runCatching { now - Files.getLastModifiedTime(vacuumMarker).toMillis() < VACUUM_MAX_MS }.getOrDefault(false)
            if (now - since >= busyTimeoutMs && !vacuuming) return 0
            Thread.sleep(minOf(tries + 1L, BUSY_SLEEP_MS))
            return 1
        }
    }

    private fun retireReferences(database: Connection) {
        database.autoCommit = false
        try {
            val retired = database.createStatement().use { statement ->
                statement.executeQuery("SELECT collection, problem, solver, version FROM reference_rows WHERE stale = 0").use { rows ->
                    buildList {
                        while (rows.next()) {
                            val collection = rows.getString("collection")
                            val solver = rows.getString("solver")
                            if (References.stale(solver, rows.getString("version"), collection)) {
                                add(Triple(collection, rows.getString("problem"), solver))
                            }
                        }
                    }
                }
            }
            database.prepareStatement(
                "UPDATE reference_rows SET stale = 1, updated_at = ? WHERE collection = ? AND problem = ? AND solver = ?",
            ).use { update ->
                val at = now()
                for ((collection, problem, solver) in retired) {
                    update.setLong(1, at)
                    update.setString(2, collection)
                    update.setString(3, problem)
                    update.setString(4, solver)
                    update.executeUpdate()
                }
            }
            database.commit()
        } catch (error: Throwable) {
            database.rollback()
            throw error
        } finally {
            database.autoCommit = true
        }
    }

    @Synchronized
    fun create(
        name: String,
        ref: String,
        commands: List<Pair<String, Long>>,
        parallel: Int = 1,
        priority: Int = 0,
        experiment: ExperimentSpec,
    ): Long = transaction {
        val id = connection.prepareStatement(
            "INSERT INTO jobs (name, ref, status, created_at, parallel, priority, experiment, backend) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use {
            it.setString(1, name)
            it.setString(2, ref)
            it.setString(3, Status.QUEUED.name)
            it.setLong(4, now())
            it.setInt(5, parallel)
            it.setInt(6, priority)
            it.setString(7, experiment.let { spec -> Json.encodeToString(spec) })
            it.setString(8, "nomad")
            it.executeUpdate()
            it.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
        }
        insertCommands(id, commands.map { (cmd, timeout) -> CaseCommand(cmd, timeout) })
        id
    }

    private fun insertCommands(jobId: Long, commands: List<CaseCommand>) = connection.prepareStatement(
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

    /** Publish a verified preparation as one plan, including its setup marker, under the cancellation lock. */
    @Synchronized
    fun prepareNomad(jobId: Long, arms: List<PlannedArm>, problems: List<Problem>, cases: List<Case>, commands: List<CaseCommand>): Boolean = transaction {
        val job = job(jobId) ?: error("missing experiment")
        require(job.backend == Backend.NOMAD && !job.cancelRequested && job.status.isActive)
        if (job.setupDone) return@transaction false
        require(!job.planned && cases.isNotEmpty() && cases.size == commands.size)
        planRows(jobId, arms, problems, cases, commands)
        update("UPDATE jobs SET sha = ?, setup_done = 1 WHERE id = ?", arms.first().sha, jobId)
        true
    }

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

    @Synchronized
    fun arms(jobId: Long): List<PlannedArm> = connection.prepareStatement(
        "SELECT arm, sha FROM arms WHERE job_id = ? ORDER BY idx",
    ).use { statement ->
        statement.setLong(1, jobId)
        statement.executeQuery().use { rows ->
            generateSequence { if (rows.next()) PlannedArm(Json.decodeFromString(rows.getString(1)), rows.getString(2)) else null }
                .toList()
        }
    }

    /** The problem and arm of case [index] of [jobId]. */
    @Synchronized
    fun caseOf(jobId: Long, index: Int): Pair<Problem, Arm>? = connection.prepareStatement(
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

    /** The solver commit case [index] of [jobId] ran: the one its arm was planned at. */
    @Synchronized
    fun caseSha(jobId: Long, index: Int): String? = connection.prepareStatement(
        """SELECT a.sha FROM cases c JOIN arms a ON a.job_id = c.job_id AND a.idx = c.arm_idx
           WHERE c.job_id = ? AND c.idx = ?""",
    ).use { statement ->
        statement.setLong(1, jobId)
        statement.setInt(2, index)
        statement.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
    }

    /** [record] as it is stored ([StoredRecords.compact]), its provenance kept once in `provenances`. In a transaction. */
    internal fun storedRecord(record: String): String {
        val compacted = StoredRecords.compact(record) ?: return record
        compacted.provenance?.let { (sha, json) -> keepProvenance(sha, json) }
        return compacted.text
    }

    private fun keepProvenance(sha: String, json: String) =
        update("INSERT OR IGNORE INTO provenances (sha, provenance) VALUES (?, ?)", sha, json)

    /** Case [index]'s whole record, final witness and provenance and all: what a source check reads. */
    @Synchronized
    fun caseRecordText(jobId: Long, index: Int): String? =
        connection.prepareStatement("SELECT record FROM cases WHERE job_id = ? AND idx = ?").use { statement ->
            statement.setLong(1, jobId)
            statement.setInt(2, index)
            statement.executeQuery().use { r -> if (r.next()) r.getString(1) else null }
        }?.let { text ->
            if (StoredRecords.PROVENANCE_REF !in text) text
            else StoredRecords.inflate(recordOf(text), ::provenance)?.toString() ?: text
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

    /** The compaction's progress ([DbCompaction]): where it is, or a fresh state before it started. */
    @Synchronized
    fun compaction(): Compaction = connection.prepareStatement("SELECT state FROM compaction WHERE id = 0").use { statement ->
        statement.executeQuery().use { r -> if (r.next()) Json.decodeFromString<Compaction>(r.getString(1)) else Compaction() }
    }

    private fun saveCompaction(state: Compaction) = update(
        "INSERT INTO compaction (id, state) VALUES (0, ?) ON CONFLICT (id) DO UPDATE SET state = excluded.state",
        Json.encodeToString(state),
    )

    /** Start the compaction: count the cases it has to go over. */
    @Synchronized
    fun startCompaction(): Compaction = transaction {
        val total = connection.prepareStatement("SELECT count(*) FROM cases WHERE record IS NOT NULL").use { s ->
            s.executeQuery().use { r -> r.next(); r.getInt(1) }
        }
        compaction().copy(total = total, startedAt = now()).also(::saveCompaction)
    }

    /**
     * Compact the next [limit] stored case records after the compaction's cursor ([StoredRecords.compact]) and move the
     * cursor past them, in one transaction. The records are read and compacted outside the store's lock, and a record
     * that changed meanwhile is left as its writer stored it, compacted. Once a batch finds fewer than [limit], the
     * compaction is done: every record written since it started was written compacted.
     */
    fun compactBatch(limit: Int): Compaction {
        val cursor = compaction().cursor
        val rows = synchronized(this) {
            connection.prepareStatement(
                "SELECT rowid, record FROM cases WHERE rowid > ? AND record IS NOT NULL ORDER BY rowid LIMIT ?",
            ).use { s ->
                s.setLong(1, cursor)
                s.setInt(2, limit)
                s.executeQuery().use { r -> generateSequence { if (r.next()) r.getLong(1) to r.getString(2) else null }.toList() }
            }
        }
        val compacted = rows.map { (rowid, text) -> Triple(rowid, text, StoredRecords.compact(text)) }
        return synchronized(this) {
            transaction {
                var state = compaction()
                for ((rowid, text, record) in compacted) {
                    if (record == null) continue
                    record.provenance?.let { (sha, json) -> keepProvenance(sha, json) }
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
    @Synchronized
    fun vacuumOutcome(outcome: String) = transaction { saveCompaction(compaction().copy(vacuum = outcome, vacuumAt = now())) }

    /** The database's size and free pages as SQLite counts them, its kept provenances and the compaction's progress. */
    @Synchronized
    fun database(): DatabaseReport {
        fun pragma(name: String) = connection.createStatement().use { s -> s.executeQuery("PRAGMA $name").use { r -> r.next(); r.getLong(1) } }
        val provenances = connection.prepareStatement("SELECT count(*) FROM provenances").use { s -> s.executeQuery().use { r -> r.next(); r.getInt(1) } }
        return DatabaseReport(pragma("page_size"), pragma("page_count"), pragma("freelist_count"), provenances, compaction())
    }

    /**
     * VACUUM the database, giving its free pages back to the disk, then checkpoint the write-ahead log it went through
     * and cut it back. It holds the write lock throughout: this store's other callers wait on its lock, and every other
     * process's writes wait while [vacuumMarker] is there ([waitWhileBusy]). Readers in other processes go on reading.
     */
    @Synchronized
    fun vacuum() {
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

    /**
     * Keep the lab's verdict on case [index]'s solution claim ([com.eignex.lab.checker.LabCheck]), as JSON, replacing
     * any earlier one. Every reader of the case sees it as its record's `labCheck` ([withLabCheck]).
     */
    fun caseCheck(jobId: Long, index: Int, check: String) {
        synchronized(this) { update("UPDATE cases SET lab_check = ? WHERE job_id = ? AND idx = ?", check, jobId, index) }
        synchronized(scoring) { scoring.remove(jobId) }
    }

    /** [cases] without their records: what rendering a case's command for the host that runs it reads ([CasePlan]). */
    @Synchronized
    fun casePlan(jobId: Long): List<CaseResult> = casesWith("NULL", jobId)

    /** Cases of [jobId] queued that no host has claimed: the work another host could join it for. */
    @Synchronized
    fun unclaimed(jobId: Long): Int = connection.prepareStatement(
        "SELECT COUNT(*) FROM commands WHERE job_id = ? AND status = 'QUEUED' AND host IS NULL",
    ).use { statement ->
        statement.setLong(1, jobId)
        statement.executeQuery().use { r -> if (r.next()) r.getInt(1) else 0 }
    }

    /** Each host's part of the jobs [ids] ([HostProgress]), the local machine first, then the nodes by name; a case that
     *  ran before cases recorded their host is left out. */
    @Synchronized
    fun hostProgress(ids: Collection<Long>): Map<Long, List<HostProgress>> = if (ids.isEmpty()) emptyMap() else
        connection.prepareStatement(
            "SELECT job_id, host, SUM(status IN ('DONE', 'FAILED')), SUM(status = 'RUNNING'), SUM(status = 'QUEUED') FROM commands " +
                "WHERE job_id IN (${ids.joinToString(",") { "?" }}) AND host IS NOT NULL GROUP BY job_id, host " +
                "ORDER BY job_id, host != '${RecordedHost.LOCAL}', host",
        ).use { statement ->
            ids.forEachIndexed { i, id -> statement.setLong(i + 1, id) }
            statement.executeQuery().use { r ->
                generateSequence { if (r.next()) r.getLong(1) to HostProgress(RecordedHost.of(r.getString(2)), r.getInt(3), r.getInt(4), r.getInt(5)) else null }
                    .groupBy({ it.first }, { it.second })
            }
        }

    /** Every case of an experiment in command order, with its problem, arm, status and record. */
    @Synchronized
    fun cases(jobId: Long): List<CaseResult> = casesWith("c.record", jobId, whole = true)

    /**
     * At most [limit] of [cases], whole, from case index [from] on. A large job's records, final witnesses included,
     * can run to megabytes each, so an export reads them a page at a time instead of all at once.
     */
    @Synchronized
    fun cases(jobId: Long, from: Int, limit: Int): List<CaseResult> = casesWith("c.record", jobId, from, limit, whole = true)

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

    @Synchronized
    private fun jobState(jobId: Long): Pair<Status, Long?>? =
        connection.prepareStatement("SELECT status, finished_at FROM jobs WHERE id = ?").use { statement ->
            statement.setLong(1, jobId)
            statement.executeQuery().use { r -> if (r.next()) Status.valueOf(r.getString(1)) to r.longOrNull("finished_at") else null }
        }

    /** The cases of [jobId] with [record] as each one's record; a [whole] one gets its provenance back ([StoredRecords.inflate]). */
    @Synchronized
    private fun casesWith(record: String, jobId: Long, from: Int = 0, limit: Int = -1, whole: Boolean = false): List<CaseResult> = connection.prepareStatement(
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

    /** The newest jobs first, older than [before] when given. A [name] keeps that name's jobs and the ones its
     *  schedule queued as `name@sha`. Without [withCommands] their commands are left out for their [Job.counts]: a
     *  list of many jobs needs their progress, not their hundreds of commands each. */
    @Synchronized
    fun jobs(limit: Int = 200, before: Long? = null, name: String? = null, withCommands: Boolean = true): List<Job> = jobsWhere(
        "id < ? AND (? IS NULL OR name = ? OR name LIKE ? ESCAPE '\\') ORDER BY id DESC LIMIT ?",
        before ?: Long.MAX_VALUE, name, name, name?.let { likeEscape(it) + "@%" }, limit, withCommands = withCommands,
    )

    /** Every job that ended (done, failed or cancelled): its id and when it ended. */
    @Synchronized
    fun ended(): List<Pair<Long, Long>> = connection.prepareStatement(
        "SELECT id, finished_at FROM jobs WHERE status IN (?, ?, ?) AND finished_at IS NOT NULL",
    ).use { statement ->
        listOf(Status.DONE, Status.FAILED, Status.CANCELLED).forEachIndexed { i, s -> statement.setString(i + 1, s.name) }
        statement.executeQuery().use { r -> generateSequence { if (r.next()) r.getLong(1) to r.getLong(2) else null }.toList() }
    }

    /**
     * A new hold on job [jobId]'s files for [ttlMs] from [at] ([Hold]). Lapsed holds are forgotten here, so the table
     * keeps no more than the downloads of the last few hours.
     */
    @Synchronized
    fun hold(jobId: Long, ttlMs: Long, at: Long = now()): Hold = transaction {
        update("DELETE FROM holds WHERE expires_at <= ?", at)
        val hold = Hold(newHoldId(), jobId, at, at + ttlMs)
        update("INSERT INTO holds (id, job_id, created_at, expires_at) VALUES (?, ?, ?, ?)", hold.id, hold.job, hold.createdAt, hold.expiresAt)
        hold
    }

    /** Hold [id] on job [jobId] extended to [ttlMs] from [at]; null when there is no such hold or it has lapsed. */
    @Synchronized
    fun renewHold(jobId: Long, id: String, ttlMs: Long, at: Long = now()): Hold? = transaction {
        val renewed = update("UPDATE holds SET expires_at = ? WHERE id = ? AND job_id = ? AND expires_at > ?", at + ttlMs, id, jobId, at)
        if (renewed == 0) null else holdsWhere("id = ?", id).single()
    }

    /** Releases hold [id] on job [jobId]; false when there was no such hold, lapsed or not. */
    @Synchronized
    fun releaseHold(jobId: Long, id: String): Boolean = update("DELETE FROM holds WHERE id = ? AND job_id = ?", id, jobId) > 0

    /** The holds that have not lapsed at [at], by job, then by when they lapse. */
    @Synchronized
    fun holds(at: Long = now()): List<Hold> = holdsWhere("expires_at > ? ORDER BY job_id, expires_at", at)

    private fun holdsWhere(where: String, vararg values: Any?): List<Hold> =
        connection.prepareStatement("SELECT * FROM holds WHERE $where").use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { r ->
                generateSequence {
                    if (r.next()) Hold(r.getString("id"), r.getLong("job_id"), r.getLong("created_at"), r.getLong("expires_at")) else null
                }.toList()
            }
        }

    /** Every queued or running job, oldest first; without [withCommands], with their [Job.counts] instead. */
    @Synchronized
    fun active(withCommands: Boolean = true): List<Job> =
        jobsWhere("status IN (?, ?) ORDER BY id", Status.QUEUED.name, Status.RUNNING.name, withCommands = withCommands)

    private fun jobsWhere(where: String, vararg values: Any?, withCommands: Boolean = true): List<Job> =
        connection.prepareStatement("SELECT * FROM jobs WHERE $where").use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rows -> generateSequence { if (rows.next()) job(rows) else null }.toList() }
        }.let { jobs ->
            if (withCommands) return@let jobs.map { it.copy(commands = commands(it.id)) }
            val counts = commandCounts(jobs.map { it.id })
            jobs.map { it.copy(counts = counts[it.id] ?: CommandCounts(0, 0, 0, 0)) }
        }

    private fun commandCounts(ids: List<Long>): Map<Long, CommandCounts> = if (ids.isEmpty()) emptyMap() else
        connection.prepareStatement(
            "SELECT job_id, COUNT(*), SUM(status IN ('DONE', 'FAILED')), SUM(status = 'FAILED'), SUM(status = 'RUNNING'), " +
                "SUM($TIMED), SUM(CASE WHEN $TIMED THEN finished_at - started_at ELSE 0 END) " +
                "FROM commands WHERE job_id IN (${ids.joinToString(",") { "?" }}) GROUP BY job_id",
        ).use { statement ->
            ids.forEachIndexed { i, id -> statement.setLong(i + 1, id) }
            statement.executeQuery().use { r ->
                generateSequence {
                    if (r.next()) r.getLong(1) to CommandCounts(r.getInt(2), r.getInt(3), r.getInt(4), r.getInt(5), r.getInt(6), r.getLong(7)) else null
                }.toMap()
            }
        }

    /** The newest [limit] experiments that ended done, their ids only, newest first: the runs [EstimateHistory] reads. */
    @Synchronized
    fun doneExperimentIds(limit: Int): List<Long> = connection.prepareStatement(
        "SELECT id FROM jobs WHERE status = ? AND experiment IS NOT NULL ORDER BY id DESC LIMIT ?",
    ).use { statement ->
        statement.setString(1, Status.DONE.name)
        statement.setInt(2, limit)
        statement.executeQuery().use { r -> generateSequence { if (r.next()) r.getLong(1) else null }.toList() }
    }

    /** The newest experiment of schedule [series] (named `<series>@<sha>`) that ended done, null when none did. */
    @Synchronized
    fun lastDoneOfSeries(series: String): Long? = connection.prepareStatement(
        "SELECT id FROM jobs WHERE status = ? AND experiment IS NOT NULL AND substr(name, 1, ?) = ? ORDER BY id DESC LIMIT 1",
    ).use { statement ->
        statement.setString(1, Status.DONE.name)
        statement.setInt(2, series.length + 1)
        statement.setString(3, "$series@")
        statement.executeQuery().use { r -> if (r.next()) r.getLong(1) else null }
    }

    /** The jobs [ids], without their commands, with their [Job.counts]. */
    @Synchronized
    fun jobsByIds(ids: Collection<Long>): List<Job> =
        if (ids.isEmpty()) emptyList() else jobsWhere("id IN (${ids.joinToString(",") { "?" }}) ORDER BY id", *ids.toTypedArray(), withCommands = false)

    private fun likeEscape(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    @Synchronized
    fun job(id: Long): Job? = connection.prepareStatement("SELECT * FROM jobs WHERE id = ?").use { statement ->
        statement.setLong(1, id)
        statement.executeQuery().use { rows -> if (rows.next()) job(rows) else null }
    }?.let { it.copy(commands = commands(it.id)) }

    @Synchronized
    fun nextAnalysis(): Job? = transaction {
        val id = connection.prepareStatement("SELECT id FROM jobs WHERE status IN ('QUEUED','RUNNING') " +
            "AND cancel_requested = 0 AND paused = 0 AND json_extract(experiment, '$.analyze') IS NOT NULL " +
            "ORDER BY status = 'RUNNING' DESC, priority DESC, id LIMIT 1").use { statement ->
                statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
            } ?: return@transaction null
        if (job(id)?.status == Status.QUEUED) markRunning(id, now())
        update("UPDATE jobs SET local = 1 WHERE id = ?", id)
        id
    }?.let(::job)

    private fun markRunning(id: Long, at: Long, interruptedRun: Long = 0) = connection.prepareStatement(
        "UPDATE jobs SET status = ?, started_at = COALESCE(started_at, ?), running_since = ?, run_ms = run_ms + ? WHERE id = ?",
    ).use {
        it.setString(1, Status.RUNNING.name)
        it.setLong(2, at)
        it.setLong(3, at)
        it.setLong(4, interruptedRun)
        it.setLong(5, id)
        it.executeUpdate()
    }

    /** Whether the local machine is running a job. */
    @Synchronized
    fun localBusy(): Boolean = connection.prepareStatement("SELECT 1 FROM jobs WHERE status = ? AND local = 1 LIMIT 1").use {
        it.setString(1, Status.RUNNING.name)
        it.executeQuery().use { rows -> rows.next() }
    }

    /** End [jobId] as [status]; 0 when it had already ended, so a cancel that landed first is never overwritten. */
    @Synchronized
    fun finish(jobId: Long, status: Status, error: String? = null): Int {
        val at = now()
        return update(
            "UPDATE jobs SET local = 0, paused = 0, status = ?, finished_at = ?, error = ?, " +
                "run_ms = run_ms + COALESCE(? - running_since, 0), running_since = NULL " +
                "WHERE id = ? AND status NOT IN ('DONE', 'FAILED', 'CANCELLED')",
            status.name, at, error, at, jobId,
        )
    }

    @Synchronized
    fun requestCancel(jobId: Long): CancelOutcome = transaction {
        val job = job(jobId) ?: return@transaction CancelOutcome.MISSING
        if (job.backend == Backend.NOMAD && job.status.isActive) {
            execution.cancelIn(connection, jobId, now())
            return@transaction CancelOutcome.CANCELLED
        }
        when (job.status) {
            Status.QUEUED -> {
                update("UPDATE jobs SET status = ?, cancel_requested = 1, finished_at = ? WHERE id = ?",
                    Status.CANCELLED.name, now(), jobId)
                update("UPDATE commands SET status = ? WHERE job_id = ? AND status = ?",
                    Status.CANCELLED.name, jobId, Status.QUEUED.name)
                CancelOutcome.CANCELLED
            }
            Status.RUNNING -> {
                update("UPDATE jobs SET cancel_requested = 1 WHERE id = ?", jobId)
                CancelOutcome.REQUESTED
            }
            else -> CancelOutcome.FINISHED
        }
    }

    /** Set how many of [jobId]'s commands may run at once; false when there is no such job. */
    @Synchronized
    fun createSchedule(name: String, ref: String, experiment: ExperimentSpec, intervalSec: Long): Long = connection.prepareStatement(
        "INSERT INTO schedules (name, ref, parallel, priority, interval_sec, experiment) VALUES (?, ?, ?, ?, ?, ?)",
        java.sql.Statement.RETURN_GENERATED_KEYS,
    ).use {
        it.setString(1, name)
        it.setString(2, ref)
        it.setInt(3, experiment.parallel ?: 0)
        it.setInt(4, experiment.priority)
        it.setLong(5, intervalSec)
        it.setString(6, Json.encodeToString(experiment))
        it.executeUpdate()
        it.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
    }

    @Synchronized
    fun schedules(): List<Schedule> = connection.prepareStatement("SELECT * FROM schedules ORDER BY id").use { statement ->
        statement.executeQuery().use { rows ->
            generateSequence {
                if (!rows.next()) return@generateSequence null
                // Parallel and priority are the experiment's own; the columns copy them for older readers.
                val experiment = storedExperiment(rows.getString("experiment"))
                Schedule(
                    rows.getLong("id"), rows.getString("name"), rows.getString("ref"), experiment,
                    experiment.parallel ?: 0, experiment.priority,
                    rows.getLong("interval_sec"), rows.getString("last_sha"), rows.longOrNull("last_job"),
                    rows.longOrNull("checked_at"), rows.getInt("launch") == 1,
                )
            }.toList()
        }
    }

    /**
     * Change schedule [id]: a new [experiment]; a new [intervalSec]; and [nextCheckAt], when its next check is due
     * (epoch ms). Unset fields stay. An experiment that measures something else ([Experiments.fingerprint]) forgets the
     * last commit, so the next check queues it at the ref's commit; one that only changes how it runs, where it may run
     * ([Placement.cloudOptIn]) or how it is described is saved, and its next run follows it, but queues no extra run.
     * A schedule keeps its phase otherwise, so staggered schedules stay apart. False when there is no such schedule.
     */
    @Synchronized
    fun updateSchedule(id: Long, experiment: ExperimentSpec?, intervalSec: Long?, nextCheckAt: Long? = null): Boolean = transaction {
        val old = schedules().firstOrNull { it.id == id }?.experiment
        val found = update("UPDATE schedules SET interval_sec = COALESCE(?, interval_sec) WHERE id = ?", intervalSec, id) == 1
        if (found && experiment != null) {
            // Compared as the schedule runs them ([SourceValidation.scheduled]), so saving the validation request a
            // schedule already gets queues no extra run.
            val changed = old == null ||
                Experiments.fingerprint(SourceValidation.scheduled(old)) != Experiments.fingerprint(SourceValidation.scheduled(experiment))
            update(
                "UPDATE schedules SET experiment = ?, parallel = ?, priority = ?" + (if (changed) ", last_sha = NULL" else "") + " WHERE id = ?",
                Json.encodeToString(experiment), experiment.parallel ?: 0, experiment.priority, id,
            )
        }
        // A schedule is due one interval after its last check, so its next check sets the last one back by an interval.
        if (found && nextCheckAt != null) update("UPDATE schedules SET checked_at = ? - interval_sec * 1000 WHERE id = ?", nextCheckAt, id)
        found
    }

    @Synchronized
    fun deleteSchedule(id: Long): Boolean = update("DELETE FROM schedules WHERE id = ?", id) == 1

    /** Forget an ended job: its row, its commands and, for an experiment, its arms, problems and cases. False when
     *  there is no such job or it has not ended, since the runner may still be writing it. */
    @Synchronized
    fun deleteJob(id: Long): Boolean = transaction {
        val deleted = update("""DELETE FROM jobs WHERE id = ? AND status NOT IN (?, ?) AND NOT EXISTS (
            SELECT 1 FROM execution_sessions s WHERE s.job_id = jobs.id AND (s.desired != 'stop' OR NOT EXISTS (
                SELECT 1 FROM execution_outbox o WHERE o.id = s.id || ':stop' AND o.acknowledged = 1)))""",
            id, Status.QUEUED.name, Status.RUNNING.name) == 1
        if (deleted) for (table in listOf("commands", "arms", "problems", "cases", "execution_ingestion", "execution_checks", "execution_queue_recipes", "execution_selections", "execution_events", "iteration_attempts", "execution_outbox", "execution_sessions", "holds")) update("DELETE FROM $table WHERE job_id = ?", id)
        deleted
    }

    /**
     * Keep each of [rows] for its (collection, problem, solver) unless the stored row is stronger (see
     * [References.replaces]), so a weaker rerun never loses a proof, while a row produced another way replaces it
     * whatever its strength. Returns how many rows changed.
     */
    @Synchronized
    fun putReferences(rows: List<Pair<ProblemKey, Reference>>, source: String): Int = transaction {
        val at = now()
        var changed = 0
        val select = connection.prepareStatement("SELECT * FROM reference_rows WHERE collection = ? AND problem = ? AND solver = ?")
        val upsert = connection.prepareStatement(
            "INSERT OR REPLACE INTO reference_rows (collection, problem, solver, maximize, objective, feasible, proven, " +
                "elapsed_ms, budget_ms, source, updated_at, version, validation, dual_bound, stale, source_identity) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        )
        select.use {
            upsert.use {
                for ((key, captured) in rows) {
                    val row = captured.copy(stale = captured.stale ||
                        References.stale(captured.solver, captured.version, key.collection))
                    select.setString(1, key.collection)
                    select.setString(2, key.problem)
                    select.setString(3, row.solver)
                    val old = select.executeQuery().use { r -> if (r.next()) reference(r) else null }
                    if (old != null && !References.replaces(row, old)) continue
                    upsert.setString(1, key.collection)
                    upsert.setString(2, key.problem)
                    upsert.setString(3, row.solver)
                    upsert.setInt(4, if (row.maximize) 1 else 0)
                    upsert.setObject(5, row.objective)
                    upsert.setObject(6, row.feasible?.let { if (it) 1 else 0 })
                    upsert.setInt(7, if (row.proven) 1 else 0)
                    upsert.setLong(8, row.elapsedMs)
                    upsert.setLong(9, row.budgetMs)
                    upsert.setString(10, source)
                    upsert.setLong(11, at)
                    upsert.setString(12, row.version)
                    upsert.setString(13, row.validation)
                    upsert.setObject(14, row.dualBound)
                    upsert.setInt(15, if (row.stale) 1 else 0)
                    upsert.setString(16, row.sourceIdentity)
                    upsert.executeUpdate()
                    changed++
                }
            }
        }
        changed
    }

    /**
     * Mark [solver]'s current rows on [collection] stale, only those of budget [budgetMs] when given: an operator's
     * retirement, as [retireReferences] does on a policy change. The rows stay as history, the lab's comparisons and
     * coverage leave them out, a `missing` selection counts their problems as missing, and the solver's next row on
     * the problem replaces them whatever its budget ([References.replaces]). Returns how many rows it marked.
     */
    @Synchronized
    fun retireReferenceRows(collection: String, solver: String, budgetMs: Long? = null): Int = update(
        "UPDATE reference_rows SET stale = 1, updated_at = ? WHERE collection = ? AND solver = ? AND stale = 0 AND (? IS NULL OR budget_ms = ?)",
        now(), collection, solver, budgetMs, budgetMs,
    )

    /** Every reference solver's row on one problem, strongest first. */
    @Synchronized
    fun referenceRows(collection: String, problem: String): List<Reference> =
        connection.prepareStatement("SELECT * FROM reference_rows WHERE collection = ? AND problem = ?").use { statement ->
            statement.setString(1, collection)
            statement.setString(2, problem)
            statement.executeQuery().use { r -> generateSequence { if (r.next()) reference(r) else null }.toList() }
        }.sortedWith { a, b -> if (References.stronger(a, b)) -1 else if (References.stronger(b, a)) 1 else 0 }

    /** How many cases of any experiment ran one problem. */
    @Synchronized
    fun problemRunCount(collection: String, problem: String): Int = connection.prepareStatement(
        """SELECT count(*) FROM problems p JOIN cases c ON c.job_id = p.job_id AND c.problem_idx = p.idx
           WHERE json_extract(p.problem, '$.collection') = ? AND COALESCE(json_extract(p.problem, '$.benchCase.attributes.problem'), json_extract(p.problem, '$.problem')) = ?""",
    ).use { statement ->
        statement.setString(1, collection)
        statement.setString(2, problem)
        statement.executeQuery().use { r -> if (r.next()) r.getInt(1) else 0 }
    }

    /** The cases of one problem across every job, newest job first; with [limit], only that many. */
    @Synchronized
    fun problemRuns(collection: String, problem: String, limit: Int? = null): List<ProblemRun> = connection.prepareStatement(
        // The record as scoring reads it: the page shows each run's outcome and time, not its witness or provenance,
        // which can make one problem's runs tens of megabytes.
        """SELECT j.id, j.name, j.created_at, a.label, a.sha, c.idx, c.seed, c.repeat, m.status, $SCORING_RECORD, c.lab_check
           FROM problems p
           JOIN cases c ON c.job_id = p.job_id AND c.problem_idx = p.idx
           JOIN commands m ON m.job_id = c.job_id AND m.idx = c.idx
           JOIN arms a ON a.job_id = c.job_id AND a.idx = c.arm_idx
           JOIN jobs j ON j.id = c.job_id
           WHERE json_extract(p.problem, '$.collection') = ? AND COALESCE(json_extract(p.problem, '$.benchCase.attributes.problem'), json_extract(p.problem, '$.problem')) = ?
           ORDER BY j.id DESC, a.idx, c.seed, c.repeat
           LIMIT ?""",
    ).use { statement ->
        statement.setString(1, collection)
        statement.setString(2, problem)
        statement.setInt(3, limit ?: -1)
        statement.executeQuery().use { r ->
            generateSequence {
                if (!r.next()) return@generateSequence null
                ProblemRun(
                    job = r.getLong(1), jobName = r.getString(2), createdAt = r.getLong(3), arm = r.getString(4),
                    sha = r.getString(5), case = r.getInt(6), seed = r.getLong(7).takeUnless { r.wasNull() }, repeat = r.getInt(8),
                    status = Status.valueOf(r.getString(9)), record = withLabCheck(r.getString(10)?.let(::recordOf), r.getString(11)),
                )
            }.toList()
        }
    }

    /**
     * The strongest trusted reference row of each of [keys] that has any, keyed by (collection, problem): stale rows
     * left out, and across solvers a proof another solver's solution contradicts set aside ([References.trusted]).
     * With [solver], that solver's own current row, whatever the others say: what its backfill needs to know.
     *
     * Only rows that speak for one source count ([References.applicable]): with [sources] ([References.sources]), the
     * one each problem's cases ran on. Without, as when selecting problems before any ran, the source of the newest
     * bound row, else the unbound rows: the problem's name as it now stands.
     */
    @Synchronized
    fun references(
        keys: Collection<ProblemKey>,
        solver: String? = null,
        sources: Map<ProblemKey, String?>? = null,
    ): Map<ProblemKey, Reference> =
        connection.prepareStatement(
            "SELECT * FROM reference_rows WHERE collection = ? AND problem = ? AND (? IS NULL OR solver = ?)",
        ).use { statement ->
            keys.distinct().mapNotNull { key ->
                statement.setString(1, key.collection)
                statement.setString(2, key.problem)
                statement.setString(3, solver)
                statement.setString(4, solver)
                val current = statement.executeQuery().use { r ->
                    generateSequence { if (r.next()) reference(r) to r.getLong("updated_at") else null }.toList()
                }.filter { !it.first.stale }
                val source = if (sources != null) sources[key]
                else current.filter { it.first.sourceIdentity != null }.maxByOrNull { it.second }?.first?.sourceIdentity
                val rows = References.applicable(current.map { it.first }, source)
                    .let { if (solver == null) References.trusted(it) else it }
                rows.reduceOrNull { a, b -> if (References.stronger(b, a)) b else a }?.let { key to it }
            }.toMap()
        }

    /**
     * Bind the unbound reference rows a lab run made (`source` `lab:<job>`) to the source files that run's cases
     * recorded ([References.identity]), so that rows from before sources were kept stop counting as unbound. A row
     * whose run recorded none, or one imported from elsewhere, stays unbound. Returns how many rows it bound.
     */
    fun bindLabReferenceSources(): Int {
        val jobs = synchronized(this) {
            connection.prepareStatement(
                "SELECT DISTINCT source FROM reference_rows WHERE source_identity IS NULL AND source LIKE 'lab:%'",
            ).use { statement ->
                statement.executeQuery().use { r -> generateSequence { if (r.next()) r.getString(1) else null }.toList() }
            }.mapNotNull { it.removePrefix("lab:").toLongOrNull() }
        }
        var bound = 0
        for (job in jobs) {
            val identities = References.sources(scoringCases(job)).filterValues { it != null && it != References.MIXED_SOURCES }
            if (identities.isEmpty()) continue
            synchronized(this) {
                transaction {
                    connection.prepareStatement(
                        "UPDATE reference_rows SET source_identity = ? WHERE collection = ? AND problem = ? AND source = ? AND source_identity IS NULL",
                    ).use { update ->
                        for ((key, identity) in identities) {
                            update.setString(1, identity)
                            update.setString(2, key.collection)
                            update.setString(3, key.problem)
                            update.setString(4, "lab:$job")
                            bound += update.executeUpdate()
                        }
                    }
                }
            }
        }
        return bound
    }

    /** Every problem two reference solvers' current rows contradict each other on, with why ([References.conflicts]). */
    @Synchronized
    fun referenceConflicts(): List<Disagreement> = connection.prepareStatement(
        "SELECT * FROM reference_rows WHERE stale = 0 AND (collection, problem) IN " +
            "(SELECT collection, problem FROM reference_rows WHERE stale = 0 GROUP BY collection, problem HAVING COUNT(*) > 1)",
    ).use { statement ->
        statement.executeQuery().use { r ->
            generateSequence { if (r.next()) ProblemKey(r.getString("collection"), r.getString("problem")) to reference(r) else null }.toList()
        }
    }.groupBy({ it.first }, { it.second }).flatMap { (key, rows) ->
        References.conflicts(rows).map { Disagreement(Problem("", key.problem, collection = key.collection), it) }
    }

    /** Keep the classifier's [features] of each problem, keyed by (collection, problem); a later classification wins. */
    @Synchronized
    fun putFeatures(features: List<ProblemFeatures>) = transaction {
        connection.prepareStatement(
            "INSERT OR REPLACE INTO problem_features (collection, problem, themes, structure, logic) VALUES (?, ?, ?, ?, ?)",
        ).use { upsert ->
            for (f in features) {
                upsert.setString(1, f.collection)
                upsert.setString(2, f.problem)
                upsert.setString(3, Json.encodeToString(f.themes))
                upsert.setString(4, f.structure)
                upsert.setString(5, f.logic)
                upsert.executeUpdate()
            }
        }
    }

    /** The classifier's features of each of [keys] that has them, keyed by (collection, problem). */
    @Synchronized
    fun features(keys: Collection<ProblemKey>): Map<ProblemKey, ProblemFeatures> =
        connection.prepareStatement("SELECT * FROM problem_features WHERE collection = ? AND problem = ?").use { statement ->
            keys.distinct().mapNotNull { key ->
                statement.setString(1, key.collection)
                statement.setString(2, key.problem)
                statement.executeQuery().use { r ->
                    if (!r.next()) return@use null
                    key to ProblemFeatures(key.collection, key.problem, Json.decodeFromString(r.getString("themes")),
                        r.getString("structure"), r.getString("logic"))
                }
            }.toMap()
        }

    /** Every current (not stale) reference row of each of [keys], by solver, keyed by (collection, problem). */
    @Synchronized
    fun currentReferenceRows(keys: Collection<ProblemKey>): Map<ProblemKey, List<Reference>> =
        connection.prepareStatement("SELECT * FROM reference_rows WHERE collection = ? AND problem = ? AND stale = 0").use { statement ->
            keys.distinct().mapNotNull { key ->
                statement.setString(1, key.collection)
                statement.setString(2, key.problem)
                val rows = statement.executeQuery().use { r -> generateSequence { if (r.next()) reference(r) else null }.toList() }
                rows.takeIf { it.isNotEmpty() }?.let { key to it }
            }.toMap()
        }

    /** When any reference row last changed: what tells a cached comparison against the references that it is stale. */
    @Synchronized
    fun referencesStamp(): Long? = connection.prepareStatement("SELECT MAX(updated_at) FROM reference_rows").use { statement ->
        statement.executeQuery().use { r -> if (r.next()) r.getLong(1).takeUnless { r.wasNull() } else null }
    }

    /**
     * The problems whose reference rows changed after [stamp] ([referencesStamp]), each with when it last changed: what
     * tells a cached point which of its problems' comparisons moved. Read by the `updated_at` index.
     */
    @Synchronized
    fun referencesChangedSince(stamp: Long): Map<ProblemKey, Long> = connection.prepareStatement(
        "SELECT collection, problem, MAX(updated_at) FROM reference_rows WHERE updated_at > ? GROUP BY collection, problem",
    ).use { statement ->
        statement.setLong(1, stamp)
        statement.executeQuery().use { r ->
            generateSequence { if (r.next()) ProblemKey(r.getString(1), r.getString(2)) to r.getLong(3) else null }.toMap()
        }
    }

    /** When a job last finished: what tells a cached view of finished runs that a newer one may belong in it. */
    @Synchronized
    fun lastFinished(): Long? = connection.prepareStatement("SELECT MAX(finished_at) FROM jobs").use { statement ->
        statement.executeQuery().use { r -> if (r.next()) r.getLong(1).takeUnless { r.wasNull() } else null }
    }

    /** Per collection and solver, current rows only: rows, decided, proven, infeasible, and when last updated. */
    @Synchronized
    fun referenceCoverage(): List<ReferenceCoverage> = connection.prepareStatement(
        "SELECT collection, solver, COUNT(*), SUM(feasible IS NOT NULL), SUM(proven), SUM(feasible = 0), MAX(updated_at) " +
            "FROM reference_rows WHERE stale = 0 GROUP BY collection, solver ORDER BY collection, solver",
    ).use { statement ->
        statement.executeQuery().use { r ->
            generateSequence {
                if (!r.next()) null else ReferenceCoverage(r.getString(1), r.getString(2), r.getInt(3), r.getInt(4), r.getInt(5), r.getInt(6), r.getLong(7))
            }.toList()
        }
    }

    /** Up to [limit] reference rows that pass [filter], with their keys, and how many pass in all. */
    @Synchronized
    fun searchReferences(filter: ReferenceFilter, limit: Int): Pair<List<Pair<ProblemKey, Reference>>, Int> {
        val conditions = ArrayList<String>()
        val values = ArrayList<Any>()
        filter.text?.let {
            conditions += "(problem LIKE ? ESCAPE '\\' OR collection LIKE ? ESCAPE '\\')"
            val like = "%" + likeEscape(it) + "%"
            values += like
            values += like
        }
        filter.solver?.let { conditions += "solver = ?"; values += it }
        filter.collection?.let { conditions += "collection = ?"; values += it }
        filter.verdict?.let { conditions += "(${it.sql})" }
        val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        fun bind(statement: java.sql.PreparedStatement) = values.forEachIndexed { i, v -> statement.setObject(i + 1, v) }
        val total = connection.prepareStatement("SELECT COUNT(*) FROM reference_rows $where").use { statement ->
            bind(statement)
            statement.executeQuery().use { r -> r.next(); r.getInt(1) }
        }
        val rows = connection.prepareStatement("SELECT * FROM reference_rows $where ORDER BY collection, problem, solver LIMIT ?").use { statement ->
            bind(statement)
            statement.setInt(values.size + 1, limit)
            statement.executeQuery().use { r ->
                generateSequence { if (r.next()) ProblemKey(r.getString("collection"), r.getString("problem")) to reference(r) else null }.toList()
            }
        }
        return rows to total
    }

    private fun reference(r: ResultSet) = Reference(
        solver = r.getString("solver"),
        maximize = r.getInt("maximize") == 1,
        objective = r.getDouble("objective").takeUnless { r.wasNull() },
        feasible = r.getInt("feasible").takeUnless { r.wasNull() }?.let { it == 1 },
        proven = r.getInt("proven") == 1,
        elapsedMs = r.getLong("elapsed_ms"),
        budgetMs = r.getLong("budget_ms"),
        version = r.getString("version"),
        validation = r.getString("validation"),
        dualBound = r.getDouble("dual_bound").takeUnless { r.wasNull() },
        stale = r.getInt("stale") == 1,
        sourceIdentity = r.getString("source_identity"),
    )

    /** Make schedule [id] due, so the runner checks it at once; false when there is no such schedule. */
    @Synchronized
    fun checkNow(id: Long): Boolean = update("UPDATE schedules SET checked_at = NULL WHERE id = ?", id) == 1

    /**
     * Ask schedule [id] for a run now, at its ref's commit whether or not that commit ran; false when there is none.
     * Its regular checks keep their times, so schedules staggered apart stay apart after a manual launch.
     */
    @Synchronized
    fun launchNow(id: Long): Boolean = update("UPDATE schedules SET launch = 1 WHERE id = ?", id) == 1

    @Synchronized
    fun scheduleChecked(id: Long, at: Long) = update("UPDATE schedules SET checked_at = ? WHERE id = ?", at, id)

    @Synchronized
    fun scheduleRan(id: Long, sha: String, jobId: Long) =
        update("UPDATE schedules SET last_sha = ?, last_job = ?, launch = 0 WHERE id = ?", sha, jobId, id)

    @Synchronized
    fun setPriority(jobId: Long, priority: Int): Boolean =
        update("UPDATE jobs SET priority = ? WHERE id = ?", priority, jobId) == 1

    /** Pause or resume a job that has not finished. False when there is no such job or it already finished. */
    @Synchronized
    fun setPaused(jobId: Long, paused: Boolean): Boolean = update(
        "UPDATE jobs SET paused = ? WHERE id = ? AND status IN (?, ?)",
        if (paused) 1 else 0, jobId, Status.QUEUED.name, Status.RUNNING.name,
    ) == 1

    @Synchronized
    fun queue(): List<Long> = connection.prepareStatement(
        "SELECT id FROM jobs WHERE status = ? AND cancel_requested = 0 AND paused = 0 " +
            QUEUE_ORDER,
    ).use {
        it.setString(1, Status.QUEUED.name)
        it.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getLong(1) else null }.toList() }
    }

    /**
     * Put a failed or cancelled job back in the queue: its error is cleared, its unfinished cases are queued again
     * and its finished ones kept. Its worktree is gone, so the runner sets it up afresh, planning it only if it never
     * was. False when there is no such job or it did not fail or get cancelled.
     */
    @Synchronized
    fun retry(jobId: Long): Boolean = transaction {
        val reopened = update(
            "UPDATE jobs SET status = ?, error = NULL, finished_at = NULL, cancel_requested = 0, paused = 0, running_since = NULL " +
                "WHERE id = ? AND backend = 'nomad' AND status IN (?, ?)",
            Status.QUEUED.name, jobId, Status.FAILED.name, Status.CANCELLED.name,
        ) == 1
        if (reopened) {
            if (job(jobId)?.backend == Backend.NOMAD) execution.retryIn(connection, jobId, now())
            update(
                "UPDATE commands SET status = ?, started_at = NULL WHERE job_id = ? AND status IN (?, ?)",
                Status.QUEUED.name, jobId, Status.CANCELLED.name, Status.RUNNING.name,
            )
        }
        reopened
    }

    /**
     * Put a running job that yielded back in the queue; its finished commands and its worktree stay. A job whose cancel
     * was requested never goes back in the queue, where no host would take it: it ends CANCELLED instead, its
     * unfinished commands with it, for the caller to clean up and log as [Runner.cancel] does. Each step is one
     * statement, so a cancel another process requests meanwhile lands on one side or the other. Returns the status the
     * job is left in, null when it was neither running nor cancel-requested.
     */
    @Synchronized
    fun requeue(jobId: Long): Status? = transaction {
        val at = now()
        val queued = update(
            "UPDATE jobs SET local = 0, status = ?, run_ms = run_ms + COALESCE(? - running_since, 0), running_since = NULL " +
                "WHERE id = ? AND status = ? AND cancel_requested = 0",
            Status.QUEUED.name, at, jobId, Status.RUNNING.name,
        )
        if (queued == 1) return@transaction Status.QUEUED
        val cancelled = update(
            "UPDATE jobs SET local = 0, status = ?, finished_at = ?, run_ms = run_ms + COALESCE(? - running_since, 0), " +
                "running_since = NULL WHERE id = ? AND status IN (?, ?) AND cancel_requested = 1",
            Status.CANCELLED.name, at, at, jobId, Status.QUEUED.name, Status.RUNNING.name,
        )
        if (cancelled == 0) return@transaction null
        cancelRemaining(jobId)
        Status.CANCELLED
    }

    /**
     * Place a queued experiment that has not been planned on [host] (`lab`, `aws` or `auto`): what [Placement] decides,
     * or an operator updates its tags. [placed], when given, is the replacement spec. Admission freezes the recipe
     * before source publication, so the SQL guard also fences a concurrent coordinator in another process.
     */
    @Synchronized
    fun place(jobId: Long, host: HostPlacement, placed: ExperimentSpec? = null): Boolean {
        val job = job(jobId) ?: return false
        val spec = placed ?: job.experiment
        if (job.status != Status.QUEUED || job.planned) return false
        val moved = spec.copy(host = host)
        return update("UPDATE jobs SET experiment = ? WHERE id = ? AND status = 'QUEUED' " +
            "AND NOT EXISTS (SELECT 1 FROM commands WHERE job_id = ?) " +
            "AND NOT EXISTS (SELECT 1 FROM execution_queue_recipes WHERE job_id = ?) " +
            "AND NOT EXISTS (SELECT 1 FROM execution_sessions WHERE job_id = ?)",
            Json.encodeToString(moved), jobId, jobId, jobId, jobId) == 1
    }

    /** Set an experiment's description; false when there is no such experiment. */
    @Synchronized
    fun setDescription(jobId: Long, description: String): Boolean {
        val spec = job(jobId)?.experiment ?: return false
        return update("UPDATE jobs SET experiment = ? WHERE id = ?", Json.encodeToString(spec.copy(description = description)), jobId) == 1
    }

    /** Set how many of [jobId]'s commands may run at once; false when there is no such job. */
    @Synchronized
    fun setParallel(jobId: Long, parallel: Int): Boolean {
        return update("UPDATE jobs SET parallel = ? WHERE id = ?", parallel, jobId) == 1
    }

    @Synchronized
    fun cancelRequested(jobId: Long): Boolean = connection.prepareStatement(
        "SELECT cancel_requested FROM jobs WHERE id = ?",
    ).use {
        it.setLong(1, jobId)
        it.executeQuery().use { rows -> rows.next() && rows.getInt(1) == 1 }
    }

    @Synchronized
    fun cancelRemaining(jobId: Long) = update(
        "UPDATE commands SET status = ? WHERE job_id = ? AND status IN (?, ?)",
        Status.CANCELLED.name, jobId, Status.QUEUED.name, Status.RUNNING.name,
    )

    private fun commands(jobId: Long): List<Command> = connection.prepareStatement(
        "SELECT * FROM commands WHERE job_id = ? ORDER BY idx",
    ).use { statement ->
        statement.setLong(1, jobId)
        statement.executeQuery().use { rows ->
            generateSequence {
                if (!rows.next()) {
                    null
                } else {
                    Command(
                        rows.getInt("idx"), rows.getString("cmd"), rows.getLong("timeout_sec"),
                        Status.valueOf(rows.getString("status")), rows.intOrNull("exit_code"),
                        rows.longOrNull("started_at"), rows.longOrNull("finished_at"), rows.getInt("cores"),
                        host = rows.getString("host")?.let(RecordedHost::of),
                    )
                }
            }.toList()
        }
    }

    private fun job(rows: ResultSet) = Job(
        rows.getLong("id"), rows.getString("name"), rows.getString("ref"), rows.getString("sha"),
        Status.valueOf(rows.getString("status")), rows.getInt("cancel_requested") == 1, rows.getInt("setup_done") == 1,
        rows.getLong("created_at"), rows.longOrNull("started_at"), rows.longOrNull("finished_at"),
        rows.getString("error"), rows.getInt("parallel"), rows.getInt("priority"), rows.getInt("paused") == 1,
        storedExperiment(rows.getString("experiment")),
        runMs = rows.getLong("run_ms"),
        runningSince = rows.longOrNull("running_since"),
        local = rows.getInt("local") == 1,
        backend = Backend.of(rows.getString("backend")),
    )

    val execution = ExecutionStore(this)

    /** Shares this connection's monitor and IMMEDIATE transaction with legacy state and cancellation. */
    @Synchronized
    internal fun <T> executionTransaction(block: (Connection) -> T): T = transaction { block(connection) }

    private fun update(sql: String, vararg values: Any?) = connection.prepareStatement(sql).use { statement ->
        values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
    }

    private fun <T> transaction(block: () -> T): T {
        connection.autoCommit = false
        return try {
            block().also { connection.commit() }
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    private fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }
    private fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }
}

private val holdIds = java.security.SecureRandom()

private fun newHoldId(): String = ByteArray(16).also(holdIds::nextBytes).joinToString("") { "%02x".format(it) }

/** A finished command that recorded when it started and ended, in SQL. */
private const val TIMED = "(status IN ('DONE', 'FAILED') AND started_at IS NOT NULL AND finished_at IS NOT NULL)"

/** A job's series in SQL, for the job in [next]'s outer query and the runs [next] looks back over: its name before
 *  any `@`, so every run a schedule queues shares its schedule's name. */
private const val SERIES_J = "CASE WHEN instr(jobs.name, '@') > 0 THEN substr(jobs.name, 1, instr(jobs.name, '@') - 1) ELSE jobs.name END"
private const val SERIES_R = "CASE WHEN instr(r.name, '@') > 0 THEN substr(r.name, 1, instr(r.name, '@') - 1) ELSE r.name END"

/** How queued jobs of equal priority are ordered: the series that last finished a run longest ago first, then age. */
private const val SERIES_ORDER = "(SELECT MAX(r.finished_at) FROM jobs r WHERE r.finished_at IS NOT NULL " +
    "AND r.status = 'DONE' AND $SERIES_R = $SERIES_J), id"

/** How queues are ordered, the local machine's ([Store.queueOrder]), a node's ([Store.nodeQueue]) and the one the pages
 *  show ([Store.queue]) alike: by priority, series and age, whatever each job's placement. */
private const val QUEUE_ORDER = "ORDER BY priority DESC, $SERIES_ORDER"

/**
 * Where case `m` ran, as [CaseResult.host] reads it: its recorded host, else, for a case that started before the lab
 * recorded one, its job's host: the local machine, or a cloud node it no longer knows ([RecordedHost.CLOUD]).
 */
private const val CASE_HOST = "COALESCE(m.host, CASE WHEN m.status IN ('RUNNING', 'DONE', 'FAILED') THEN " +
    "CASE COALESCE(json_extract(j.experiment, '\$.host'), 'lab') WHEN 'aws' THEN 'cloud' WHEN 'auto' THEN NULL ELSE 'local' END END) AS host"

/** Runtime debug state is reported by the suite; old jobs retain their recorded arm policy. */
private const val LAB_DEBUG = "CASE WHEN json_extract(j.experiment, '$.bench') IS NULL THEN " +
    "(COALESCE(json_extract(a.arm, '$.values.debug'), 'true') != 'false') " +
    "WHEN json_valid(c.record) AND json_extract(c.record, '$.stats.labDebug') IN ('true', 'false') " +
    "THEN (json_extract(c.record, '$.stats.labDebug') = 'true') ELSE NULL END AS lab_debug"

/** Instrumented captures are identified per result, including repository-owned paired arms. */
private const val INSTRUMENTED = "COALESCE(json_extract(j.experiment, '$.measurement') IN ('compilation', 'compilation+log') OR " +
    "CASE WHEN json_valid(c.record) THEN json_extract(c.record, '$.stats.labMeasurement') IN ('compilation', 'compilation+log') ELSE 0 END, 0) AS instrumented"

/** The host a job runs on, from its experiment ([ExperimentSpec.host]): the lab machine unless it names another. */
private const val HOST = "COALESCE(json_extract(experiment, '\$.host'), 'lab')"

/** A stored case record, or null for one that does not parse: one bad record must not take a page or a trend down. */
internal fun recordOf(text: String): kotlinx.serialization.json.JsonElement? = runCatching { Json.parseToJsonElement(text) }.getOrNull()

/**
 * [record] as every reader sees it once the lab has checked its claim: with the verdict, [check], as its `labCheck`,
 * and the verdict's policy as its `validationPolicy`, the bench's own kept as `benchValidationPolicy`. A case the lab
 * has not checked reads as its record alone, as do a record or a check that do not parse.
 */
internal fun withLabCheck(record: JsonElement?, check: String?): JsonElement? {
    if (check == null || record !is JsonObject) return record
    val verdict = runCatching { Json.parseToJsonElement(check) }.getOrNull() as? JsonObject ?: return record
    val policy = verdict["policy"] as? JsonPrimitive ?: return record
    return JsonObject(record + mapOf("labCheck" to verdict, "validationPolicy" to policy,
        "benchValidationPolicy" to (record["validationPolicy"] ?: JsonNull)))
}

/** The stats scoring, the trend and the scoreboard read from a case record ([Outcome.of], [Scoreboard.profiles]). */
internal val SCORING_STATS = listOf(
    "sourceValidation", "sourceValidationReason", "floatApproximation", "referenceError", "unsupported", "loadError",
    "solveTime", "profile", "solvePipeline", "referenceVersion", "validation", "dualBound",
    "reportedFeasible", "reportedObjective", "reportedExactObjective", "reportedProven",
)

/**
 * The producer record an MPS or SMT check keeps, a JSON text of tens of kilobytes. Scoring reads only its time to the
 * best solution, for a claim the check withdrew and the lab restores ([Outcome.withdrawn]), so a scoring
 * record carries it cut to that one field, and only where the record's own claim was withdrawn.
 */
private const val PRODUCER_RECORD = "sourceValidationProducerRecordJson"

/** A case record as [Store.scoringCases] reads it: cut down in SQLite, and left whole when it is not valid JSON. */
private val SCORING_RECORD = """CASE WHEN c.record IS NULL OR NOT json_valid(c.record) THEN c.record ELSE
    json_set(json_remove(c.record, '$.buildProvenance', '$.buildProvenanceRef', '$.finalWitness', '$.attribution'), '$.stats',
        json((SELECT json_group_object(key, CASE WHEN key = '$PRODUCER_RECORD'
                  THEN '' || json_object('timeToBestMs', json_extract(value, '$.timeToBestMs')) ELSE value END)
              FROM json_each(c.record, '$.stats')
              WHERE key IN (${SCORING_STATS.joinToString(", ") { "'$it'" }})
                  OR (key = '$PRODUCER_RECORD' AND json_extract(c.record, '$.feasible') IS NULL AND json_valid(value))))) END"""

/** How long a write waits for a locked database, unless a vacuum holds it ([Store.vacuum]). */
private const val BUSY_TIMEOUT_MS = 10_000L
private const val BUSY_SLEEP_MS = 20L

/** The longest a vacuum is waited for; a marker older than this was left by one that died. */
private const val VACUUM_MAX_MS = 2 * 3_600_000L

/** What a checkpointed write-ahead log is cut back to. */
private const val WAL_LIMIT_BYTES = 64L shl 20

/** How many parsed build provenances a store keeps. */
private const val PROVENANCES = 64

/** How many finished jobs' scoring cases [Store.scoringCases] keeps. */
private const val SCORING_JOBS = 48
private const val LOAD_FACTOR = 0.75f

