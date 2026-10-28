package com.eignex.lab

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet

@Serializable
data class ExecutionSession(
    val id: String, val jobId: Long, val generation: Int, val executionClass: String, val release: String,
    val budget: ResourceBudget, val nomadJobId: String, val desired: String, val allocation: String?, val node: String?,
)

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
data class ExecutionIntent(val id: String, val jobId: Long, val session: String, val operation: String, val payload: String, val acknowledged: Boolean)

@Serializable data class LiveLog(val session: String, val iteration: String, val generation: Int)
@Serializable data class ExecutionEvent(val id: Long, val session: String?, val detail: String, val at: Long)

class ExecutionFenced(message: String) : IllegalStateException(message)

/** All worker mutations share Store's IMMEDIATE write transaction, including cancellation and case projection. */
class ExecutionStore internal constructor(private val store: Store) {
    fun events(job: Long): List<ExecutionEvent> = store.executionTransaction { db ->
        db.sqlRows("SELECT * FROM execution_events WHERE job_id = ? ORDER BY id DESC LIMIT 5000", job) {
            ExecutionEvent(it.getLong("id"), it.getString("session_id"), it.getString("detail"), it.getLong("at"))
        }.reversed()
    }
    fun liveLog(job: Long, index: Int, at: Long = now()): LiveLog? = store.executionTransaction { db ->
        db.sqlRows("SELECT a.* FROM iteration_attempts a JOIN execution_sessions s ON s.id = a.session_id WHERE a.job_id = ? AND a.state IN $ACTIVE_ATTEMPTS AND a.lease_until > ? AND s.desired != 'stop' AND s.allocation_id = a.allocation_id", job, at, map = ::attemptRow)
            .singleOrNull { index in it.indices }?.let { LiveLog(it.session, it.iteration, it.generation) }
    }
    fun capacityLeases(at: Long = now()): List<ManualCapacityLease> = store.executionTransaction { db ->
        db.sqlRows("SELECT * FROM capacity_leases WHERE expires_at > ? ORDER BY id", at) { ManualCapacityLease(it.getString("id"), it.getString("pool"), it.getInt("nodes"), it.getLong("expires_at")) }
    }
    fun requestCapacity(pool: String, nodes: Int, ttlMs: Long, at: Long = now()): ManualCapacityLease = store.executionTransaction { db ->
        require(nodes > 0 && ttlMs in 1..3_600_000)
        val lease = ManualCapacityLease(java.util.UUID.randomUUID().toString(), pool, nodes, at + ttlMs)
        db.sqlUpdate("INSERT INTO capacity_leases (id, pool, nodes, expires_at) VALUES (?, ?, ?, ?)", lease.id, lease.pool, lease.nodes, lease.expiresAt)
        lease
    }
    fun releaseCapacity(id: String): Boolean = store.executionTransaction { it.sqlUpdate("DELETE FROM capacity_leases WHERE id = ?", id) == 1 }

    fun session(id: String): ExecutionSession? = store.executionTransaction { it.session(id) }
    fun reports(job: Long): List<ExecutionSessionReport> = store.executionTransaction { db ->
        db.sqlRows("SELECT * FROM execution_sessions WHERE job_id = ? ORDER BY created_at, id", job) { row ->
            val s = sessionRow(row)
            val active = db.sqlRows("SELECT state, generation FROM iteration_attempts WHERE session_id = ? ORDER BY generation DESC, started_at DESC LIMIT 1", s.id) { it.getString(1) to it.getInt(2) }.singleOrNull()
            val done = db.sqlRows("SELECT COUNT(*) FROM iteration_attempts WHERE session_id = ? AND state = 'committed'", s.id) { it.getInt(1) }.single()
            val observed = row.getObject("observed_at")?.let { row.getLong("observed_at") }
            ExecutionSessionReport(s, active?.first, active?.second, done, row.getString("observation"), observed)
        }
    }
    fun sessions(jobId: Long? = null): List<ExecutionSession> = store.executionTransaction { db ->
        db.sqlRows("SELECT * FROM execution_sessions" + if (jobId == null) " ORDER BY id" else " WHERE job_id = ? ORDER BY id",
            *listOfNotNull(jobId).toTypedArray(), map = ::sessionRow)
    }

    /** Current assignments, including preparation before any case has started; stopped generations are omitted. */
    internal fun runningNodes(jobs: Collection<Long>): Map<Long, List<RunningNode>> {
        if (jobs.isEmpty()) return emptyMap()
        return store.executionTransaction { db ->
            db.sqlRows("""SELECT s.job_id, s.node_id, s.allocation_id, o.payload
                FROM execution_sessions s JOIN execution_outbox o ON o.id = s.id || ':submit'
                WHERE s.job_id IN (${jobs.joinToString(",") { "?" }}) AND s.desired != 'stop' AND s.node_id IS NOT NULL
                ORDER BY s.job_id, s.node_id""", *jobs.toTypedArray()) { row ->
                row.getLong("job_id") to runningNode(row.getString("node_id"), row.getString("allocation_id"), row.getString("payload"))
            }.groupBy({ it.first }, { it.second })
        }
    }

