package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet

/**
 * The job queue, persisted in SQLite so that neither a crashed runner nor a reboot loses queued or finished work.
 * Every state change is one transaction; WAL mode lets the API read while the runner writes.
 */
class Store(file: Path, busyTimeoutMs: Long = Database.BUSY_TIMEOUT_MS) {
    private val db = Database(file, busyTimeoutMs, lock = this)

    /** The reference solvers' rows and the classifier's problem features. */
    val references = ReferenceRepository(db, this)

    /** Schedules: experiments queued again whenever their ref moves. */
    val schedules = ScheduleRepository(db, this)

    /** Downloads' holds on jobs' files. */
    val holds = HoldRepository(db, this)
    val trendPoints = TrendPointRepository(db, this)

    /** Experiments' plans: arms, problems, cases and their records. */
    val plans = PlanRepository(db, this)

    /** Keeping the database small: the record compaction and VACUUM. */
    val maintenance = MaintenanceRepository(db, this, plans)

    /** Present while a [vacuum] runs ([Database.vacuumMarker]). */

    /** The connection a read on this thread uses ([reading]), else the writer. */
    private val readConnection = ThreadLocal<Connection>()
    private val connection: Connection get() = readConnection.get() ?: db.writer

    /**
     * Run [block] against one committed snapshot, on the read-only connection: without waiting for a write in this
     * process or another. Store methods that only read use it, so pages and lists never queue behind the runner.
     */
    internal fun <T> reading(block: () -> T): T = db.read { conn ->
        if (conn === db.writer) block() else {
            readConnection.set(conn)
            try { block() } finally { readConnection.remove() }
        }
    }

    @Synchronized
    fun create(
        name: String,
        ref: String,
        commands: List<Pair<String, Long>>,
        vcpus: Int = 1,
        priority: Int = 0,
        experiment: ExperimentSpec,
        origin: JobOrigin = JobOrigin.of(name),
    ): Long = transaction {
        val columns = JobColumns.of(experiment)
        val id = connection.sqlInsert(
            "INSERT INTO jobs (name, ref, status, created_at, parallel, priority, experiment, backend, host, analysis, bench, measurement, " +
                "origin, series) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            name, ref, Status.QUEUED.name, now(), vcpus, priority, Json.encodeToString(experiment), "nomad",
            columns.host, if (columns.analysis) 1 else 0, if (columns.bench) 1 else 0, columns.measurement, origin.kind, origin.series,
        )
        plans.insertCommands(id, commands.map { (cmd, timeout) -> CaseCommand(cmd, timeout) })
        id
    }

    /** Publish a verified preparation as one plan, including its setup marker, under the cancellation lock. */
    @Synchronized
    fun prepareNomad(jobId: Long, arms: List<PlannedArm>, problems: List<Problem>, cases: List<Case>, commands: List<CaseCommand>): Boolean = transaction {
        val job = job(jobId) ?: error("missing experiment")
        require(job.backend == Backend.NOMAD && !job.cancelRequested && job.status.isActive)
        if (job.setupDone) return@transaction false
        require(!job.planned && cases.isNotEmpty() && cases.size == commands.size)
        plans.planRows(jobId, arms, problems, cases, commands)
        update("UPDATE jobs SET sha = ?, setup_done = 1 WHERE id = ?", arms.first().sha, jobId)
        true
    }

    /** Cases of [jobId] queued that no host has claimed: the work another host could join it for. */
    fun unclaimed(jobId: Long): Int = reading {
        rows("SELECT COUNT(*) FROM commands WHERE job_id = ? AND status = 'QUEUED' AND host IS NULL", jobId) { it.getInt(1) }
            .firstOrNull() ?: 0
    }

    /** Each host's part of the jobs [ids] ([HostProgress]), the local machine first, then the nodes by name; a case that
     *  ran before cases recorded their host is left out. */
    fun hostProgress(ids: Collection<Long>): Map<Long, List<HostProgress>> = reading {
        if (ids.isEmpty()) emptyMap() else
            rows(
                "SELECT job_id, host, SUM(status IN ('DONE', 'FAILED')), SUM(status = 'RUNNING'), SUM(status = 'QUEUED') FROM commands " +
                    "WHERE job_id IN (${ids.joinToString(",") { "?" }}) AND host IS NOT NULL GROUP BY job_id, host " +
                    "ORDER BY job_id, host != '${RecordedHost.LOCAL}', host",
                *ids.toTypedArray(),
            ) { r -> r.getLong(1) to HostProgress(RecordedHost.of(r.getString(2)), r.getInt(3), r.getInt(4), r.getInt(5)) }
                .groupBy({ it.first }, { it.second })
    }

