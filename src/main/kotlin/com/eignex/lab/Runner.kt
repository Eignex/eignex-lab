package com.eignex.lab

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json
import com.eignex.lab.checker.CaseChecks
import com.eignex.lab.checker.Checkers
import com.eignex.lab.checker.RecheckResult
import com.eignex.lab.checker.SourceChecker

/** Schedules, retained-capture analysis and maintenance. Experiment execution belongs exclusively to Nomad. */
class Runner(
    private val config: Config,
    private val store: Store,
    sourceChecker: () -> SourceChecker = { Checkers.of(config) },
    private val selfUpdater: SelfUpdater? = null,
    private val sourceUrl: (String) -> String = { Repositories.source(config, it) },
) {
    internal val checks = CaseChecks(store, sourceChecker, ::log)
    private val running: MutableSet<Process> = ConcurrentHashMap.newKeySet()
    @Volatile private var stopping = false
    private val mirrorLock = Any()
    private fun mirror(repository: String) = Repositories.mirror(config, repository)
    private val updater by lazy { selfUpdater ?: SelfUpdater(
        config.sourceDir.takeIf { config.updateCheckSec > 0 }, config.updateCheckSec * 1000,
        config.dataDir.resolve("logs/update.log").toFile(), requestFile = config.updateRequest.toFile()) }
    private val dockerCleanup by lazy {
        dockerCleanup(config, store) { pattern, revision -> synchronized(mirrorLock) { mirrorGrep(config.mirror.toFile())(pattern, revision) } }
    }
    internal val disk = DiskGuard { Volumes.hold(config) }
    @Volatile internal var recovered = false
        private set
    @Volatile internal var recoveryWaits: String? = null
        private set

    fun loop() {
        config.dataDir.createDirectories()
        disk.allowsWork()
        thread(isDaemon = true, name = "bind-references") { runCatching { store.bindLabReferenceSources() } }
        JobTrim(config.jobsDir, store::ended, { freeBytes(config.jobsDir) }, ::log,
            held = { store.holds().mapTo(HashSet()) { it.job } }, graceMs = config.trimGraceMs,
            criticalFreeBytes = config.trimCriticalFreeBytes, targetFreeBytes = config.jobsTargetFreeBytes,
            maxAgeMs = config.trimAgeMs).start()
        DbCompaction(store, { freeBytes(config.dataDir) }, { store.active(withCommands = false).any { it.status == Status.RUNNING } }).start()
        thread(isDaemon = true, name = "schedules") {
            while (!stopping) {
                runCatching { enqueueScheduled() }.onFailure { println("schedules: ${it.message}") }
                Thread.sleep(SCHEDULE_POLL_MS)
            }
        }
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
        require(job.experiment?.isAnalysis == true) { "experiment execution requires Nomad" }
        runCatching { analyze(job) }.onFailure { failure ->
            if (stopping) return false
            if (store.cancelRequested(job.id)) cancel(job.id)
            else { log(job.id, "analysis failed: ${failure.message}"); store.finish(job.id, Status.FAILED, failure.message) }
        }
        return !stopping
    }

    internal fun recover(): Boolean {
        if (recovered) return true
        Volumes.jobFiles(config)?.let { recoveryWaits = it; return false }
        for (job in store.active(withCommands = false).filter { it.experiment?.isAnalysis == true }) {
            if (job.cancelRequested) cancel(job.id)
            else if (job.status == Status.RUNNING) store.requeue(job.id)
        }
        recovered = true
        recoveryWaits = null
        return true
    }

    internal fun queueConfirmation(job: Job) {
        val spec = job.experiment ?: return
        val series = job.name.substringBefore('@', "")
        if (series.isEmpty() || series.endsWith(Confirm.SUFFIX) || store.schedules().none { it.name == series }) return
        val fingerprint = Experiments.fingerprint(spec)
        val previous = store.jobs(limit = PREVIOUS_RUNS, name = series)
            .filter { it.id < job.id && it.status == Status.DONE && it.experiment?.let(Experiments::fingerprint) == fingerprint }
            .maxByOrNull { it.id } ?: return
        val before = previous.sha ?: return
        val after = checkNotNull(store.job(job.id)?.sha)
        if (before == after) return
        val flips = Confirm.flips(firstArmCases(previous.id), firstArmCases(job.id))
        if (flips.confirmable.isEmpty()) return
        val confirmation = Confirm.spec(series, spec, before, after, flips, job.id)
        if (store.jobs(limit = 100, name = confirmation.name).any { it.name == confirmation.name && it.experiment == confirmation }) return
        val id = store.create(confirmation.name, after, emptyList(), confirmation.parallel ?: config.defaultParallel, confirmation.priority, confirmation)
        log(job.id, "${flips.lost.size} feasibility lost, ${flips.gained.size} gained; " +
            "${flips.proofLost.size} proof lost, ${flips.proofGained.size} gained; " +
            "${flips.objectiveLost.size} objective worse, ${flips.objectiveGained.size} better against job ${previous.id}: queued confirmation job $id")
    }

    private fun firstArmCases(jobId: Long): List<CaseResult> {
        val label = store.arms(jobId).firstOrNull()?.arm?.label ?: return emptyList()
        return store.cases(jobId).filter { it.arm == label }
    }

    internal fun enqueueScheduled() {
        fun regular(s: Schedule) = s.checkedAt?.let { now() - it >= s.intervalSec * 1000 } ?: true
        // A requested launch is looked at on every poll until it queues, without moving the schedule's regular checks:
        // only a regular check stamps the time, so schedules staggered apart stay apart after a manual launch.
        for (schedule in store.schedules().filter { it.launchRequested || regular(it) }) {
            val checking = regular(schedule)
            if (checking) store.scheduleChecked(schedule.id, now())
            // A launch asked for while the last run is still going waits for it rather than queueing a second; it stays
            // requested, so it queues on the first poll after that run ends.
            if (schedule.lastJob?.let(store::job)?.status in setOf(Status.QUEUED, Status.RUNNING)) continue
            val repository = schedule.experiment.repository
            Repositories.requireAllowed(repository)
            val mirror = mirror(repository)
            val advertised = advertised(schedule.ref, repository)
            if (!schedule.launchRequested && advertised != null && advertised == schedule.lastSha) continue
            val fetched = runCatching {
                withRetry(null, "schedule ${schedule.name}: fetching") {
                    synchronized(mirrorLock) {
                        if (!mirror.exists()) {
                            mirror.parent.createDirectories()
                            capture(config.dataDir.toFile(), "git clone --mirror ${quote(sourceUrl(repository))} ${quote(mirror.toString())}")
                        }
                        capture(mirror.toFile(), "git remote set-url origin ${quote(sourceUrl(repository))}")
                        capture(mirror.toFile(), "git fetch --prune origin '+refs/heads/*:refs/heads/*' '+refs/tags/*:refs/tags/*'")
                    }
                }
            }
            if (fetched.isFailure) {
                // Left due rather than checked, so the next poll tries again instead of a whole interval later.
                println("schedule ${schedule.name}: fetch failed, retrying on the next poll: ${fetched.exceptionOrNull()?.message}")
                if (checking) store.checkNow(schedule.id)
                continue
            }
            // A ref origin no longer has is not retried: it waits for the next interval like an unmoved one.
            val sha = runCatching {
                synchronized(mirrorLock) { capture(mirror.toFile(), "git rev-parse --verify ${quote(schedule.ref + "^{commit}")}") }
            }.getOrNull() ?: continue
            if (sha == schedule.lastSha && !schedule.launchRequested) continue
            val name = "${schedule.name}@${sha.take(9)}"
            // Every scheduled Klause run asks for source validation ([SourceValidation.scheduled]), whatever spec the
            // schedule was saved with.
            val experiment = SourceValidation.scheduled(schedule.experiment)
            val run = experiment.copy(name = name, base = experiment.base + ("ref" to sha))
            // A schedule's runs stay on the local machine ([Placement.localOnly]) unless its spec opts in to the cloud
            // ([Placement.cloudOptIn]): its host says only whether it opted in, and the run is placed by the rules.
            val spec = run.copy(host = Placement.host(run, name, config, listOf(schedule)), auto = false, machines = null)
            val id = store.create(name, sha, emptyList(), spec.parallel ?: config.defaultParallel, spec.priority, spec)
            store.scheduleRan(schedule.id, sha, id)
            println("schedule ${schedule.name}: queued job $id for ${sha.take(9)}")
        }
    }

    /** The commit origin advertises for branch or tag [ref]; null when it names neither, or origin does not answer. */
    private fun advertised(ref: String, repository: String): String? = runCatching {
        capture(
            config.dataDir.toFile(),
            "GIT_TERMINAL_PROMPT=0 git ls-remote ${quote(sourceUrl(repository))} ${quote("refs/heads/$ref")} ${quote("refs/tags/$ref")}",
        )
    }.getOrNull()?.lineSequence()?.map { it.substringBefore('\t').trim() }?.firstOrNull { SHA.matches(it) }

    internal fun reportCompilation(jobId: Long, index: Int, dir: Path, analyzeJob: Long? = null): List<Pair<Path, CompilationReport>> =
        runCatching {
            val source = store.caseOf(jobId, index)?.let { (problem, arm) ->
                SourceProvenance(jobId, index, if (problem.suite.isEmpty()) problem.problem else "${problem.suite}/${problem.problem}",
                    arm.label, arm.ref, store.arms(jobId).firstOrNull { it.arm.label == arm.label }?.sha)
            } ?: SourceProvenance(jobId, index)
            val worker = CompilationReports.worker(analyzeJob)
            CompilationReports.profileDirs(dir, index)
                .filter { if (analyzeJob == null) !Files.exists(it.resolve(CompilationReports.REPORT)) else CompilationReports.hasCaptures(it) }
                .map { profile ->
                    val report = CompilationReports.analyzeAndWrite(profile, source, worker)
                    val gaps = (report.missing + report.incomplete).joinToString { it.what }
                    log(jobId, "case $index: compilation report ${dir.relativize(profile)}: ${report.totals.compilations} compilations, " +
                        "${"%.1f".format(report.totals.compilerMs)} ms compiler time" + if (gaps.isEmpty()) "" else "; missing or incomplete: $gaps")
                    profile to report
                }
        }.getOrElse { e ->
            log(jobId, "case $index: compilation report not written: ${e.message}")
            emptyList()
        }

    /**
     * Run analysis job [job]: the lab's analyser over the retained captures of the ended job it names, writing each
     * case's report afresh beside them, and a brief of them all in its own directory ([CompilationReports.ANALYSIS]).
     * Nothing is built and no klause code runs. It fails, saying why, when the captures were trimmed or are missing.
     */
    internal fun analyze(job: Job) {
        val spec = checkNotNull(job.experiment?.analyze)
        val dir = config.createJobDir(job.id)
        if (spec.kind == AnalyzeSpec.CHECK) return recheck(job, checkNotNull(spec.job), dir)
        if (spec.archive != null) return analyzeArchive(job, spec, dir)
        val analysed = checkNotNull(spec.job)
        val target = config.jobDir(analysed)
        CompilationReports.refusal(store.job(analysed), target)?.let { refusal ->
            log(job.id, "job failed: $refusal")
            store.finish(job.id, Status.FAILED, refusal)
            return
        }
        log(job.id, "analysing job $analysed's retained captures with the lab's ${spec.kind} analyser")
        log(analysed, "analysis job ${job.id} writes its compilation reports again from the retained captures")
        val indices = CompilationReports.profileDirs(target).mapNotNull { it.parent.name.toIntOrNull() }.distinct()
        val written = ArrayList<AnalysisEntry>()
        for (index in indices) {
            if (store.cancelRequested(job.id)) return cancel(job.id)
            for ((profile, report) in reportCompilation(analysed, index, target, analyzeJob = job.id)) {
                written += analysisEntry(index, target, profile, report)
            }
        }
        finishAnalysis(job, dir, AnalysisResult(analysed, spec.kind, written), "job $analysed's captures could not be analysed: see its job.log",
            "for job $analysed")
    }

    /**
     * Run recheck job [job]: the lab's source checkers over ended job [target]'s stored records, each verdict replacing
     * the case's earlier one, and a summary in [CaseChecks.RESULT]. Nothing is built and no solver runs.
     */
    private fun recheck(job: Job, target: Long, dir: Path) {
        CaseChecks.refusal(store.job(target))?.let { refusal ->
            log(job.id, "job failed: $refusal")
            store.finish(job.id, Status.FAILED, refusal)
            return
        }
        log(job.id, "checking job $target's records again with the lab's source checkers")
        log(target, "recheck job ${job.id} checks this job's solution claims again with the lab's source checkers")
        val result = checks.recheck(target) { store.cancelRequested(job.id) }
        if (store.cancelRequested(job.id)) return cancel(job.id)
        dir.resolve(CaseChecks.RESULT).writeAtomically(Json { encodeDefaults = true }.encodeToString(RecheckResult.serializer(), result))
        val states = result.states.entries.joinToString(", ") { "${it.value} ${it.key}" }.ifEmpty { "none" }
        log(job.id, "job finished: ${result.checked} of job $target's ${result.cases} cases in checked formats have a lab verdict: $states")
        store.finish(job.id, Status.DONE)
    }

    private fun analysisEntry(index: Int, root: Path, profile: Path, report: CompilationReport) = AnalysisEntry(
        index, root.relativize(profile.resolve(CompilationReports.REPORT)).joinToString("/"), report.totals.compilations,
        report.totals.compilerMs, report.complete, (report.missing + report.incomplete).map { it.what },
        report.tasks?.let { root.relativize(profile.resolve(it.file)).joinToString("/") },
    )

    private fun finishAnalysis(job: Job, dir: Path, result: AnalysisResult, empty: String, of: String) {
        dir.resolve(CompilationReports.ANALYSIS).writeAtomically(Json { encodeDefaults = true }.encodeToString(AnalysisResult.serializer(), result))
        if (result.reports.isEmpty()) {
            log(job.id, "job failed: $empty")
            store.finish(job.id, Status.FAILED, empty)
            return
        }
        store.finish(job.id, Status.DONE)
        log(job.id, "job finished: ${result.reports.size} reports written $of")
    }

    /**
     * Run analysis job [job] over the pinned archive [spec] names ([CaptureArchives]), a file under the lab's archive
     * directory: check it against its SHA-256, verify every member against its manifest, extract the profile
     * directories' captures under [CaptureArchives.ROOT], and write each one's report and task records there, with the
     * archive in their provenance. No original job is needed, nothing is built and no klause code runs; a refused archive fails the job,
     * saying why.
     */
    private fun analyzeArchive(job: Job, spec: AnalyzeSpec, dir: Path) {
        log(job.id, "analysing the captures in archive ${spec.archive} (sha256 ${spec.sha256}) with the lab's ${spec.kind} analyser")
        val fetched = try {
            CaptureArchives.fetch(spec, config, dir)
        } catch (e: Exception) {
            if (e !is IOException && e !is EvidenceException && e !is IllegalArgumentException) throw e
            val error = "archive refused: ${e.message}"
            log(job.id, "job failed: $error")
            store.finish(job.id, Status.FAILED, error)
            return
        }
        val archive = fetched.provenance
        log(job.id, "archive ${archive.name}: ${archive.bytes} bytes, sha256 ${archive.sha256} as pinned; ${archive.verifiedFiles} files verified " +
            "against manifest ${archive.manifest} (sha256 ${archive.manifestSha256}); ${fetched.profiles.size} profile directories extracted")
        val root = dir.resolve(CaptureArchives.ROOT)
        val worker = CompilationReports.worker(job.id)
        val written = ArrayList<AnalysisEntry>()
        for (profile in fetched.profiles) {
            if (store.cancelRequested(job.id)) return cancel(job.id)
            val member = root.relativize(profile).joinToString("/")
            val index = profile.parent.name.toInt()
            runCatching {
                val report = CompilationReports.analyzeAndWrite(profile, CaptureArchives.source(profile), worker, archive.copy(member = member))
                val gaps = (report.missing + report.incomplete).joinToString { it.what }
                log(job.id, "$member: ${report.totals.compilations} compilations, ${"%.1f".format(report.totals.compilerMs)} ms compiler time" +
                    if (gaps.isEmpty()) "" else "; missing or incomplete: $gaps")
                written += analysisEntry(index, dir, profile, report)
            }.onFailure { log(job.id, "$member: compilation report not written: ${it.message}") }
        }
        finishAnalysis(job, dir, AnalysisResult(kind = spec.kind, reports = written, archive = archive),
            "the archive holds no profile directory whose captures could be analysed", "from archive ${archive.name}")
    }


    internal fun <T> withRetry(jobId: Long?, what: String, backoff: Backoff = config.retry, step: () -> T): T = retrying(
        backoff, onRetry = { attempt, wait, error ->
            val text = "$what failed (try $attempt): ${error.message}; retrying in ${wait / 1000} s"
            if (jobId == null) println(text) else log(jobId, text)
        }, sleep = { wait ->
            val until = now() + wait
            while (now() < until) {
                check(!stopping) { "runner stopping" }
                if (jobId != null && store.cancelRequested(jobId)) throw JobCancelled("cancelled while waiting for $what")
                Thread.sleep((until - now()).coerceIn(1, 1000))
            }
        }, step = step)

    private fun capture(dir: File, cmd: String): String {
        val process = ProcessBuilder(Background.prefix + inSession("bash", "-c", cmd)).directory(dir).redirectErrorStream(true).start()
        running.add(process)
        try {
            val output = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0) { "git operation failed: $output" }
            return output
        } finally { running.remove(process) }
    }

    fun installShutdownHook() { Runtime.getRuntime().addShutdownHook(Thread { stop() }) }
    internal fun stop() {
        stopping = true
        running.forEach { process -> killGroup(process.pid()); process.destroyForcibly() }
    }
    internal fun cancel(jobId: Long) {
        store.cancelRemaining(jobId)
        store.finish(jobId, Status.CANCELLED)
        log(jobId, "analysis cancelled")
    }
    internal fun log(jobId: Long, message: String) {
        println("[job $jobId] $message")
        runCatching { config.createJobDir(jobId).resolve("job.log").toFile().appendText("${now()} $message\n") }
    }
    private companion object {
        const val IDLE_POLL_MS = 2000L
        const val SCHEDULE_POLL_MS = 10_000L
        const val PREVIOUS_RUNS = 50
        val SHA = Regex("[0-9a-f]{40}")
    }
}