    /** The coordinator chooses the credential before persisting intent; a retry must supply identical inputs. */
    fun createSession(id: String, jobId: Long, executionClass: String, release: String, budget: ResourceBudget,
                      token: String, payload: String, at: Long = now(), manifest: String? = null): ExecutionSession = store.executionTransaction { db ->
        require(id.matches(Regex("[a-z0-9-]{1,64}")) && executionClass.matches(Regex("[a-z][a-z0-9-]{0,31}")))
        require(release.isNotBlank() && token.length >= 32)
        val job = store.job(jobId) ?: error("missing experiment")
        require(job.backend == "nomad" && !job.cancelRequested && job.status in setOf(Status.QUEUED, Status.RUNNING))
        val nomadId = "lab-$jobId-$executionClass-$id-1"
        db.session(id)?.let { old ->
            require(old.jobId == jobId && old.executionClass == executionClass && old.release == release && old.budget == budget)
            require(db.sqlRows("SELECT token_hash FROM execution_sessions WHERE id = ?", id) { it.getString(1) }.single() == digest(token))
            require(db.sqlRows("SELECT payload FROM execution_outbox WHERE id = ?", "$id:submit") { it.getString(1) }.single() == payload)
            return@executionTransaction old
        }
        val native = db.sqlRows("SELECT json_extract(?, '$.Meta.lab_cpu_enforcement')", payload) { it.getString(1) }.single() == "advisory-native"
        if (native) {
            val limit = db.sqlRows("SELECT json_extract(?, '$.Meta.lab_native_cpu_limit'), json_extract(?, '$.Meta.lab_native_memory_limit')", payload, payload) {
                if (it.getObject(1) == null || it.getObject(2) == null) null else it.getLong(1) to it.getLong(2)
            }.single()
            if (limit == null) check(!nativeOwnedByAnother(db, jobId)) { "native execution is reserved by another experiment" }
            else {
                val held = nativeReservation(db)
                check(held.first + budget.cpuMillis <= limit.first && held.second + budget.memoryMiB <= limit.second) { "native CPU or memory budget exhausted" }
            }
        }
        db.sqlUpdate("INSERT INTO execution_sessions (id, job_id, generation, execution_class, release, budget, nomad_job_id, desired, token_hash, created_at) VALUES (?, ?, 1, ?, ?, ?, ?, 'run', ?, ?)",
            id, jobId, executionClass, release, Json.encodeToString(budget), nomadId, digest(token), at)
        db.sqlUpdate("INSERT INTO execution_outbox (id, job_id, session_id, operation, payload) VALUES (?, ?, ?, 'submit', ?)", "$id:submit", jobId, id, payload)
        db.sqlUpdate("UPDATE execution_sessions SET manifest = ? WHERE id = ?", manifest, id)
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
            if (store.job(session.jobId)!!.paused || session.desired != "run") return@executionTransaction null
            // An HTTP retry of a claim returns the existing ownership, never a second concurrent iteration.
            val active = db.sqlRows("SELECT * FROM iteration_attempts WHERE session_id = ? AND state IN $ACTIVE_ATTEMPTS ORDER BY generation DESC", id) { attemptRow(it) }.singleOrNull()
            if (active != null) {
                // Claim tokens are derived from the scoped session secret; only their digest is persisted.
                val claimToken = claimToken(token, active.iteration, active.generation)
                return@executionTransaction IterationClaim(AttemptOwner(id, allocation, active.iteration, active.generation, claimToken), active.indices, active.leaseUntil, active.parallel)
            }
            if (session.executionClass == "prepare") {
                if (store.job(session.jobId)!!.setupDone || db.sqlRows("SELECT 1 FROM iteration_attempts WHERE job_id = ? AND iteration_id = 'preparation' AND state = 'committed'", session.jobId) { it.getInt(1) }.isNotEmpty()) return@executionTransaction null
                val iteration = "preparation"
                val generation = db.sqlRows("SELECT COALESCE(MAX(generation), 0) + 1 FROM iteration_attempts WHERE job_id = ? AND iteration_id = ?", session.jobId, iteration) { it.getInt(1) }.single()
                val claimToken = claimToken(token, iteration, generation)
                db.sqlUpdate("INSERT INTO iteration_attempts (job_id, iteration_id, generation, session_id, allocation_id, token_hash, lease_until, state, indices, started_at, parallel_limit) VALUES (?, ?, ?, ?, ?, ?, ?, 'claimed', '[]', ?, 1)",
                    session.jobId, iteration, generation, id, allocation, digest(claimToken), at + leaseMs, at)
                db.event(session.jobId, id, "claimed:$iteration:$generation", at)
                return@executionTransaction IterationClaim(AttemptOwner(id, allocation, iteration, generation, claimToken), emptyList(), at + leaseMs)
            }
            val next = db.iterations(session.jobId).entries.firstOrNull { (_, rows) -> rows.all { it.status == "QUEUED" && it.host == null } }
                ?: return@executionTransaction null
            val iteration = next.key
            val generation = db.sqlRows("SELECT COALESCE(MAX(generation), 0) + 1 FROM iteration_attempts WHERE job_id = ? AND iteration_id = ?", session.jobId, iteration) { it.getInt(1) }.single()
            val claimToken = claimToken(token, iteration, generation)
            val indices = next.value.map { it.index }
            val placement = Json.parseToJsonElement(db.sqlRows("SELECT payload FROM execution_outbox WHERE id = ?", "$id:submit") { it.getString(1) }.single()).jsonObject
            val perHost = placement["Meta"]?.jsonObject?.get("lab_per_host_parallel")?.jsonPrimitive?.intOrNull
            val heldParallel = if (perHost == null) 0 else db.sqlRows("SELECT COALESCE(SUM(a.parallel_limit), 0) FROM iteration_attempts a JOIN execution_sessions s ON s.id = a.session_id WHERE a.job_id = ? AND s.node_id = ? AND a.state IN $ACTIVE_ATTEMPTS", session.jobId, session.node) { it.getInt(1) }.single()
            val available = perHost?.let { (it - heldParallel).coerceAtLeast(0) } ?: session.budget.parallel
            val parallel = minOf(session.budget.parallel, indices.size, available)
            if (parallel == 0) return@executionTransaction null
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

    fun remainingRunMs(session: String, at: Long = now()): Long? = store.executionTransaction { db ->
        val job = store.job(requireNotNull(db.session(session)).jobId) ?: throw ExecutionFenced("missing experiment")
        job.runRemainingMs(at)
    }

    private fun Job.runRemainingMs(at: Long): Long? = experiment?.profile?.let(Experiments::profileRunTimeoutSec)?.let { it * 1000 - elapsedMs(at) }

    fun runTimedOut(owner: AttemptOwner, at: Long = now()) = store.executionTransaction { db ->
        val prior = db.findAttempt(owner) ?: throw ExecutionFenced("unknown attempt")
        if (store.job(prior.jobId)?.error == ProfileRunTimeout().message) return@executionTransaction
        val attempt = db.attempt(owner, at)
        require(store.job(attempt.jobId)?.experiment?.profile?.runTimeoutSec != null)
        db.failRunDeadline(attempt.jobId, at)
    }

    fun expireRunDeadlines(at: Long = now()) = store.executionTransaction { db ->
        store.active(withCommands = false).filter { it.backend == "nomad" }.forEach { job ->
            val remaining = job.runRemainingMs(at)
            if (remaining != null && (remaining <= 0 || db.sqlRows("SELECT result FROM iteration_attempts WHERE job_id = ? AND state = 'committed'", job.id) {
                Json.decodeFromString<IterationResult>(it.getString(1)).runTimedOut
            }.any { it })) db.failRunDeadline(job.id, at)
        }
    }

    private fun Connection.failRunDeadline(job: Long, at: Long) {
        sqlRows("SELECT * FROM iteration_attempts WHERE job_id = ? AND state IN $ACTIVE_ATTEMPTS", job, map = ::attemptRow)
            .forEach { invalidate(it, "run-timeout", at) }
        sqlRows("SELECT * FROM execution_sessions WHERE job_id = ?", job, map = ::sessionRow).forEach { s ->
            sqlUpdate("UPDATE execution_sessions SET desired = 'stop' WHERE id = ?", s.id); stopIntent(s)
        }
        sqlUpdate("UPDATE commands SET status = 'CANCELLED', host = NULL WHERE job_id = ? AND status IN ('QUEUED', 'RUNNING')", job)
        sqlUpdate("UPDATE jobs SET status = 'FAILED', error = ?, finished_at = ?, run_ms = run_ms + COALESCE(? - running_since, 0), running_since = NULL WHERE id = ?",
            ProfileRunTimeout().message, at, at, job)
        event(job, null, "profile-run-timeout", at)
    }

    fun phase(owner: AttemptOwner, phase: String, at: Long = now()) = store.executionTransaction { db ->
        require(phase in listOf("preparing", "executing", "uploading"))
        val a = db.attempt(owner, at)
        val states = listOf("claimed", "preparing", "executing", "uploading")
        require(states.indexOf(phase) >= states.indexOf(a.state)) { "phase cannot go backwards" }
        db.sqlUpdate("UPDATE iteration_attempts SET state = ? WHERE job_id = ? AND iteration_id = ? AND generation = ?", phase, a.jobId, owner.iteration, owner.generation)
        db.event(a.jobId, owner.session, "phase:$phase:${owner.iteration}:${owner.generation}", at)
    }

    /** A source/build error is permanent work failure, not another infrastructure revocation. */
    fun rejectBuild(owner: AttemptOwner, detail: String, at: Long = now()) = store.executionTransaction { db ->
        val reason = "source-build: " + detail.take(16_384)
        val prior = db.findAttempt(owner) ?: throw ExecutionFenced("unknown attempt")
        if (store.job(prior.jobId)?.let { it.status == Status.FAILED && it.error == reason } == true) return@executionTransaction
        val attempt = db.attempt(owner, at)
        require(attempt.state in setOf("claimed", "preparing")) { "build failure outside preparation" }
        db.sqlRows("SELECT * FROM iteration_attempts WHERE job_id = ? AND state IN $ACTIVE_ATTEMPTS", attempt.jobId, map = ::attemptRow)
            .forEach { db.invalidate(it, "source-build-failed", at) }
        db.sqlRows("SELECT * FROM execution_sessions WHERE job_id = ?", attempt.jobId, map = ::sessionRow).forEach { session ->
            db.sqlUpdate("UPDATE execution_sessions SET desired = 'stop' WHERE id = ?", session.id); db.stopIntent(session)
        }
        db.sqlUpdate("UPDATE jobs SET status = 'FAILED', error = ?, finished_at = ?, run_ms = run_ms + COALESCE(? - running_since, 0), running_since = NULL WHERE id = ?", reason, at, at, attempt.jobId)
        db.sqlUpdate("UPDATE commands SET status = 'CANCELLED', host = NULL WHERE job_id = ? AND status IN ('QUEUED', 'RUNNING')", attempt.jobId)
        db.event(attempt.jobId, owner.session, "source-build-failed", at)
    }

    fun caseStarted(owner: AttemptOwner, index: Int, at: Long = now()) = store.executionTransaction { db ->
        val a = db.attempt(owner, at); require(index in a.indices && a.state == "executing") { "case start outside executing iteration" }
        db.sqlUpdate("UPDATE commands SET status = 'RUNNING', started_at = COALESCE(started_at, ?) WHERE job_id = ? AND idx = ?", at, a.jobId, index)
        db.event(a.jobId, owner.session, "case-start:$index:${owner.generation}", at)
    }

    /** Artifacts must have been checksum-verified before this transaction. A lost acknowledgement is idempotent. */
    internal fun commit(owner: AttemptOwner, result: IterationResult, at: Long = now()): Boolean = store.executionTransaction { db ->
        val a = db.findAttempt(owner) ?: throw ExecutionFenced("unknown attempt")
        if (a.state == "committed") {
            if (a.result != Json.encodeToString(result)) throw ExecutionFenced("result differs from accepted manifest")
            return@executionTransaction true
        }
        db.attempt(owner, at)
        if (store.job(a.jobId)!!.runRemainingMs(at)?.let { it <= 0 } == true) throw ProfileRunTimeout()
        require((a.iteration == "preparation") == (result.preparation != null))
        require(!result.runTimedOut || store.job(a.jobId)?.experiment?.profile?.runTimeoutSec != null)
        require(a.state == "uploading") { "attempt is not uploading" }
        require(result.cases.map { it.index }.sorted() == a.indices.sorted()) { "all arms must be committed together, exactly once" }
        val node = db.session(owner.session)!!.node!!
        for (case in result.cases) {
            case.record?.let { Json.parseToJsonElement(it) }
            db.sqlUpdate("UPDATE commands SET status = ?, exit_code = ?, started_at = ?, finished_at = ?, phase = NULL, host = ?, cmd = COALESCE(?, cmd) WHERE job_id = ? AND idx = ?",
                if (case.exitCode == 0) "DONE" else "FAILED", case.exitCode, case.startedAt, case.finishedAt, node, case.command, a.jobId, case.index)
            db.sqlUpdate("UPDATE cases SET record = ?, lab_check = NULL WHERE job_id = ? AND idx = ?", case.record?.let(store::storedRecord), a.jobId, case.index)
        }
        db.sqlUpdate("UPDATE iteration_attempts SET state = 'committed', result = ?, finished_at = ? WHERE job_id = ? AND iteration_id = ? AND generation = ?",
            Json.encodeToString(result), at, a.jobId, owner.iteration, owner.generation)
        db.event(a.jobId, owner.session, "committed:${owner.iteration}:${owner.generation}", at)
        if (result.runTimedOut) db.failRunDeadline(a.jobId, at)
        true
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
        if (s.desired != "run" || s.allocation != null) return@executionTransaction false
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
            ExecutionIntent(it.getString("id"), it.getLong("job_id"), it.getString("session_id"), it.getString("operation"), it.getString("payload"), false)
        }
    }
    fun acknowledge(id: String) = store.executionTransaction { it.sqlUpdate("UPDATE execution_outbox SET acknowledged = 1 WHERE id = ?", id) }


