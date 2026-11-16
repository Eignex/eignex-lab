package com.eignex.lab

import com.eignex.lab.util.*
import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.execution.*
import com.eignex.lab.worker.*
import com.eignex.lab.nomad.*
import com.eignex.lab.web.*
import com.eignex.lab.cli.*

/** Fixture state transition for record/history tests. No submitted command is executed. */
internal fun Store.startForTest(): Job? {
    val id = queue().firstOrNull() ?: return null
    executionTransaction { db ->
        db.sqlUpdate("UPDATE jobs SET status = 'RUNNING', local = 1, started_at = ?, running_since = ? WHERE id = ?", now(), now(), id)
    }
    return job(id)
}
