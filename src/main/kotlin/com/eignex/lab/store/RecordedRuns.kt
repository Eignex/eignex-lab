package com.eignex.lab.store

import com.eignex.lab.domain.*
import com.eignex.lab.util.*


/*
 * Write a run's plan and outcome straight into a store, without executing anything: what a check bench's scratch store
 * and the tests' fixtures record. Lab execution never takes these paths; its jobs are planned by [Store.prepareNomad]
 * and their cases recorded by [ExecutionStore] as Nomad workers commit them.
 */

/** Record an experiment's plan in one transaction: its arms, its problems, and a command per case, the case at each
 *  command's index. A job with commands is planned. */
internal fun Store.plan(jobId: Long, arms: List<PlannedArm>, problems: List<Problem>, cases: List<Case>, commands: List<Pair<String, Long>>) {
    require(cases.size == commands.size) { "a command per case" }
    executionTransaction { plans.planRows(jobId, arms, problems, cases, commands.map { (cmd, timeout) -> CaseCommand(cmd, timeout) }) }
}

/** Mark [jobId] set up at [sha]. */
internal fun Store.setup(jobId: Long, sha: String) =
    executionTransaction { it.sqlUpdate("UPDATE jobs SET sha = ?, setup_done = 1 WHERE id = ?", sha, jobId) }

/** Command [index] of [jobId] starts on [host] as [cmd], when given. */
internal fun Store.commandStarted(jobId: Long, index: Int, host: String? = null, cmd: String? = null) = executionTransaction {
    it.sqlUpdate(
        "UPDATE commands SET status = ?, started_at = ?, exit_code = NULL, phase = NULL, host = COALESCE(?, host), " +
            "cmd = COALESCE(?, cmd) WHERE job_id = ? AND idx = ? AND status IN ('QUEUED', 'RUNNING')",
        Status.RUNNING.name, now(), host, cmd, jobId, index,
    )
}

/** Command [index] of [jobId] ends with [exitCode]: done on 0, failed otherwise. */
internal fun Store.commandFinished(jobId: Long, index: Int, exitCode: Int) = executionTransaction {
    it.sqlUpdate(
        "UPDATE commands SET status = ?, exit_code = ?, finished_at = ?, phase = NULL WHERE job_id = ? AND idx = ? AND status IN ('QUEUED', 'RUNNING')",
        if (exitCode == 0) Status.DONE.name else Status.FAILED.name, exitCode, now(), jobId, index,
    )
}

/** Keep the record a case wrote, compacted ([StoredRecords]); a lab check of the record it replaces goes with it. */
internal fun Store.caseRecord(jobId: Long, index: Int, record: String): Int = executionTransaction {
    it.sqlUpdate("UPDATE cases SET record = ?, lab_check = NULL WHERE job_id = ? AND idx = ?", plans.storedRecord(record), jobId, index)
}
