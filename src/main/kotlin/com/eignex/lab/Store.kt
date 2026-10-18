package com.eignex.lab

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.Properties

@Serializable
enum class Status { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

@Serializable
data class Command(
    val index: Int,
    val cmd: String,
    val timeoutSec: Long,
    val status: Status,
    val exitCode: Int? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    /** Cores the command keeps busy: its arm's `processors`; the runner's core budget counts these. */
    val cores: Int = 1,
    /** What the lab is doing with it while it runs on AWS; null when it does not run or runs on the lab machine. */
    val phase: CasePhase? = null,
    /** Why the lab, or something else, ended its processes ([Termination]), each line with when; read from the job's
     *  directory for a single job's JSON, empty elsewhere. */
    val terminations: List<String> = emptyList(),
)

/**
 * What the lab is doing with a running AWS case, so a long RUNNING case tells the solver's work apart from the lab's:
 * the case [EXECUTING][Phase.EXECUTING] over SSH, its output coming back, a wait before running it again after SSH
 * dropped, or its tree being killed after a timeout or a cancel.
 */
@Serializable
data class CasePhase(
    val phase: Phase,
    /** When it entered [phase]. */
    val since: Long,
    /** Which run of the case this is, counting from 1, of at most [attempts]. */
    val attempt: Int = 1,
    val attempts: Int = 1,
    /** When the next run starts, during a [Phase.RETRY_BACKOFF]. */
    val retryAt: Long? = null,
    /** The solve's own time from the case's record, once it came back. */
    val elapsedMs: Long? = null,
) {
    @Serializable
    enum class Phase { EXECUTING, TRANSFERRING_OUTPUT, RETRY_BACKOFF, TERMINATING }
}

/** A command as planned: what runs, how long it may, and how many cores it holds. */
data class CaseCommand(val cmd: String, val timeoutSec: Long, val cores: Int = 1)

/** How many of a job's commands there are, and how many finished, failed and are running: its progress, without them. */
@Serializable
data class CommandCounts(val total: Int, val done: Int, val failed: Int, val running: Int)

@Serializable
data class Job(
    val id: Long,
    val name: String,
    val ref: String,
    val sha: String? = null,
    val status: Status,
    val cancelRequested: Boolean,
    val setupDone: Boolean,
    val createdAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val error: String? = null,
    /** How many of the job's commands may run at once. The runner rereads it, so it can change mid-job. */
    val parallel: Int = 1,
    /** The runner takes the highest priority first, the oldest first within one. */
    val priority: Int = 0,
    /** A paused job is not started, and a running one stops starting commands and goes back to the queue. */
    val paused: Boolean = false,
    /** Set for an experiment, whose commands the runner writes from it when it plans the job. */
    val experiment: ExperimentSpec? = null,
    val commands: List<Command> = emptyList(),
    /** Time spent running in earlier stretches: a job that yields or is paused stops its clock until taken again. */
    val runMs: Long = 0,
    /** When the current running stretch began; null while the job waits. */
    val runningSince: Long? = null,
    /** Set instead of [commands] by a list that leaves them out ([Store.jobs]). */
    val counts: CommandCounts? = null,
) {
    /** Time the job has spent running, so far or in all; waiting in the queue, paused or yielded, does not count. */
    fun elapsedMs(now: Long): Long = runMs + (runningSince?.let { now - it } ?: 0)

    val total: Int get() = counts?.total ?: commands.size
    val done: Int get() = counts?.done ?: commands.count { it.status == Status.DONE || it.status == Status.FAILED }
    val failed: Int get() = counts?.failed ?: commands.count { it.status == Status.FAILED }
    val running: Int get() = counts?.running ?: commands.count { it.status == Status.RUNNING }
}

enum class CancelOutcome { CANCELLED, REQUESTED, FINISHED, MISSING }

/** An experiment arm as planned: its configuration and the commit its ref resolved to. */
@Serializable
data class PlannedArm(val arm: Arm, val sha: String)

/** One case of an experiment, with the record its `solve-one` wrote once it ran. */
@Serializable
data class CaseResult(
    val index: Int,
    val status: Status,
    val problem: Problem,
    val arm: String,
    val seed: Long? = null,
    val record: JsonElement? = null,
    /** Which of the identical runs of its (problem, arm, seed) this is, from 0. */
    val repeat: Int = 0,
)

/**
 * An experiment queued again whenever [ref] moves: the runner checks it at most every [intervalSec], and queues a run
 * with every arm at the commit the ref resolves to when that commit is not [lastSha] and the previous run has ended.
 */
@Serializable
data class Schedule(
    val id: Long,
    val name: String,
    val ref: String,
    val experiment: ExperimentSpec,
    val parallel: Int,
    val priority: Int,
    val intervalSec: Long,
    val lastSha: String? = null,
    val lastJob: Long? = null,
    val checkedAt: Long? = null,
    /** A run was asked for now: the next check queues one at the ref's commit even if that commit already ran. */
    val launchRequested: Boolean = false,
)

/**
 * The job queue, persisted in SQLite so that neither a crashed runner nor a reboot loses queued or finished work.
 * Every state change is one transaction; WAL mode lets the API read while the runner writes.
 */
class Store(file: Path) {
    // IMMEDIATE takes the write lock at BEGIN. A deferred transaction that reads and then writes cannot wait for a
    // lock the other process holds, and fails with SQLITE_BUSY instead of honouring the busy timeout.
    private val connection: Connection = DriverManager.getConnection(
        "jdbc:sqlite:$file",
        Properties().apply { setProperty("transaction_mode", "IMMEDIATE") },
    ).apply {
        createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("PRAGMA synchronous=FULL")
            it.execute("PRAGMA busy_timeout=10000")
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
            // [referencesStamp] reads the newest row on every reference and trend page load.
            it.execute("CREATE INDEX IF NOT EXISTS reference_rows_updated ON reference_rows (updated_at)")
            val scheduleColumns = it.executeQuery("PRAGMA table_info(schedules)").use { r -> generateSequence { if (r.next()) r.getString("name") else null }.toSet() }
            if ("launch" !in scheduleColumns) it.execute("ALTER TABLE schedules ADD COLUMN launch INTEGER NOT NULL DEFAULT 0")
            val commandColumns = it.executeQuery("PRAGMA table_info(commands)").use { r -> generateSequence { if (r.next()) r.getString("name") else null }.toSet() }
            if ("phase" !in commandColumns) it.execute("ALTER TABLE commands ADD COLUMN phase TEXT")
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
        experiment: ExperimentSpec? = null,
    ): Long = transaction {
        val id = connection.prepareStatement(
            "INSERT INTO jobs (name, ref, status, created_at, parallel, priority, experiment) VALUES (?, ?, ?, ?, ?, ?, ?)",
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use {
            it.setString(1, name)
            it.setString(2, ref)
            it.setString(3, Status.QUEUED.name)
            it.setLong(4, now())
            it.setInt(5, parallel)
            it.setInt(6, priority)
            it.setString(7, experiment?.let { spec -> Json.encodeToString(spec) })
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

    /** Record an experiment's plan in one transaction: its arms, its problems, and a command per case, the case at
     *  each command's index. A job with commands is planned. */
    @Synchronized
    fun plan(jobId: Long, arms: List<PlannedArm>, problems: List<Problem>, cases: List<Case>, commands: List<Pair<String, Long>>) =
        planCommands(jobId, arms, problems, cases, commands.map { (cmd, timeout) -> CaseCommand(cmd, timeout) })

    @Synchronized
    fun planCommands(jobId: Long, arms: List<PlannedArm>, problems: List<Problem>, cases: List<Case>, commands: List<CaseCommand>) {
        require(cases.size == commands.size) { "a command per case" }
        transaction { planRows(jobId, arms, problems, cases, commands) }
    }

    /**
     * Turn records an earlier job left into a finished experiment: a new job of [name] at [sha], whose cases are
     * [records], each already done with its record kept, so the experiment views compare them like any other.
     */
    @Synchronized
    fun importExperiment(
        name: String,
        ref: String,
        sha: String,
        spec: ExperimentSpec,
        arms: List<PlannedArm>,
        problems: List<Problem>,
        records: List<Pair<Case, String>>,
        sources: List<String>,
    ): Long = transaction {
        val id = connection.prepareStatement(
            "INSERT INTO jobs (name, ref, sha, status, setup_done, created_at, started_at, finished_at, experiment) " +
                "VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?)",
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use {
            val at = now()
            it.setString(1, name)
            it.setString(2, ref)
            it.setString(3, sha)
            it.setString(4, Status.DONE.name)
            it.setLong(5, at)
            it.setLong(6, at)
            it.setLong(7, at)
            it.setString(8, Json.encodeToString(spec))
            it.executeUpdate()
            it.generatedKeys.use { keys -> keys.next(); keys.getLong(1) }
        }
        planRows(id, arms, problems, records.map { it.first }, sources.map { CaseCommand(it, 0L) })
        update("UPDATE commands SET status = ? WHERE job_id = ?", Status.DONE.name, id)
        records.forEachIndexed { index, (_, record) -> update("UPDATE cases SET record = ? WHERE job_id = ? AND idx = ?", record, id, index) }
        id
    }

    private fun planRows(jobId: Long, arms: List<PlannedArm>, problems: List<Problem>, cases: List<Case>, commands: List<CaseCommand>) {
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

    /** Keep the record a case's `solve-one` wrote. */
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

    @Synchronized
    fun caseRecord(jobId: Long, index: Int, record: String) =
        update("UPDATE cases SET record = ? WHERE job_id = ? AND idx = ?", record, jobId, index)

    /** Every case of an experiment in command order, with its problem, arm, status and record. */
    @Synchronized
    fun cases(jobId: Long): List<CaseResult> = casesWith("c.record", jobId)

    /**
     * [cases] with each record cut to what scoring and the trend read: its stats only [SCORING_STATS], and none of its
     * build provenance, final witness or attribution. A record runs to tens of kilobytes, nearly all of it provenance,
     * so SQLite cuts it before it is parsed here. A finished job's are kept between calls ([SCORING_JOBS] of them), its
     * cases being final until it is queued again, which changes its finish time.
     */
    fun scoringCases(jobId: Long): List<CaseResult> {
        val (status, finishedAt) = jobState(jobId) ?: return emptyList()
        val final = status !in ACTIVE
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

    @Synchronized
    private fun casesWith(record: String, jobId: Long): List<CaseResult> = connection.prepareStatement(
        """SELECT c.idx, m.status, p.problem, a.label, c.seed, $record AS record, c.repeat FROM cases c
           JOIN commands m ON m.job_id = c.job_id AND m.idx = c.idx
           JOIN problems p ON p.job_id = c.job_id AND p.idx = c.problem_idx
           JOIN arms a ON a.job_id = c.job_id AND a.idx = c.arm_idx
           WHERE c.job_id = ? ORDER BY c.idx""",
    ).use { statement ->
        statement.setLong(1, jobId)
        statement.executeQuery().use { rows ->
            generateSequence {
                if (!rows.next()) return@generateSequence null
                CaseResult(
                    rows.getInt(1), Status.valueOf(rows.getString(2)), Json.decodeFromString(rows.getString(3)),
                    rows.getString(4), rows.longOrNull("seed"), rows.getString(6)?.let(::recordOf),
                    rows.getInt(7),
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
            "SELECT job_id, COUNT(*), SUM(status IN ('DONE', 'FAILED')), SUM(status = 'FAILED'), SUM(status = 'RUNNING') " +
                "FROM commands WHERE job_id IN (${ids.joinToString(",") { "?" }}) GROUP BY job_id",
        ).use { statement ->
            ids.forEachIndexed { i, id -> statement.setLong(i + 1, id) }
            statement.executeQuery().use { r ->
                generateSequence { if (r.next()) r.getLong(1) to CommandCounts(r.getInt(2), r.getInt(3), r.getInt(4), r.getInt(5)) else null }.toMap()
            }
        }

    private fun likeEscape(text: String) = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    @Synchronized
    fun job(id: Long): Job? = connection.prepareStatement("SELECT * FROM jobs WHERE id = ?").use { statement ->
        statement.setLong(1, id)
        statement.executeQuery().use { rows -> if (rows.next()) job(rows) else null }
    }?.let { it.copy(commands = commands(it.id)) }

    /**
     * The job to work on: one left RUNNING by a crashed runner first, else the queued, unpaused job with the highest
     * priority, claimed. Among equals, the job whose series last finished a run longest ago goes first, one that never
     * has before any that has, then the oldest: so a schedule that just ran cannot keep going ahead of one that has
     * waited. A series is a schedule's runs (`<schedule>@<sha>`), or an experiment's reruns under its name.
     */
    @Synchronized
    fun next(host: String = Experiments.LAB_HOST, profiles: Boolean = true): Job? = transaction {
        // SQLite sorts NULL first ascending: a series that never finished a run goes before one that has.
        val running = if (host != Experiments.LAB_HOST) null else connection.prepareStatement(
            "SELECT id FROM jobs WHERE status = ? AND $HOST = ? ORDER BY id LIMIT 1",
        ).use {
            it.setString(1, Status.RUNNING.name)
            it.setString(2, host)
            it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        }
        val id = running ?: connection.prepareStatement(
            "SELECT id FROM jobs WHERE status = ? AND cancel_requested = 0 AND paused = 0 AND $HOST = ? ${noProfiles(profiles)} $QUEUE_ORDER LIMIT 1",
        ).use {
            it.setString(1, Status.QUEUED.name)
            it.setString(2, host)
            it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        } ?: nextAuto(host, profiles)?.also { claimed ->
            // Taken where it goes: from now on it is that host's job, resumed there after a pause or a restart.
            connection.prepareStatement("UPDATE jobs SET experiment = json_set(experiment, '\$.host', ?, '\$.auto', json('true')) WHERE id = ?").use {
                it.setString(1, host)
                it.setLong(2, claimed)
                it.executeUpdate()
            }
        }
        id?.also { claimed ->
            val previous = if (running == claimed) job(claimed) else null
            val at = now()
            val interruptedRun = if (running == claimed && previous?.experiment?.profile?.runTimeoutSec != null) {
                previous.runningSince?.let { (at - it).coerceAtLeast(0) } ?: 0
            } else 0
            connection.prepareStatement(
                "UPDATE jobs SET status = ?, started_at = COALESCE(started_at, ?), running_since = ?, run_ms = run_ms + ? WHERE id = ?",
            ).use {
                it.setString(1, Status.RUNNING.name)
                it.setLong(2, at)
                it.setLong(3, at)
                it.setLong(4, interruptedRun)
                it.setLong(5, claimed)
                it.executeUpdate()
            }
        }
        id
    }?.let(::job)

    /** The job [next] would take for [host] now, without taking it: its own queue's first, else an `auto` job ([nextAuto]). */
    @Synchronized
    fun peek(host: String, profiles: Boolean = true): Long? = queueOrder(host, profiles).firstOrNull() ?: nextAuto(host, profiles)

    /**
     * The `auto` job [host] takes when nothing is queued for it: the lab machine any, at once, being free whenever it
     * asks; AWS only one that has waited [autoGraceMs], so that the lab machine gets the first chance.
     */
    private fun nextAuto(host: String, profiles: Boolean = true): Long? = connection.prepareStatement(
        "SELECT id FROM jobs WHERE status = ? AND cancel_requested = 0 AND paused = 0 AND $HOST = ? AND created_at <= ? " +
            "${noProfiles(profiles)} $QUEUE_ORDER LIMIT 1",
    ).use {
        it.setString(1, Status.QUEUED.name)
        it.setString(2, Experiments.AUTO_HOST)
        it.setLong(3, if (host == Experiments.LAB_HOST) Long.MAX_VALUE else now() - autoGraceMs())
        it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
    }

    /**
     * How long an `auto` job waits for the lab machine before AWS may take it: [AUTO_GRACE_MS] shared out over the jobs
     * waiting for the machine, its own queue's and the `auto` ones, and never under [AUTO_GRACE_MIN_MS]. The longer the
     * line, the longer the machine would take to reach a job anyway, so the sooner AWS takes it.
     */
    private fun autoGraceMs(): Long {
        val waiting = connection.prepareStatement(
            "SELECT COUNT(*) FROM jobs WHERE status = ? AND cancel_requested = 0 AND paused = 0 AND $HOST IN (?, ?)",
        ).use {
            it.setString(1, Status.QUEUED.name)
            it.setString(2, Experiments.LAB_HOST)
            it.setString(3, Experiments.AUTO_HOST)
            it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0 }
        }
        return maxOf(AUTO_GRACE_MIN_MS, AUTO_GRACE_MS / maxOf(1, waiting))
    }

    /** A command a crashed runner left RUNNING is rerun from the start: its partial output is not a result. */
    @Synchronized
    fun requeueInterrupted(jobId: Long) = update(
        "UPDATE commands SET status = ?, started_at = NULL WHERE job_id = ? AND status = ?",
        Status.QUEUED.name, jobId, Status.RUNNING.name,
    )

    @Synchronized
    fun setup(jobId: Long, sha: String) = update("UPDATE jobs SET sha = ?, setup_done = 1 WHERE id = ?", sha, jobId)

    /** Put [indices] of [jobId] that started back to queued: their host went away before they finished. */
    @Synchronized
    fun requeueCommands(jobId: Long, indices: Collection<Int>) = indices.forEach { index ->
        update("UPDATE commands SET status = ?, started_at = NULL WHERE job_id = ? AND idx = ? AND status = ?",
            Status.QUEUED.name, jobId, index, Status.RUNNING.name)
    }

    @Synchronized
    fun commandStarted(jobId: Long, index: Int) = update(
        "UPDATE commands SET status = ?, started_at = ?, exit_code = NULL, phase = NULL WHERE job_id = ? AND idx = ?",
        Status.RUNNING.name, now(), jobId, index,
    )

    @Synchronized
    fun commandFinished(jobId: Long, index: Int, exitCode: Int) = update(
        "UPDATE commands SET status = ?, exit_code = ?, finished_at = ?, phase = NULL WHERE job_id = ? AND idx = ?",
        if (exitCode == 0) Status.DONE.name else Status.FAILED.name, exitCode, now(), jobId, index,
    )

    /** Record what the lab is doing with [jobId]'s running command [index]; written only as the phase changes. */
    @Synchronized
    fun commandPhase(jobId: Long, index: Int, phase: CasePhase) = update(
        "UPDATE commands SET phase = ? WHERE job_id = ? AND idx = ? AND status = ?",
        Json.encodeToString(phase), jobId, index, Status.RUNNING.name,
    )

    @Synchronized
    fun finish(jobId: Long, status: Status, error: String? = null): Int {
        val at = now()
        return update(
            "UPDATE jobs SET status = ?, finished_at = ?, error = ?, run_ms = run_ms + COALESCE(? - running_since, 0), " +
                "running_since = NULL WHERE id = ?",
            status.name, at, error, at, jobId,
        )
    }

    @Synchronized
    fun requestCancel(jobId: Long): CancelOutcome = transaction {
        val job = job(jobId) ?: return@transaction CancelOutcome.MISSING
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
                Schedule(
                    rows.getLong("id"), rows.getString("name"), rows.getString("ref"),
                    Json.decodeFromString<ExperimentSpec>(rows.getString("experiment")),
                    rows.getInt("parallel"), rows.getInt("priority"),
                    rows.getLong("interval_sec"), rows.getString("last_sha"), rows.longOrNull("last_job"),
                    rows.longOrNull("checked_at"), rows.getInt("launch") == 1,
                )
            }.toList()
        }
    }

    /**
     * Change schedule [id]: a new [experiment]; a new [intervalSec]; and [nextCheckAt], when its next check is due
     * (epoch ms). Unset fields stay. An experiment that measures something else ([Experiments.fingerprint]) forgets the
     * last commit, so the next check queues it at the ref's commit; one that only changes how it runs or is described
     * does not. A schedule keeps its phase otherwise, so staggered schedules stay apart. False when there is no such
     * schedule.
     */
    @Synchronized
    fun updateSchedule(id: Long, experiment: ExperimentSpec?, intervalSec: Long?, nextCheckAt: Long? = null): Boolean = transaction {
        val old = schedules().firstOrNull { it.id == id }?.experiment
        val found = update("UPDATE schedules SET interval_sec = COALESCE(?, interval_sec) WHERE id = ?", intervalSec, id) == 1
        if (found && experiment != null) {
            val changed = old == null || Experiments.fingerprint(old) != Experiments.fingerprint(experiment)
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
        val deleted = update("DELETE FROM jobs WHERE id = ? AND status NOT IN (?, ?)", id, Status.QUEUED.name, Status.RUNNING.name) == 1
        if (deleted) for (table in listOf("commands", "arms", "problems", "cases")) update("DELETE FROM $table WHERE job_id = ?", id)
        deleted
    }

    /**
     * Keep each of [rows] for its (collection, problem, solver) unless the stored row is stronger (see
     * [References.replaces]), so a weaker rerun never loses a proof, while a row produced another way replaces it
     * whatever its strength. Returns how many rows changed.
     */
    @Synchronized
    fun putReferences(rows: List<Pair<Pair<String, String>, Reference>>, source: String): Int = transaction {
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
                        References.stale(captured.solver, captured.version, key.first))
                    select.setString(1, key.first)
                    select.setString(2, key.second)
                    select.setString(3, row.solver)
                    val old = select.executeQuery().use { r -> if (r.next()) reference(r) else null }
                    if (old != null && !References.replaces(row, old)) continue
                    upsert.setString(1, key.first)
                    upsert.setString(2, key.second)
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

    /** Every reference solver's row on one problem, strongest first. */
    @Synchronized
    fun referenceRows(collection: String, problem: String): List<Reference> =
        connection.prepareStatement("SELECT * FROM reference_rows WHERE collection = ? AND problem = ?").use { statement ->
            statement.setString(1, collection)
            statement.setString(2, problem)
            statement.executeQuery().use { r -> generateSequence { if (r.next()) reference(r) else null }.toList() }
        }.sortedWith { a, b -> if (References.stronger(a, b)) -1 else if (References.stronger(b, a)) 1 else 0 }

    /** Every case of any experiment that ran one problem, newest experiment first. */
    @Synchronized
    fun problemRuns(collection: String, problem: String): List<ProblemRun> = connection.prepareStatement(
        """SELECT j.id, j.name, j.created_at, a.label, a.sha, c.idx, c.seed, c.repeat, m.status, c.record
           FROM problems p
           JOIN cases c ON c.job_id = p.job_id AND c.problem_idx = p.idx
           JOIN commands m ON m.job_id = c.job_id AND m.idx = c.idx
           JOIN arms a ON a.job_id = c.job_id AND a.idx = c.arm_idx
           JOIN jobs j ON j.id = c.job_id
           WHERE json_extract(p.problem, '$.collection') = ? AND json_extract(p.problem, '$.problem') = ?
           ORDER BY j.id DESC, a.idx, c.seed, c.repeat""",
    ).use { statement ->
        statement.setString(1, collection)
        statement.setString(2, problem)
        statement.executeQuery().use { r ->
            generateSequence {
                if (!r.next()) return@generateSequence null
                ProblemRun(
                    job = r.getLong(1), jobName = r.getString(2), createdAt = r.getLong(3), arm = r.getString(4),
                    sha = r.getString(5), case = r.getInt(6), seed = r.getLong(7).takeUnless { r.wasNull() }, repeat = r.getInt(8),
                    status = Status.valueOf(r.getString(9)), record = r.getString(10)?.let(::recordOf),
                )
            }.toList()
        }
    }

    /**
     * The strongest trusted reference row of each of [keys] that has any, keyed by (collection, problem): stale rows
     * left out, and across solvers a proof another solver's solution contradicts set aside ([References.trusted]).
     * With [solver], that solver's own current row, whatever the others say: what its backfill needs to know.
     *
     * Only rows on one source count ([Reference.sourceIdentity]): with [sources] ([References.sources]), the one each
     * problem's cases ran on, an unbound problem matching only unbound rows. Without, as when selecting problems
     * before any ran, the source of the newest bound row, else the unbound rows: the problem's name as it now stands.
     */
    @Synchronized
    fun references(
        keys: Collection<Pair<String, String>>,
        solver: String? = null,
        sources: Map<Pair<String, String>, String?>? = null,
    ): Map<Pair<String, String>, Reference> =
        connection.prepareStatement(
            "SELECT * FROM reference_rows WHERE collection = ? AND problem = ? AND (? IS NULL OR solver = ?)",
        ).use { statement ->
            keys.distinct().mapNotNull { key ->
                statement.setString(1, key.first)
                statement.setString(2, key.second)
                statement.setString(3, solver)
                statement.setString(4, solver)
                val current = statement.executeQuery().use { r ->
                    generateSequence { if (r.next()) reference(r) to r.getLong("updated_at") else null }.toList()
                }.filter { !it.first.stale }
                val source = if (sources != null) sources[key]
                else current.filter { it.first.sourceIdentity != null }.maxByOrNull { it.second }?.first?.sourceIdentity
                val rows = current.map { it.first }.filter { it.sourceIdentity == source }
                    .let { if (solver == null) References.trusted(it) else it }
                rows.reduceOrNull { a, b -> if (References.stronger(b, a)) b else a }?.let { key to it }
            }.toMap()
        }

    /** Every problem two reference solvers' current rows contradict each other on, with why ([References.conflicts]). */
    @Synchronized
    fun referenceConflicts(): List<Disagreement> = connection.prepareStatement(
        "SELECT * FROM reference_rows WHERE stale = 0 AND (collection, problem) IN " +
            "(SELECT collection, problem FROM reference_rows WHERE stale = 0 GROUP BY collection, problem HAVING COUNT(*) > 1)",
    ).use { statement ->
        statement.executeQuery().use { r ->
            generateSequence { if (r.next()) (r.getString("collection") to r.getString("problem")) to reference(r) else null }.toList()
        }
    }.groupBy({ it.first }, { it.second }).flatMap { (key, rows) ->
        References.conflicts(rows).map { Disagreement(Problem("", key.second, collection = key.first), it) }
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
    fun features(keys: Collection<Pair<String, String>>): Map<Pair<String, String>, ProblemFeatures> =
        connection.prepareStatement("SELECT * FROM problem_features WHERE collection = ? AND problem = ?").use { statement ->
            keys.distinct().mapNotNull { key ->
                statement.setString(1, key.first)
                statement.setString(2, key.second)
                statement.executeQuery().use { r ->
                    if (!r.next()) return@use null
                    key to ProblemFeatures(key.first, key.second, Json.decodeFromString(r.getString("themes")),
                        r.getString("structure"), r.getString("logic"))
                }
            }.toMap()
        }

    /** Every current (not stale) reference row of each of [keys], by solver, keyed by (collection, problem). */
    @Synchronized
    fun currentReferenceRows(keys: Collection<Pair<String, String>>): Map<Pair<String, String>, List<Reference>> =
        connection.prepareStatement("SELECT * FROM reference_rows WHERE collection = ? AND problem = ? AND stale = 0").use { statement ->
            keys.distinct().mapNotNull { key ->
                statement.setString(1, key.first)
                statement.setString(2, key.second)
                val rows = statement.executeQuery().use { r -> generateSequence { if (r.next()) reference(r) else null }.toList() }
                rows.takeIf { it.isNotEmpty() }?.let { key to it }
            }.toMap()
        }

    /** When any reference row last changed: what tells a cached comparison against the references that it is stale. */
    @Synchronized
    fun referencesStamp(): Long? = connection.prepareStatement("SELECT MAX(updated_at) FROM reference_rows").use { statement ->
        statement.executeQuery().use { r -> if (r.next()) r.getLong(1).takeUnless { r.wasNull() } else null }
    }

    /** When a job last finished: what tells a cached view of finished runs that a newer one may belong in it. */
    @Synchronized
    fun lastFinished(): Long? = connection.prepareStatement("SELECT MAX(finished_at) FROM jobs").use { statement ->
        statement.executeQuery().use { r -> if (r.next()) r.getLong(1).takeUnless { r.wasNull() } else null }
    }

    /** Per collection and solver: rows, decided, proven, infeasible, and when last updated. */
    @Synchronized
    fun referenceCoverage(): List<ReferenceCoverage> = connection.prepareStatement(
        "SELECT collection, solver, COUNT(*), SUM(feasible IS NOT NULL), SUM(proven), SUM(feasible = 0), MAX(updated_at) " +
            "FROM reference_rows GROUP BY collection, solver ORDER BY collection, solver",
    ).use { statement ->
        statement.executeQuery().use { r ->
            generateSequence {
                if (!r.next()) null else ReferenceCoverage(r.getString(1), r.getString(2), r.getInt(3), r.getInt(4), r.getInt(5), r.getInt(6), r.getLong(7))
            }.toList()
        }
    }

    /** Up to [limit] reference rows that pass [filter], with their keys, and how many pass in all. */
    @Synchronized
    fun searchReferences(filter: ReferenceFilter, limit: Int): Pair<List<Pair<Pair<String, String>, Reference>>, Int> {
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
                generateSequence { if (r.next()) (r.getString("collection") to r.getString("problem")) to reference(r) else null }.toList()
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

    /** Ask schedule [id] for a run now, at its ref's commit whether or not that commit ran; false when there is none. */
    @Synchronized
    fun launchNow(id: Long): Boolean = update("UPDATE schedules SET launch = 1, checked_at = NULL WHERE id = ?", id) == 1

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

    /** Whether running [jobId] should stop starting commands: it was paused, or a job it must give way to waits. */
    @Synchronized
    fun shouldYield(jobId: Long): Boolean = connection.prepareStatement(
        // A job queued for the host outranks any auto job the host took, whatever their priorities: an auto job gives
        // way to it, and it never gives way to one. Among jobs of one kind the higher priority goes first.
        "SELECT (SELECT paused FROM jobs WHERE id = ?) OR EXISTS (SELECT 1 FROM jobs WHERE status = ? " +
            "AND cancel_requested = 0 AND paused = 0 AND $HOST = (SELECT $HOST FROM jobs WHERE id = ?) AND (" +
            "(COALESCE(json_extract(experiment, '\$.auto'), 0) = COALESCE((SELECT json_extract(experiment, '\$.auto') FROM jobs WHERE id = ?), 0) " +
            "AND priority > (SELECT priority FROM jobs WHERE id = ?)) OR " +
            "(COALESCE(json_extract(experiment, '\$.auto'), 0) = 0 AND " +
            "COALESCE((SELECT json_extract(experiment, '\$.auto') FROM jobs WHERE id = ?), 0) = 1)))",
    ).use {
        it.setLong(1, jobId)
        it.setString(2, Status.QUEUED.name)
        it.setLong(3, jobId)
        it.setLong(4, jobId)
        it.setLong(5, jobId)
        it.setLong(6, jobId)
        it.executeQuery().use { rows -> rows.next() && rows.getInt(1) == 1 }
    }

    /** The queued, unpaused jobs for [host] in the order [next] takes them. */
    @Synchronized
    fun queueOrder(host: String = Experiments.LAB_HOST, profiles: Boolean = true): List<Long> = connection.prepareStatement(
        "SELECT id FROM jobs WHERE status = ? AND cancel_requested = 0 AND paused = 0 AND $HOST = ? ${noProfiles(profiles)} $QUEUE_ORDER",
    ).use {
        it.setString(1, Status.QUEUED.name)
        it.setString(2, host)
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
                "WHERE id = ? AND status IN (?, ?)",
            Status.QUEUED.name, jobId, Status.FAILED.name, Status.CANCELLED.name,
        ) == 1
        if (reopened) {
            update(
                "UPDATE commands SET status = ?, started_at = NULL WHERE job_id = ? AND status IN (?, ?)",
                Status.QUEUED.name, jobId, Status.CANCELLED.name, Status.RUNNING.name,
            )
        }
        reopened
    }

    /** Put a running job that yielded back in the queue; its finished commands and its worktree stay. */
    @Synchronized
    fun requeue(jobId: Long): Int {
        val at = now()
        return update(
            "UPDATE jobs SET status = ?, run_ms = run_ms + COALESCE(? - running_since, 0), running_since = NULL " +
                "WHERE id = ? AND status = ?",
            Status.QUEUED.name, at, jobId, Status.RUNNING.name,
        )
    }

    /** Move a queued experiment that has not been planned to [host], with [machines] for AWS; false otherwise, since a
     *  planned job's commands name paths on the host that planned it. */
    @Synchronized
    fun setHost(jobId: Long, host: String, machines: Int?): Boolean {
        val job = job(jobId) ?: return false
        val spec = job.experiment ?: return false
        if (job.status != Status.QUEUED || job.commands.isNotEmpty()) return false
        require(!spec.profileCli || host == Experiments.AWS_HOST) { "profileCli requires host=aws" }
        val moved = spec.copy(host = host, auto = false, machines = machines.takeIf { host != Experiments.LAB_HOST })
        return update("UPDATE jobs SET experiment = ? WHERE id = ?", Json.encodeToString(moved), jobId) == 1
    }

    /** Set how many instances an AWS experiment that has not ended splits over, null for as many as are free. A running
     *  job keeps the instances it launched: the number applies at its next launch, after a pause and resume. */
    @Synchronized
    fun setMachines(jobId: Long, machines: Int?): Boolean {
        val job = job(jobId) ?: return false
        val spec = job.experiment ?: return false
        if (spec.host != Experiments.AWS_HOST || job.status !in ACTIVE) return false
        return update("UPDATE jobs SET experiment = ? WHERE id = ?", Json.encodeToString(spec.copy(machines = machines)), jobId) == 1
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
        require(job(jobId)?.experiment?.profileCli != true || parallel == 1) {
            "profileCli requires parallel=1"
        }
        return update("UPDATE jobs SET parallel = ? WHERE id = ?", parallel, jobId) == 1
    }

    @Synchronized
    fun parallel(jobId: Long): Int = connection.prepareStatement("SELECT parallel FROM jobs WHERE id = ?").use {
        it.setLong(1, jobId)
        it.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 1 }
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
                        // A phase is the running command's only: one a requeue or a cancel left behind is stale.
                        rows.getString("phase")?.takeIf { rows.getString("status") == Status.RUNNING.name }
                            ?.let { runCatching { Json.decodeFromString<CasePhase>(it) }.getOrNull() },
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
        rows.getString("experiment")?.let { Json.decodeFromString<ExperimentSpec>(it) },
        runMs = rows.getLong("run_ms"),
        runningSince = rows.longOrNull("running_since"),
    )

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

fun now(): Long = System.currentTimeMillis()

/** A job's series in SQL, for the job in [next]'s outer query and the runs [next] looks back over: its name before
 *  any `@`, so every run a schedule queues shares its schedule's name. */
private const val SERIES_J = "CASE WHEN instr(jobs.name, '@') > 0 THEN substr(jobs.name, 1, instr(jobs.name, '@') - 1) ELSE jobs.name END"
private const val SERIES_R = "CASE WHEN instr(r.name, '@') > 0 THEN substr(r.name, 1, instr(r.name, '@') - 1) ELSE r.name END"

/** How [Store.next] orders queued jobs: by priority, then the series that last finished a run longest ago, then age. */
// A job queued for its host goes before an `auto` job the host took, whatever their priorities: an `auto` job only
// fills a host that has nothing of its own to run.
private const val QUEUE_ORDER = "ORDER BY COALESCE(json_extract(jobs.experiment, '\$.auto'), 0), priority DESC, (SELECT MAX(r.finished_at) FROM jobs r WHERE r.finished_at IS NOT NULL " +
    "AND r.status = 'DONE' AND $SERIES_R = $SERIES_J), id"

/** The host a job runs on, from its experiment ([ExperimentSpec.host]): the lab machine unless it names another. */
private const val HOST = "COALESCE(json_extract(experiment, '\$.host'), 'lab')"

/** A queue query's filter that, without [profiles], leaves profile jobs out: what a host that cannot run them passes over. */
private fun noProfiles(profiles: Boolean) = if (profiles) "" else "AND json_extract(experiment, '\$.profile') IS NULL"

/** How long a queued `auto` job waits for the lab machine before AWS may take it, when it is the only job waiting
 *  for the machine; with more waiting it is shared out over them ([Store.autoGraceMs]), down to [AUTO_GRACE_MIN_MS]. */
internal const val AUTO_GRACE_MS = 15 * 60_000L
internal const val AUTO_GRACE_MIN_MS = 60_000L

/** A stored case record, or null for one that does not parse: one bad record must not take a page or a trend down. */
internal fun recordOf(text: String): kotlinx.serialization.json.JsonElement? = runCatching { Json.parseToJsonElement(text) }.getOrNull()

/** The stats scoring, the trend and the scoreboard read from a case record ([Outcome.of], [Scoreboard.profiles]). */
internal val SCORING_STATS = listOf(
    "sourceValidation", "sourceValidationReason", "floatApproximation", "referenceError", "unsupported", "loadError",
    "solveTime", "profile", "solvePipeline", "referenceVersion", "validation", "dualBound",
)

/** A case record as [Store.scoringCases] reads it: cut down in SQLite, and left whole when it is not valid JSON. */
private val SCORING_RECORD = """CASE WHEN c.record IS NULL OR NOT json_valid(c.record) THEN c.record ELSE
    json_set(json_remove(c.record, '$.buildProvenance', '$.finalWitness', '$.attribution'), '$.stats',
        json((SELECT json_group_object(key, value) FROM json_each(c.record, '$.stats')
              WHERE key IN (${SCORING_STATS.joinToString(", ") { "'$it'" }})))) END"""

/** How many finished jobs' scoring cases [Store.scoringCases] keeps. */
private const val SCORING_JOBS = 48
private const val LOAD_FACTOR = 0.75f
