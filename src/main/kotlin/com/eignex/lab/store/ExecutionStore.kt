package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import com.eignex.lab.capacity.RunningNode
import com.eignex.lab.capacity.runningNode
import com.eignex.lab.worker.PreparationResult
import com.eignex.lab.worker.RunDeadlineExceeded

@Serializable
data class ExecutionSession(
    val id: String, val jobId: Long, val generation: Int, val executionClass: ExecutionClass, val release: String,
    val budget: ResourceBudget, val nomadJobId: String, val desired: Desired, val allocation: String?, val node: String?,
)

/** What a session's worker does: prepare a job's sources and selection once, or solve its cases. Stored as [stored]. */
@Serializable
enum class ExecutionClass(val stored: String) {
    @SerialName("prepare") PREPARE("prepare"),
    @SerialName("solve") SOLVE("solve"),
    @SerialName("build") BUILD("build");

    override fun toString() = stored

    companion object {
        fun of(stored: String): ExecutionClass = entries.firstOrNull { it.stored == stored } ?: error("unknown execution class $stored")
    }
}

/** What the lab wants of a session: keep running, finish its current iteration and leave, or stop now. */
@Serializable
enum class Desired(val stored: String) {
    @SerialName("run") RUN("run"),
    @SerialName("drain") DRAIN("drain"),
    @SerialName("stop") STOP("stop");

    override fun toString() = stored

    companion object {
        fun of(stored: String): Desired = entries.firstOrNull { it.stored == stored } ?: error("unknown session state $stored")
    }
}

/**
 * Where an iteration attempt is. The active states advance in declaration order and never go back; an attempt ends
 * [COMMITTED], or invalidated with its reason stored as its state ([ENDED] here, whatever the reason).
 */
enum class AttemptState(val stored: String) {
    CLAIMED("claimed"), PREPARING("preparing"), EXECUTING("executing"), UPLOADING("uploading"), COMMITTED("committed"), ENDED("");

    val isActive: Boolean get() = this in ACTIVE

    override fun toString() = stored

    companion object {
        val ACTIVE = setOf(CLAIMED, PREPARING, EXECUTING, UPLOADING)

        /** The active states as an SQL list, for `state IN …`. */
        val ACTIVE_SQL = ACTIVE.joinToString(", ", "(", ")") { "'${it.stored}'" }

        fun of(stored: String): AttemptState = entries.firstOrNull { it.stored == stored && it != ENDED } ?: ENDED
    }
}

/** What an outbox intent asks of Nomad. */
@Serializable
enum class IntentOp(val stored: String) {
    @SerialName("submit") SUBMIT("submit"),
    @SerialName("stop") STOP("stop");

    override fun toString() = stored

    companion object {
        fun of(stored: String): IntentOp = entries.firstOrNull { it.stored == stored } ?: error("unknown execution intent $stored")
    }
}

@Serializable
data class AttemptOwner(val session: String, val allocation: String, val iteration: String, val generation: Int, val token: String)

@Serializable
data class IterationClaim(val owner: AttemptOwner, val indices: List<Int>, val leaseUntil: Long, val parallel: Int = 1)

@Serializable
data class ExecutionCaseResult(val index: Int, val exitCode: Int, val startedAt: Long, val finishedAt: Long, val record: String? = null, val command: String? = null) {
    init { require(index >= 0 && finishedAt >= startedAt); require(command == null || command.length <= 65536 && '\u0000' !in command) }
}

@Serializable
data class IterationResult(val cases: List<ExecutionCaseResult>, val artifacts: Map<String, String> = emptyMap(), val runTimedOut: Boolean = false, val preparation: PreparationResult? = null)

@Serializable
data class ExecutionIntent(val id: String, val jobId: Long, val session: String, val operation: IntentOp, val payload: String, val acknowledged: Boolean)

@Serializable data class LiveLog(val session: String, val iteration: String, val generation: Int)
@Serializable data class ExecutionEvent(val id: Long, val session: String?, val detail: String, val at: Long)

class ExecutionFenced(message: String) : IllegalStateException(message)

internal data class PreparationExitFailure(val count: Int, val stage: String, val release: String)

/** All worker mutations share Store's IMMEDIATE write transaction, including cancellation and case projection. */
class ExecutionStore internal constructor(private val store: Store) {
    fun events(job: Long): List<ExecutionEvent> = store.executionRead { db ->
        db.sqlRows("SELECT * FROM execution_events WHERE job_id = ? ORDER BY id DESC LIMIT 5000", job) {
            ExecutionEvent(it.getLong("id"), it.getString("session_id"), it.getString("detail"), it.getLong("at"))
        }.reversed()
    }
    fun liveLog(job: Long, index: Int, at: Long = now()): LiveLog? = store.executionRead { db ->
        db.sqlRows("SELECT a.* FROM iteration_attempts a JOIN execution_sessions s ON s.id = a.session_id WHERE a.job_id = ? AND a.state IN $ACTIVE_ATTEMPTS AND a.lease_until > ? AND s.desired != 'stop' AND s.allocation_id = a.allocation_id", job, at, map = ::attemptRow)
            .singleOrNull { index in it.indices }?.let { LiveLog(it.session, it.iteration, it.generation) }
    }
    fun capacityLeases(at: Long = now()): List<ManualCapacityLease> = store.executionRead { db ->
        db.sqlRows("SELECT * FROM capacity_leases WHERE expires_at > ? ORDER BY id", at) { ManualCapacityLease(it.getString("id"), it.getString("pool"), it.getInt("nodes"), it.getLong("expires_at")) }
    }
    fun requestCapacity(pool: String, nodes: Int, ttlMs: Long, at: Long = now()): ManualCapacityLease = store.executionTransaction { db ->
        require(nodes > 0 && ttlMs in 1..3_600_000)
        val lease = ManualCapacityLease(java.util.UUID.randomUUID().toString(), pool, nodes, at + ttlMs)
        db.sqlUpdate("INSERT INTO capacity_leases (id, pool, nodes, expires_at) VALUES (?, ?, ?, ?)", lease.id, lease.pool, lease.nodes, lease.expiresAt)
        lease
    }
    fun releaseCapacity(id: String): Boolean = store.executionTransaction { it.sqlUpdate("DELETE FROM capacity_leases WHERE id = ?", id) == 1 }

    fun session(id: String): ExecutionSession? = store.executionRead { it.session(id) }

    /** Where session [id]'s worker was placed ([SessionPlacement]), as kept when it was created. */
    fun placement(id: String): SessionPlacement = store.executionRead { db ->
        db.sqlRows("SELECT placement, preparation_slots FROM execution_sessions WHERE id = ?", id) { row ->
            row.getString(1)?.let { value -> SessionPlacement.parse(value).copy(preparationSlots = row.getObject(2)?.let { row.getInt(2) }) }
        }.singleOrNull()
    } ?: SessionPlacement.of(submission(id))
    /** Executing claim concurrency and unallocated solve slots, without treating reserved arms as running CPUs. */
    internal fun parallelism(jobs: Collection<Long>, at: Long = now()): Map<Long, Pair<Int, Int>> {
        if (jobs.isEmpty()) return emptyMap()
        return store.executionRead { db ->
            db.sqlRows("""SELECT s.job_id, s.budget, s.allocation_id, a.state, a.parallel_limit, a.lease_until
                FROM execution_sessions s LEFT JOIN iteration_attempts a ON a.session_id = s.id AND a.state IN $ACTIVE_ATTEMPTS
                WHERE s.execution_class = 'solve' AND s.desired != 'stop' AND s.job_id IN (${jobs.joinToString(",") { "?" }})""",
                *jobs.toTypedArray()) { row ->
                val executing = if (row.getString(4) == "executing" && row.getLong(6) > at) row.getInt(5) else 0
                val pending = if (row.getString(3) == null) Json.decodeFromString<ResourceBudget>(row.getString(2)).parallel else 0
                row.getLong(1) to (executing to pending)
            }.groupBy({ it.first }, { it.second }).mapValues { (_, workers) -> workers.sumOf { it.first } to workers.sumOf { it.second } }
        }
    }