    /** The newest jobs first, older than [before] when given. A [name] keeps the jobs of that series ([JobOrigin.series]):
     *  a submitted job of that name, and the runs its schedule queued. Without [withCommands] their commands are left out for their [Job.counts]: a
     *  list of many jobs needs their progress, not their hundreds of commands each. */
    fun jobs(limit: Int = 200, before: Long? = null, name: String? = null, withCommands: Boolean = true): List<Job> = reading {
        jobsWhere(
            "id < ? AND (? IS NULL OR series = ?) ORDER BY id DESC LIMIT ?",
            before ?: Long.MAX_VALUE, name, name, limit, withCommands = withCommands,
        )
    }

    /** Every job that ended (done, failed or cancelled): its id and when it ended. */
    fun ended(): List<Pair<Long, Long>> = reading {
        rows(
            "SELECT id, finished_at FROM jobs WHERE status IN (?, ?, ?) AND finished_at IS NOT NULL",
            Status.DONE.name, Status.FAILED.name, Status.CANCELLED.name,
        ) { r -> r.getLong(1) to r.getLong(2) }
    }

    /** Every queued or running job, oldest first; without [withCommands], with their [Job.counts] instead. */
    fun active(withCommands: Boolean = true): List<Job> = reading {
        jobsWhere("status IN (?, ?) ORDER BY id", Status.QUEUED.name, Status.RUNNING.name, withCommands = withCommands)
    }

    /** Activity guards read ids alone, without recipes or per-command progress. */
    fun activeJobIds(): Set<Long> = reading {
        rows("SELECT id FROM jobs WHERE status IN ('QUEUED', 'RUNNING')") { it.getLong(1) }.toSet()
    }

    /** Conservatively protect preparation and execution whenever unpaused work is queued or running. */
    fun hasActiveWork(): Boolean = reading {
        rows("SELECT 1 FROM jobs WHERE status IN ('QUEUED', 'RUNNING') AND paused = 0 AND cancel_requested = 0 LIMIT 1") { true }
            .isNotEmpty()
    }

    private fun jobsWhere(where: String, vararg values: Any?, withCommands: Boolean = true): List<Job> =
        rows("SELECT * FROM jobs WHERE $where", *values) { job(it) }.let { jobs ->
            if (withCommands) return@let jobs.map { it.copy(commands = commands(it.id)) }
            val counts = commandCounts(jobs.map { it.id })
            jobs.map { it.copy(counts = counts[it.id] ?: CommandCounts(0, 0, 0, 0)) }
        }

    private fun commandCounts(ids: List<Long>): Map<Long, CommandCounts> = if (ids.isEmpty()) emptyMap() else
        rows(
            "SELECT job_id, COUNT(*), SUM(status IN ('DONE', 'FAILED')), SUM(status = 'FAILED'), SUM(status = 'RUNNING'), " +
                "SUM($TIMED), SUM(CASE WHEN $TIMED THEN finished_at - started_at ELSE 0 END) " +
                "FROM commands WHERE job_id IN (${ids.joinToString(",") { "?" }}) GROUP BY job_id",
            *ids.toTypedArray(),
        ) { r -> r.getLong(1) to CommandCounts(r.getInt(2), r.getInt(3), r.getInt(4), r.getInt(5), r.getInt(6), r.getLong(7)) }.toMap()

    /** The newest [limit] experiments that ended done, their ids only, newest first: the runs [EstimateHistory] reads. */
    fun doneExperimentIds(limit: Int): List<Long> = reading {
        rows("SELECT id FROM jobs WHERE status = ? AND experiment IS NOT NULL ORDER BY id DESC LIMIT ?", Status.DONE.name, limit) {
            it.getLong(1)
        }
    }