    fun credential(id: String, token: String, allocation: String? = null): ExecutionSession = store.executionTransaction { db ->
        val s = db.session(id) ?: throw ExecutionFenced("unknown session")
        val hash = db.sqlRows("SELECT token_hash FROM execution_sessions WHERE id = ?", id) { it.getString(1) }.single()
        if (!MessageDigest.isEqual(hash.toByteArray(), digest(token).toByteArray()) || (allocation != null && s.allocation != null && s.allocation != allocation)) throw ExecutionFenced("invalid session credential")
        s
    }
    fun authorize(owner: AttemptOwner, at: Long = now()): Long = store.executionTransaction { db -> db.attempt(owner, at).jobId }
    fun manifest(id: String): WorkManifest = store.executionTransaction { db ->
        Json.decodeFromString(db.sqlRows("SELECT manifest FROM execution_sessions WHERE id = ?", id) { it.getString(1) }.single())
    }
    fun drain(id: String) = store.executionTransaction { db -> db.sqlUpdate("UPDATE execution_sessions SET desired = 'drain' WHERE id = ? AND desired = 'run'", id) }
    fun accepted(jobId: Long): List<Pair<String, IterationResult>> = store.executionTransaction { db ->
        db.sqlRows("SELECT iteration_id, result FROM iteration_attempts WHERE job_id = ? AND state = 'committed' ORDER BY iteration_id", jobId) { it.getString(1) to Json.decodeFromString<IterationResult>(it.getString(2)) }
    }
    fun submission(id: String): String = store.executionTransaction { db -> db.sqlRows("SELECT payload FROM execution_outbox WHERE id = ?", "$id:submit") { it.getString(1) }.single() }
    fun hasActiveAttempt(session: String): Boolean = store.executionTransaction { db -> db.sqlRows("SELECT 1 FROM iteration_attempts WHERE session_id = ? AND state IN $ACTIVE_ATTEMPTS", session) { true }.isNotEmpty() }
    internal fun retryIn(db: Connection, job: Long, at: Long) { db.event(job, null, "manual-retry", at) }
    /** Diagnostic loss count since manual retry; infrastructure losses never exhaust the experiment. */
    fun failureCount(job: Long): Int = store.executionTransaction { db -> db.sqlRows("""SELECT COUNT(*) FROM execution_events
        WHERE job_id = ? AND detail LIKE 'lost:infrastructure:%' AND id > COALESCE(
            (SELECT MAX(id) FROM execution_events WHERE job_id = ? AND detail = 'manual-retry'), 0)""", job, job) { it.getInt(1) }.single() }
    fun pendingProjectionJobs(): List<Long> = store.executionTransaction { db -> db.sqlRows("""
        SELECT DISTINCT a.job_id FROM iteration_attempts a JOIN jobs j ON j.id = a.job_id
        WHERE a.state = 'committed' AND j.backend = 'nomad' AND (
          NOT EXISTS (SELECT 1 FROM execution_ingestion i WHERE i.job_id = a.job_id AND i.iteration_id = a.iteration_id)
          OR EXISTS (SELECT 1 FROM commands c WHERE c.job_id = a.job_id AND c.status IN ('DONE', 'FAILED')
            AND NOT EXISTS (SELECT 1 FROM execution_checks e WHERE e.job_id = c.job_id AND e.idx = c.idx)))
        ORDER BY a.job_id
    """.trimIndent()) { it.getLong(1) } }
    fun ingested(job: Long): Set<String> = store.executionTransaction { db -> db.sqlRows("SELECT iteration_id FROM execution_ingestion WHERE job_id = ?", job) { it.getString(1) }.toSet() }
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
    fun failIfActive(job: Long, error: String) = store.executionTransaction { db ->
        val at = now()
        val changed = db.sqlUpdate("UPDATE jobs SET status = 'FAILED', error = ?, finished_at = ?, run_ms = run_ms + COALESCE(? - running_since, 0), running_since = NULL WHERE id = ? AND backend = 'nomad' AND status IN ('QUEUED', 'RUNNING') AND cancel_requested = 0", error, at, at, job)
        if (changed == 0) return@executionTransaction
        db.sqlRows("SELECT * FROM iteration_attempts WHERE job_id = ? AND state IN $ACTIVE_ATTEMPTS", job, map = ::attemptRow)
            .forEach { db.invalidate(it, "failed", at) }
        db.sqlRows("SELECT * FROM execution_sessions WHERE job_id = ? AND desired != 'stop'", job, map = ::sessionRow).forEach { session ->
            db.sqlUpdate("UPDATE execution_sessions SET desired = 'stop' WHERE id = ?", session.id)
            db.stopIntent(session)
        }
        db.sqlUpdate("UPDATE commands SET status = 'CANCELLED', host = NULL WHERE job_id = ? AND status IN ('QUEUED', 'RUNNING')", job)
        db.event(job, null, "failed:${error.take(1024)}", at)
    }
    fun finishIfComplete(job: Long): Boolean = store.executionTransaction { db ->
        db.sqlUpdate("UPDATE jobs SET status = 'DONE', finished_at = ?, run_ms = run_ms + COALESCE(? - running_since, 0), running_since = NULL WHERE id = ? AND backend = 'nomad' AND status IN ('QUEUED', 'RUNNING') AND cancel_requested = 0 AND EXISTS (SELECT 1 FROM commands WHERE job_id = ?) AND NOT EXISTS (SELECT 1 FROM commands c WHERE c.job_id = ? AND (c.status NOT IN ('DONE', 'FAILED') OR NOT EXISTS (SELECT 1 FROM execution_checks e WHERE e.job_id = c.job_id AND e.idx = c.idx))) AND NOT EXISTS (SELECT 1 FROM iteration_attempts a WHERE a.job_id = ? AND a.state = 'committed' AND NOT EXISTS (SELECT 1 FROM execution_ingestion i WHERE i.job_id = a.job_id AND i.iteration_id = a.iteration_id))", now(), now(), job, job, job, job) == 1
    }
    fun preparationSelection(owner: AttemptOwner, requestHash: String? = null): List<List<Problem>>? = store.executionTransaction { db ->
        val attempt = db.attempt(owner, now())
        require(attempt.iteration == "preparation" && attempt.state == "preparing")
        db.sqlRows("SELECT request_hash, selected FROM execution_selections WHERE job_id = ? AND generation = ?", attempt.jobId, owner.generation) {
            if (requestHash != null && it.getString(1) != requestHash) throw ExecutionFenced("selection request changed")
            Json.decodeFromString<List<List<Problem>>>(it.getString(2))
        }.singleOrNull()
    }
    fun approvePreparationSelection(owner: AttemptOwner, requestHash: String, selected: List<List<Problem>>): List<List<Problem>> = store.executionTransaction { db ->
        val attempt = db.attempt(owner, now())
        require(attempt.iteration == "preparation" && attempt.state == "preparing")
        val prior = db.sqlRows("SELECT request_hash, selected FROM execution_selections WHERE job_id = ? AND generation = ?", attempt.jobId, owner.generation) {
            if (it.getString(1) != requestHash) throw ExecutionFenced("selection request changed")
            Json.decodeFromString<List<List<Problem>>>(it.getString(2))
        }.singleOrNull()
        if (prior != null) return@executionTransaction prior
        db.sqlUpdate("INSERT INTO execution_selections (job_id, generation, request_hash, selected) VALUES (?, ?, ?, ?)", attempt.jobId, owner.generation, requestHash, Json.encodeToString(selected))
        db.event(attempt.jobId, owner.session, "selection-approved:${selected.sumOf { it.size }}", now())
        selected
    }
    fun acceptedPreparationSelection(owner: AttemptOwner): List<List<Problem>> = store.executionTransaction { db ->
        val attempt = db.findAttempt(owner) ?: throw ExecutionFenced("unknown preparation attempt")
        db.sqlRows("SELECT selected FROM execution_selections WHERE job_id = ? AND generation = ?", attempt.jobId, owner.generation) {
            Json.decodeFromString<List<List<Problem>>>(it.getString(1))
        }.singleOrNull() ?: throw ExecutionFenced("preparation selection was not approved")
    }