    fun reports(job: Long): List<ExecutionSessionReport> = store.executionRead { db ->
        db.sqlRows("SELECT $SESSION_COLUMNS, observed_at, observation FROM execution_sessions WHERE job_id = ? ORDER BY created_at, id", job) { row ->
            val s = sessionRow(row)
            val active = db.sqlRows("SELECT state, generation, preparation_progress, preparation_updated_at, preparation_caches, preparation_diagnostics FROM iteration_attempts WHERE session_id = ? ORDER BY generation DESC, started_at DESC LIMIT 1", s.id) { attempt ->
                Triple(attempt.getString(1), attempt.getInt(2), attempt.getString(3)?.let {
                    PreparationStatus(Json.decodeFromString<PreparationProgress>(it).copy(caches = attempt.getString(5)?.let { value ->
                        Json.decodeFromString<Map<String, PreparationCacheEvent>>(value)
                    }.orEmpty()), attempt.getLong(4), attempt.getString(6)?.let { value -> Json.decodeFromString<PreparationDiagnostics>(value) })
                })
            }.singleOrNull()
            val done = db.sqlRows("SELECT COUNT(*) FROM iteration_attempts WHERE session_id = ? AND state = 'committed'", s.id) { it.getInt(1) }.single()
            val observed = row.getObject("observed_at")?.let { row.getLong("observed_at") }
            ExecutionSessionReport(s, active?.first, active?.second, done, row.getString("observation"), observed, active?.third)
        }
    }
    internal fun activePreparations(): List<ExecutionSession> = store.executionRead { db ->
        db.sqlRows("SELECT $SESSION_COLUMNS FROM execution_sessions WHERE desired != 'stop' AND execution_class IN ('prepare', 'build')", map = ::sessionRow)
    }

    fun sessions(jobId: Long? = null): List<ExecutionSession> = store.executionRead { db ->
        db.sqlRows("SELECT $SESSION_COLUMNS FROM execution_sessions" + if (jobId == null) " ORDER BY id" else " WHERE job_id = ? ORDER BY id",
            *listOfNotNull(jobId).toTypedArray(), map = ::sessionRow)
    }

    /** Current assignments, including preparation before any case has started; stopped generations are omitted. */
    internal fun runningNodes(jobs: Collection<Long>): Map<Long, List<RunningNode>> {
        if (jobs.isEmpty()) return emptyMap()
        return store.executionRead { db ->
            db.sqlRows("""SELECT s.job_id, s.node_id, s.allocation_id, s.placement, o.payload
                FROM execution_sessions s JOIN execution_outbox o ON o.id = s.id || ':submit'
                WHERE s.job_id IN (${jobs.joinToString(",") { "?" }}) AND s.desired != 'stop' AND s.node_id IS NOT NULL
                ORDER BY s.job_id, s.node_id""", *jobs.toTypedArray()) { row ->
                val placement = row.getString("placement")?.let(SessionPlacement::parse) ?: SessionPlacement.of(row.getString("payload"))
                row.getLong("job_id") to runningNode(row.getString("node_id"), row.getString("allocation_id"), placement)
            }.groupBy({ it.first }, { it.second })
        }
    }

    /** The coordinator chooses the credential before persisting intent; a retry must supply identical inputs. */
    fun createSession(id: String, jobId: Long, executionClass: ExecutionClass, release: String, budget: ResourceBudget,
                      token: String, payload: String, at: Long = now(), manifest: String? = null): ExecutionSession = store.executionTransaction { db ->
        require(id.matches(Regex("[a-z0-9-]{1,64}")))
        require(release.isNotBlank() && token.length >= 32)
        val job = store.job(jobId) ?: error("missing experiment")
        require(job.backend == Backend.NOMAD && !job.cancelRequested && job.status.isActive)
        val nomadId = "lab-$jobId-$executionClass-$id-1"
        db.session(id)?.let { old ->
            require(old.jobId == jobId && old.executionClass == executionClass && old.release == release && old.budget == budget)
            require(db.sqlRows("SELECT token_hash FROM execution_sessions WHERE id = ?", id) { it.getString(1) }.single() == digest(token))
            require(db.sqlRows("SELECT payload FROM execution_outbox WHERE id = ?", "$id:submit") { it.getString(1) }.single() == payload)
            return@executionTransaction old
        }
        val placement = SessionPlacement.of(payload)
        val pool = placement.pool
        if (pool is WorkerPool.Native) {
            val limit = pool.limits
            if (limit == null) check(!nativeOwnedByAnother(db, jobId)) { "native execution is reserved by another experiment" }
            else {
                val held = nativeReservation(db)
                check(held.first + budget.cpuMillis <= limit.first && held.second + budget.memoryMiB <= limit.second) { "native CPU or memory budget exhausted" }
            }
        }
        db.sqlUpdate("INSERT INTO execution_sessions (id, job_id, generation, execution_class, release, budget, nomad_job_id, desired, token_hash, created_at) VALUES (?, ?, 1, ?, ?, ?, ?, 'run', ?, ?)",
            id, jobId, executionClass.stored, release, Json.encodeToString(budget), nomadId, digest(token), at)
        db.sqlUpdate("INSERT INTO execution_outbox (id, job_id, session_id, operation, payload) VALUES (?, ?, ?, 'submit', ?)", "$id:submit", jobId, id, payload)
        db.sqlUpdate("UPDATE execution_sessions SET placement = ?, cpu_enforcement = ?, preparation_slots = ? WHERE id = ?",
            placement.json(), placement.enforcement?.stored, placement.preparationSlots, id)
        if (manifest != null) db.sqlUpdate("INSERT INTO execution_manifests (session_id, manifest) VALUES (?, ?)", id, manifest)
        db.event(jobId, id, "session-created", at)
        db.session(id)!!
    }

    /** Call only after authenticating the allocation against Nomad's job/task identity. Never rebind a live session. */
    fun bind(id: String, token: String, allocation: String, node: String, release: String, at: Long = now()): ExecutionSession = store.executionTransaction { db ->
        val s = db.authenticate(id, token)
        require(allocation.isNotBlank() && node.isNotBlank() && release == s.release)
        if (s.allocation != null && (s.allocation != allocation || s.node != node)) throw ExecutionFenced("allocation superseded")
        db.sqlUpdate("UPDATE execution_sessions SET allocation_id = ?, node_id = ?, observed_at = ? WHERE id = ?", allocation, node, at, id)
        db.sqlUpdate("UPDATE jobs SET status = 'RUNNING', started_at = COALESCE(started_at, ?), running_since = COALESCE(running_since, ?) WHERE id = ?", at, at, s.jobId)
        db.session(id)!!
    }