    /** The newest run of schedule [series] ([JobOrigin.Scheduled]) that ended done, null when none did. */
    fun lastDoneOfSeries(series: String): Long? = reading {
        rows(
            "SELECT id FROM jobs WHERE status = ? AND origin = ? AND series = ? ORDER BY id DESC LIMIT 1",
            Status.DONE.name, JobOrigin.SCHEDULED, series,
        ) { it.getLong(1) }.firstOrNull()
    }

    /** The jobs [ids], without their commands, with their [Job.counts]. */
    fun jobsByIds(ids: Collection<Long>): List<Job> = reading {
        if (ids.isEmpty()) emptyList() else jobsWhere("id IN (${ids.joinToString(",") { "?" }}) ORDER BY id", *ids.toTypedArray(), withCommands = false)
    }

    /** Existence alone, without decoding an experiment or loading its potentially large command list. */
    fun jobExists(id: Long): Boolean = reading {
        rows("SELECT 1 FROM jobs WHERE id = ?", id) { true }.isNotEmpty()
    }

    /** A waiter's state check reads no recipe or command rows. Missing jobs have no status. */
    fun jobStatus(id: Long): Status? = reading {
        rows("SELECT status FROM jobs WHERE id = ?", id) { Status.valueOf(it.getString(1)) }.firstOrNull()
    }

    /** Job [id] with its commands, both read from one snapshot. */
    fun job(id: Long): Job? = reading {
        rows("SELECT * FROM jobs WHERE id = ?", id) { job(it) }.firstOrNull()?.let { it.copy(commands = commands(it.id)) }
    }

    @Synchronized
    fun nextAnalysis(): Job? = transaction {
        val id = rows("SELECT id FROM jobs WHERE status IN ('QUEUED','RUNNING') " +
            "AND cancel_requested = 0 AND paused = 0 AND analysis = 1 " +
            "ORDER BY status = 'RUNNING' DESC, priority DESC, id LIMIT 1") { it.getLong(1) }.firstOrNull()
            ?: return@transaction null
        if (job(id)?.status == Status.QUEUED) markRunning(id, now())
        update("UPDATE jobs SET local = 1 WHERE id = ?", id)
        id
    }?.let(::job)

    private fun markRunning(id: Long, at: Long, interruptedRun: Long = 0) = update(
        "UPDATE jobs SET status = ?, started_at = COALESCE(started_at, ?), running_since = ?, run_ms = run_ms + ? WHERE id = ?",
        Status.RUNNING.name, at, at, interruptedRun, id,
    )