    fun nativeReservation(): Pair<Long, Long> = store.executionTransaction(::nativeReservation)

    private fun nativeReservation(db: Connection): Pair<Long, Long> =
        db.sqlRows("""SELECT COALESCE(SUM(json_extract(s.budget, '$.cpuMillis')),0), COALESCE(SUM(json_extract(s.budget, '$.memoryMiB')),0)
            FROM execution_sessions s JOIN execution_outbox o ON o.id = s.id || ':submit'
            WHERE s.desired != 'stop' AND json_extract(o.payload, '$.Meta.lab_cpu_enforcement') = 'advisory-native'""".trimIndent()) { it.getLong(1) to it.getLong(2) }.single()
    fun nativeOwnedByAnother(job: Long): Boolean = store.executionTransaction { db -> nativeOwnedByAnother(db, job) }

    private fun nativeOwnedByAnother(db: Connection, job: Long): Boolean =
        db.sqlRows("""SELECT 1 FROM execution_sessions s JOIN execution_outbox o ON o.id = s.id || ':submit'
            WHERE s.job_id != ? AND s.desired != 'stop' AND json_extract(o.payload, '$.Meta.lab_cpu_enforcement') = 'advisory-native' LIMIT 1""",
            job) { true }.isNotEmpty()

    fun queueRecipe(job: Long, proposed: NomadQueueSettings): NomadQueueSettings = store.executionTransaction { db ->
        val prior = db.sqlRows("SELECT settings FROM execution_queue_recipes WHERE job_id = ?", job) { it.getString(1) }.singleOrNull()
        if (prior != null) return@executionTransaction Json.decodeFromString(prior)
        require(store.job(job)?.backend == "nomad")
        db.sqlUpdate("INSERT INTO execution_queue_recipes (job_id, settings) VALUES (?, ?)", job, Json.encodeToString(proposed))
        proposed
    }