    fun claim(id: String, token: String, allocation: String, at: Long = now(), leaseMs: Long = LEASE_MS): IterationClaim? {
        expire(at)
        return store.executionTransaction { db ->
            require(leaseMs in 1..LEASE_MS)
            val session = db.authenticate(id, token, allocation)
            if (store.job(session.jobId)!!.runRemainingMs(at)?.let { it <= 0 } == true) {
                db.failRunDeadline(session.jobId, at)
                return@executionTransaction null
            }
            if (store.job(session.jobId)!!.paused || session.desired != Desired.RUN) return@executionTransaction null
            // An HTTP retry of a claim returns the existing ownership, never a second concurrent iteration.
            val active = db.sqlRows("SELECT * FROM iteration_attempts WHERE session_id = ? AND state IN $ACTIVE_ATTEMPTS ORDER BY generation DESC", id) { attemptRow(it) }.singleOrNull()
            if (active != null) {
                // Claim tokens are derived from the scoped session secret; only their digest is persisted.
                val claimToken = claimToken(token, active.iteration, active.generation)
                return@executionTransaction IterationClaim(AttemptOwner(id, allocation, active.iteration, active.generation, claimToken), active.indices, active.leaseUntil, active.parallel)
            }
            if (session.executionClass == ExecutionClass.PREPARE) {
                if (store.job(session.jobId)!!.setupDone || db.sqlRows("SELECT 1 FROM iteration_attempts WHERE job_id = ? AND iteration_id = 'preparation' AND state = 'committed'", session.jobId) { it.getInt(1) }.isNotEmpty()) return@executionTransaction null
                val iteration = "preparation"
                val generation = db.sqlRows("SELECT COALESCE(MAX(generation), 0) + 1 FROM iteration_attempts WHERE job_id = ? AND iteration_id = ?", session.jobId, iteration) { it.getInt(1) }.single()
                val claimToken = claimToken(token, iteration, generation)
                db.sqlUpdate("INSERT INTO iteration_attempts (job_id, iteration_id, generation, session_id, allocation_id, token_hash, lease_until, state, indices, started_at, parallel_limit) VALUES (?, ?, ?, ?, ?, ?, ?, 'claimed', '[]', ?, 1)",
                    session.jobId, iteration, generation, id, allocation, digest(claimToken), at + leaseMs, at)
                db.event(session.jobId, id, "claimed:$iteration:$generation", at)
                return@executionTransaction IterationClaim(AttemptOwner(id, allocation, iteration, generation, claimToken), emptyList(), at + leaseMs)
            }
            val free = db.iterations(session.jobId).entries.filter { (_, rows) -> rows.all { it.status == "QUEUED" && it.host == null } }
            if (free.isEmpty()) return@executionTransaction null
            val placement = Json.parseToJsonElement(db.sqlRows("SELECT payload FROM execution_outbox WHERE id = ?", "$id:submit") { it.getString(1) }.single()).jsonObject
            val perHost = placement["Meta"]?.jsonObject?.get("lab_per_host_parallel")?.jsonPrimitive?.intOrNull
            val heldParallel = if (perHost == null) 0 else db.sqlRows("SELECT COALESCE(SUM(a.parallel_limit), 0) FROM iteration_attempts a JOIN execution_sessions s ON s.id = a.session_id WHERE a.job_id = ? AND s.node_id = ? AND a.state IN $ACTIVE_ATTEMPTS", session.jobId, session.node) { it.getInt(1) }.single()
            val slots = minOf(session.budget.parallel, perHost?.let { (it - heldParallel).coerceAtLeast(0) } ?: session.budget.parallel)
            if (slots == 0) return@executionTransaction null
            // Whole rows until they fill the session's case slots: every arm of a row runs here, in this one attempt, and
            // a lost attempt gives all its rows back. One row keeps its own key, so single-row attempts read as before.
            val rows = mutableListOf(free.first())
            for (row in free.drop(1)) { if (rows.sumOf { it.value.size } >= slots) break; rows += row }
            val iteration = rows.singleOrNull()?.key ?: rows.map { it.key }.let { keys ->
                "${keys.first()}+${keys.size - 1}~${digest(keys.joinToString(",")).take(12)}"
            }
            val generation = db.sqlRows("SELECT COALESCE(MAX(generation), 0) + 1 FROM iteration_attempts WHERE job_id = ? AND iteration_id = ?", session.jobId, iteration) { it.getInt(1) }.single()
            val claimToken = claimToken(token, iteration, generation)
            val indices = rows.flatMap { row -> row.value.map { it.index } }
            val parallel = minOf(slots, indices.size)
            db.sqlUpdate("INSERT INTO iteration_attempts (job_id, iteration_id, generation, session_id, allocation_id, token_hash, lease_until, state, indices, started_at, parallel_limit) VALUES (?, ?, ?, ?, ?, ?, ?, 'claimed', ?, ?, ?)",
                session.jobId, iteration, generation, id, allocation, digest(claimToken), at + leaseMs, Json.encodeToString(indices), at, parallel)
            for (index in indices) db.sqlUpdate("UPDATE commands SET host = ? WHERE job_id = ? AND idx = ?", session.node, session.jobId, index)
            db.event(session.jobId, id, "claimed:$iteration:$generation", at)
            IterationClaim(AttemptOwner(id, allocation, iteration, generation, claimToken), indices, at + leaseMs, parallel)
        }
    }

    fun renew(owner: AttemptOwner, at: Long = now(), leaseMs: Long = LEASE_MS): Long = store.executionTransaction { db ->
        require(leaseMs in 1..LEASE_MS)
        val a = db.attempt(owner, at)
        db.sqlUpdate("UPDATE iteration_attempts SET lease_until = ? WHERE job_id = ? AND iteration_id = ? AND generation = ?", at + leaseMs, a.jobId, owner.iteration, owner.generation)
        at + leaseMs
    }

    fun remainingRunMs(session: String, at: Long = now()): Long? = store.executionRead { db ->
        val job = store.job(requireNotNull(db.session(session)).jobId) ?: throw ExecutionFenced("missing experiment")
        job.runRemainingMs(at)
    }

    private fun Job.runRemainingMs(at: Long): Long? = experiment.let(Experiments::runTimeoutSec)?.let { it * 1000 - elapsedMs(at) }

    fun runTimedOut(owner: AttemptOwner, at: Long = now()) = store.executionTransaction { db ->
        val prior = db.findAttempt(owner) ?: throw ExecutionFenced("unknown attempt")
        if (store.job(prior.jobId)?.error == RunDeadlineExceeded.ERROR) return@executionTransaction
        val attempt = db.attempt(owner, at)
        require(store.job(attempt.jobId)?.experiment?.let(Experiments::runTimeoutSec) != null)
        db.failRunDeadline(attempt.jobId, at)
    }

    fun expireRunDeadlines(at: Long = now()) = store.executionTransaction { db ->
        store.active(withCommands = false).filter { it.backend == Backend.NOMAD }.forEach { job ->
            val remaining = job.runRemainingMs(at)
            if (remaining != null && (remaining <= 0 || db.sqlRows("SELECT result FROM iteration_attempts WHERE job_id = ? AND state = 'committed'", job.id) {
                Json.decodeFromString<IterationResult>(it.getString(1)).runTimedOut
            }.any { it })) db.failRunDeadline(job.id, at)
        }
    }

    private fun Tx.failRunDeadline(job: Long, at: Long) {
        sqlRows("SELECT * FROM iteration_attempts WHERE job_id = ? AND state IN $ACTIVE_ATTEMPTS", job, map = ::attemptRow)
            .forEach { invalidate(it, "run-timeout", at) }
        sqlRows("SELECT $SESSION_COLUMNS FROM execution_sessions WHERE job_id = ?", job, map = ::sessionRow).forEach { s ->
            sqlUpdate("UPDATE execution_sessions SET desired = 'stop' WHERE id = ?", s.id); stopIntent(s)
        }
        sqlUpdate("UPDATE commands SET status = 'CANCELLED', host = NULL WHERE job_id = ? AND status IN ('QUEUED', 'RUNNING')", job)
        sqlUpdate("UPDATE jobs SET status = 'FAILED', error = ?, finished_at = ?, run_ms = run_ms + COALESCE(? - running_since, 0), running_since = NULL, local = 0, paused = 0 WHERE id = ? AND status IN ('QUEUED', 'RUNNING')",
            RunDeadlineExceeded.ERROR, at, at, job)
        event(job, null, "profile-run-timeout", at)
    }

    fun phase(owner: AttemptOwner, phase: String, at: Long = now()) = store.executionTransaction { db ->
        val next = AttemptState.of(phase)
        require(next in setOf(AttemptState.PREPARING, AttemptState.EXECUTING, AttemptState.UPLOADING))
        val a = db.attempt(owner, at)
        require(next >= a.state) { "phase cannot go backwards" }
        db.sqlUpdate("UPDATE iteration_attempts SET state = ? WHERE job_id = ? AND iteration_id = ? AND generation = ?", next.stored, a.jobId, owner.iteration, owner.generation)
        db.event(a.jobId, owner.session, "phase:$phase:${owner.iteration}:${owner.generation}", at)
    }

    fun preparationProgress(owner: AttemptOwner, progress: PreparationProgress, at: Long = now()) = store.executionTransaction { db ->
        val attempt = db.attempt(owner, at)
        require(attempt.iteration == "preparation" && attempt.state == AttemptState.PREPARING) { "preparation progress outside preparation" }
        val prior = db.sqlRows("SELECT preparation_progress FROM iteration_attempts WHERE job_id = ? AND iteration_id = ? AND generation = ?",
            attempt.jobId, owner.iteration, owner.generation) { it.getString(1)?.let { value -> Json.decodeFromString<PreparationProgress>(value) } }.single()
        // A final snapshot can race the background reporter. Never replace it with an older observation.
        if (prior != null && prior.elapsedMs > progress.elapsedMs) return@executionTransaction
        db.sqlUpdate("UPDATE iteration_attempts SET preparation_progress = ?, preparation_updated_at = ?, preparation_caches = ? WHERE job_id = ? AND iteration_id = ? AND generation = ?",
            // Keep the original JSON shape readable by an older coordinator after rollback.
            Json.encodeToString(progress.copy(caches = emptyMap())), at, Json.encodeToString(progress.caches), attempt.jobId, owner.iteration, owner.generation)
        if (prior?.stage != progress.stage) db.event(attempt.jobId, owner.session, "preparation:${progress.stage}:${owner.generation}", at)
    }