    /** Whether the local machine is running a job. */
    fun localBusy(): Boolean = reading {
        rows("SELECT 1 FROM jobs WHERE status = ? AND local = 1 LIMIT 1", Status.RUNNING.name) { true }.isNotEmpty()
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

    /** How many cases of any experiment ran one problem. */
    fun problemRunCount(collection: String, problem: String): Int = reading {
        rows(
            """SELECT count(*) FROM problems p JOIN cases c ON c.job_id = p.job_id AND c.problem_idx = p.idx
               WHERE json_extract(p.problem, '$.collection') = ? AND COALESCE(json_extract(p.problem, '$.benchCase.attributes.problem'), json_extract(p.problem, '$.problem')) = ?""",
            collection, problem,
        ) { it.getInt(1) }.firstOrNull() ?: 0
    }

    /** The cases of one problem across every job, newest job first; with [limit], only that many. */
    @Synchronized
    fun problemRuns(collection: String, problem: String, limit: Int? = null, withWitness: Boolean = false): List<ProblemRun> = rows(
        // The record as scoring reads it: the page shows each run's outcome and time, not its witness or provenance,
        // which can make one problem's runs tens of megabytes.
        """SELECT j.id, j.name, j.created_at, a.label, a.sha, c.idx, c.seed, c.repeat, m.status, ${if (withWitness) OPTIMUM_EVIDENCE_RECORD else SCORING_RECORD}, c.lab_check
           FROM problems p
           JOIN cases c ON c.job_id = p.job_id AND c.problem_idx = p.idx
           JOIN commands m ON m.job_id = c.job_id AND m.idx = c.idx
           JOIN arms a ON a.job_id = c.job_id AND a.idx = c.arm_idx
           JOIN jobs j ON j.id = c.job_id
           WHERE json_extract(p.problem, '$.collection') = ? AND COALESCE(json_extract(p.problem, '$.benchCase.attributes.problem'), json_extract(p.problem, '$.problem')) = ?
           ORDER BY j.id DESC, a.idx, c.seed, c.repeat
           LIMIT ?""",
        collection, problem, limit ?: -1,
    ) { r ->
        ProblemRun(
            job = r.getLong(1), jobName = r.getString(2), createdAt = r.getLong(3), arm = r.getString(4),
            sha = r.getString(5), case = r.getInt(6), seed = r.getLong(7).takeUnless { r.wasNull() }, repeat = r.getInt(8),
            status = Status.valueOf(r.getString(9)), record = withLabCheck(r.getString(10)?.let(::recordOf), r.getString(11)),
        )
    }

    /** When a job last finished: what tells a cached view of finished runs that a newer one may belong in it. */
    fun lastFinished(): Long? = reading {
        rows("SELECT MAX(finished_at) FROM jobs") { r -> r.getLong(1).takeUnless { r.wasNull() } }.firstOrNull()
    }

    @Synchronized
    fun setPriority(jobId: Long, priority: Int): Boolean =
        update("UPDATE jobs SET priority = ? WHERE id = ?", priority, jobId) == 1

    /** Pause or resume a job that has not finished. False when there is no such job or it already finished. */
    @Synchronized
    fun setPaused(jobId: Long, paused: Boolean): Boolean = update(
        "UPDATE jobs SET paused = ? WHERE id = ? AND status IN (?, ?)",
        if (paused) 1 else 0, jobId, Status.QUEUED.name, Status.RUNNING.name,
    ) == 1

    fun queue(): List<Long> = reading {
        rows("SELECT id FROM jobs WHERE status = ? AND cancel_requested = 0 AND paused = 0 " + QUEUE_ORDER, Status.QUEUED.name) {
            it.getLong(1)
        }
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

    /** Set an experiment's description; false when there is no such experiment. */
    @Synchronized
    fun setDescription(jobId: Long, description: String): Boolean =
        // One statement: a write to another field of the experiment, in this process or another, is never lost.
        update("UPDATE jobs SET experiment = json_set(experiment, '$.description', ?) WHERE id = ?", description, jobId) == 1

    /** Set the vCPUs [jobId] holds ([Job.vcpus]); false when there is no such job. */
    @Synchronized
    fun setVcpus(jobId: Long, vcpus: Int): Boolean {
        return update("UPDATE jobs SET parallel = ? WHERE id = ?", vcpus, jobId) == 1
    }

    fun cancelRequested(jobId: Long): Boolean = reading {
        rows("SELECT cancel_requested FROM jobs WHERE id = ?", jobId) { it.getInt(1) == 1 }.firstOrNull() ?: false
    }

    @Synchronized
    fun cancelRemaining(jobId: Long) = update(
        "UPDATE commands SET status = ? WHERE job_id = ? AND status IN (?, ?)",
        Status.CANCELLED.name, jobId, Status.QUEUED.name, Status.RUNNING.name,
    )

    private fun commands(jobId: Long): List<Command> = rows("SELECT * FROM commands WHERE job_id = ? ORDER BY idx", jobId) { rows ->
        Command(
            rows.getInt("idx"), rows.getString("cmd"), rows.getLong("timeout_sec"),
            Status.valueOf(rows.getString("status")), rows.intOrNull("exit_code"),
            rows.longOrNull("started_at"), rows.longOrNull("finished_at"), rows.getInt("cores"),
            host = rows.getString("host")?.let(RecordedHost::of),
        )
    }

    private fun job(rows: ResultSet) = Job(
        rows.getLong("id"), rows.getString("name"), rows.getString("ref"), rows.getString("sha"),
        Status.valueOf(rows.getString("status")), rows.getInt("cancel_requested") == 1, rows.getInt("setup_done") == 1,
        rows.getLong("created_at"), rows.longOrNull("started_at"), rows.longOrNull("finished_at"),
        rows.getString("error"), rows.getInt("parallel"), rows.getInt("priority"), rows.getInt("paused") == 1,
        storedExperiment(rows.getString("experiment")),
        runMs = rows.getLong("run_ms"),
        runningSince = rows.longOrNull("running_since"),
        deadlineFromMs = rows.getLong("deadline_from_ms"),
        local = rows.getInt("local") == 1,
        backend = Backend.of(rows.getString("backend")),
        origin = JobOrigin.stored(rows.getString("origin"), rows.getString("series") ?: rows.getString("name")),
    )

    val execution = ExecutionStore(this)
    val capacityLeases = CapacityLeases(this)
    val executionMaintenance = ExecutionMaintenance(this)

    /** The execution tables' writes: one IMMEDIATE transaction, joined by one already open ([Database.write]). */
    internal fun <T> executionTransaction(block: (Tx) -> T): T = db.write { block(Tx(it)) }

    /** The execution tables' reads: one committed snapshot ([Database.read]). */
    internal fun <T> executionRead(block: (Tx) -> T): T = db.read { block(Tx(it)) }

    private fun update(sql: String, vararg values: Any?): Int = connection.sqlUpdate(sql, *values)

    private fun <T> rows(sql: String, vararg values: Any?, map: (ResultSet) -> T): List<T> = connection.sqlRows(sql, *values, map = map)

    /** One IMMEDIATE transaction; one begun inside another joins it ([Database.write]). */
    private fun <T> transaction(block: () -> T): T = db.write { block() }

    private fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }
    private fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }
}