    fun pendingDomainCompletion(): List<Long> = store.executionTransaction { db ->
        db.sqlRows("SELECT j.id FROM jobs j WHERE j.backend = 'nomad' AND j.status = 'DONE' AND NOT EXISTS (SELECT 1 FROM execution_events e WHERE e.job_id = j.id AND e.detail = 'domain-completed') ORDER BY j.id LIMIT 100") { it.getLong(1) }
    }
    fun domainCompleted(job: Long) = store.executionTransaction { db -> db.event(job, null, "domain-completed", now()) }

    fun checksComplete(job: Long): Set<Int> = store.executionTransaction { db -> db.sqlRows("SELECT idx FROM execution_checks WHERE job_id = ?", job) { it.getInt(1) }.toSet() }

    /** Rollback needs an external proof of stopped allocations; never expose as a backend toggle. */
    internal fun cancelIn(db: Connection, jobId: Long, at: Long) {
        db.sqlRows("SELECT * FROM iteration_attempts WHERE job_id = ? AND state IN $ACTIVE_ATTEMPTS", jobId, map = ::attemptRow)
            .forEach { db.invalidate(it, "cancelled", at) }
        db.sqlRows("SELECT * FROM execution_sessions WHERE job_id = ?", jobId, map = ::sessionRow).forEach { s ->
            db.sqlUpdate("UPDATE execution_sessions SET desired = 'stop' WHERE id = ?", s.id)
            db.stopIntent(s)
        }
        db.sqlUpdate("UPDATE jobs SET status = 'CANCELLED', cancel_requested = 1, finished_at = ?, running_since = NULL WHERE id = ?", at, jobId)
        db.sqlUpdate("UPDATE commands SET status = 'CANCELLED', host = NULL WHERE job_id = ? AND status IN ('QUEUED', 'RUNNING')", jobId)
        db.event(jobId, null, "cancelled", at)
    }