    /** Keep failure observations independently of progress JSON so an older coordinator can still read progress. */
    fun preparationDiagnostics(owner: AttemptOwner, diagnostics: PreparationDiagnostics, at: Long = now()) = store.executionTransaction { db ->
        val attempt = db.attempt(owner, at)
        require(attempt.iteration == "preparation" && attempt.state == AttemptState.PREPARING)
        require(diagnostics.budget == db.session(owner.session)!!.budget) { "diagnostic budget differs from reservation" }
        val prior = db.sqlRows("SELECT preparation_diagnostics FROM iteration_attempts WHERE job_id = ? AND iteration_id = ? AND generation = ?",
            attempt.jobId, owner.iteration, owner.generation) { it.getString(1)?.let { value -> Json.decodeFromString<PreparationDiagnostics>(value) } }.single()
        if (prior != null && (prior.elapsedMs > diagnostics.elapsedMs ||
            prior.elapsedMs == diagnostics.elapsedMs && prior.sequence >= diagnostics.sequence)) return@executionTransaction
        db.sqlUpdate("UPDATE iteration_attempts SET preparation_diagnostics = ?, preparation_progress = COALESCE(preparation_progress, ?), preparation_updated_at = COALESCE(preparation_updated_at, ?) WHERE job_id = ? AND iteration_id = ? AND generation = ?",
            Json.encodeToString(diagnostics), Json.encodeToString(PreparationProgress(diagnostics.stage, diagnostics.elapsedMs, 0, emptyMap())),
            at, attempt.jobId, owner.iteration, owner.generation)
    }

    /** A source/build error is permanent work failure, not another infrastructure revocation. */
    fun rejectBuild(owner: AttemptOwner, detail: String, at: Long = now()) = store.executionTransaction { db ->
        val reason = "source-build: " + detail.take(16_384)
        val prior = db.findAttempt(owner) ?: throw ExecutionFenced("unknown attempt")
        if (store.job(prior.jobId)?.let { it.status == Status.FAILED && it.error == reason } == true) return@executionTransaction
        val attempt = db.attempt(owner, at)
        require(attempt.state in setOf(AttemptState.CLAIMED, AttemptState.PREPARING)) { "build failure outside preparation" }
        db.sqlRows("SELECT * FROM iteration_attempts WHERE job_id = ? AND state IN $ACTIVE_ATTEMPTS", attempt.jobId, map = ::attemptRow)
            .forEach { db.invalidate(it, "source-build-failed", at) }
        db.sqlRows("SELECT $SESSION_COLUMNS FROM execution_sessions WHERE job_id = ?", attempt.jobId, map = ::sessionRow).forEach { session ->
            db.sqlUpdate("UPDATE execution_sessions SET desired = 'stop' WHERE id = ?", session.id); db.stopIntent(session)
        }
        db.sqlUpdate("UPDATE jobs SET status = 'FAILED', error = ?, finished_at = ?, run_ms = run_ms + COALESCE(? - running_since, 0), running_since = NULL, local = 0, paused = 0 WHERE id = ? AND status IN ('QUEUED', 'RUNNING')", reason, at, at, attempt.jobId)
        db.sqlUpdate("UPDATE commands SET status = 'CANCELLED', host = NULL WHERE job_id = ? AND status IN ('QUEUED', 'RUNNING')", attempt.jobId)
        db.event(attempt.jobId, owner.session, "source-build-failed", at)
    }

    /** Count reproducible coordinator errors, never worker/node losses. Durable across replacement and restart. */
    internal fun preparationError(owner: AttemptOwner, fingerprint: String, at: Long = now()): Int = store.executionTransaction { db ->
        val attempt = db.attempt(owner, at)
        require(attempt.iteration == "preparation" && attempt.state == AttemptState.PREPARING)
        val event = "preparation-error:$fingerprint"
        db.event(attempt.jobId, owner.session, event, at)
        db.sqlRows("""SELECT COUNT(*) FROM execution_events WHERE job_id = ? AND detail = ? AND id > COALESCE(
            (SELECT MAX(id) FROM execution_events WHERE job_id = ? AND detail = 'manual-retry'), 0)""",
            attempt.jobId, event, attempt.jobId) { it.getInt(1) }.single()
    }

    /** Called only with positive Nomad evidence of a worker's nonzero, unsignalled process exit. */
    internal fun preparationExited(id: String, code: Int, at: Long = now()): PreparationExitFailure? = store.executionTransaction { db ->
        val session = db.session(id) ?: return@executionTransaction null
        if (session.executionClass != ExecutionClass.PREPARE || session.desired != Desired.RUN) return@executionTransaction null
        if (db.sqlRows("SELECT 1 FROM iteration_attempts WHERE job_id = ? AND iteration_id = 'preparation' AND state = 'committed'", session.jobId) { true }.isNotEmpty()) return@executionTransaction null
        val progress = db.sqlRows("SELECT preparation_progress FROM iteration_attempts WHERE session_id = ? AND state IN $ACTIVE_ATTEMPTS", id) {
            it.getString(1)?.let { value -> Json.decodeFromString<PreparationProgress>(value) }
        }
        val stage = progress.singleOrNull()?.stage ?: if (progress.isNotEmpty()) "unreported" else if (session.allocation == null) "registration" else "claim"
        val fingerprint = digest("${session.release}:$stage:$code")
        val detail = "preparation-worker-exit:$fingerprint:exit=$code,stage=$stage,release=${session.release}"
        if (db.sqlRows("SELECT 1 FROM execution_events WHERE session_id = ? AND detail = ?", id, detail) { true }.isEmpty())
            db.event(session.jobId, id, detail, at)
        val count = db.sqlRows("""SELECT COUNT(*) FROM execution_events WHERE job_id = ? AND detail = ? AND id > COALESCE(
            (SELECT MAX(id) FROM execution_events WHERE job_id = ? AND detail = 'manual-retry'), 0)""",
            session.jobId, detail, session.jobId) { it.getInt(1) }.single()
        // The worker reports what ended it before exiting; carry it into the failure, which is all the job log shows.
        val error = db.sqlRows("""SELECT json_extract(preparation_diagnostics, '$.error') FROM iteration_attempts
            WHERE job_id = ? AND iteration_id = 'preparation' AND json_extract(preparation_diagnostics, '$.error') IS NOT NULL
            ORDER BY generation DESC LIMIT 1""", session.jobId) { it.getString(1) }.singleOrNull()
        if (count >= 3) db.failActive(session.jobId,
            "Nomad preparation stopped after $count worker exits without acceptance at stage $stage (exit $code, release ${session.release})" +
                if (error != null) ": $error" else "; inspect the worker log and retry after fixing the cause", at)
        PreparationExitFailure(count, stage, session.release)
    }

    fun caseStarted(owner: AttemptOwner, index: Int, at: Long = now()) = store.executionTransaction { db ->
        val a = db.attempt(owner, at); require(index in a.indices && a.state == AttemptState.EXECUTING) { "case start outside executing iteration" }
        db.sqlUpdate("UPDATE commands SET status = 'RUNNING', started_at = COALESCE(started_at, ?) WHERE job_id = ? AND idx = ?", at, a.jobId, index)
        db.event(a.jobId, owner.session, "case-start:$index:${owner.generation}", at)
    }

    /** Artifacts must have been checksum-verified before this transaction. A lost acknowledgement is idempotent. */
    internal fun commit(owner: AttemptOwner, result: IterationResult, at: Long = now(),
                        projectRecord: (ExecutionCaseResult) -> String? = { it.record },
                        benchSelections: List<com.eignex.lab.execution.BenchSelectionEntry> = emptyList()): Boolean = store.executionTransaction { db ->
        val a = db.findAttempt(owner) ?: throw ExecutionFenced("unknown attempt")
        if (a.state == AttemptState.COMMITTED) {
            if (a.result != Json.encodeToString(result)) throw ExecutionFenced("result differs from accepted manifest")
            return@executionTransaction true
        }
        db.attempt(owner, at)
        if (store.job(a.jobId)!!.runRemainingMs(at)?.let { it <= 0 } == true) throw RunDeadlineExceeded()
        require((a.iteration == "preparation") == (result.preparation != null))
        require(!result.runTimedOut || store.job(a.jobId)?.experiment?.let(Experiments::runTimeoutSec) != null)
        require(a.state == AttemptState.UPLOADING) { "attempt is not uploading" }
        require(result.cases.map { it.index }.sorted() == a.indices.sorted()) { "all arms must be committed together, exactly once" }
        val node = db.session(owner.session)!!.node!!
        for (case in result.cases) {
            case.record?.let { Json.parseToJsonElement(it) }
            val record = projectRecord(case)
            record?.let { Json.parseToJsonElement(it) }
            db.sqlUpdate("UPDATE commands SET status = ?, exit_code = ?, started_at = ?, finished_at = ?, phase = NULL, host = ?, cmd = COALESCE(?, cmd) WHERE job_id = ? AND idx = ?",
                if (case.exitCode == 0) "DONE" else "FAILED", case.exitCode, case.startedAt, case.finishedAt, node, case.command, a.jobId, case.index)
            db.sqlUpdate("UPDATE cases SET record = ?, lab_check = NULL WHERE job_id = ? AND idx = ?", record?.let(store.plans::storedRecord), a.jobId, case.index)
        }
        db.sqlUpdate("UPDATE iteration_attempts SET state = 'committed', result = ?, finished_at = ? WHERE job_id = ? AND iteration_id = ? AND generation = ?",
            Json.encodeToString(result), at, a.jobId, owner.iteration, owner.generation)
        db.event(a.jobId, owner.session, "committed:${owner.iteration}:${owner.generation}", at)
        // The newest acceptance owns a key: its inputs are the likeliest to remain, and the older job may be deleted.
        for (entry in benchSelections) db.sqlUpdate("INSERT OR REPLACE INTO bench_selections (selection_key, job_id, entry, created_at) VALUES (?, ?, ?, ?)",
            entry.key, a.jobId, Json.encodeToString(entry), at)
        if (result.runTimedOut) db.failRunDeadline(a.jobId, at)
        true
    }