/** A finished command that recorded when it started and ended, in SQL. */
private const val TIMED = "(status IN ('DONE', 'FAILED') AND started_at IS NOT NULL AND finished_at IS NOT NULL)"

/** A job's series in SQL, for the job in [next]'s outer query and the runs [next] looks back over: its name before
 *  any `@`, so every run a schedule queues shares its schedule's name. */
private const val SERIES_J = "jobs.series"
private const val SERIES_R = "r.series"

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
internal const val CASE_HOST = "COALESCE(m.host, CASE WHEN m.status IN ('RUNNING', 'DONE', 'FAILED') THEN " +
    "CASE j.host WHEN 'aws' THEN 'cloud' WHEN 'auto' THEN NULL ELSE 'local' END END) AS host"

/** Runtime debug state is reported by the suite; old jobs retain their recorded arm policy. */
internal const val LAB_DEBUG = "CASE WHEN j.bench = 0 THEN " +
    "(COALESCE(json_extract(a.arm, '$.values.debug'), 'true') != 'false') " +
    "WHEN json_valid(c.record) AND json_extract(c.record, '$.stats.labDebug') IN ('true', 'false') " +
    "THEN (json_extract(c.record, '$.stats.labDebug') = 'true') ELSE NULL END AS lab_debug"

/** Instrumented captures are identified per result, including repository-owned paired arms. */
internal const val INSTRUMENTED = "COALESCE(j.measurement IN ('compilation', 'compilation+log', 'native-startup') OR " +
    "CASE WHEN json_valid(c.record) THEN json_extract(c.record, '$.stats.labMeasurement') IN ('compilation', 'compilation+log') ELSE 0 END, 0) AS instrumented"

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
internal val SCORING_RECORD = scoringRecord(withWitness = false)
internal val OPTIMUM_EVIDENCE_RECORD = scoringRecord(withWitness = true)

private fun scoringRecord(withWitness: Boolean) = """CASE WHEN c.record IS NULL OR NOT json_valid(c.record) THEN c.record ELSE
    json_set(json_remove(c.record, '$.buildProvenance', '$.buildProvenanceRef', '$.attribution'${if (withWitness) "" else ", '$.finalWitness'"}), '$.stats',
        json((SELECT json_group_object(key, CASE WHEN key = '$PRODUCER_RECORD'
                  THEN '' || json_object('timeToBestMs', json_extract(value, '$.timeToBestMs')) ELSE value END)
              FROM json_each(c.record, '$.stats')
              WHERE key IN (${SCORING_STATS.joinToString(", ") { "'$it'" }})
                  OR (key = '$PRODUCER_RECORD' AND json_extract(c.record, '$.feasible') IS NULL AND json_valid(value))))) END"""

/** How many parsed build provenances a store keeps. */
internal const val PROVENANCES = 64

/** How many finished jobs' scoring cases [Store.scoringCases] keeps. */
internal const val SCORING_JOBS = 48
internal const val LOAD_FACTOR = 0.75f

