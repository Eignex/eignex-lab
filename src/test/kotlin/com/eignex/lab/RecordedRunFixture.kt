package com.eignex.lab

/** Fixture state transition for record/history tests. No submitted command is executed. */
internal fun Store.startForTest(): Job? {
    val id = queue().firstOrNull() ?: return null
    executionTransaction { db -> db.prepareStatement("UPDATE jobs SET status='RUNNING', local=1, started_at=?, running_since=? WHERE id=?").use {
        it.setLong(1, now()); it.setLong(2, now()); it.setLong(3, id); it.executeUpdate()
    } }
    return job(id)
}
