package com.eignex.lab

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json

/**
 * Works the queue one job at a time. Each job checks its ref out into a fresh worktree, builds klause-cli, and
 * runs its commands in order. Every step's outcome is committed to the store before the next starts, so a crash
 * or reboot resumes the job at its first unfinished command; a command cut off mid-run is rerun from scratch.
 *
 * Every process the runner starts leads its own session, so killing that process group reaches everything the
 * command spawned, including children whose parent shell has already died. A case that reaches its time limit, or a
 * profile whose whole-run limit runs out, is stopped gracefully: SIGTERM to its group, [stopGraceMs] for it to flush
 * and exit (a JVM runs its shutdown hooks, JFR dumps its recording), then SIGKILL to what is left. A cancel or a
 * shutdown still kills at once: a cancelled case's results are not wanted, and a case cut off by a shutdown reruns
 * from scratch, while the service manager gives the runner only seconds to stop.
 */
class Runner(
    private val config: Config,
    private val store: Store,
    /** How long a case stopped at its limit has between SIGTERM and SIGKILL; shorter only in tests. */
    private val stopGraceMs: Long = Experiments.STOP_GRACE_SEC * 1000,
    /** What an analysis job downloads a saved-capture archive with; a test serves its own. */
    private val archiveHttp: CaptureArchives.Http = CaptureArchives.Http.DEFAULT,
) {
    /** Every process the runner has started and not yet seen exit, so cancel and shutdown can reach them all. */
    private val running: MutableSet<Process> = ConcurrentHashMap.newKeySet()

    /** The `<n>.termination` file ([Termination]) of each running case's process, where a kill says why first. */
    private val terminations = ConcurrentHashMap<Process, Path>()

    /** The running cases' processes the lab has killed, whose signal exit is then no outside kill. */
    private val killed: MutableSet<Process> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var stopping = false

    internal val lenient = Json { ignoreUnknownKeys = true }

    /** Held over every git operation on the shared mirror, which the schedule thread and job setup both touch. */
    private val mirrorLock = Any()

    /** Which commits a job may build: checked on the mirror and against GitHub's record of who pushed what. */
    internal val trust = Trust(
        config.trustedUsers,
        config.repoUrl,
        git = { args -> synchronized(mirrorLock) { gitIn(config.mirror.toFile(), args) } },
        github = { path -> Trust.githubApi(config.githubTokenFile.toFile().takeIf { it.isFile }?.readText()?.trim())(path) },
    )

    private val updater by lazy {
        SelfUpdater(
            config.sourceDir.takeIf { config.updateCheckSec > 0 },
            config.updateCheckSec * 1000,
            config.dataDir.resolve("logs").resolve("update.log").toFile(),
            requestFile = config.updateRequest.toFile(),
        )
    }

    /** Prunes the reference solvers' Docker images and build cache between jobs ([DockerCleanup]). */
    private val dockerCleanup by lazy {
        dockerCleanup(config, store) { pattern, revision -> synchronized(mirrorLock) { mirrorGrep(config.mirror.toFile())(pattern, revision) } }
    }

    fun loop() {
        config.dataDir.createDirectories()
        if (config.requireDocker) awaitDocker()
        val disk = DiskGuard(config.minFreeBytes) { freeBytes(config.dataDir) }
        // A job requeued with its cancel requested, before a requeue ended such a job, would wait for a host forever.
        runCatching { store.queuedCancels().forEach(::cancel) }.onFailure { println("cancelling requeued jobs failed: ${it.message}") }
        // Jobs queued before the lab placed them itself may name a host their submitter chose: placed again by the rules.
        runCatching { Placement.replaceQueued(store, config) }
            .onSuccess { if (it.isNotEmpty()) println("placed queued jobs again by the lab's rules: $it") }
            .onFailure { println("placing queued jobs failed: ${it.message}") }
        config.aws?.let { aws ->
            val worker = AwsWorker(this, store, config, aws)
            awsActive = { worker.active }
            thread(isDaemon = true, name = "aws") { worker.loop() }
        }
        // Reference rows lab runs made before sources were kept learn them from those runs' records, off the loop.
        thread(isDaemon = true, name = "bind-references") {
            runCatching { store.bindLabReferenceSources() }
                .onSuccess { if (it > 0) println("bound $it reference rows to their runs' source files") }
                .onFailure { println("binding reference rows failed: ${it.message}") }
        }
        // Ended jobs' bulk goes after a week, or sooner when the disk runs low ([JobTrim]).
        JobTrim(config.dataDir.resolve("jobs"), store::ended, { freeBytes(config.dataDir) }, ::log).start()
        // On its own thread, so a schedule queues its run on time while a long job holds the runner; the queue's
        // priorities then decide when it runs.
        thread(isDaemon = true, name = "schedules") {
            while (true) {
                runCatching { enqueueScheduled() }.onFailure { println("schedules: ${it.message}") }
                Thread.sleep(SCHEDULE_POLL_MS)
            }
        }
        while (true) {
            // Only between jobs: an update builds on this machine and restarts the runner, so a job yields to it
            // between its cases (see dispatch) and the update runs here with nothing running.
            if (!awsActive()) updater.maybeUpdate()
            // Also only between jobs, so no case of the runner's starts a container while images go.
            runCatching { dockerCleanup.maybeRun() }.onFailure { println("docker cleanup: ${it.message}") }
            if (!disk.allowsWork()) {
                Thread.sleep(IDLE_POLL_MS)
                continue
            }
            // While a cloud node is up or launching it can take the auto jobs, so local-only ones go first at equal priority.
            val job = store.next(nodesUp = awsActive())
            if (job == null) {
                Thread.sleep(IDLE_POLL_MS)
                continue
            }
            if (job.experiment?.profile?.runTimeoutSec != null) {
                workWithDeadline(job)
                continue
            }
            if (job.experiment?.isAnalysis == true) {
                runCatching { analyze(job) }.onFailure { e ->
                    if (stopping) return
                    log(job.id, "job failed: ${e.message}")
                    store.finish(job.id, Status.FAILED, e.message ?: e.toString())
                }
                continue
            }
            runCatching { work(job) }.onFailure { e ->
                if (stopping) return
                // A cancel that lands while a setup step waits to retry ends the wait; it is a cancel, not a failure.
                if (store.cancelRequested(job.id)) return@onFailure cancel(job.id)
                log(job.id, "job failed: ${e.message}")
                store.cancelRemaining(job.id)
                store.finish(job.id, Status.FAILED, e.message ?: e.toString())
                runCatching { removeWorktree(job.id) }
            }
        }
    }

    private fun work(claimed: Job) {
        finishWork(claimed, executeWork(claimed))
    }

    internal fun workWithDeadline(job: Job) {
        val limit = checkNotNull(job.experiment?.profile?.let(Experiments::profileRunTimeoutSec)) * MS_PER_SEC
        ProfileRunDeadline(limit - job.elapsedMs(now()), cancelled = { store.cancelRequested(job.id) }, graceMillis = stopGraceMs).use { deadline ->
            var outcome: Dispatched? = null
            var failure: Throwable? = null
            try {
                outcome = deadline.work { executeWork(job) }
            } catch (error: Throwable) {
                if (stopping) return
                failure = error
            }
            if (outcome == Dispatched.YIELDED && failure == null) {
                if (store.requeue(job.id) == Status.CANCELLED) return cancel(job.id)
                log(job.id, "job paused between commands")
                return
            }
            try {
                deadline.cleanup { removeWorktree(job.id) }
            } catch (cleanup: Throwable) {
                if (failure == null) failure = cleanup else failure.addSuppressed(cleanup)
                log(job.id, "worktree cleanup incomplete: ${cleanup.message}")
            }
            if (store.cancelRequested(job.id) || outcome == Dispatched.CANCELLED) {
                store.cancelRemaining(job.id)
                store.finish(job.id, Status.CANCELLED)
                log(job.id, "job cancelled")
            } else if (failure != null || outcome != Dispatched.FINISHED) {
                store.cancelRemaining(job.id)
                val error = failure?.message ?: "profile did not finish"
                store.finish(job.id, Status.FAILED, error)
                log(job.id, "job failed: $error")
            } else {
                store.finish(job.id, Status.DONE)
                log(job.id, "job finished")
            }
        }
    }

    private fun executeWork(claimed: Job): Dispatched {
        val dir = config.jobDir(claimed.id).createDirectories()
        killOrphan(dir.resolve("setup.pid"))
        claimed.commands.filter { it.status == Status.RUNNING }.forEach {
            killOrphan(dir.resolve("${it.index}.pid"), Termination.file(dir, it.index))
        }
        store.requeueInterrupted(claimed.id)
        var job = checkNotNull(store.job(claimed.id))
        if (!job.setupDone || !config.worktree(job.id).exists()) {
            val sha = setupExperiment(job, dir)
            ProfileRunDeadline.checkActive()
            store.setup(job.id, sha)
            job = checkNotNull(store.job(job.id))
        }
        return dispatch(job, dir)
    }

    private fun finishWork(job: Job, outcome: Dispatched) {
        when (outcome) {
            Dispatched.CANCELLED -> return cancel(job.id)
            // Its worktree and finished commands stay, so the job resumes at its next command when picked again.
            Dispatched.YIELDED -> {
                if (store.requeue(job.id) == Status.CANCELLED) return cancel(job.id)
                log(job.id, "job paused between commands")
                return
            }
            Dispatched.FINISHED -> Unit
            // Only an AWS instance is lost; this dispatch asks for no `lost` callback, so a lost host stays an error.
            Dispatched.LOST -> error("the lab machine dispatched as a lost host")
        }
        // A job that ran every command is done; which commands failed is on the commands. FAILED is kept for a job
        // that could not run, so a sweep with a few refused instances does not read as a broken job.
        store.finish(job.id, Status.DONE)
        removeWorktree(job.id)
        log(job.id, "job finished")
        runCatching { queueConfirmation(job) }.onFailure { log(job.id, "could not queue a confirmation: ${it.message}") }
    }

    /**
     * Run [job]'s queued commands in index order, up to the job's `parallel` many at once, and record each result
     * as it ends. The limit is reread from the store on every pass, so it can be raised or lowered while the job
     * runs: raising it starts more commands at once, lowering it lets the running ones finish and starts no more
     * until the count is below it. Before each command it checks whether the job was paused or a higher-priority job
     * waits; then it starts no more and returns once the running ones end, so a job yields only between commands.
     */
    internal fun dispatch(
        job: Job,
        dir: Path,
        host: ExecutionHost = LocalHost(job, "", File("/dev/null")),
        /**
         * Where the cases come from when a job is split over several hosts: the next problem's case indices, taken
         * when this host has room for another case, null once every problem is taken. Null runs every queued case.
         */
        next: (() -> Collection<Int>?)? = null,
        /** Told the cases this host held, started or not, when the host went away ([HostLost]): they go back to the
         *  queue [next] reads, and the dispatch ends [Dispatched.LOST] rather than failing the job. The cases it
         *  finished of an iteration it did not finish go back with them, to run again beside the rest of it
         *  ([Store.requeueSplitIterations]): an iteration's arms are compared, so they run on one machine. */
        lost: ((Collection<Int>) -> Unit)? = null,
    ): Dispatched {
        // [next] hands out only cases left to run, which a host that started earlier can hold as running.
        val queued = job.commands.filter { it.status == Status.QUEUED || next != null }.associateBy { it.index }
        val pending = ArrayDeque(if (next == null) queued.values else emptyList())
        // Cases already started elsewhere, or finished, are not in [queued]: a problem with none left is skipped.
        fun take() {
            if (next != null) while (pending.isEmpty()) next()?.mapNotNullTo(pending) { queued[it] } ?: break
        }
        take()
        val active = LinkedHashMap<Int, Future<Int>>()
        val finished = mutableSetOf<Int>()
        val pool = Executors.newCachedThreadPool()
        var cancelled = false
        var yielding = false
        try {
            while (pending.isNotEmpty() || active.isNotEmpty()) {
                ProfileRunDeadline.checkActive()
                // Shutdown kills the commands and leaves them RUNNING to rerun; nothing here may record them.
                while (stopping) Thread.sleep(CANCEL_POLL_MS)
                for (index in active.filterValues { it.isDone }.keys) {
                    // Read before removed: a case whose host was lost is still held, and goes back to the queue.
                    val exit = active.getValue(index).get()
                    active.remove(index)
                    if (exit == CANCELLED_EXIT) {
                        cancelled = true
                    } else {
                        store.commandFinished(job.id, index, exit)
                        finished += index
                        keepRecord(job.id, index, dir)
                        if (job.experiment?.instrumented == true) reportCompilation(job.id, index, dir)
                    }
                }
                // Another problem only once this host has room for a case of it, so the problems left go to whichever
                // host frees up first, a host added later included.
                if (!cancelled && !yielding && active.size < host.parallel(store.parallel(job.id))) take()
                // Each running command sees the request and kills its own tree; the job ends once they have.
                if (!cancelled && store.cancelRequested(job.id)) cancelled = true
                if (cancelled) {
                    if (active.isEmpty()) return Dispatched.CANCELLED
                } else if (yielding || (pending.isNotEmpty() && (yields(job.id, host) || host.yieldsToUpdates && updateDue(job.id)))) {
                    yielding = true
                    if (active.isEmpty()) return Dispatched.YIELDED
                } else {
                    // Within the job's case limit and the lab's core budget, in order: a case too big for the cores
                    // left waits for room rather than letting smaller later ones jump it. One case always may run.
                    val limit = host.parallel(store.parallel(job.id))
                    while (pending.isNotEmpty() && active.size < limit &&
                        (active.isEmpty() || held(active.keys, job) + pending.first().cores <= host.cores)
                    ) {
                        val command = pending.removeFirst()
                        ProfileRunDeadline.checkActive()
                        store.commandStarted(job.id, command.index)
                        active[command.index] = pool.submit(ProfileRunDeadline.task { host.run(command, dir) })
                    }
                }
                Thread.sleep(DISPATCH_POLL_MS)
            }
            return Dispatched.FINISHED
        } catch (e: ExecutionException) {
            if (e.cause is HostLost && lost != null) {
                store.requeueCommands(job.id, active.keys)
                val held = active.keys + pending.map { it.index } + store.requeueSplitIterations(job.id, finished)
                lost(held)
                log(job.id, "${e.cause?.message}: cases ${held.sorted().joinToString()} go back to the queue")
                return Dispatched.LOST
            }
            // One command's failure to run fails the job, which must not leave its siblings running past it.
            if (!ProfileRunDeadline.isActive()) running.forEach { kill(it, Termination.SIBLING) }
            throw e.cause ?: e
        } finally {
            pool.shutdown()
            pool.awaitTermination(KILL_WAIT_SEC, TimeUnit.SECONDS)
        }
    }

    /**
     * After a scheduled run, queue a [Confirm] job for the problems it flipped against the run before it of the same
     * experiment at another commit. Nothing is queued for a confirmation itself, a first run, or a run with no flips.
     */
    private fun queueConfirmation(job: Job) {
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
        val id = store.create(confirmation.name, after, emptyList(), confirmation.parallel ?: config.defaultParallel, confirmation.priority, confirmation)
        log(job.id, "${flips.lost.size} feasibility lost, ${flips.gained.size} gained; " +
            "${flips.proofLost.size} proof lost, ${flips.proofGained.size} gained; " +
            "${flips.objectiveLost.size} objective worse, ${flips.objectiveGained.size} better against job ${previous.id}: queued confirmation job $id")
    }

    private fun firstArmCases(jobId: Long): List<CaseResult> {
        val label = store.arms(jobId).firstOrNull()?.arm?.label ?: return emptyList()
        return store.cases(jobId).filter { it.arm == label }
    }

    /** The one reference solver every arm of a reference run names, whose own results its filter then reads: a
     *  second solver's `missing` is the problems that solver has no result for, whatever the first one has. */
    private fun referenceSolver(spec: ExperimentSpec): String? {
        val arms = Experiments.arms(spec)
        if (arms.any { it.values["backend"] != REFERENCE_BACKEND }) return null
        return arms.map { it.values["solver"] }.distinct().singleOrNull()
    }

    private fun yields(jobId: Long, host: ExecutionHost): Boolean =
        if (host.yieldsToPriority) store.shouldYield(jobId) else store.job(jobId)?.paused == true

    /** Whether a lab update waits, logged once to [jobId] when it makes the job yield. */
    private fun updateDue(jobId: Long): Boolean = !awsActive() && updater.due().also { if (it) log(jobId, "yielding for a lab update") }

    /** Whether a lab update waits: the AWS worker then starts no new job, so the running ones drain and it can land. */
    internal fun updateWaiting(): Boolean = updater.due()

    /** Whether a cloud node is up or launching ([AwsWorker.active]), which holds a lab update back and puts the
     *  local-only jobs first among equal priorities ([Store.next]); always false without AWS settings. */
    private var awsActive: () -> Boolean = { false }

    /** Cores the running cases at [indices] of [job] hold. */
    private fun held(indices: Collection<Int>, job: Job): Int = job.commands.filter { it.index in indices }.sumOf { it.cores }

    /**
     * Queue a run of each due schedule whose ref has moved since its last run, pinned to the commit it resolves to, so
     * the job records exactly what it ran. A schedule whose ref has not moved queues nothing, and one whose previous run
     * has not ended waits for it. A branch or tag is first asked of origin directly, one small request, so a check that
     * finds it unmoved fetches nothing and checks can come every minute or two; the mirror is fetched only when it
     * moved, or for a ref origin does not advertise, such as a commit.
     */
    private fun enqueueScheduled() {
        val due = store.schedules().filter { s -> s.checkedAt?.let { now() - it >= s.intervalSec * 1000 } ?: true }
        for (schedule in due) {
            store.scheduleChecked(schedule.id, now())
            // A launch asked for while the last run is still going waits for it rather than queueing a second.
            if (schedule.lastJob?.let(store::job)?.status in setOf(Status.QUEUED, Status.RUNNING)) continue
            val advertised = advertised(schedule.ref)
            if (!schedule.launchRequested && advertised != null && advertised == schedule.lastSha) continue
            val fetched = runCatching {
                withRetry(null, "schedule ${schedule.name}: fetching") {
                    synchronized(mirrorLock) {
                        if (!config.mirror.exists()) {
                            capture(config.dataDir.toFile(), "git clone --mirror ${quote(config.repoUrl)} ${quote(config.mirror.toString())}")
                        }
                        capture(config.mirror.toFile(), "git fetch --prune origin '+refs/heads/*:refs/heads/*' '+refs/tags/*:refs/tags/*'")
                    }
                }
            }
            if (fetched.isFailure) {
                // Left due rather than checked, so the next poll tries again instead of a whole interval later.
                println("schedule ${schedule.name}: fetch failed, retrying on the next poll: ${fetched.exceptionOrNull()?.message}")
                store.checkNow(schedule.id)
                continue
            }
            // A ref origin no longer has is not retried: it waits for the next interval like an unmoved one.
            val sha = runCatching {
                synchronized(mirrorLock) { capture(config.mirror.toFile(), "git rev-parse --verify ${quote(schedule.ref + "^{commit}")}") }
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
    private fun advertised(ref: String): String? = runCatching {
        capture(
            config.dataDir.toFile(),
            "GIT_TERMINAL_PROMPT=0 git ls-remote ${quote(config.repoUrl)} ${quote("refs/heads/$ref")} ${quote("refs/tags/$ref")}",
        )
    }.getOrNull()?.lineSequence()?.map { it.substringBefore('\t').trim() }?.firstOrNull { SHA.matches(it) }

    /**
     * Set an experiment up: build every commit its arms name, each in its own worktree, and on the first setup plan
     * it. Planning selects the problems with the bench at the first arm's commit, refuses a plan whose estimate is
     * over [Config.maxExperimentHours] unless the spec confirms it, and writes a command per case. A setup after a
     * crash or a yield only rebuilds, against the commits the plan recorded.
     */
    private fun setupExperiment(job: Job, dir: Path): String {
        val spec = checkNotNull(job.experiment)
        val log = dir.resolve("setup.log").toFile()
        log.writeText("")
        val (arms, shas) = resolveArms(job, spec, log)
        val primary = shas.getValue(arms.first().ref)
        // A reference case runs the lab's own launcher; the bench is built only to select and resolve the problems.
        val tasks = if (arms.all { it.values["backend"] == REFERENCE_BACKEND }) ":klause-bench:installDist"
            else ":klause-cli:installJvmDist :klause-bench:installDist"
        removeWorktree(job.id)
        for (sha in shas.values.distinct()) {
            val path = experimentWorktree(job.id, sha, primary)
            withRetry(job.id, "adding the worktree for ${sha.take(9)}") {
                synchronized(mirrorLock) {
                    // What a failed try left would make the next one refuse the path.
                    removeTree(path)
                    path.parent.createDirectories()
                    sh(log, config.mirror.toFile(), "git worktree add --detach ${quote(path.toString())} $sha")
                }
            }
            linkShared(path)
            withRetry(job.id, "building ${sha.take(9)}", config.buildRetry) {
                val start = log.length()
                runCatching {
                    sh(log, path.toFile(),
                        "./gradlew $tasks --max-workers=${config.gradleWorkers} -q" +
                            if (ProfileRunDeadline.isActive()) " --no-daemon -Pkotlin.compiler.execution.strategy=in-process" else "")
                }.onFailure { e ->
                    if (!ProfileRunDeadline.isActive() && brokenTransforms(log, start)) {
                        log(job.id, "the build found Gradle's transform cache broken: clearing it before the next try")
                        clearTransforms(path.toFile())
                    }
                    throw e
                }
                // A profileCli job's launcher goes in before the provenance manifest describes the build, as on a node.
                if (spec.profileCli) sh(log, path.toFile(), CliMeasurements.install(path.toString(), CliMeasurements.localMacOs))
                sh(log, path.toFile(), Experiments.captureProvenance(path.toString()))
            }
        }
        if (job.commands.isEmpty()) plan(job, spec, arms, shas, primary, dir, LocalHost(job, primary, log))
        else adopt(job, spec, LocalHost(job, primary, log))
        dir.resolve("sha").writeAtomically(primary)
        return primary
    }

    /** [job]'s arms and the commit each ref names: the ones its plan recorded, or, unplanned, resolved now on the
     *  freshly fetched mirror. */
    internal fun resolveArms(job: Job, spec: ExperimentSpec, log: File): Pair<List<Arm>, Map<String, String>> {
        val planned = store.arms(job.id)
        val arms = planned.map { it.arm }.ifEmpty { Experiments.arms(spec) }
        withRetry(job.id, "fetching the mirror") { synchronized(mirrorLock) { fetchMirror(log) } }
        val shas = synchronized(mirrorLock) {
            planned.associate { it.arm.ref to it.sha }.ifEmpty { arms.map { it.ref }.distinct().associateWith(::resolve) }
        }
        // Before anything is built: a job runs only code a trusted user pushed ([Trust]).
        for ((ref, sha) in shas) withRetry(job.id, "checking who pushed $ref") { trust.requireCommit(sha, ref) }
        return arms to shas
    }

    internal fun plan(job: Job, spec: ExperimentSpec, arms: List<Arm>, shas: Map<String, String>, primary: String, dir: Path, host: ExecutionHost) {
        // Each selection is capped on its own; a problem two selections share is solved once.
        // Selections interleave, so a sweep cut short or paused part-way has covered every selection, not the first few.
        val budgetMs = arms.maxOf { it.timeoutMs }
        require(!spec.isScript) { "script jobs are retired: the lab runs only committed code; resubmit as a profile job" }
        if (spec.isProfile) {
            planCases(job, spec, arms, shas, listOf(Experiments.profileProblem(spec)), host)
            return
        }
        val problems = spec.problemList.ifEmpty { selectProblems(job, spec, host.worktree(primary), budgetMs, dir, host) }
        require(problems.isNotEmpty()) { "the selection matched no problems the reference filter keeps" }
        classify(job, problems, host.worktree(primary), host)
        val solved = if (arms.any { it.values["backend"] == REFERENCE_BACKEND }) {
            withFiles(job, problems, host.worktree(primary), host)
        } else {
            problems
        }
        planCases(job, spec, arms, shas, solved, host)
    }

    /**
     * [problems] with the files `select files=true` resolves, which a reference arm's cases solve without the bench.
     * Asked by name, one suite at a time, so a named problem list resolves as a selection does.
     */
    private fun withFiles(job: Job, problems: List<Problem>, worktree: String, host: ExecutionHost): List<Problem> {
        val resolved = HashMap<Pair<String, String>, Problem>()
        for ((suite, group) in problems.filter { it.model == null }.groupBy { it.suite }) {
            val names = group.map { it.problem }
            require(names.none { name -> name.any { it in GLOB_CHARS } }) {
                "a reference case needs each problem named exactly; $suite has a pattern among ${names.joinToString()}"
            }
            val asked = mapOf("suite" to suite, "name" to names.joinToString(","), "per-family" to "1000000",
                "files" to "true")
            val lines = withRetry(job.id, "resolving $suite's problem files", config.buildRetry) {
                host.select(worktree, Experiments.selectArgs(asked))
            }
            for (problem in lines.filter { it.startsWith("{") }.map { lenient.decodeFromString<Problem>(it) }) {
                resolved[problem.suite to problem.problem] = problem
            }
        }
        return problems.map { problem ->
            if (problem.model != null) return@map problem
            val files = requireNotNull(resolved[problem.suite to problem.problem]) {
                "the bench resolved no files for ${problem.suite}/${problem.problem}"
            }
            problem.copy(format = problem.format.ifEmpty { files.format }, model = files.model, data = files.data,
                includeDirs = files.includeDirs)
        }
    }

    /**
     * Keep the classifier's features (themes, structure, logic) of the planned [problems] the lab has none for yet,
     * which the reference scoreboard groups problems by. Asked by name, one suite at a time, so only those are
     * classified; a failure only leaves them unclassified.
     */
    private fun classify(job: Job, problems: List<Problem>, worktree: String, host: ExecutionHost) {
        val known = store.features(problems.map { it.collection to it.problem }).keys
        val missing = problems.filter { (it.collection to it.problem) !in known && it.problem.none { c -> c in GLOB_CHARS } }
        for ((suite, group) in missing.groupBy { it.suite }) {
            runCatching {
                val asked = mapOf("suite" to suite, "name" to group.joinToString(",") { it.problem }, "per-family" to "1000000",
                    "features" to "true")
                val features = host.select(worktree, Experiments.selectArgs(asked)).filter { it.startsWith("{") }
                    .map { lenient.decodeFromString<ProblemFeatures>(it) }.filter { it.themes.isNotEmpty() }
                store.putFeatures(features)
            }.onFailure { log(job.id, "classifying $suite's problems failed, they stay unclassified: ${it.message}") }
        }
    }

    /** The problems [spec]'s selections pick, each selection asked of the bench at [worktree] and filtered and capped. */
    private fun selectProblems(job: Job, spec: ExperimentSpec, worktree: String, budgetMs: Long, dir: Path, host: ExecutionHost): List<Problem> =
        interleave(spec.problems.withIndex().map { (index, problemSelection) ->
            val selection = dir.resolve("selection-$index.jsonl")
            val mode = Experiments.referenceFilter(spec, problemSelection)
            // A filtered, capped selection asks the bench for the problems without its caps and caps what the filter
            // keeps, so the problems the reference never decided are replaced rather than leaving the selection short.
            val refill = Experiments.refills(mode, problemSelection)
            val asked = if (refill) Experiments.uncapped(problemSelection) else problemSelection
            // Selecting can fetch a corpus the cache does not hold yet.
            val lines = withRetry(job.id, "selecting problems for selection $index", config.buildRetry) {
                host.select(worktree, Experiments.selectArgs(asked))
            }
            selection.toFile().writeText(lines.joinToString("\n", postfix = "\n"))
            val selected = lines.filter { it.startsWith("{") }.map { lenient.decodeFromString<Problem>(it) }
            val references = store.references(selected.map { it.collection to it.problem }, referenceSolver(spec))
            val kept = selected.filter { mode.keeps(references[it.collection to it.problem], budgetMs) }
            val planned = if (refill) Experiments.cap(kept, problemSelection["per-family"]?.toIntOrNull(), problemSelection["max"]?.toIntOrNull()) else kept
            log(job.id, "selection $index (${Experiments.selectArgs(problemSelection)}, reference=${mode.name.lowercase()}): " +
                "${selected.size} selected, ${selected.size - kept.size} left out by the reference filter, ${planned.size} planned")
            Experiments.defaultCapped(problemSelection, selected)?.let { log(job.id, "selection $index: $it") }
            planned
        }).distinctBy { it.suite to it.problem }

    private fun planCases(job: Job, spec: ExperimentSpec, arms: List<Arm>, shas: Map<String, String>, problems: List<Problem>, host: ExecutionHost) {
        val cases = Experiments.cases(problems.size, arms.size, spec.seeds, spec.repeats)
        val parallel = spec.parallel ?: config.defaultParallel
        val hours = Experiments.estimateHours(cases, arms, parallel, host.cores)
        require(spec.confirm || hours <= config.maxExperimentHours) {
            "${cases.size} cases could take %.1f h at ×$parallel, over the ${config.maxExperimentHours} h limit; ".format(hours) +
                "resubmit with \"confirm\": true"
        }
        val commands = cases.mapIndexed { index, case ->
            val arm = arms[case.arm]
            val cmd = caseCommand(spec, arm, problems[case.problem], case.seed, index, host.worktree(shas.getValue(arm.ref)), host)
            val timeout = if (spec.profile != null) Experiments.profileTimeoutSec(arm) else Experiments.caseTimeoutSec(arm)
            CaseCommand(cmd, timeout, arm.cores)
        }
        store.planCommands(job.id, arms.map { PlannedArm(it, shas.getValue(it.ref)) }, problems, cases, commands)
        if (spec.profile != null) {
            log(job.id, "profile time limit: ${Experiments.profileLimitText(spec)}; SIGTERM at it, SIGKILL ${Experiments.STOP_GRACE_SEC} s later")
            Experiments.profileCaps(spec).forEach { log(job.id, "capped: $it") }
        }
        spec.capped.forEach { log(job.id, "capped to fit the local machine: $it") }
        log(job.id, "planned ${problems.size} problems × ${arms.size} arms = ${cases.size} cases, up to %.1f h".format(hours))
    }

    /** The command line of one case of [spec] on [host], in [worktree]: a profile's main, or a solve of [problem]. */
    private fun caseCommand(spec: ExperimentSpec, arm: Arm, problem: Problem, seed: Long?, index: Int, worktree: String, host: ExecutionHost): String {
        val profile = spec.profile
        return if (profile != null) Experiments.profileCommand(worktree, profile, index, host.corpus)
        else if (arm.values["backend"] == REFERENCE_BACKEND) Experiments.referenceCommand(config.launcher, problem, arm, seed, index)
        else Experiments.command(worktree, problem, arm, seed, index, host.corpus)
    }

    /**
     * Take over planned [job] on [host], which sets it up afresh: the local machine taking a job a node planned, or a
     * node claiming one again. Every iteration left part done runs again whole here ([Store.requeueSplitIterations]),
     * and the queued commands are written again for this host's worktrees and corpus, since the plan named the paths of
     * the host that made it. The finished cases and their results stay.
     */
    internal fun adopt(job: Job, spec: ExperimentSpec, host: ExecutionHost) {
        val again = store.requeueSplitIterations(job.id)
        if (again.isNotEmpty()) log(job.id, "cases ${again.joinToString()} run again: their iterations were only part done elsewhere")
        val arms = store.arms(job.id).associateBy { it.arm.label }
        val commands = store.casePlan(job.id).filter { it.status == Status.QUEUED }.associate { case ->
            val planned = arms.getValue(case.arm)
            case.index to caseCommand(spec, planned.arm, case.problem, case.seed, case.index, host.worktree(planned.sha), host)
        }
        store.rewriteCommands(job.id, commands)
    }

    /** The lab machine itself: worktrees under the data directory, the shared corpus cache, the lab's core budget. */
    private inner class LocalHost(private val job: Job, private val primary: String, private val log: File) : ExecutionHost {
        override val cores get() = config.cores
        override val corpus get() = config.corpusDir.toString()
        override val yieldsToUpdates = true
        override val yieldsToPriority = true
        override val maxParallel get() = config.maxParallel

        override fun worktree(sha: String) = experimentWorktree(job.id, sha, primary).toString()

        override fun select(worktree: String, args: String): List<String> {
            val opts = "-Dklause.bench.corpusCache=${config.corpusDir} -Dklause.workspace.root=$worktree"
            val out = Files.createTempFile("select", ".jsonl")
            try {
                sh(log, File(worktree, "klause-bench"),
                    "JAVA_OPTS=${quote(opts)} KLAUSE_BENCH_CORPUS_MAX_GB=off ./build/install/klause-bench/bin/klause-bench " +
                        "select $args > ${quote(out.toString())}")
                return out.toFile().readLines()
            } finally {
                Files.deleteIfExists(out)
            }
        }

        override fun run(command: Command, dir: Path): Int = this@Runner.run(job, command, dir)
    }

    /** The worktree an experiment builds [sha] in: the job's own for its first arm's commit, a sibling for others. */
    private fun experimentWorktree(jobId: Long, sha: String, primary: String): Path =
        if (sha == primary) config.worktree(jobId) else config.worktree(jobId).resolveSibling("$jobId@${sha.take(SHA_DIR_LENGTH)}")

    /** Keep the record a finished case wrote; a case that wrote none (it could not run) keeps none. */
    internal fun keepRecord(jobId: Long, index: Int, dir: Path) {
        val record = dir.resolve(CASES).resolve(index.toString()).toFile().listFiles { f -> f.extension == "json" }?.singleOrNull()
        runCatching {
            val text = record?.readText() ?: return@runCatching
            Json.parseToJsonElement(text) // a record that does not parse is not kept: it would break every page that reads it
            store.caseRecord(jobId, index, text)
            promote(jobId, index, text)
        }.onFailure { log(jobId, "case $index: record not kept: ${it.message}") }
    }

    /**
     * Write the compilation report ([CompilationReports]) of each of case [index]'s profile directories under job
     * directory [dir] that has none yet, on the lab, once a node's captures have come back too. An analysis job
     * ([analyzeJob]) writes each one again from the captures still there. A report that cannot be written is logged
     * and the job goes on: the captures stay for an analysis job to try again.
     */
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
        val dir = config.jobDir(job.id).createDirectories()
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
     * Run analysis job [job] over the pinned archive [spec] names ([CaptureArchives]): download it into [dir], check it
     * against its SHA-256, verify every member against its manifest, extract the profile directories' captures under
     * [CaptureArchives.ROOT], and write each one's report and task records there, with the archive in their
     * provenance. No original job is needed, nothing is built and no klause code runs; a refused archive fails the job,
     * saying why.
     */
    private fun analyzeArchive(job: Job, spec: AnalyzeSpec, dir: Path) {
        log(job.id, "analysing the captures in archive ${spec.archive} (sha256 ${spec.sha256}) with the lab's ${spec.kind} analyser")
        val fetched = try {
            CaptureArchives.fetch(spec, config, dir, archiveHttp)
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

    /** A case of a reference arm adds its verdict to the reference results as it finishes, so a run cut short still
     *  contributes what it solved; a stored stronger verdict stays. */
    private fun promote(jobId: Long, index: Int, record: String) {
        val (problem, arm) = store.caseOf(jobId, index) ?: return
        if (arm.values["backend"] != REFERENCE_BACKEND || problem.collection.isEmpty()) return
        val reference = References.of(Json.parseToJsonElement(record)) ?: return
        store.putReferences(listOf((problem.collection to problem.problem) to reference), "lab:$jobId")
    }

    private fun fetchMirror(log: File) {
        if (!config.mirror.exists()) {
            sh(log, config.dataDir.toFile(), "git clone --mirror ${quote(config.repoUrl)} ${quote(config.mirror.toString())}")
        }
        sh(log, config.mirror.toFile(), "git fetch --prune origin '+refs/heads/*:refs/heads/*' '+refs/tags/*:refs/tags/*'")
    }

    private fun resolve(ref: String): String =
        runCatching { capture(config.mirror.toFile(), "git rev-parse --verify ${quote(ref + "^{commit}")}") }
            .getOrElse { error("unknown ref: $ref (not on origin; is it pushed?)") }

    /**
     * Point each of [Config.sharedPaths] in the worktree at one directory every job uses. The bench result cache is
     * the reason: reference results are keyed by instance, solver and budget alone, so a later job replays them
     * instead of rerunning the reference, and klause results also key on the CLI binary each job builds afresh.
     */
    private fun linkShared(worktree: Path) {
        for (relative in config.sharedPaths) {
            ProfileRunDeadline.checkActive()
            val target = config.sharedDir.resolve(relative).createDirectories()
            val link = worktree.resolve(relative)
            link.parent.createDirectories()
            if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) deleteTree(link)
            Files.createSymbolicLink(link, target)
        }
    }

    /**
     * Run one case's command. Output streams straight to disk; the exit code is written atomically
     * once the process ends, so a file named `<n>.exit` always holds a complete result. The whole process tree is
     * killed on timeout or cancellation.
     */
    private fun run(job: Job, command: Command, dir: Path): Int {
        val work = config.worktree(job.id).toFile()
        val builder = ProcessBuilder(inSession("bash", "-c", command.cmd))
            .directory(work)
            .redirectOutput(dir.resolve("${command.index}.out").toFile())
            .redirectError(dir.resolve("${command.index}.err").toFile())
        // A profileCli case's CLI records JFR and its resources into a directory of its own under the case, as on a node.
        val measurementId = if (job.experiment?.profileCli == true) UUID.randomUUID().toString() else null
        val profile = measurementId?.let { dir.resolve("$CASES/${command.index}/profile-$it").createDirectories() }
        val compilation = job.experiment?.profileCompilation
        val runtimeOptions = "${config.solveJavaOpts} -XX:ActiveProcessorCount=${command.cores}".trim()
        val javaOptions = ("$runtimeOptions " + profile?.let { CliMeasurements.options(it.toString(), compilation) }.orEmpty()).trim()
        builder.environment().apply {
            put("JOB_DIR", dir.toString())
            // The solve sees the cores its case holds, so a portfolio sizes its threads to them and a single-engine
            // solve does not size its pools to the whole machine.
            put("KLAUSE_CLI_OPTS", javaOptions)
            // The bench's `--version` check before the solve runs without the recording options.
            put("KLAUSE_CLI_PREFLIGHT_OPTS", runtimeOptions)
            profile?.let { put("KLAUSE_LAB_PROFILE_DIR", it.toString()) }
            // Host BLAS libraries otherwise size their own thread pools to the machine.
            put("OPENBLAS_NUM_THREADS", "1")
            put("VECLIB_MAXIMUM_THREADS", "1")
            put("MKL_NUM_THREADS", "1")
            put("OMP_NUM_THREADS", "1")
            if (config.javaHome.isNotBlank()) put("JAVA_HOME", config.javaHome)
        }
        log(job.id, "command ${command.index}: ${command.cmd}")
        val process = start(builder, dir.resolve("${command.index}.pid"))
        terminations[process] = Termination.file(dir, command.index)
        try {
            return await(job, command, dir, process).also { exit ->
                if (profile != null && exit != CANCELLED_EXIT) runCatching {
                    val record = dir.resolve(CASES).resolve(command.index.toString()).toFile()
                        .listFiles { f -> f.extension == "json" }?.singleOrNull()?.readText()
                    CliMeasurements.writeManifest(profile, command.index, 1, requireNotNull(measurementId), Experiments.LAB_HOST,
                        command.cmd, exit, javaOptions, record, CliMeasurements.localMacOs, compilation)
                }.onFailure { log(job.id, "case ${command.index}: measurement manifest not written: ${it.message}") }
            }
        } finally {
            running.remove(process)
            terminations.remove(process)
            killed.remove(process)
        }
    }

    private fun await(job: Job, command: Command, dir: Path, process: Process): Int {
        val deadline = now() + command.timeoutSec * 1000
        // A profile job's whole-run limit stops its processes from its own thread ([ProfileRunDeadline]), gracefully;
        // this one only says so, once, and whether the grace ran out.
        val graceMs = ProfileRunDeadline.graceMillis()
        var deadlineStop = false
        fun ended() = ProfileRunDeadline.isActive() && runCatching { ProfileRunDeadline.checkActive() }.isFailure
        while (!process.waitFor(CANCEL_POLL_MS, TimeUnit.MILLISECONDS)) {
            if (!stopping && store.cancelRequested(job.id)) {
                kill(process, Termination.CANCEL)
                return CANCELLED_EXIT
            }
            if (!stopping && process !in killed && ended()) {
                killed.add(process)
                deadlineStop = true
                Termination.record(Termination.file(dir, command.index), Termination.deadline(graceMs))
            }
            if (!deadlineStop && now() > deadline) {
                stopGracefully(process, Termination.timeoutGraceful(command.timeoutSec, stopGraceMs), "timeout")
                dir.resolve("${command.index}.exit").writeAtomically("timeout\n")
                return TIMEOUT_EXIT
            }
        }
        // A command the shutdown killed has no result. Leaving it RUNNING in the store makes the next runner
        // rerun it, as it would after a crash; recording the kill would mark it failed and skip it.
        while (stopping) Thread.sleep(CANCEL_POLL_MS)
        val exit = process.exitValue()
        dir.resolve("${command.index}.exit").writeAtomically("$exit\n")
        val termination = Termination.file(dir, command.index)
        // The case can end on the deadline's SIGTERM before the loop above sees the deadline, with any exit when it
        // traps the signal; a cancel of the job ends the same way.
        val ended = process !in killed && ended()
        if (process !in killed && (ended || Termination.signal(exit) != null)) {
            val reason = when {
                ended && store.cancelRequested(job.id) -> Termination.CANCEL
                ended -> Termination.deadline(graceMs).also { deadlineStop = true }
                else -> Termination.external(exit)
            }
            Termination.record(termination, checkNotNull(reason))
        }
        if (deadlineStop && exit == Termination.SIGKILL_EXIT) Termination.record(termination, Termination.graceExpired("deadline", graceMs))
        return exit
    }

    /** Start a process that leads its own session, and record its pid and start time in [pidFile]. */
    private fun start(builder: ProcessBuilder, pidFile: Path): Process {
        val process = ProfileRunDeadline.start(builder, alreadyInSession = true)
        running.add(process)
        val handle = process.toHandle()
        pidFile.writeAtomically("${handle.pid()} ${handle.info().startInstant().map { it.toEpochMilli() }.orElse(0)}\n")
        return process
    }

    /**
     * A runner that died mid-step leaves that step's process group running. Kill it before the step reruns, or the
     * rerun competes with it for the core. The group outlives its leader when the leader dies first, so a dead
     * leader still means the group is killed; a live process under that pid with another start time is a reused
     * pid, and its group is left alone.
     */
    private fun killOrphan(pidFile: Path, termination: Path? = null) {
        if (!pidFile.exists()) return
        val (pid, started) = pidFile.toFile().readText().trim().split(" ").map { it.toLong() }
        val leader = ProcessHandle.of(pid)
        val reused = leader.map { handle ->
            handle.info().startInstant().map { it.toEpochMilli() != started }.orElse(true)
        }.orElse(false)
        if (reused) return
        println("killing orphaned process group $pid from ${pidFile.fileName}")
        termination?.let { Termination.record(it, Termination.ORPHAN) }
        killGroup(pid)
        leader.ifPresent { it.onExit().get(KILL_WAIT_SEC, TimeUnit.SECONDS) }
    }

    /** Wait until Docker answers before taking work, since reference commands need it and it starts after boot. */
    private fun awaitDocker() {
        var waited = 0L
        while (ProcessBuilder("docker", "info").redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() != 0
        ) {
            if (waited % DOCKER_LOG_EVERY_MS == 0L) println("waiting for docker (${waited / 1000} s)")
            Thread.sleep(DOCKER_POLL_MS)
            waited += DOCKER_POLL_MS
        }
        println("docker is up")
    }

    /** Kill the running command's tree when the service manager stops the runner. */
    fun installShutdownHook() {
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
    }

    /** Kill every running process, saying why for each case, and record nothing more: the cases rerun. */
    internal fun stop() {
        stopping = true
        running.forEach { kill(it, Termination.SHUTDOWN) }
    }

    internal fun cancel(jobId: Long) {
        store.cancelRemaining(jobId)
        store.finish(jobId, Status.CANCELLED)
        removeWorktree(jobId)
        log(jobId, "job cancelled")
    }

    /** Remove the job's worktree and, for an experiment, the ones it built other commits in. */
    private fun removeWorktree(jobId: Long) {
        val path = config.worktree(jobId)
        val siblings = path.parent.toFile().listFiles { f -> f.name.startsWith("$jobId@") }.orEmpty()
        for (sibling in siblings) removeTree(sibling.toPath())
        removeTree(path)
    }

    private fun removeTree(path: Path) {
        // Unlink the shared directories first, so that nothing below can reach the data they point at.
        for (relative in config.sharedPaths) {
            ProfileRunDeadline.checkActive()
            val link = path.resolve(relative)
            if (link.isSymbolicLink()) Files.delete(link)
        }
        synchronized(mirrorLock) {
            if (config.mirror.exists()) {
                runCatching { capture(config.mirror.toFile(), "git worktree remove --force ${quote(path.toString())}") }
                runCatching { capture(config.mirror.toFile(), "git worktree prune") }
            }
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) deleteTree(path)
    }

    /**
     * Run [step] by [backoff], logging each retry to job [jobId] (or the console without one). The wait stops for a
     * shutdown, as a running step does, and gives up early when the job's cancel is requested.
     */
    internal fun <T> withRetry(jobId: Long?, what: String, backoff: Backoff = config.retry, step: () -> T): T = retrying(
        backoff,
        onRetry = { attempt, waitMs, e ->
            val message = "$what failed (try $attempt of ${backoff.attempts}), retrying in ${waitMs / MS_PER_SEC} s: ${e.message}"
            if (jobId != null) log(jobId, message) else println(message)
        },
        sleep = { waitMs ->
            val until = now() + waitMs
            while (now() < until) {
                ProfileRunDeadline.checkActive()
                while (stopping) Thread.sleep(CANCEL_POLL_MS)
                check(jobId == null || !store.cancelRequested(jobId)) { "cancelled while waiting to retry $what" }
                Thread.sleep((until - now()).coerceIn(1, CANCEL_POLL_MS))
            }
        },
        step = { ProfileRunDeadline.checkActive(); step().also { ProfileRunDeadline.checkActive() } },
    )

    /** Whether the build output [log] wrote after [from] says a Gradle transform's file is missing: a cache whose
     *  records outlived the files they name, which every later build would trip on the same way. */
    private fun brokenTransforms(log: File, from: Long): Boolean = runCatching {
        java.io.RandomAccessFile(log, "r").use { raf ->
            raf.seek(minOf(from, raf.length()))
            val bytes = ByteArray((raf.length() - raf.filePointer).coerceAtMost(MAX_BUILD_LOG_SCAN).toInt())
            raf.readFully(bytes)
            String(bytes).let { "FileNotFoundException" in it && "/transforms/" in it }
        }
    }.getOrDefault(false)

    /** Stop the lab's Gradle daemons and remove its transform caches, which the next build rebuilds. */
    private fun clearTransforms(worktree: File) {
        runCatching { sh(File.createTempFile("gradle-stop", ".log"), worktree, "./gradlew --stop -q") }
        config.gradleHome.resolve("caches").toFile().listFiles().orEmpty()
            .map { it.resolve("transforms") }.filter { it.isDirectory }.forEach { it.deleteRecursively() }
    }

    private fun sh(log: File, dir: File, cmd: String) {
        ProfileRunDeadline.checkActive()
        log.appendText("$ $cmd\n")
        val builder = ProcessBuilder(inSession("bash", "-c", cmd)).directory(dir)
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        if (config.javaHome.isNotBlank()) builder.environment()["JAVA_HOME"] = config.javaHome
        builder.environment()["GRADLE_USER_HOME"] = config.gradleHome.toString()
        val process = start(builder, log.toPath().resolveSibling("setup.pid"))
        val finished = try {
            process.waitFor(config.setupTimeoutSec, TimeUnit.SECONDS)
        } finally {
            running.remove(process)
        }
        while (stopping) Thread.sleep(CANCEL_POLL_MS)
        if (!finished) {
            killTree(process)
            error("setup step timed out: $cmd")
        }
        check(process.exitValue() == 0) { "setup step failed (${process.exitValue()}): $cmd — see setup.log" }
    }

    /** Run a git command the runner needs beside the cases (a schedule's check, removing a worktree) in the
     *  background, off the solves' cores. Setup steps, which run alone, go through [sh] at full speed. */
    private fun capture(dir: File, cmd: String): String {
        val process = ProfileRunDeadline.start(
            ProcessBuilder(Background.prefix + listOf("bash", "-c", cmd)).directory(dir).redirectErrorStream(true),
        )
        val out = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "failed: $cmd: $out" }
        return out
    }

    /** Kill [process]'s tree, first recording [reason] when it runs a case. */
    private fun kill(process: Process, reason: String) {
        terminations[process]?.let { file ->
            killed.add(process)
            Termination.record(file, reason)
        }
        killTree(process)
    }

    /**
     * Stop [process]'s case gracefully, first recording [reason]: SIGTERM to its process group, up to [stopGraceMs] for
     * the group to be gone, then SIGKILL to what is left, recorded as a [kind] line of its own.
     */
    private fun stopGracefully(process: Process, reason: String, kind: String) {
        val file = terminations[process]
        file?.let {
            killed.add(process)
            Termination.record(it, reason)
        }
        stopGroups(listOf(process.pid()), stopGraceMs) { file?.let { Termination.record(it, Termination.graceExpired(kind, stopGraceMs)) } }
        killTree(process)
    }

    private fun killTree(process: Process) {
        killGroup(process.pid())
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor(KILL_WAIT_SEC, TimeUnit.SECONDS)
    }

    internal fun log(jobId: Long, message: String) {
        ProfileRunDeadline.checkActive()
        println("[job $jobId] $message")
        runCatching { config.jobDir(jobId).createDirectories().resolve("job.log").toFile().appendText("${now()} $message\n") }
    }

    private companion object {
        const val IDLE_POLL_MS = 2000L
        const val CANCEL_POLL_MS = 1000L
        const val MS_PER_SEC = 1000L
        /** How far back a run looks for the run before it of the same experiment. */
        const val PREVIOUS_RUNS = 50
        const val KILL_WAIT_SEC = 10L
        const val CANCELLED_EXIT = -1000
        const val TIMEOUT_EXIT = -1001
        const val DISPATCH_POLL_MS = 100L
        const val CASES = "cases"
        const val SHA_DIR_LENGTH = 12
        const val DOCKER_POLL_MS = 5000L
        /** How often the schedule thread looks for a due schedule: the most a forced check waits. */
        const val SCHEDULE_POLL_MS = 10_000L
        val SHA = Regex("[0-9a-f]{40}")
        const val DOCKER_LOG_EVERY_MS = 60_000L
    }
}

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

internal enum class Dispatched { FINISHED, CANCELLED, YIELDED, LOST }

/** Characters a `name=` selection reads as a glob, so a problem named with one cannot be asked for exactly. */
private val GLOB_CHARS = setOf('*', '?', '[', ',')