    /**
     * Accepted planner outputs under [keys], for the preparing attempt [owner]. Primary key lookups only. A key is a
     * digest of the repository and plan inputs ([com.eignex.lab.execution.BenchSelections]), so it names one plan.
     */
    fun benchSelections(owner: AttemptOwner, keys: List<String>, at: Long = now()): List<com.eignex.lab.execution.BenchSelectionEntry> = store.executionRead { db ->
        val attempt = db.attempt(owner, at)
        require(attempt.iteration == PREPARATION && attempt.state == AttemptState.PREPARING)
        keys.flatMap { key ->
            db.sqlRows("SELECT entry FROM bench_selections WHERE selection_key = ?", key) {
                Json.decodeFromString<com.eignex.lab.execution.BenchSelectionEntry>(it.getString(1))
            }
        }
    }

    /** Retire this credential before allowing a replacement session. Completed attempts remain accepted. */
    fun loseSession(id: String, reason: String, at: Long = now()) = store.executionTransaction { db ->
        db.session(id)?.let { s ->
            db.sqlUpdate("UPDATE execution_sessions SET desired = 'stop', observation = ? WHERE id = ?", reason.take(1024), id)
            db.sqlRows("SELECT * FROM iteration_attempts WHERE session_id = ? AND state IN $ACTIVE_ATTEMPTS", id, map = ::attemptRow)
                .forEach { db.invalidate(it, "lost", at) }
            db.stopIntent(s)
            db.event(s.jobId, id, "lost:${reason.take(1024)}", at)
        }
    }

    fun loseUnallocatedSession(id: String, reason: String, at: Long = now()): Boolean = store.executionTransaction { db ->
        val s = db.session(id) ?: return@executionTransaction false
        if (s.desired != Desired.RUN || s.allocation != null) return@executionTransaction false
        db.sqlUpdate("UPDATE execution_sessions SET desired = 'stop', observation = ? WHERE id = ?", reason.take(1024), id)
        db.stopIntent(s)
        db.event(s.jobId, id, "lost:${reason.take(1024)}", at)
        true
    }

    fun expire(at: Long = now()) = store.executionTransaction { db ->
        db.sqlRows("SELECT DISTINCT job_id FROM iteration_attempts WHERE state IN $ACTIVE_ATTEMPTS AND lease_until <= ?", at) { it.getLong(1) }
            .forEach { db.expire(it, at) }
    }

    fun intents(): List<ExecutionIntent> = store.executionTransaction { db ->
        db.sqlRows("SELECT * FROM execution_outbox WHERE acknowledged = 0 ORDER BY rowid") {
            ExecutionIntent(it.getString("id"), it.getLong("job_id"), it.getString("session_id"), IntentOp.of(it.getString("operation")), it.getString("payload"), false)
        }
    }
    fun acknowledge(id: String) = store.executionTransaction { it.sqlUpdate("UPDATE execution_outbox SET acknowledged = 1 WHERE id = ?", id) }

    /** Record [detail] in [jobId]'s execution log, for [session] when given. */
    fun note(jobId: Long, session: String?, detail: String, at: Long = now()) = store.executionTransaction { it.event(jobId, session, detail, at) }