/**
 * The Gradle tasks that build a Klause commit for an experiment: the CLI and the bench. A reference experiment runs
 * the lab's own launcher and builds the bench only to select and resolve its problems, so it needs no CLI. No checker
 * is packaged into the bench: the lab checks witnesses itself ([com.eignex.lab.checker]).
 */
internal fun klauseBuildTasks(referenceOnly: Boolean): String =
    if (referenceOnly) ":klause-bench:installDist" else ":klause-cli:installJvmDist :klause-bench:installDist"

/**
 * [command] wrapped so that it leads a new session, and so its own process group. perl is the portable way to
 * get there: macOS ships no `setsid` command, and both macOS and every common Linux base image ship perl.
 */
internal fun inSession(vararg command: String): List<String> =
    listOf("perl", "-e", "use POSIX qw(setsid); setsid() or die \"setsid: \$!\"; exec @ARGV or die \"exec: \$!\"") +
        command

/** SIGKILL every process in the group that [leader] leads. */
internal fun killGroup(leader: Long) {
    signalGroups("KILL", listOf(leader))
}

/**
 * Send [signal] (a name such as TERM or KILL, or 0 to send none) to every process in the groups [leaders] lead: whether
 * any process was there to take it.
 */
internal fun signalGroups(signal: String, leaders: Collection<Long>): Boolean {
    val kill = ProcessBuilder(listOf("kill", "-$signal", "--") + leaders.map { "-$it" }).redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    if (!kill.waitFor(SIGNAL_WAIT_SEC, TimeUnit.SECONDS)) {
        kill.destroyForcibly()
        return true
    }
    return kill.exitValue() == 0
}

