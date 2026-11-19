package com.eignex.lab.execution

import com.eignex.lab.util.*
import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.analysis.*
import com.eignex.lab.checker.*
import com.eignex.lab.reference.*
import com.eignex.lab.capacity.*


import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import com.eignex.lab.domain.REFERENCE_BACKEND
import com.eignex.lab.analysis.SourceValidation
import com.eignex.lab.execution.SharedCheckInputUnavailable
import com.eignex.lab.execution.SharedInputs
import com.eignex.lab.store.Store
import com.eignex.lab.util.size

/** The lab's checkers as this machine provisions them: one adapter per format, the sources from its own corpus. */
object Checkers {
    private val adapters: Map<String, (DataPaths) -> FormatChecker> = linkedMapOf(
        "XCSP3" to { Xcsp3Checker() },
        "SMTLIB" to { paths -> SmtChecker(Dolmen(dir(paths))::binary) },
        "DIMACS" to { DimacsChecker() },
    )

    /** Registered formats, available without provisioning or running checker tools. */
    val formats: Set<String> get() = adapters.keys

    fun covers(format: String): Boolean = formats.any { it.equals(format, ignoreCase = true) }

    fun of(paths: DataPaths): SourceChecker {
        return SourceChecker(
        adapters = adapters.values.map { it(paths) },
        locator = SourceLocator(paths.corpusDir.toFile(), fixtures = fixtures(paths), problemPaths = { problem, revision ->
            if (revision == null) problem else {
                fun path(value: String): String = if (value.startsWith("lab-source/") || value.startsWith("lab-corpus/"))
                    portablePath(value, paths.jobDir(revision.job).resolve("nomad/prepared-source/${revision.sha}").takeIf(java.nio.file.Files::exists) ?: paths.worktree(revision.job), paths.jobDir(revision.job).resolve("nomad/prepared-corpus")).toString() else value
                problem.copy(model = problem.model?.let(::path), data = problem.data?.let(::path), includeDirs = problem.includeDirs.map(::path))
            }
        }),
        workRoot = dir(paths).resolve("tmp"),
    )
    }

    /** Repository files read at a case's commit: from its job's worktree of that commit, else from the lab's mirror.
     *  A planned path under the lab's or a cloud node's worktrees is a repository path. */
    fun fixtures(paths: DataPaths): RepositoryFixtures = RepositoryFixtures(
        mirror = paths.mirror.toFile(),
        mirrorFor = { revision -> checkerMirror(paths, revision.repository).toFile() },
        worktrees = { (job, sha) ->
            val own = paths.worktree(job).toFile()
            listOf(paths.jobDir(job).resolve("nomad/prepared-source/$sha").toFile(), own, own.resolveSibling("$job@${sha.take(SHA_DIR)}"))
        },
        worktreeRoots = listOf(paths.worktree(0).parent.toString(), "/home/ubuntu/work"),
    )

    /** Where the lab keeps its checker binaries (`$LAB_DATA/checkers`), and only those: scratch inputs go under `tmp`
     *  and are deleted after each check. */
    fun dir(paths: DataPaths): File = paths.dataDir.resolve("checkers").toFile()

    /** How much of a commit names a job's worktree of it beside its own (`<job>@<sha>`), as the runner builds it. */
    private const val SHA_DIR = 12
}

/** Missing repository fields in pre-adapter records used the original checker mirror. */
internal fun checkerRepository(spec: ExperimentSpec?): String? = spec?.let {
    it.repository.takeUnless { repository -> !it.isBench && repository == Repositories.DEFAULT }
}

internal fun checkerMirror(paths: DataPaths, repository: String?): java.nio.file.Path {
    if (repository == null) return paths.mirror
    val mirror = Repositories.mirror(paths, repository)
    // Old Klause checks used repo.git, including records that explicitly named the repository.
    return if (repository == "Eignex/klause" && !java.nio.file.Files.exists(mirror)) paths.mirror else mirror
}

/** What a recheck job ([CaseChecks.recheck]) did, written as `recheck.json` in its directory. */
@Serializable
data class RecheckResult(val job: Long, val cases: Int, val checked: Int, val states: Map<String, Int>, val policies: List<String>,
                         val outcomes: List<RecheckCaseResult> = emptyList())