    fun credential(id: String, token: String, allocation: String? = null): ExecutionSession = store.executionRead { db ->
        val s = db.session(id) ?: throw ExecutionFenced("unknown session")
        val hash = db.sqlRows("SELECT token_hash FROM execution_sessions WHERE id = ?", id) { it.getString(1) }.single()
        if (!MessageDigest.isEqual(hash.toByteArray(), digest(token).toByteArray()) || (allocation != null && s.allocation != null && s.allocation != allocation)) throw ExecutionFenced("invalid session credential")
        s
    }
    fun authorize(owner: AttemptOwner, at: Long = now()): Long = store.executionRead { db -> db.attempt(owner, at).jobId }
    internal fun authorizeCommit(owner: AttemptOwner, result: IterationResult, at: Long = now()): Long = store.executionRead { db ->
        val attempt = db.findAttempt(owner) ?: throw ExecutionFenced("unknown attempt")
        if (attempt.state == AttemptState.COMMITTED) {
            if (attempt.result != Json.encodeToString(result)) throw ExecutionFenced("result differs from accepted manifest")
        } else db.attempt(owner, at)
        attempt.jobId
    }
    fun manifest(id: String): WorkManifest = store.executionRead { db ->
        require(db.sqlRows("SELECT 1 FROM execution_sessions WHERE id = ?", id) { true }.isNotEmpty()) { "unknown session $id" }
        val text = db.sqlRows("SELECT manifest FROM execution_manifests WHERE session_id = ?", id) { it.getString(1) }.singleOrNull()
            // Not yet moved ([moveManifests]).
            ?: db.sqlRows("SELECT manifest FROM execution_sessions WHERE id = ?", id) { it.getString(1) }.single()
        Json.decodeFromString(requireNotNull(text) { "session $id: its manifest was dropped after its job was done" })
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
            db.sqlUpdate("INSERT OR IGNORE INTO execution_manifests (session_id, manifest) SELECT id, manifest FROM execution_sessions WHERE id = ?", id)
            db.sqlUpdate("UPDATE execution_sessions SET manifest = NULL WHERE id = ?", id)
        }
        ids.size
    }

    fun dropDoneManifests(doneBefore: Long, batch: Int = 5): Int = store.executionTransaction { db ->
        db.sqlUpdate("""DELETE FROM execution_manifests WHERE session_id IN (
            SELECT s.id FROM execution_manifests m JOIN execution_sessions s ON s.id = m.session_id JOIN jobs j ON j.id = s.job_id
            WHERE s.desired = 'stop' AND j.status = 'DONE' AND j.finished_at < ? LIMIT ?)""", doneBefore, batch)
    }
    /** Whether session [id]'s frozen contract requests external adapter resource receipts. */
    fun adapterResources(id: String): Boolean = store.executionRead { db ->
        db.sqlRows("""SELECT CASE WHEN json_valid(manifest) THEN COALESCE(json_extract(manifest, '$.experiment.bench.adapterResources'), 0) ELSE 0 END
            FROM (SELECT COALESCE(m.manifest, s.manifest) AS manifest FROM execution_sessions s
                  LEFT JOIN execution_manifests m ON m.session_id = s.id WHERE s.id = ?)""", id) {
            it.getInt(1) != 0
        }.single()
    }
    fun drain(id: String) = store.executionTransaction { db -> db.sqlUpdate("UPDATE execution_sessions SET desired = 'drain' WHERE id = ? AND desired = 'run'", id) }
    fun accepted(jobId: Long): List<Pair<String, IterationResult>> = store.executionRead { db ->
        db.sqlRows("SELECT iteration_id, result FROM iteration_attempts WHERE job_id = ? AND state = 'committed' ORDER BY iteration_id", jobId) { it.getString(1) to Json.decodeFromString<IterationResult>(it.getString(2)) }
    }
    /** The job's accepted result of [iteration], read alone: results are large, and most readers want one of them. */
    fun accepted(jobId: Long, iteration: String): IterationResult? = store.executionRead { db ->
        db.sqlRows("SELECT result FROM iteration_attempts WHERE job_id = ? AND iteration_id = ? AND state = 'committed'", jobId, iteration) {
            Json.decodeFromString<IterationResult>(it.getString(1))
        }.singleOrNull()
    }
    /** The job's accepted preparation, the one iteration whose result carries one. */
    fun acceptedPreparation(jobId: Long): PreparationResult? = accepted(jobId, PREPARATION)?.preparation
    /** Each accepted iteration's case indices, without reading its result. */
    fun acceptedIndices(jobId: Long): List<Pair<String, List<Int>>> = store.executionRead { db ->
        db.sqlRows("SELECT iteration_id, indices FROM iteration_attempts WHERE job_id = ? AND state = 'committed' ORDER BY iteration_id", jobId) {
            it.getString(1) to Json.decodeFromString<List<Int>>(it.getString(2))
        }
    }
    fun submission(id: String): String = store.executionRead { db -> db.sqlRows("SELECT payload FROM execution_outbox WHERE id = ?", "$id:submit") { it.getString(1) }.single() }
    fun hasActiveAttempt(session: String): Boolean = store.executionRead { db -> db.sqlRows("SELECT 1 FROM iteration_attempts WHERE session_id = ? AND state IN $ACTIVE_ATTEMPTS", session) { true }.isNotEmpty() }
    internal fun retryIn(db: Tx, job: Long, at: Long) { db.event(job, null, "manual-retry", at) }
    /** Diagnostic loss count since manual retry; infrastructure losses never exhaust the experiment. */
    fun failureCount(job: Long): Int = store.executionRead { db -> db.sqlRows("""SELECT COUNT(*) FROM execution_events
        WHERE job_id = ? AND detail LIKE 'lost:infrastructure:%' AND id > COALESCE(
            (SELECT MAX(id) FROM execution_events WHERE job_id = ? AND detail = 'manual-retry'), 0)""", job, job) { it.getInt(1) }.single() }
    /**
     * Jobs with accepted work not yet projected or checked. With [endedSince], only active jobs and those that ended
     * since then: an ended job gains no work until a retry makes it active again, and the scan of every job's cases
     * is what makes the full form slow.
     */
    fun pendingProjectionJobs(endedSince: Long? = null): List<Long> = store.executionRead { db -> db.sqlRows("""
        SELECT j.id FROM jobs j
        WHERE j.backend = 'nomad' AND (? IS NULL OR j.status IN ('QUEUED', 'RUNNING') OR j.finished_at >= ?) AND EXISTS (SELECT 1 FROM iteration_attempts a WHERE a.job_id = j.id AND a.state = 'committed') AND (
          EXISTS (SELECT 1 FROM iteration_attempts a WHERE a.job_id = j.id AND a.state = 'committed'
            AND NOT EXISTS (SELECT 1 FROM execution_ingestion i WHERE i.job_id = a.job_id AND i.iteration_id = a.iteration_id))
          OR EXISTS (SELECT 1 FROM commands c WHERE c.job_id = j.id AND c.status IN ('DONE', 'FAILED')
            AND NOT EXISTS (SELECT 1 FROM execution_checks e WHERE e.job_id = c.job_id AND e.idx = c.idx)))
        ORDER BY j.id
    """.trimIndent(), endedSince, endedSince) { it.getLong(1) } }
    fun ingested(job: Long): Set<String> = store.executionRead { db -> db.sqlRows("SELECT iteration_id FROM execution_ingestion WHERE job_id = ?", job) { it.getString(1) }.toSet() }
    fun markIngested(job: Long, iteration: String) = store.executionTransaction { db -> db.sqlUpdate("INSERT OR IGNORE INTO execution_ingestion (job_id, iteration_id) VALUES (?, ?)", job, iteration) }
    fun observed(id: String, detail: String, at: Long = now()) = store.executionTransaction { db ->
        db.sqlUpdate("UPDATE execution_sessions SET observation = ?, observed_at = ? WHERE id = ?", detail.take(2048), at, id)
    }
    fun checkComplete(job: Long, index: Int, detail: String) = store.executionTransaction { db ->
        db.sqlUpdate("INSERT OR REPLACE INTO execution_checks (job_id, idx, detail) VALUES (?, ?, ?)", job, index, detail.take(1024))
    }
    fun pauseClock(job: Long) = store.executionTransaction { db ->
        db.sqlUpdate("UPDATE jobs SET run_ms = run_ms + COALESCE(? - running_since, 0), running_since = NULL WHERE id = ? AND backend = 'nomad' AND status = 'RUNNING' AND NOT EXISTS (SELECT 1 FROM execution_sessions WHERE job_id = ? AND desired != 'stop')", now(), job, job)
    }
    fun failIfActive(job: Long, error: String) = store.executionTransaction { db -> db.failActive(job, error, now()) }

    private fun Tx.failActive(job: Long, error: String, at: Long) {
        val changed = sqlUpdate("UPDATE jobs SET status = 'FAILED', error = ?, finished_at = ?, run_ms = run_ms + COALESCE(? - running_since, 0), running_since = NULL, local = 0, paused = 0 WHERE id = ? AND backend = 'nomad' AND status IN ('QUEUED', 'RUNNING') AND cancel_requested = 0", error, at, at, job)
        if (changed == 0) return
        sqlRows("SELECT * FROM iteration_attempts WHERE job_id = ? AND state IN $ACTIVE_ATTEMPTS", job, map = ::attemptRow)
            .forEach { invalidate(it, "failed", at) }
        sqlRows("SELECT $SESSION_COLUMNS FROM execution_sessions WHERE job_id = ? AND desired != 'stop'", job, map = ::sessionRow).forEach { session ->
            sqlUpdate("UPDATE execution_sessions SET desired = 'stop' WHERE id = ?", session.id)
            stopIntent(session)
        }
        sqlUpdate("UPDATE commands SET status = 'CANCELLED', host = NULL WHERE job_id = ? AND status IN ('QUEUED', 'RUNNING')", job)
        event(job, null, "failed:${error.take(1024)}", at)
    }
    fun finishIfComplete(job: Long): Boolean = store.executionTransaction { db ->
        db.sqlUpdate("UPDATE jobs SET status = 'DONE', finished_at = ?, run_ms = run_ms + COALESCE(? - running_since, 0), running_since = NULL, local = 0, paused = 0 WHERE id = ? AND backend = 'nomad' AND status IN ('QUEUED', 'RUNNING') AND cancel_requested = 0 AND EXISTS (SELECT 1 FROM commands WHERE job_id = ?) AND NOT EXISTS (SELECT 1 FROM commands c WHERE c.job_id = ? AND (c.status NOT IN ('DONE', 'FAILED') OR NOT EXISTS (SELECT 1 FROM execution_checks e WHERE e.job_id = c.job_id AND e.idx = c.idx))) AND NOT EXISTS (SELECT 1 FROM iteration_attempts a WHERE a.job_id = ? AND a.state = 'committed' AND NOT EXISTS (SELECT 1 FROM execution_ingestion i WHERE i.job_id = a.job_id AND i.iteration_id = a.iteration_id))", now(), now(), job, job, job, job) == 1
    }
    fun preparationSelection(owner: AttemptOwner, requestHash: String? = null): List<List<Problem>>? = store.executionRead { db ->
        val attempt = db.attempt(owner, now())
        require(attempt.iteration == "preparation" && attempt.state == AttemptState.PREPARING)
        db.sqlRows("SELECT request_hash, selected FROM execution_selections WHERE job_id = ? AND generation = ?", attempt.jobId, owner.generation) {
            if (requestHash != null && it.getString(1) != requestHash) throw ExecutionFenced("selection request changed")
            Json.decodeFromString<List<List<Problem>>>(it.getString(2))
        }.singleOrNull()
    }
    fun approvePreparationSelection(owner: AttemptOwner, requestHash: String, selected: List<List<Problem>>): List<List<Problem>> = store.executionTransaction { db ->
        val attempt = db.attempt(owner, now())
        require(attempt.iteration == "preparation" && attempt.state == AttemptState.PREPARING)
        val prior = db.sqlRows("SELECT request_hash, selected FROM execution_selections WHERE job_id = ? AND generation = ?", attempt.jobId, owner.generation) {
            if (it.getString(1) != requestHash) throw ExecutionFenced("selection request changed")
            Json.decodeFromString<List<List<Problem>>>(it.getString(2))
        }.singleOrNull()
        if (prior != null) return@executionTransaction prior
        db.sqlUpdate("INSERT INTO execution_selections (job_id, generation, request_hash, selected) VALUES (?, ?, ?, ?)", attempt.jobId, owner.generation, requestHash, Json.encodeToString(selected))
        db.event(attempt.jobId, owner.session, "selection-approved:${selected.sumOf { it.size }}", now())
        selected
    }
    fun acceptedPreparationSelection(owner: AttemptOwner): List<List<Problem>> = store.executionRead { db ->
        val attempt = db.findAttempt(owner) ?: throw ExecutionFenced("unknown preparation attempt")
        db.sqlRows("SELECT selected FROM execution_selections WHERE job_id = ? AND generation = ?", attempt.jobId, owner.generation) {
            Json.decodeFromString<List<List<Problem>>>(it.getString(1))
        }.singleOrNull() ?: throw ExecutionFenced("preparation selection was not approved")
    }

    fun nativeReservation(): Pair<Long, Long> = store.executionTransaction(::nativeReservation)

    // Only active jobs' sessions hold the Mac: an ended job's session left unstopped (its stop not yet observed) must not
    // keep every other job off it.
    private fun nativeReservation(db: Tx): Pair<Long, Long> =
        db.sqlRows("""SELECT COALESCE(SUM(json_extract(s.budget, '$.cpuMillis')),0), COALESCE(SUM(json_extract(s.budget, '$.memoryMiB')),0)
            FROM execution_sessions s JOIN jobs j ON j.id = s.job_id
            WHERE s.desired != 'stop' AND s.cpu_enforcement = ? AND j.status IN ('QUEUED', 'RUNNING')""".trimIndent(), CpuEnforcement.ADVISORY_NATIVE.stored) {
            it.getLong(1) to it.getLong(2)
        }.single()
    fun nativeOwnedByAnother(job: Long): Boolean = store.executionRead { db -> nativeOwnedByAnother(db, job) }

    private fun nativeOwnedByAnother(db: Tx, job: Long): Boolean =
        db.sqlRows("SELECT 1 FROM execution_sessions s JOIN jobs j ON j.id = s.job_id WHERE s.job_id != ? AND s.desired != 'stop' AND s.cpu_enforcement = ? AND j.status IN ('QUEUED', 'RUNNING') LIMIT 1",
            job, CpuEnforcement.ADVISORY_NATIVE.stored) { true }.isNotEmpty()

    fun queueRecipe(job: Long, proposed: NomadQueueSettings): NomadQueueSettings = store.executionTransaction { db ->
        val prior = db.sqlRows("SELECT settings FROM execution_queue_recipes WHERE job_id = ?", job) { it.getString(1) }.singleOrNull()
        if (prior != null) return@executionTransaction Json.decodeFromString(prior)
        require(store.job(job)?.backend == Backend.NOMAD)
        db.sqlUpdate("INSERT INTO execution_queue_recipes (job_id, settings) VALUES (?, ?)", job, Json.encodeToString(proposed))
        proposed
    }

    fun pendingDomainCompletion(): List<Long> = store.executionRead { db ->
        db.sqlRows("SELECT j.id FROM jobs j WHERE j.backend = 'nomad' AND j.status = 'DONE' AND NOT EXISTS (SELECT 1 FROM execution_events e WHERE e.job_id = j.id AND e.detail = 'domain-completed') ORDER BY j.id LIMIT 100") { it.getLong(1) }
    }
    fun domainCompleted(job: Long) = store.executionTransaction { db -> db.event(job, null, "domain-completed", now()) }

    fun checksComplete(job: Long): Set<Int> = store.executionRead { db -> db.sqlRows("SELECT idx FROM execution_checks WHERE job_id = ?", job) { it.getInt(1) }.toSet() }

    /** Rollback needs an external proof of stopped allocations; never expose as a backend toggle. */
    /** Whether [jobId]'s execution began: its recipe was frozen for admission, or a session was opened for it. */
    internal fun started(db: Tx, jobId: Long): Boolean =
        db.sqlRows("SELECT 1 FROM execution_queue_recipes WHERE job_id = ? UNION ALL SELECT 1 FROM execution_sessions WHERE job_id = ? LIMIT 1",
            jobId, jobId) { true }.isNotEmpty()

    /** Whether a session of [jobId] may still run: one not told to stop, or whose stop Nomad has not acknowledged. */
    internal fun unstopped(db: Tx, jobId: Long): Boolean =
        db.sqlRows("""SELECT 1 FROM execution_sessions s WHERE s.job_id = ? AND (s.desired != 'stop' OR NOT EXISTS (
            SELECT 1 FROM execution_outbox o WHERE o.id = s.id || ':stop' AND o.acknowledged = 1)) LIMIT 1""", jobId) { true }.isNotEmpty()

    internal fun cancelIn(db: Tx, jobId: Long, at: Long) {
        db.sqlRows("SELECT * FROM iteration_attempts WHERE job_id = ? AND state IN $ACTIVE_ATTEMPTS", jobId, map = ::attemptRow)
            .forEach { db.invalidate(it, "cancelled", at) }
        db.sqlRows("SELECT $SESSION_COLUMNS FROM execution_sessions WHERE job_id = ?", jobId, map = ::sessionRow).forEach { s ->
            db.sqlUpdate("UPDATE execution_sessions SET desired = 'stop' WHERE id = ?", s.id)
            db.stopIntent(s)
        }
        db.sqlUpdate("UPDATE jobs SET status = 'CANCELLED', cancel_requested = 1, finished_at = ?, run_ms = run_ms + COALESCE(? - running_since, 0), " +
            "running_since = NULL, local = 0, paused = 0 WHERE id = ?", at, at, jobId)
        db.sqlUpdate("UPDATE commands SET status = 'CANCELLED', host = NULL WHERE job_id = ? AND status IN ('QUEUED', 'RUNNING')", jobId)
        db.event(jobId, null, "cancelled", at)
    }

    private fun Tx.stopIntent(s: ExecutionSession) {
        sqlUpdate("INSERT OR IGNORE INTO execution_outbox (id, job_id, session_id, operation, payload) VALUES (?, ?, ?, 'stop', ?)", "${s.id}:stop", s.jobId, s.id, s.nomadJobId)
    }
    private fun Tx.session(id: String) = sqlRows("SELECT $SESSION_COLUMNS FROM execution_sessions WHERE id = ?", id, map = ::sessionRow).singleOrNull()
    private fun Tx.authenticate(id: String, token: String, allocation: String? = null): ExecutionSession {
        val s = session(id) ?: throw ExecutionFenced("unknown session")
        val hash = sqlRows("SELECT token_hash FROM execution_sessions WHERE id = ?", id) { it.getString(1) }.single()
        if (!MessageDigest.isEqual(hash.toByteArray(), digest(token).toByteArray())) throw ExecutionFenced("invalid credential")
        if (s.desired == Desired.STOP || (allocation != null && s.allocation != allocation)) throw ExecutionFenced("session fenced")
        val job = store.job(s.jobId)!!
        if (job.backend != Backend.NOMAD || job.cancelRequested || job.status.isTerminal) throw ExecutionFenced("experiment fenced")
        return s
    }
    private fun Tx.findAttempt(o: AttemptOwner): Attempt? {
        val a = sqlRows("SELECT * FROM iteration_attempts WHERE session_id = ? AND allocation_id = ? AND iteration_id = ? AND generation = ?", o.session, o.allocation, o.iteration, o.generation, map = ::attemptRow).singleOrNull() ?: return null
        if (!MessageDigest.isEqual(a.tokenHash.toByteArray(), digest(o.token).toByteArray())) throw ExecutionFenced("invalid attempt credential")
        return a
    }
    private fun Tx.attempt(o: AttemptOwner, at: Long): Attempt {
        val a = findAttempt(o) ?: throw ExecutionFenced("unknown attempt")
        val s = session(o.session) ?: throw ExecutionFenced("unknown session")
        val job = store.job(a.jobId)!!
        if (s.desired == Desired.STOP || s.allocation != o.allocation || !a.state.isActive || a.leaseUntil <= at || job.cancelRequested || job.backend != Backend.NOMAD || job.status !in setOf(Status.RUNNING, Status.QUEUED)) throw ExecutionFenced("attempt fenced")
        return a
    }
    private fun Tx.expire(jobId: Long, at: Long) {
        sqlRows("SELECT * FROM iteration_attempts WHERE job_id = ? AND state IN $ACTIVE_ATTEMPTS AND lease_until <= ?", jobId, at, map = ::attemptRow)
            .forEach { a ->
                invalidate(a, "lost", at)
                // Expired credentials cannot bind again and fetch a fresh iteration.
                val s = session(a.session)!!
                sqlUpdate("UPDATE execution_sessions SET desired = 'stop', observation = 'lease-expired' WHERE id = ?", a.session)
                event(jobId, a.session, "lost:infrastructure:lease-expired", at)
                stopIntent(s)
            }
    }
    private fun Tx.invalidate(a: Attempt, state: String, at: Long) {
        sqlUpdate("UPDATE iteration_attempts SET state = ?, finished_at = ? WHERE job_id = ? AND iteration_id = ? AND generation = ?", state, at, a.jobId, a.iteration, a.generation)
        for (index in a.indices) {
            sqlUpdate("UPDATE commands SET status = 'QUEUED', host = NULL, exit_code = NULL, started_at = NULL, finished_at = NULL, phase = NULL WHERE job_id = ? AND idx = ?", a.jobId, index)
            sqlUpdate("UPDATE cases SET record = NULL, lab_check = NULL, stdout = NULL WHERE job_id = ? AND idx = ?", a.jobId, index)
        }
        event(a.jobId, a.session, "$state:${a.iteration}:${a.generation}", at)
    }
    private data class IterationRow(val index: Int, val status: String, val host: String?, val key: String)
    private fun Tx.iterations(jobId: Long): Map<String, List<IterationRow>> = sqlRows(
        "SELECT m.idx, m.status, m.host, c.problem_idx, c.seed, c.repeat FROM commands m LEFT JOIN cases c ON c.job_id = m.job_id AND c.idx = m.idx WHERE m.job_id = ? ORDER BY m.idx", jobId,
    ) { r ->
        val key = if (r.getObject("problem_idx") == null) "command:${r.getInt("idx")}" else "${r.getInt("problem_idx")}:${r.getObject("seed") ?: "none"}:${r.getInt("repeat")}"
        IterationRow(r.getInt("idx"), r.getString("status"), r.getString("host"), key)
    }.groupBy { it.key }
    private fun Tx.event(job: Long, session: String?, detail: String, at: Long) = sqlUpdate("INSERT INTO execution_events (job_id, session_id, detail, at) VALUES (?, ?, ?, ?)", job, session, detail, at)

    companion object {
        const val LEASE_MS = 90_000L
        /** The iteration id of a job's preparation: the one accepted result that carries a [PreparationResult]. */
        const val PREPARATION = "preparation"
        private val ACTIVE_ATTEMPTS = AttemptState.ACTIVE_SQL
        private val random = SecureRandom()
        fun token(): String = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        internal fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        private fun claimToken(secret: String, iteration: String, generation: Int): String {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            return mac.doFinal("$iteration:$generation".toByteArray()).joinToString("") { "%02x".format(it) }
        }
        /** What [sessionRow] reads. Sessions are scanned on every coordinator pass; `SELECT *` would also read each manifest. */
        private const val SESSION_COLUMNS = "id, job_id, generation, execution_class, release, budget, nomad_job_id, desired, allocation_id, node_id"
        private fun sessionRow(r: ResultSet) = ExecutionSession(r.getString("id"), r.getLong("job_id"), r.getInt("generation"), ExecutionClass.of(r.getString("execution_class")), r.getString("release"), Json.decodeFromString(r.getString("budget")), r.getString("nomad_job_id"), Desired.of(r.getString("desired")), r.getString("allocation_id"), r.getString("node_id"))
        private data class Attempt(val jobId: Long, val iteration: String, val generation: Int, val session: String, val indices: List<Int>, val state: AttemptState, val leaseUntil: Long, val tokenHash: String, val result: String?, val parallel: Int)
        private fun attemptRow(r: ResultSet) = Attempt(r.getLong("job_id"), r.getString("iteration_id"), r.getInt("generation"), r.getString("session_id"), Json.decodeFromString<List<Int>>(r.getString("indices")), AttemptState.of(r.getString("state")), r.getLong("lease_until"), r.getString("token_hash"), r.getString("result"), r.getInt("parallel_limit"))
        /** API and coordinator may initialize the same database concurrently. A column snapshot is only a
         * hint: another autocommit connection can publish the ALTER before ours. Recheck the schema after a
         * failed ALTER, accepting only the now-present column rather than hiding unrelated SQL failures. */
        private fun addColumn(db: Connection, table: String, column: String, definition: String, known: Set<String>) {
            if (column in known) return
            try {
                db.createStatement().use { it.execute("ALTER TABLE $table ADD COLUMN $column $definition") }
            } catch (failure: SQLException) {
                val present = try {
                    db.createStatement().use { statement ->
                        statement.executeQuery("PRAGMA table_info($table)").use { rows ->
                            var found = false
                            while (rows.next()) if (rows.getString("name") == column) found = true
                            found
                        }
                    }
                } catch (recheck: SQLException) {
                    failure.addSuppressed(recheck)
                    throw failure
                }
                if (!present) throw failure
            }
        }
        internal fun migrate(db: Connection) {
            db.createStatement().use { s ->
                s.execute("""CREATE TABLE IF NOT EXISTS execution_sessions (id TEXT PRIMARY KEY, job_id INTEGER NOT NULL, generation INTEGER NOT NULL, execution_class TEXT NOT NULL, release TEXT NOT NULL, budget TEXT NOT NULL, nomad_job_id TEXT NOT NULL UNIQUE, desired TEXT NOT NULL, token_hash TEXT NOT NULL, allocation_id TEXT, node_id TEXT, created_at INTEGER NOT NULL, observed_at INTEGER, observation TEXT)""")
                s.execute("""CREATE TABLE IF NOT EXISTS iteration_attempts (job_id INTEGER NOT NULL, iteration_id TEXT NOT NULL, generation INTEGER NOT NULL, session_id TEXT NOT NULL, allocation_id TEXT NOT NULL, token_hash TEXT NOT NULL, lease_until INTEGER NOT NULL, state TEXT NOT NULL, indices TEXT NOT NULL, started_at INTEGER NOT NULL, finished_at INTEGER, result TEXT, PRIMARY KEY (job_id, iteration_id, generation))""")
                val attemptColumns = s.executeQuery("PRAGMA table_info(iteration_attempts)").use { r -> buildSet { while (r.next()) add(r.getString("name")) } }
                addColumn(db, "iteration_attempts", "preparation_progress", "TEXT", attemptColumns)
                addColumn(db, "iteration_attempts", "preparation_caches", "TEXT", attemptColumns)
                addColumn(db, "iteration_attempts", "preparation_updated_at", "INTEGER", attemptColumns)
                addColumn(db, "iteration_attempts", "parallel_limit", "INTEGER NOT NULL DEFAULT 1", attemptColumns)
                s.execute("CREATE UNIQUE INDEX IF NOT EXISTS active_iteration ON iteration_attempts (job_id, iteration_id) WHERE state IN $ACTIVE_ATTEMPTS")
                s.execute("CREATE UNIQUE INDEX IF NOT EXISTS active_session_iteration ON iteration_attempts (session_id) WHERE state IN $ACTIVE_ATTEMPTS")
                s.execute("CREATE TABLE IF NOT EXISTS execution_outbox (id TEXT PRIMARY KEY, job_id INTEGER NOT NULL, session_id TEXT NOT NULL, operation TEXT NOT NULL, payload TEXT NOT NULL, acknowledged INTEGER NOT NULL DEFAULT 0)")
                s.execute("CREATE TABLE IF NOT EXISTS execution_selections (job_id INTEGER NOT NULL, generation INTEGER NOT NULL, request_hash TEXT NOT NULL, selected TEXT NOT NULL, PRIMARY KEY (job_id, generation))")
                s.execute("CREATE TABLE IF NOT EXISTS execution_queue_recipes (job_id INTEGER PRIMARY KEY, settings TEXT NOT NULL)")
                s.execute("CREATE TABLE IF NOT EXISTS execution_events (id INTEGER PRIMARY KEY AUTOINCREMENT, job_id INTEGER NOT NULL, session_id TEXT, detail TEXT NOT NULL, at INTEGER NOT NULL)")
                val columns = s.executeQuery("PRAGMA table_info(execution_sessions)").use { r -> buildSet { while (r.next()) add(r.getString("name")) } }
                addColumn(db, "execution_sessions", "manifest", "TEXT", columns)
                s.execute("CREATE TABLE IF NOT EXISTS capacity_leases (id TEXT PRIMARY KEY, pool TEXT NOT NULL, nodes INTEGER NOT NULL, expires_at INTEGER NOT NULL)")
                s.execute("CREATE TABLE IF NOT EXISTS execution_ingestion (job_id INTEGER NOT NULL, iteration_id TEXT NOT NULL, PRIMARY KEY (job_id, iteration_id))")
                s.execute("CREATE TABLE IF NOT EXISTS execution_checks (job_id INTEGER NOT NULL, idx INTEGER NOT NULL, detail TEXT NOT NULL, PRIMARY KEY (job_id, idx))")
                s.execute("CREATE INDEX IF NOT EXISTS session_experiment ON execution_sessions (job_id)")
            }
        }
    }
}

