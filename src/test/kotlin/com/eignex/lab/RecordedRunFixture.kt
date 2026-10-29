package com.eignex.lab

/** Fixture state transition for record/history tests. No submitted command is executed. */
internal fun Store.startForTest(): Job? {
    val id = queue().firstOrNull() ?: return null
    executionTransaction { db ->
        db.sqlUpdate("UPDATE jobs SET status = 'RUNNING', local = 1, started_at = ?, running_since = ? WHERE id = ?", now(), now(), id)
    }
    return job(id)
}
