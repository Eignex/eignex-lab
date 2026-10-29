package com.eignex.lab

/** Downloads' holds on jobs' files (`holds`): while one lasts, [JobTrim] leaves the job alone. */
class HoldRepository internal constructor(db: Database, lock: Any) : Repository(db, lock) {
    /**
     * A new hold on job [jobId]'s files for [ttlMs] from [at] ([Hold]). Lapsed holds are forgotten here, so the table
     * keeps no more than the downloads of the last few hours.
     */
    fun take(jobId: Long, ttlMs: Long, at: Long = now()): Hold = synchronized(lock) {
        transaction {
            update("DELETE FROM holds WHERE expires_at <= ?", at)
            val hold = Hold(newHoldId(), jobId, at, at + ttlMs)
            update("INSERT INTO holds (id, job_id, created_at, expires_at) VALUES (?, ?, ?, ?)", hold.id, hold.job, hold.createdAt, hold.expiresAt)
            hold
        }
    }

    /** Hold [id] on job [jobId] extended to [ttlMs] from [at]; null when there is no such hold or it has lapsed. */
    fun renew(jobId: Long, id: String, ttlMs: Long, at: Long = now()): Hold? = synchronized(lock) {
        transaction {
            val renewed = update("UPDATE holds SET expires_at = ? WHERE id = ? AND job_id = ? AND expires_at > ?", at + ttlMs, id, jobId, at)
            if (renewed == 0) null else holdsWhere("id = ?", id).single()
        }
    }

    /** Releases hold [id] on job [jobId]; false when there was no such hold, lapsed or not. */
    fun release(jobId: Long, id: String): Boolean = synchronized(lock) {
        update("DELETE FROM holds WHERE id = ? AND job_id = ?", id, jobId) > 0
    }

    /** The holds that have not lapsed at [at], by job, then by when they lapse. */
    fun active(at: Long = now()): List<Hold> = reading {
        holdsWhere("expires_at > ? ORDER BY job_id, expires_at", at)
    }

    private fun holdsWhere(where: String, vararg values: Any?): List<Hold> =
        connection.prepareStatement("SELECT * FROM holds WHERE $where").use { statement ->
            values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { r ->
                generateSequence {
                    if (r.next()) Hold(r.getString("id"), r.getLong("job_id"), r.getLong("created_at"), r.getLong("expires_at")) else null
                }.toList()
            }
        }
}

private val holdIds = java.security.SecureRandom()

private fun newHoldId(): String = ByteArray(16).also(holdIds::nextBytes).joinToString("") { "%02x".format(it) }