    private fun Connection.stopIntent(s: ExecutionSession) {
        sqlUpdate("INSERT OR IGNORE INTO execution_outbox (id, job_id, session_id, operation, payload) VALUES (?, ?, ?, 'stop', ?)", "${s.id}:stop", s.jobId, s.id, s.nomadJobId)
    }
    private fun Connection.session(id: String) = sqlRows("SELECT * FROM execution_sessions WHERE id = ?", id, map = ::sessionRow).singleOrNull()
    private fun Connection.authenticate(id: String, token: String, allocation: String? = null): ExecutionSession {
        val s = session(id) ?: throw ExecutionFenced("unknown session")
        val hash = sqlRows("SELECT token_hash FROM execution_sessions WHERE id = ?", id) { it.getString(1) }.single()
        if (!MessageDigest.isEqual(hash.toByteArray(), digest(token).toByteArray())) throw ExecutionFenced("invalid credential")
        if (s.desired == "stop" || (allocation != null && s.allocation != allocation)) throw ExecutionFenced("session fenced")
        val job = store.job(s.jobId)!!
        if (job.backend != "nomad" || job.cancelRequested || job.status !in setOf(Status.QUEUED, Status.RUNNING)) throw ExecutionFenced("experiment fenced")
        return s
    }
    private fun Connection.findAttempt(o: AttemptOwner): Attempt? {
        val a = sqlRows("SELECT * FROM iteration_attempts WHERE session_id = ? AND allocation_id = ? AND iteration_id = ? AND generation = ?", o.session, o.allocation, o.iteration, o.generation, map = ::attemptRow).singleOrNull() ?: return null
        if (!MessageDigest.isEqual(a.tokenHash.toByteArray(), digest(o.token).toByteArray())) throw ExecutionFenced("invalid attempt credential")
        return a
    }
    private fun Connection.attempt(o: AttemptOwner, at: Long): Attempt {
        val a = findAttempt(o) ?: throw ExecutionFenced("unknown attempt")
        val s = session(o.session) ?: throw ExecutionFenced("unknown session")
        val job = store.job(a.jobId)!!
        if (s.desired == "stop" || s.allocation != o.allocation || a.state !in ACTIVE_STATES || a.leaseUntil <= at || job.cancelRequested || job.backend != "nomad" || job.status !in setOf(Status.RUNNING, Status.QUEUED)) throw ExecutionFenced("attempt fenced")
        return a
    }
    private fun Connection.expire(jobId: Long, at: Long) {
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
    private fun Connection.invalidate(a: Attempt, state: String, at: Long) {
        sqlUpdate("UPDATE iteration_attempts SET state = ?, finished_at = ? WHERE job_id = ? AND iteration_id = ? AND generation = ?", state, at, a.jobId, a.iteration, a.generation)
        for (index in a.indices) {
            sqlUpdate("UPDATE commands SET status = 'QUEUED', host = NULL, exit_code = NULL, started_at = NULL, finished_at = NULL, phase = NULL WHERE job_id = ? AND idx = ?", a.jobId, index)
            sqlUpdate("UPDATE cases SET record = NULL, lab_check = NULL, stdout = NULL WHERE job_id = ? AND idx = ?", a.jobId, index)
        }
        event(a.jobId, a.session, "$state:${a.iteration}:${a.generation}", at)
    }
    private data class IterationRow(val index: Int, val status: String, val host: String?, val key: String)
    private fun Connection.iterations(jobId: Long): Map<String, List<IterationRow>> = sqlRows(
        "SELECT m.idx, m.status, m.host, c.problem_idx, c.seed, c.repeat FROM commands m LEFT JOIN cases c ON c.job_id = m.job_id AND c.idx = m.idx WHERE m.job_id = ? ORDER BY m.idx", jobId,
    ) { r ->
        val key = if (r.getObject("problem_idx") == null) "command:${r.getInt("idx")}" else "${r.getInt("problem_idx")}:${r.getObject("seed") ?: "none"}:${r.getInt("repeat")}"
        IterationRow(r.getInt("idx"), r.getString("status"), r.getString("host"), key)
    }.groupBy { it.key }
    private fun Connection.event(job: Long, session: String?, detail: String, at: Long) = sqlUpdate("INSERT INTO execution_events (job_id, session_id, detail, at) VALUES (?, ?, ?, ?)", job, session, detail, at)

    companion object {
        const val LEASE_MS = 90_000L
        private val ACTIVE_STATES = setOf("claimed", "preparing", "executing", "uploading")
        private const val ACTIVE_ATTEMPTS = "('claimed', 'preparing', 'executing', 'uploading')"
        private val random = SecureRandom()
        fun token(): String = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        internal fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        private fun claimToken(secret: String, iteration: String, generation: Int): String {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            return mac.doFinal("$iteration:$generation".toByteArray()).joinToString("") { "%02x".format(it) }
        }
        private fun sessionRow(r: ResultSet) = ExecutionSession(r.getString("id"), r.getLong("job_id"), r.getInt("generation"), r.getString("execution_class"), r.getString("release"), Json.decodeFromString(r.getString("budget")), r.getString("nomad_job_id"), r.getString("desired"), r.getString("allocation_id"), r.getString("node_id"))
        private data class Attempt(val jobId: Long, val iteration: String, val generation: Int, val session: String, val indices: List<Int>, val state: String, val leaseUntil: Long, val tokenHash: String, val result: String?, val parallel: Int)
        private fun attemptRow(r: ResultSet) = Attempt(r.getLong("job_id"), r.getString("iteration_id"), r.getInt("generation"), r.getString("session_id"), Json.decodeFromString<List<Int>>(r.getString("indices")), r.getString("state"), r.getLong("lease_until"), r.getString("token_hash"), r.getString("result"), r.getInt("parallel_limit"))
        internal fun migrate(db: Connection) {
            db.createStatement().use { s ->
                s.execute("""CREATE TABLE IF NOT EXISTS execution_sessions (id TEXT PRIMARY KEY, job_id INTEGER NOT NULL, generation INTEGER NOT NULL, execution_class TEXT NOT NULL, release TEXT NOT NULL, budget TEXT NOT NULL, nomad_job_id TEXT NOT NULL UNIQUE, desired TEXT NOT NULL, token_hash TEXT NOT NULL, allocation_id TEXT, node_id TEXT, created_at INTEGER NOT NULL, observed_at INTEGER, observation TEXT)""")
                s.execute("""CREATE TABLE IF NOT EXISTS iteration_attempts (job_id INTEGER NOT NULL, iteration_id TEXT NOT NULL, generation INTEGER NOT NULL, session_id TEXT NOT NULL, allocation_id TEXT NOT NULL, token_hash TEXT NOT NULL, lease_until INTEGER NOT NULL, state TEXT NOT NULL, indices TEXT NOT NULL, started_at INTEGER NOT NULL, finished_at INTEGER, result TEXT, PRIMARY KEY (job_id, iteration_id, generation))""")
                val attemptColumns = s.executeQuery("PRAGMA table_info(iteration_attempts)").use { r -> buildSet { while (r.next()) add(r.getString("name")) } }
                if ("parallel_limit" !in attemptColumns) s.execute("ALTER TABLE iteration_attempts ADD COLUMN parallel_limit INTEGER NOT NULL DEFAULT 1")
                s.execute("CREATE UNIQUE INDEX IF NOT EXISTS active_iteration ON iteration_attempts (job_id, iteration_id) WHERE state IN $ACTIVE_ATTEMPTS")
                s.execute("CREATE UNIQUE INDEX IF NOT EXISTS active_session_iteration ON iteration_attempts (session_id) WHERE state IN $ACTIVE_ATTEMPTS")
                s.execute("CREATE TABLE IF NOT EXISTS execution_outbox (id TEXT PRIMARY KEY, job_id INTEGER NOT NULL, session_id TEXT NOT NULL, operation TEXT NOT NULL, payload TEXT NOT NULL, acknowledged INTEGER NOT NULL DEFAULT 0)")
                s.execute("CREATE TABLE IF NOT EXISTS execution_selections (job_id INTEGER NOT NULL, generation INTEGER NOT NULL, request_hash TEXT NOT NULL, selected TEXT NOT NULL, PRIMARY KEY (job_id, generation))")
                s.execute("CREATE TABLE IF NOT EXISTS execution_queue_recipes (job_id INTEGER PRIMARY KEY, settings TEXT NOT NULL)")
                s.execute("CREATE TABLE IF NOT EXISTS execution_events (id INTEGER PRIMARY KEY AUTOINCREMENT, job_id INTEGER NOT NULL, session_id TEXT, detail TEXT NOT NULL, at INTEGER NOT NULL)")
                val columns = s.executeQuery("PRAGMA table_info(execution_sessions)").use { r -> buildSet { while (r.next()) add(r.getString("name")) } }
                if ("manifest" !in columns) s.execute("ALTER TABLE execution_sessions ADD COLUMN manifest TEXT")
                s.execute("CREATE TABLE IF NOT EXISTS capacity_leases (id TEXT PRIMARY KEY, pool TEXT NOT NULL, nodes INTEGER NOT NULL, expires_at INTEGER NOT NULL)")
                s.execute("CREATE TABLE IF NOT EXISTS execution_ingestion (job_id INTEGER NOT NULL, iteration_id TEXT NOT NULL, PRIMARY KEY (job_id, iteration_id))")
                s.execute("CREATE TABLE IF NOT EXISTS execution_checks (job_id INTEGER NOT NULL, idx INTEGER NOT NULL, detail TEXT NOT NULL, PRIMARY KEY (job_id, idx))")
                s.execute("CREATE INDEX IF NOT EXISTS session_experiment ON execution_sessions (job_id)")
            }
        }
    }
}

internal fun Connection.sqlUpdate(sql: String, vararg values: Any?): Int = prepareStatement(sql).use { s ->
    values.forEachIndexed { i, value -> s.setObject(i + 1, value) }; s.executeUpdate()
}
internal fun <T> Connection.sqlRows(sql: String, vararg values: Any?, map: (ResultSet) -> T): List<T> = prepareStatement(sql).use { s ->
    values.forEachIndexed { i, value -> s.setObject(i + 1, value) }
    s.executeQuery().use { r -> buildList { while (r.next()) add(map(r)) } }
}
