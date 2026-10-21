package com.eignex.lab.checker

import com.eignex.lab.ACTIVE
import com.eignex.lab.Config
import com.eignex.lab.Job
import com.eignex.lab.Problem
import com.eignex.lab.REFERENCE_BACKEND
import com.eignex.lab.SourceValidation
import com.eignex.lab.Status
import com.eignex.lab.Store
import com.eignex.lab.isAnalysis
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** The lab's checkers as this machine provisions them: one adapter per format, the sources from its own corpus. */
object Checkers {
    fun of(config: Config): SourceChecker {
        val solchecker = Solchecker(dir(config))
        return SourceChecker(
        adapters = listOf(Xcsp3Checker(), MpsChecker(solchecker::binary)),
        locator = SourceLocator(config.corpusDir.toFile()),
        workRoot = dir(config).resolve("tmp"),
    )
    }

    /** Where the lab keeps its checker binaries (`$LAB_DATA/checkers`), and only those: scratch inputs go under `tmp`
     *  and are deleted after each check. */
    fun dir(config: Config): File = config.dataDir.resolve("checkers").toFile()
}

/** What a recheck job ([CaseChecks.recheck]) did, written as `recheck.json` in its directory. */
@Serializable
data class RecheckResult(val job: Long, val cases: Int, val checked: Int, val states: Map<String, Int>, val policies: List<String>)

/**
 * Runs the lab's checks for the runner: after each case of an arm that asked for validation, on one background thread
 * so cases go on solving meanwhile ([afterCase]); a job's dispatch waits for its own before it ends ([await]). A recheck
 * job runs them again over an ended job's records, solving nothing ([recheck]).
 */
class CaseChecks(
    private val store: Store,
    checker: () -> SourceChecker,
    private val log: (Long, String) -> Unit,
) {
    private val checker by lazy(checker)
    private val pool = Executors.newSingleThreadExecutor { Thread(it, "source-checks").apply { isDaemon = true } }
    private val pending = ConcurrentHashMap<Long, MutableList<Future<*>>>()

    /** Case [index] of [jobId] has kept its record: check it later when its arm asked for validation and the lab checks
     *  its format. */
    fun afterCase(jobId: Long, index: Int) {
        val (problem, arm) = store.caseOf(jobId, index) ?: return
        if (arm.values["backend"] == REFERENCE_BACKEND || !SourceValidation.requested(arm.values)) return
        if (!checker.covers(problem.format)) return
        val future = pool.submit {
            runCatching { check(jobId, index, problem) }.onFailure { log(jobId, "case $index: source check failed: ${it.message}") }
        }
        pending.computeIfAbsent(jobId) { Collections.synchronizedList(mutableListOf()) }.add(future)
    }

    /** Wait for [jobId]'s queued checks to end. */
    fun await(jobId: Long) {
        val futures = pending.remove(jobId)?.let { synchronized(it) { it.toList() } } ?: return
        for (future in futures) runCatching { future.get() }
    }

    /** Check case [index] of [jobId], a case of [problem], from its whole stored record, and keep the verdict. */
    fun check(jobId: Long, index: Int, problem: Problem): LabCheck? {
        val text = store.caseRecordText(jobId, index) ?: return null
        val record = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val check = checker.check(problem, record) ?: return null
        store.caseCheck(jobId, index, check.json())
        log(jobId, "case $index: ${check.policy}: ${check.state}: ${check.reason}")
        return check
    }

    /**
     * Check every finished case of ended job [target] whose format the lab checks, whether or not it asked for
     * validation, replacing earlier lab verdicts: a backfill, solving nothing. Stops early when [cancelled].
     */
    fun recheck(target: Long, cancelled: () -> Boolean): RecheckResult {
        val cases = store.casePlan(target).filter { it.status == Status.DONE && checker.covers(it.problem.format) }
        val states = sortedMapOf<String, Int>()
        val policies = sortedSetOf<String>()
        var checked = 0
        for (case in cases) {
            if (cancelled()) break
            val check = runCatching { check(target, case.index, case.problem) }
                .onFailure { log(target, "case ${case.index}: source check failed: ${it.message}") }.getOrNull() ?: continue
            checked++
            states.merge(check.state, 1, Int::plus)
            policies += check.policy
        }
        return RecheckResult(target, cases.size, checked, states, policies.toList())
    }

    companion object {
        /** What a recheck job writes in its directory. */
        const val RESULT = "recheck.json"

        /** Why ended job [job]'s records cannot be checked again, or null when they can. */
        fun refusal(job: Job?): String? {
            if (job == null) return "no such job"
            val spec = job.experiment ?: return "job ${job.id} is not an experiment"
            if (spec.isAnalysis) return "job ${job.id} is an analysis or recheck job itself; name the job it checked (${spec.analyze?.job})"
            if (job.status in ACTIVE) return "job ${job.id} is ${job.status.name.lowercase()}: check it again once it has ended"
            return null
        }
    }
}
