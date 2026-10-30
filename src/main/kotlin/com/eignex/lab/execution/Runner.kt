package com.eignex.lab.execution

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import com.eignex.lab.checker.SourceChecker

/**
 * The local maintenance process: it queues schedules' runs ([Schedules]), runs the lab's analysis jobs ([AnalysisJobs])
 * between native workers, and keeps the machine tidy (job trim, database compaction, docker cleanup, self-update).
 * Experiment execution belongs exclusively to Nomad.
 */
class Runner(
    private val config: Config,
    private val store: Store,
    sourceChecker: () -> SourceChecker = { Checkers.of(config.paths) },
    private val selfUpdater: SelfUpdater? = null,
    sourceUrl: (String) -> String = { Repositories.source(it) },
) {
    internal val checks = CaseChecks(store, sourceChecker, config::jobDir, ::log)
    @Volatile private var stopping = false
    private val schedules = Schedules(config.paths, config.workers, config.retry, store, sourceUrl) { stopping }
    private val confirmations = Confirmations(config.local, config.paths, store)
    private val analysis = AnalysisJobs(config.paths, config.archives, store, checks)
    private val updater by lazy { selfUpdater ?: SelfUpdater(
        config.sourceDir.takeIf { config.updateCheckSec > 0 }, config.updateCheckSec * 1000,
        config.dataDir.resolve("logs/update.log").toFile(), requestFile = config.updateRequest.toFile()) }
    private val dockerCleanup by lazy {
        dockerCleanup(config.paths, config.docker, config.jobsTargetFreeBytes, store) { pattern, revision -> withMirrorLock(config.mirror) { mirrorGrep(config.mirror.toFile())(pattern, revision) } }
    }
    internal val disk = DiskGuard { Volumes.hold(config.paths, config.minFreeBytes) }
    @Volatile internal var recovered = false
        private set
    @Volatile internal var recoveryWaits: String? = null
        private set

    fun loop() {
        config.dataDir.createDirectories()
        disk.allowsWork()
        thread(isDaemon = true, name = "bind-references") {
            runCatching { store.references.retireStale() }.onFailure { println("references: ${describeFailure(it)}") }
            runCatching { store.references.bindLabSources(store.plans::scoringCases) }
        }
        JobTrim(config.jobsDir, store::ended, { freeBytes(config.jobsDir) }, ::log,
            held = { store.holds.active().mapTo(HashSet()) { it.job } }, graceMs = config.trimGraceMs,
            criticalFreeBytes = config.trimCriticalFreeBytes, targetFreeBytes = config.jobsTargetFreeBytes,
            maxAgeMs = config.trimAgeMs).start()
        DbCompaction(store, { freeBytes(config.dataDir) }, { store.active(withCommands = false).any { it.status == Status.RUNNING } }).start()
        periodic("schedules", SCHEDULE_POLL_MS) { if (!stopping) enqueueScheduled() }
        while (!stopping) {
            val ownership = NativeExecutionLock.tryAcquire(config.nativeExecutionLock)
            if (ownership == null) {
                if (updater.due()) { config.nativeYield.parent.createDirectories(); config.nativeYield.writeText("update pending\n") }
                Thread.sleep(IDLE_POLL_MS)
                continue
            }
            var idle = true
            try {
                updater.maybeUpdate()
                Files.deleteIfExists(config.nativeYield)
                runCatching { dockerCleanup.maybeRun() }.onFailure { println("docker cleanup: ${it.message}") }
                if (disk.allowsWork() && recover()) store.nextAnalysis()?.let { idle = false; runJob(it) }
            } finally { ownership.close() }
            if (idle) Thread.sleep(IDLE_POLL_MS)
        }
    }

    /** The maintenance runner accepts analysis only; builds, solves and profiles cannot enter this process. */
    internal fun runJob(job: Job): Boolean {
        require(job.experiment.isAnalysis == true) { "experiment execution requires Nomad" }
        runCatching { analyze(job) }.onFailure { failure ->
            if (stopping) return false
            if (store.cancelRequested(job.id)) cancel(job.id)
            else { log(job.id, "analysis failed: ${failure.message}"); store.finish(job.id, Status.FAILED, failure.message) }
        }
        return !stopping
    }

    internal fun recover(): Boolean {
        if (recovered) return true
        Volumes.jobFiles(config.paths)?.let { recoveryWaits = it; return false }
        for (job in store.active(withCommands = false).filter { it.experiment.isAnalysis == true }) {
            if (job.cancelRequested) cancel(job.id)
            else if (job.status == Status.RUNNING) store.requeue(job.id)
        }
        recovered = true
        recoveryWaits = null
        return true
    }

    internal fun queueConfirmation(job: Job) = confirmations.queue(job)
    internal fun enqueueScheduled() = schedules.enqueue()
    internal fun reportCompilation(jobId: Long, index: Int, dir: Path, analyzeJob: Long? = null) =
        analysis.reportCompilation(jobId, index, dir, analyzeJob)
    internal fun analyze(job: Job) = analysis.analyze(job)
    internal fun cancel(jobId: Long) = analysis.cancel(jobId)
    internal fun log(jobId: Long, message: String) = logJob(config.paths, jobId, message)

    fun installShutdownHook() { Runtime.getRuntime().addShutdownHook(Thread { stop() }) }
    internal fun stop() { stopping = true }

    private companion object {
        const val IDLE_POLL_MS = 2000L
        const val SCHEDULE_POLL_MS = 10_000L
    }
}
