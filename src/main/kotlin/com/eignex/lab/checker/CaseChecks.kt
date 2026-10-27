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
        val minizinc = MiniZincInstall()
        val dolmen = Dolmen(dir(config))
        val z3 = Z3Install()
        return SourceChecker(
        adapters = listOf(Xcsp3Checker(), MpsChecker(solchecker::binary), MiniZincChecker(minizinc::tool),
            SmtChecker(dolmen::binary, algebraic = Z3AlgebraicCheck(z3::tool))),
        locator = SourceLocator(config.corpusDir.toFile(), fixtures = fixtures(config), problemPaths = { problem, revision ->
            if (revision == null) problem else {
                fun path(value: String): String = if (value.startsWith("lab-source/") || value.startsWith("lab-corpus/"))
                    com.eignex.lab.portablePath(value, config.worktree(revision.job), config.jobDir(revision.job).resolve("nomad/prepared-corpus")).toString() else value
                problem.copy(model = problem.model?.let(::path), data = problem.data?.let(::path), includeDirs = problem.includeDirs.map(::path))
            }
        }),
        workRoot = dir(config).resolve("tmp"),
    )
    }

    /** Repository files read at a case's commit: from its job's worktree of that commit, else from the lab's mirror.
     *  A planned path under the lab's or a cloud node's worktrees is a repository path. */
    fun fixtures(config: Config): RepositoryFixtures = RepositoryFixtures(
        mirror = config.mirror.toFile(),
        worktrees = { (job, sha) ->
            val own = config.worktree(job).toFile()
            listOf(own, own.resolveSibling("$job@${sha.take(SHA_DIR)}"))
        },
        worktreeRoots = listOf(config.worktree(0).parent.toString(), "/home/ubuntu/work"),
    )

    /** Where the lab keeps its checker binaries (`$LAB_DATA/checkers`), and only those: scratch inputs go under `tmp`
     *  and are deleted after each check. */
    fun dir(config: Config): File = config.dataDir.resolve("checkers").toFile()

    /** How much of a commit names a job's worktree of it beside its own (`<job>@<sha>`), as the runner builds it. */
    private const val SHA_DIR = 12
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

    /**
     * Case [index] of [jobId] has kept its record: check it later when its arm asked for validation and the lab checks
     * its format. Nothing here fails the case or its job: a checker the lab cannot even set up is logged, and the case
     * keeps the solver's claim, unchecked.
     */
    fun afterCase(jobId: Long, index: Int) {
        runCatching {
            val (problem, arm) = store.caseOf(jobId, index) ?: return
            if (arm.values["backend"] == REFERENCE_BACKEND || !SourceValidation.requested(arm.values)) return
            if (!checker.covers(problem.format)) return
            val future = pool.submit {
                runCatching { check(jobId, index, problem) }.onFailure { log(jobId, "case $index: source check failed: ${it.message}") }
            }
            pending.computeIfAbsent(jobId) { Collections.synchronizedList(mutableListOf()) }.add(future)
        }.onFailure { log(jobId, "case $index: source check not queued: ${it.message}") }
    }

    /** Wait for [jobId]'s queued checks to end. */
    fun await(jobId: Long) {
        val futures = pending.remove(jobId)?.let { synchronized(it) { it.toList() } } ?: return
        for (future in futures) runCatching { future.get() }
    }

    /** Stop waiting for [jobId]'s queued checks, as a cancelled job does: they still run and keep their verdicts. */
    fun release(jobId: Long) {
        pending.remove(jobId)
    }

    /** Check case [index] of [jobId], a case of [problem], from its whole stored record, and keep the verdict. */
    fun check(jobId: Long, index: Int, problem: Problem): LabCheck? {
        val text = store.caseRecordText(jobId, index) ?: return null
        val record = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val revision = store.caseSha(jobId, index)?.let { Revision(jobId, it) }
        val inputs = store.execution.accepted(jobId).firstOrNull { it.second.preparation != null }?.second?.preparation?.sharedInputs.orEmpty()
        val lease = if (inputs.isNotEmpty()) try {
            val required = (listOfNotNull(problem.model, problem.data) + problem.includeDirs)
                .filter { it.startsWith("lab-corpus/") }.map { it.removePrefix("lab-corpus/") }
            requireNotNull(com.eignex.lab.SharedInputs.environment()).acquire(inputs, required = required)
        } catch (failure: Exception) { throw com.eignex.lab.SharedCheckInputUnavailable(failure) } else null
        val check = lease.use { checker.check(problem, record, revision) } ?: return null
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