/** An explicit recheck outcome, including cases for which no replacement receipt could be produced. */
@Serializable
data class RecheckCaseResult(val index: Int, val state: String, val reason: String, val policy: String? = null,
                             val receiptWritten: Boolean = false)

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
            val (problem, arm) = store.plans.caseOf(jobId, index) ?: return
            if (arm.values["backend"] == REFERENCE_BACKEND || !ValidationRequest.requested(store.job(jobId)?.experiment, arm.values)) return
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
        if (!checker.covers(problem.format)) return null
        val text = store.plans.caseRecordText(jobId, index) ?: return null
        val record = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        return checkRecord(jobId, index, problem, record)
    }

    private fun checkRecord(jobId: Long, index: Int, problem: Problem, record: JsonObject): LabCheck? {
        val revision = store.plans.caseSha(jobId, index)?.let { Revision(jobId, it, checkerRepository(store.job(jobId)?.experiment)) }
        val inputs = store.execution.acceptedPreparation(jobId)?.sharedInputs.orEmpty()
        val lease = if (inputs.isNotEmpty()) try {
            val required = (listOfNotNull(problem.model, problem.data) + problem.includeDirs)
                .filter { it.startsWith("lab-corpus/") }.map { it.removePrefix("lab-corpus/") }
            checkNotNull(SharedInputs.environment()) { "LAB_SHARED_INPUT_ROOT is not set in this service's environment" }
                .acquire(inputs, required = required)
        } catch (failure: Exception) { throw SharedCheckInputUnavailable(failure) } else null
        val check = lease.use { checker.check(problem, record, revision) } ?: return null
        store.plans.caseCheck(jobId, index, check.json())
        log(jobId, "case $index: ${check.policy}: ${check.state}: ${check.reason}")
        return check
    }

    /**
     * Account for every completed case of ended job [target], whether or not it asked for validation. Supported
     * claims get replacement receipts; unsupported, skipped and failed attempts retain earlier receipts and get
     * explicit outcomes in the recheck report. Solves nothing. Stops early when [cancelled].
     */
    fun recheck(target: Long, cancelled: () -> Boolean): RecheckResult {
        val cases = store.plans.casePlan(target).filter { it.status == Status.DONE }
        val states = sortedMapOf<String, Int>()
        val policies = sortedSetOf<String>()
        val outcomes = mutableListOf<RecheckCaseResult>()
        var checked = 0
        for (case in cases) {
            if (cancelled()) break
            val outcome = try {
                recheckCase(target, case)
            } catch (failure: Exception) {
                if (failure is InterruptedException) throw failure
                RecheckCaseResult(case.index, LabCheck.NOT_RUN,
                    "source check failed: ${failure.message ?: failure::class.simpleName}".take(500))
            }
            outcomes += outcome
            if (outcome.receiptWritten) checked++
            states.merge(outcome.state, 1, Int::plus)
            outcome.policy?.let { policies += it }
            log(target, "case ${case.index}: recheck ${outcome.state}: ${outcome.reason}")
        }
        return RecheckResult(target, cases.size, checked, states, policies.toList(), outcomes)
    }

    private fun recheckCase(target: Long, case: CaseResult): RecheckCaseResult {
        fun result(state: String, reason: String) = RecheckCaseResult(case.index, state, reason)
        if (!checker.covers(case.problem.format)) return result(LabCheck.UNSUPPORTED,
            "no independent source checker is registered for format ${case.problem.format.ifBlank { "(unspecified)" }}")
        val text = store.plans.caseRecordText(target, case.index)
            ?: return result(LabCheck.NOT_RUN, "the completed case has no retained record")
        val record = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return result(LabCheck.NOT_RUN, "the retained record is not a JSON object")
        val check = checkRecord(target, case.index, case.problem, record)
            ?: return result("skipped", "the retained record has no Klause solution claim to check")
        return RecheckCaseResult(case.index, check.state, check.reason, check.policy, receiptWritten = true)
    }

    companion object {
        /** What a recheck job writes in its directory. */
        const val RESULT = "recheck.json"

        /** Why ended job [job]'s records cannot be checked again, or null when they can. */
        fun refusal(job: Job?): String? {
            if (job == null) return "no such job"
            val spec = job.experiment
            if (spec.isAnalysis) return "job ${job.id} is an analysis or recheck job itself; name the job it checked (${spec.work.analysis?.job})"
            if (job.status.isActive) return "job ${job.id} is ${job.status.name.lowercase()}: check it again once it has ended"
            return null
        }
    }
}