/**
 * Stop the process groups [leaders] lead: SIGTERM, up to [graceMs] for every process in them to exit, then SIGKILL to
 * any group with one left, after calling [beforeKill]. With no grace, straight to SIGKILL. Whether SIGKILL was needed.
 */
internal fun stopGroups(leaders: Collection<Long>, graceMs: Long, beforeKill: () -> Unit = {}): Boolean {
    var left = leaders.toList()
    var interrupted = false
    if (graceMs > 0) {
        signalGroups("TERM", left)
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMs)
        try {
            while (true) {
                left = left.filter { signalGroups("0", listOf(it)) }
                val remaining = TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())
                if (left.isEmpty() || remaining <= 0) break
                Thread.sleep(remaining.coerceAtMost(GROUP_POLL_MS))
            }
        } catch (_: InterruptedException) {
            // Interrupted mid-grace: kill now, and leave the interrupt for the caller to see once that is done.
            interrupted = true
        }
    }
    try {
        if (left.isEmpty()) return false
        beforeKill()
        signalGroups("KILL", left)
        return true
    } finally {
        if (interrupted) Thread.currentThread().interrupt()
    }
}

private const val SIGNAL_WAIT_SEC = 1L
private const val GROUP_POLL_MS = 100L

/** Copy the tree at [source] to [target], replacing files already there. Links are not followed, nor copied. */
internal fun copyTree(source: Path, target: Path) {
    Files.walk(source).use { paths ->
        paths.filter { !it.isSymbolicLink() }.forEach { path ->
            val destination = target.resolve(source.relativize(path).toString())
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                destination.createDirectories()
            } else {
                destination.parent.createDirectories()
                Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

/** Delete [root] and everything under it without following symbolic links, which are removed as links. */
internal fun deleteTree(root: Path) {
    ProfileRunDeadline.checkActive()
    if (root.isSymbolicLink() || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
        Files.deleteIfExists(root)
        return
    }
    Files.walk(root).use { paths -> paths.peek { ProfileRunDeadline.checkActive() }.sorted(Comparator.reverseOrder()).forEach { path ->
        ProfileRunDeadline.checkActive()
        Files.delete(path)
    } }
}

/** Run git with [args] in [dir], no shell between: its exit and its output. */
internal fun gitIn(dir: File, args: List<String>): Pair<Int, String> {
    val process = ProfileRunDeadline.start(ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true)
        .apply { environment()["GIT_TERMINAL_PROMPT"] = "0" })
    val out = process.inputStream.bufferedReader().readText()
    return process.waitFor() to out
}

/** The most of a build's output [Runner] reads to tell why it failed. */
private const val MAX_BUILD_LOG_SCAN = 4L * 1024 * 1024

internal fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/** Write via a temp file and an atomic rename, so a reader never sees a half-written file. */
internal fun Path.writeAtomically(text: String) {
    val temp = resolveSibling("$fileName.tmp")
    temp.writeText(text)
    Files.move(temp, this, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

/** A job's step stopped, or not started, because the job's cancel was requested: never retried ([retrying]). */
class JobCancelled(message: String) : CancellationException(message)

/** Characters a `name=` selection reads as a glob, so a problem named with one cannot be asked for exactly. */
private val GLOB_CHARS = setOf('*', '?', '[', ',')
