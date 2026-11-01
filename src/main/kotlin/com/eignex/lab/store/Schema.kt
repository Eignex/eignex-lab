package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import java.sql.Connection
import com.eignex.lab.analysis.Confirm
import com.eignex.lab.analysis.References

/**
 * The lab database's schema, as numbered migrations. `PRAGMA user_version` counts the ones applied; [migrate] applies
 * the rest in order, each in its own IMMEDIATE transaction that re-reads the version once it holds the write lock, so
 * processes that open the database together apply each step once, and a step that fails leaves no part of itself.
 * A step is never edited once released: a change to the schema is a new step at the end.
 */
internal object Schema {
    private val steps: List<(Connection) -> Unit> = listOf(::baseline, ::endedJobFlags, ::cascadeAndInvariants, ::jobLifecycle, ::experimentColumns, ::jobOrigins, ::commandLifecycle, ::sessionPlacement, ::preparationAdmission, ::preparationDiagnostics, ::coordinatorIndexes, ::sessionManifests, ::benchSelections, ::liveSessionIndex, ::retryDeadlines)

    /** The number of steps: the `user_version` of a database [migrate] has brought up to date. */
    val version: Int get() = steps.size

    fun migrate(db: Connection) {
        while (true) {
            db.autoCommit = false
            try {
                val applied = db.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { r -> r.next(); r.getInt(1) } }
                check(applied <= steps.size) { "database schema $applied is newer than this release's ${steps.size}" }
                if (applied == steps.size) { db.commit(); return }
                steps[applied](db)
                db.createStatement().use { it.execute("PRAGMA user_version = ${applied + 1}") }
                db.commit()
            } catch (e: Throwable) {
                db.rollback()
                throw e
            } finally {
                db.autoCommit = true
            }
        }
    }

    /**
     * Step 1: the schema as the lab built it before steps were numbered, every part of it conditional, so a database
     * from any earlier release (or a new one) arrives at the same tables.
     */
    private fun baseline(db: Connection) { db.createStatement().use {
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
            it.execute("UPDATE jobs SET local = 1 WHERE status = 'RUNNING' AND COALESCE(json_extract(experiment, '$.host'), 'lab') = 'lab'")
        }
        if ("backend" !in jobColumns) it.execute("ALTER TABLE jobs ADD COLUMN backend TEXT NOT NULL DEFAULT 'legacy'")
        ExecutionStore.migrate(db)
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
    } }

    /** Step 2: an ended job is neither the local machine's nor paused; older terminal paths left either set. */
    private fun endedJobFlags(db: Connection) {
        db.sqlUpdate("UPDATE jobs SET local = 0, paused = 0 WHERE status IN ('DONE', 'FAILED', 'CANCELLED') AND (local = 1 OR paused = 1)")
    }

    /**
     * Step 3: what the jobs table may hold, enforced by SQLite, and a job's rows going with it. Triggers rather than
     * table constraints, so no table is rebuilt: the case records alone run to gigabytes.
     * - An ended job has ended: it has a finish time, no running stretch, and is neither local nor paused.
     * - An active job has no finish time.
     * - A session's desired state is one the lab knows ([Desired]).
     * - Deleting a job deletes everything recorded for it.
     */
    private fun cascadeAndInvariants(db: Connection) { db.createStatement().use { s ->
        val ended = "NEW.status IN ('DONE', 'FAILED', 'CANCELLED')"
        val invalid = "($ended AND (NEW.finished_at IS NULL OR NEW.running_since IS NOT NULL OR NEW.local = 1 OR NEW.paused = 1)) " +
            "OR (NEW.status IN ('QUEUED', 'RUNNING') AND NEW.finished_at IS NOT NULL) " +
            "OR NEW.status NOT IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED')"
        for (event in listOf("INSERT", "UPDATE")) {
            s.execute("""CREATE TRIGGER IF NOT EXISTS jobs_valid_${event.lowercase()} BEFORE $event ON jobs
                WHEN $invalid BEGIN SELECT RAISE(ABORT, 'job state is not valid'); END""")
            s.execute("""CREATE TRIGGER IF NOT EXISTS sessions_desired_${event.lowercase()} BEFORE $event ON execution_sessions
                WHEN NEW.desired NOT IN ('run', 'drain', 'stop') BEGIN SELECT RAISE(ABORT, 'session desired state is not valid'); END""")
        }
        s.execute("""CREATE TRIGGER IF NOT EXISTS jobs_cascade AFTER DELETE ON jobs BEGIN
            ${JOB_TABLES.joinToString(" ") { "DELETE FROM $it WHERE job_id = OLD.id;" }}
            END""")
    } }

    /** Step 4: a job's status moves only as [JobLifecycle] allows: a finished job never changes, an ended one only
     *  goes back to the queue. */
    private fun jobLifecycle(db: Connection) {
        db.createStatement().use {
            it.execute("""CREATE TRIGGER IF NOT EXISTS jobs_lifecycle BEFORE UPDATE OF status ON jobs
                WHEN NOT (${JobLifecycle.SQL}) BEGIN SELECT RAISE(ABORT, 'job status move is not allowed'); END""")
        }
    }

    /**
     * Step 5: what queries filter jobs by, as columns the writers set from the experiment ([JobColumns]), not paths into
     * its JSON: renaming a property can no longer change what a query finds. Existing rows are filled from their JSON
     * once, here.
     */
    private fun experimentColumns(db: Connection) {
        db.createStatement().use {
            val columns = it.columns("jobs")
            if ("host" !in columns) it.execute("ALTER TABLE jobs ADD COLUMN host TEXT NOT NULL DEFAULT 'lab'")
            if ("analysis" !in columns) it.execute("ALTER TABLE jobs ADD COLUMN analysis INTEGER NOT NULL DEFAULT 0")
            if ("bench" !in columns) it.execute("ALTER TABLE jobs ADD COLUMN bench INTEGER NOT NULL DEFAULT 0")
            if ("measurement" !in columns) it.execute("ALTER TABLE jobs ADD COLUMN measurement TEXT NOT NULL DEFAULT 'none'")
            it.execute(
                """UPDATE jobs SET
                    host = CASE COALESCE(json_extract(experiment, '$.host'), 'lab') WHEN 'local' THEN 'lab' WHEN 'cloud' THEN 'aws'
                        ELSE COALESCE(json_extract(experiment, '$.host'), 'lab') END,
                    analysis = json_extract(experiment, '$.analyze') IS NOT NULL,
                    bench = json_extract(experiment, '$.bench') IS NOT NULL,
                    measurement = COALESCE(json_extract(experiment, '$.measurement'), 'none')""",
            )
        }
    }

    /** Step 6: why each job exists ([JobOrigin]), stored rather than read from its name; jobs from before are read
     *  from theirs once, here ([JobOrigin.of]). */
    private fun jobOrigins(db: Connection) {
        db.createStatement().use {
            val columns = it.columns("jobs")
            if ("origin" !in columns) it.execute("ALTER TABLE jobs ADD COLUMN origin TEXT NOT NULL DEFAULT '${JobOrigin.SUBMITTED}'")
            if ("series" !in columns) it.execute("ALTER TABLE jobs ADD COLUMN series TEXT")
            it.execute(
                """UPDATE jobs SET
                    series = CASE WHEN instr(name, '@') > 0 THEN substr(name, 1, instr(name, '@') - 1) ELSE name END,
                    origin = CASE WHEN instr(name, '${Confirm.SUFFIX}') > 0 THEN '${JobOrigin.CONFIRMATION}'
                        WHEN instr(name, '@') > 0 THEN '${JobOrigin.SCHEDULED}' ELSE '${JobOrigin.SUBMITTED}' END""",
            )
            it.execute("CREATE INDEX IF NOT EXISTS jobs_series ON jobs (series)")
        }
    }

    /** Step 7: the moves a command's status may make ([CommandLifecycle]), enforced for every writer. */
    private fun commandLifecycle(db: Connection) {
        db.createStatement().use {
            it.execute("""CREATE TRIGGER IF NOT EXISTS commands_lifecycle BEFORE UPDATE OF status ON commands
                WHEN NOT (${CommandLifecycle.SQL}) BEGIN SELECT RAISE(ABORT, 'command status move is not allowed'); END""")
        }
    }

    /**
     * Step 8: where each session's worker was placed ([SessionPlacement]) and how its CPU is held to its budget, kept
     * with the session rather than read back out of its submitted job; existing sessions' are read from theirs once, here.
     */
    private fun sessionPlacement(db: Connection) {
        db.createStatement().use {
            val columns = it.columns("execution_sessions")
            if ("cpu_enforcement" !in columns) it.execute("ALTER TABLE execution_sessions ADD COLUMN cpu_enforcement TEXT")
            if ("placement" !in columns) it.execute("ALTER TABLE execution_sessions ADD COLUMN placement TEXT")
        }
        val submitted = db.sqlRows("""SELECT s.id, o.payload FROM execution_sessions s JOIN execution_outbox o ON o.id = s.id || ':submit'
            WHERE s.placement IS NULL""") { it.getString(1) to it.getString(2) }
        for ((id, payload) in submitted) {
            val placement = runCatching { SessionPlacement.of(payload) }.getOrNull() ?: continue
            db.sqlUpdate("UPDATE execution_sessions SET placement = ?, cpu_enforcement = ? WHERE id = ?", placement.json(), placement.enforcement?.stored, id)
        }
    }

    /** Step 9: preserve compiler admission limits without extending the strict placement JSON shape. */
    private fun preparationAdmission(db: Connection) {
        db.createStatement().use {
            if ("preparation_slots" !in it.columns("execution_sessions"))
                it.execute("ALTER TABLE execution_sessions ADD COLUMN preparation_slots INTEGER")
        }
    }

    /** Step 10: failure evidence remains separate from the original strict progress JSON. */
    private fun preparationDiagnostics(db: Connection) {
        db.createStatement().use {
            if ("preparation_diagnostics" !in it.columns("iteration_attempts"))
                it.execute("ALTER TABLE iteration_attempts ADD COLUMN preparation_diagnostics TEXT")
        }
    }

    /**
     * Step 11: the coordinator's passes look attempts and events up by session and by job; without these indexes each
     * lookup scans the whole table, so a pass grows with history instead of with the active jobs.
     */
    private fun coordinatorIndexes(db: Connection) {
        db.createStatement().use {
            it.execute("CREATE INDEX IF NOT EXISTS attempt_session ON iteration_attempts (session_id, generation)")
            it.execute("CREATE INDEX IF NOT EXISTS attempt_job ON iteration_attempts (job_id, state)")
            it.execute("CREATE INDEX IF NOT EXISTS event_job ON execution_events (job_id, detail)")
        }
    }

    /**
     * Step 12: a session's manifest, often megabytes, gets its own table. In the session row it sits before the
     * placement columns, so every read of those walks through it. The rows already there move in small batches after
     * startup ([ExecutionStore.moveManifests]), not here, where gigabytes would hold the write lock for minutes.
     * A session's deletion takes its manifest along.
     */
    private fun sessionManifests(db: Connection) {
        db.createStatement().use {
            it.execute("CREATE TABLE IF NOT EXISTS execution_manifests (session_id TEXT PRIMARY KEY, manifest TEXT NOT NULL)")
            it.execute("CREATE TRIGGER IF NOT EXISTS session_manifest_cascade AFTER DELETE ON execution_sessions BEGIN " +
                "DELETE FROM execution_manifests WHERE session_id = OLD.id; END")
        }
    }

    /**
     * Step 13: accepted bench planner outputs by selection key ([com.eignex.lab.execution.BenchSelections]), so a
     * preparation whose plan inputs are unchanged reuses one instead of planning and hashing its corpus again. Read by
     * key only; deleting the job that produced a row deletes the row, through its own trigger and job index.
     */
    private fun benchSelections(db: Connection) {
        db.createStatement().use {
            it.execute("CREATE TABLE IF NOT EXISTS bench_selections (selection_key TEXT PRIMARY KEY, job_id INTEGER NOT NULL, " +
                "entry TEXT NOT NULL, created_at INTEGER NOT NULL)")
            it.execute("CREATE INDEX IF NOT EXISTS bench_selection_job ON bench_selections (job_id)")
            it.execute("CREATE TRIGGER IF NOT EXISTS bench_selection_cascade AFTER DELETE ON jobs BEGIN " +
                "DELETE FROM bench_selections WHERE job_id = OLD.id; END")
        }
    }

    /**
     * Step 14: the few sessions not yet stopped, indexed apart from the thousands that are, so capacity collection
     * reads live sessions without scanning every session the lab ever ran.
     */
    private fun liveSessionIndex(db: Connection) {
        db.createStatement().use { it.execute("CREATE INDEX IF NOT EXISTS session_live ON execution_sessions (id) WHERE desired != 'stop'") }
    }

    /** Step 15: where a manually retried job's whole-run limit counts from, so a retry is not out of time at once. */
    private fun retryDeadlines(db: Connection) {
        db.createStatement().use { if ("deadline_from_ms" !in it.columns("jobs")) it.execute("ALTER TABLE jobs ADD COLUMN deadline_from_ms INTEGER NOT NULL DEFAULT 0") }
    }

    /** Every table that keeps rows of one job, by `job_id`: what deleting the job deletes. */
    val JOB_TABLES = listOf("commands", "arms", "problems", "cases", "holds", "execution_ingestion", "execution_checks",
        "execution_queue_recipes", "execution_selections", "execution_events", "iteration_attempts", "execution_outbox",
        "execution_sessions")
}
