package com.eignex.lab.nomad

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import com.eignex.lab.worker.*

import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Admission is repeatable from accepted preparation and persisted sessions, without a second queue database. */
class NomadQueue(
    private val config: Config, private val store: Store, private val backend: NomadBackend,
    private val artifacts: ArtifactStore, private val settings: NomadQueueSettings,
    private val allowed: () -> Boolean,
    private val completed: (Job) -> Unit = Confirmations(config, store)::queue,
    private val nativeAllowed: (Long) -> Boolean = { job -> !store.localBusy() && !Files.exists(config.nativeYield) },
    private val source: (Job, List<NomadQueueTarget>, NomadQueueSettings) -> Pair<List<PlannedArm>, List<WorkerBundle>>? =
        TrustedQueueSources(config, artifacts, store)::publish,
    private val preparationPlan: (Long, () -> Unit) -> Boolean = { _, work -> work(); true },
) {
    fun reconcile() {
        if (!allowed()) return
        for (id in store.execution.pendingDomainCompletion()) {
            val job = store.job(id) ?: continue
            completed(job)
            store.execution.domainCompleted(id)
        }
        val schedules = store.schedules.all()
        for (listed in store.active().filter { it.runsOnNomad && !it.paused && !it.cancelRequested }
            .sortedWith(compareByDescending<Job> { it.priority }.thenBy { it.id })) {
            var job = listed
            try {
                val settings = NomadWorkerRuntime(this.settings).queue(store.execution.queueRecipe(job.id, this.settings))
                // A tags edit may have won before the admission transaction; read the frozen input afterwards.
                job = store.job(job.id) ?: continue
                if (job.paused || job.cancelRequested || job.status.isTerminal) continue
                val spec = checkNotNull(job.experiment)
                // Persisted jobs predate submission validation; refuse retired recipes before publishing or allocating.
                Experiments.requireExecutable(spec)
                val acceptedPreparation = store.execution.accepted(job.id).singleOrNull { it.second.preparation != null }?.second?.preparation
                val preparedBench = acceptedPreparation?.let { preparation ->
                    val plans = preparation.armPlans.values.ifEmpty { listOfNotNull(preparation.benchPlan) }
                    plans.takeIf { it.isNotEmpty() }?.let { com.eignex.lab.bench.BenchPlan(it.flatMap { plan -> plan.cases }.mapIndexed { index, case -> case.copy(id = index.toString()) }) }
                }
                val targets = settings.matching(spec, job.origin, Placement.cloudSchedule(job.origin, schedules))
                    .filter { settings.solveBudget(it, job, preparedBench) != null }
                    .filter { if (it.native) spec.host != Experiments.AWS_HOST else spec.host != Experiments.LAB_HOST }
                require(targets.isNotEmpty()) { "no configured Nomad worker can run this experiment within its resource budget" }
                var sessions = store.execution.sessions(job.id)
                if (sessions.isEmpty()) {
                    val fitting = targets.filter {
                        it.template.budget.cpuMillis >= settings.preparationBudget.cpuMillis && it.template.budget.memoryMiB >= settings.preparationBudget.memoryMiB
                    }
                    require(fitting.isNotEmpty()) { "no target fits the preparation budget" }
                    val (arms, bundles) = source(job, targets, settings) ?: continue
                    val required = bundles.flatMap { it.build?.requiredTags.orEmpty() }.toSet()
                    val compatible = fitting.filter { target -> NodeTags.matches(NodeSelector(require = required), target.capabilities) &&
                        arms.all { arm -> bundles.any { it.commit == arm.sha && it.os == target.os && it.arch == target.arch } } }
                    require(compatible.isNotEmpty()) { "no preparation worker satisfies the committed runtime requirements" }
                    val target = ordered(compatible) { settings.preparationBudget }.firstOrNull {
                        !it.native || mayUseNative(job.id, it) && store.execution.nativeReservation().let { held ->
                            held.first + settings.preparationBudget.cpuMillis <= config.cores * 1000L &&
                                held.second + settings.preparationBudget.memoryMiB <= it.template.budget.memoryMiB
                        }
                    } ?: continue
                    val manifest = WorkManifest(UUID.randomUUID().toString(), settings.release, spec, arms, emptyList(), bundles,
                        settings.preparationBudget, solveJavaOptions = settings.solveOptions(config.solveJavaOpts), preparationTimeoutSec = settings.preparationTimeoutSec,
                        benchReplay = spec.work.bench?.let { benchReplay(replaySources(store, spec, arms), it) })
                    backend.enqueue(job.id, manifest, target.template.copy(budget = manifest.budget, nativeCapacity = nativeCapacity(target), environment = target.template.environment + ("LAB_WORKER_MAX_CONCURRENT_BUILDS" to this.settings.maxConcurrentBuildsPerNode.toString())), settings.workerApi)
                    continue
                }
                val accepted = store.execution.accepted(job.id).singleOrNull { it.second.preparation != null } ?: continue
                val preparation = checkNotNull(accepted.second.preparation)
                val recipe = store.execution.manifest(sessions.first { it.executionClass == ExecutionClass.PREPARE }.id)
                if (!job.setupDone && !preparationPlan(job.id) { plan(job, recipe, preparation) }) continue
                val current = store.job(job.id) ?: continue
                if (current.cancelRequested || current.paused || current.status.isTerminal) continue
                if (store.unclaimed(job.id) == 0) continue
                val cases = store.plans.casePlan(job.id).map { row ->
                    val armIndex = recipe.arms.indexOfFirst { it.arm.label == row.arm }
                    val execution = preparation.armPlans[row.arm]?.cases?.single { BenchRuns.key(it) == BenchRuns.key(requireNotNull(row.problem.benchCase)) }
                    WorkCase(row.index, armIndex, row.problem, row.problem.benchCase?.attributes?.get("seed")?.toLongOrNull() ?: row.seed, row.repeat,
                        current.commands.single { it.index == row.index }.timeoutSec, execution)
                }
                val pending = store.plans.casePlan(job.id).filter { it.status.isActive }
                    .map { Triple(it.problem, it.seed, it.repeat) }.distinct().size
                val minimumCpu = targets.minOf { checkNotNull(settings.solveBudget(it, current, preparedBench)).cpuMillis }
                val requestedCpu = Math.multiplyExact(Math.multiplyExact(current.parallel, preparedBench?.let(BenchRuns::resources)?.cpus ?: recipe.arms.maxOf { it.arm.cores }), 1000)
                val cpuLimit = maxOf(minOf(this.settings.maxVcpusPerJob * 1000, requestedCpu), minimumCpu)
                val limit = minOf(this.settings.maxSessionsPerJob, pending, cpuLimit / minimumCpu)
                val active = sessions.filter { it.executionClass == ExecutionClass.SOLVE && it.desired != Desired.STOP }
                var keptCpu = 0
                var keptWorkers = 0
                for (session in active.filter { it.desired == Desired.RUN }) {
                    if (keptWorkers >= limit || keptCpu + session.budget.cpuMillis > cpuLimit) store.execution.drain(session.id)
                    else { keptWorkers++; keptCpu += session.budget.cpuMillis }
                }
                if (active.size >= limit) continue
                val bundles = recipe.bundles.map { original -> preparation.bundles.singleOrNull {
                    it.commit == original.commit && it.os == original.os && it.arch == original.arch
                } ?: original }
                val ordered = ordered(targets) { checkNotNull(settings.solveBudget(it, current, preparedBench)) }
                fun solving(enforcement: CpuEnforcement) = sessions.filter { session -> session.executionClass == ExecutionClass.SOLVE &&
                    session.desired != Desired.STOP && store.execution.placement(session.id).enforcement == enforcement }
                fun cloudSessions() = solving(CpuEnforcement.HARD_QUOTA)
                for (index in active.size until limit) {
                    val remainingCpu = cpuLimit - sessions.filter { it.executionClass == ExecutionClass.SOLVE && it.desired != Desired.STOP }.sumOf { it.budget.cpuMillis }
                    val available = ordered.filter { checkNotNull(settings.solveBudget(it, current, preparedBench)).cpuMillis <= remainingCpu }.filter { target ->
                        // Wait for the previous cloud placement before deciding which hosts still have parallel slots.
                        if (!target.native) cloudSessions().none { it.node == null } else {
                            val budget = checkNotNull(settings.solveBudget(target, current, preparedBench))
                            val nativeSessions = solving(CpuEnforcement.ADVISORY_NATIVE)
                            val maximum = this.settings.perHostParallel?.let { (it + budget.parallel - 1) / budget.parallel } ?: Int.MAX_VALUE
                            mayUseNative(job.id, target) && nativeSessions.size < maximum
                        }
                    }
                    val reserved = store.execution.nativeReservation()
                    val target = available.firstOrNull { target ->
                        if (!target.native) true else {
                            val budget = checkNotNull(settings.solveBudget(target, current, preparedBench))
                            reserved.first + budget.cpuMillis <= config.cores * 1000L &&
                                reserved.second + budget.memoryMiB <= target.template.budget.memoryMiB
                        }
                    } ?: break
                    val budget = checkNotNull(settings.solveBudget(target, current, preparedBench))
                    val manifest = WorkManifest(UUID.randomUUID().toString(), settings.release, spec, recipe.arms, cases, bundles, budget,
                        solveJavaOptions = recipe.solveJavaOptions, inputCorpus = preparation.corpus, sharedInputs = preparation.sharedInputs)
                    val excluded = if (target.native || this.settings.perHostParallel == null) emptySet() else cloudSessions().filter { it.node != null }
                        .groupBy { checkNotNull(it.node) }.filterValues { hosted ->
                            hosted.sumOf { it.budget.parallel.toLong() } + budget.parallel > this.settings.perHostParallel
                        }.keys
                    backend.enqueue(job.id, manifest, target.template.copy(budget = budget, nativeCapacity = nativeCapacity(target), excludedNodes = target.template.excludedNodes + excluded, perHostParallel = this.settings.perHostParallel, environment = target.template.environment + ("LAB_WORKER_MAX_CONCURRENT_BUILDS" to this.settings.maxConcurrentBuildsPerNode.toString())), settings.workerApi)
                    sessions = store.execution.sessions(job.id)
                }
            } catch (failure: Exception) {
                if (failure is InterruptedException) throw failure
                if (failure is IllegalArgumentException && failure.rejectsAdmission) {
                    store.execution.failIfActive(job.id, "Nomad preparation/admission: ${failure.message?.take(1024)}")
                    store.execution.sessions(job.id).filter { it.desired != Desired.STOP }.forEach { store.execution.loseSession(it.id, "admission-failed") }
                } else System.err.println("nomad queue job ${job.id}: ${describeFailure(failure)}; will retry")
            }
        }
    }

    private fun nativeCapacity(target: NomadQueueTarget): ResourceBudget? =
        if (target.native) ResourceBudget(config.cores * 1000, target.template.budget.memoryMiB) else null

    private fun mayUseNative(id: Long, target: NomadQueueTarget): Boolean =
        nativeAllowed(id) && NativePriority(store).allows(id, target.capabilities)

    /** Reuse compatible running capacity, then prefer larger nodes so sessions share host and build overhead. */
    private fun ordered(targets: List<NomadQueueTarget>, budget: (NomadQueueTarget) -> ResourceBudget): List<NomadQueueTarget> =
        targets.sortedWith(compareBy<NomadQueueTarget> { if (it.native) 0 else if (backend.hasCapacity(it.template, budget(it))) 1 else 2 }
            .thenByDescending { it.template.cpuCapacity.logicalCpus }.thenByDescending { it.template.budget.memoryMiB }.thenBy { it.template.nodeClass })

    private fun plan(job: Job, recipe: WorkManifest, preparation: PreparationResult) {
        config.createJobDir(job.id)
        val spec = recipe.experiment
        // The selection was filtered and approved before the worker resolved/downloaded its files.
        val selections = preparation.selections
        val problems = interleave(selections).distinctBy { it.suite to it.problem }
        require(problems.isNotEmpty()) { "the selection matched no problems the reference filter keeps" }
        val cases = Experiments.cases(problems.size, recipe.arms.size, spec.seeds, spec.repeats).map { case ->
            case.copy(seed = problems[case.problem].benchCase?.attributes?.get("seed")?.toLongOrNull() ?: case.seed)
        }
        val hours = preparation.benchPlan?.let { plan ->
            maxOf(cases.sumOf { case -> (preparation.armPlans[recipe.arms[case.arm].arm.label]?.cases?.get(case.problem) ?: plan.cases[case.problem]).resources.timeoutSeconds.toDouble() } / job.parallel,
                cases.sumOf { case -> val resources = (preparation.armPlans[recipe.arms[case.arm].arm.label]?.cases?.get(case.problem) ?: plan.cases[case.problem]).resources; resources.timeoutSeconds.toDouble() * resources.cpus } / config.cores) / 3600
        } ?: Experiments.estimateHours(cases, recipe.arms.map { it.arm }, job.parallel, config.cores)
        require(spec.confirm || hours <= config.maxExperimentHours) { "${cases.size} cases could take $hours h; resubmit with confirm=true" }
        preparation.corpus?.let { bundle ->
            val root = VerifiedCache(config.dataDir.resolve("nomad/check-inputs")).stage(bundle) { artifacts.open(job.id, bundle.sha256) }
            val link = config.jobDir(job.id).resolve("nomad/prepared-corpus")
            Files.createDirectories(link.parent)
            if (!Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.createSymbolicLink(link, root.toAbsolutePath())
            require(Files.isSymbolicLink(link) && Files.readSymbolicLink(link) == root.toAbsolutePath())
        }
        if (preparation.sharedInputs.isNotEmpty()) {
            val root = requireNotNull(SharedInputs.environment()) { "shared input transport is not configured" }.view(preparation.sharedInputs)
            val link = config.jobDir(job.id).resolve("nomad/prepared-corpus")
            Files.createDirectories(link.parent)
            if (!Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.createSymbolicLink(link, root.toAbsolutePath())
            require(Files.isSymbolicLink(link) && Files.readSymbolicLink(link) == root.toAbsolutePath())
        }
        if (spec.work.bench?.sourceValidation == true) for (arm in recipe.arms.distinctBy { it.sha }) {
            val bundle = preparation.bundles.first { it.commit == arm.sha }
            val root = VerifiedCache(config.dataDir.resolve("nomad/check-sources")).stage(bundle) { artifacts.open(job.id, bundle.sha256) }
            val link = config.jobDir(job.id).resolve("nomad/prepared-source/${arm.sha}")
            Files.createDirectories(link.parent)
            if (!Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)) Files.createSymbolicLink(link, root.toAbsolutePath())
            require(Files.isSymbolicLink(link) && Files.readSymbolicLink(link) == root.toAbsolutePath())
        }
        val commands = cases.mapIndexed { index, case ->
            val arm = recipe.arms[case.arm].arm
            val problem = problems[case.problem]
            val execution = preparation.armPlans[arm.label]?.cases?.get(case.problem) ?: problem.benchCase
            val command = if (arm.values["backend"] == REFERENCE_BACKEND) Experiments.referenceCommand(config.launcher, problem, arm, case.seed, index)
                else when (val work = spec.work) {
                    is Work.Bench -> "bench ${spec.repository}: ${requireNotNull(execution).id}"
                    is Work.Profile -> Experiments.profileCommand("lab-source", work.main, index, "lab-corpus", arm.debug, spec.repository)
                    is Work.Analysis, is Work.Script, Work.Recipe -> error("execution requires a bench manifest or explicit profile")
                }
            CaseCommand(command, execution?.resources?.timeoutSeconds ?: Experiments.profileTimeoutSec(arm), execution?.resources?.cpus ?: arm.cores)
        }
        store.prepareNomad(job.id, recipe.arms, problems, cases, commands)
        if (preparation.features.isNotEmpty()) {
            val known = store.references.features(preparation.features.map { ProblemKey(it.collection, it.problem) }).keys
            store.references.putFeatures(preparation.features.filter { ProblemKey(it.collection, it.problem) !in known })
        }
    }
}

/** Resolves and verifies source on the coordinator; all builds and selection run in preparation allocations. */
internal class TrustedQueueSources(private val config: Config, private val artifacts: ArtifactStore, private val store: Store) {
    fun publish(job: Job, targets: List<NomadQueueTarget>, settings: NomadQueueSettings): Pair<List<PlannedArm>, List<WorkerBundle>> {
        val spec = checkNotNull(job.experiment); val mirror = Repositories.mirror(config.paths, spec.repository)
        val directory = config.createJobDir(job.id).resolve("nomad/source"); Files.createDirectories(directory)
        fun git(at: Path, vararg args: String): Pair<Int, String> {
            val log = directory.resolve("git-output.log")
            val process = ProcessBuilder(Background.prefix + listOf("git", "-C", at.toString()) + args)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start()
            try {
                check(process.waitFor(120, TimeUnit.SECONDS)) { "source operation timed out" }
                return process.exitValue() to Files.readString(log).take(1024 * 1024).trim()
            } finally { if (process.isAlive) { process.descendants().forEach { it.destroyForcibly() }; process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) } }
        }
        fun checked(at: Path, vararg args: String): String = git(at, *args).let { (code, output) -> check(code == 0) { "source operation failed" }; output }
        val url = Repositories.source(spec.repository)
        val trust = Trust(config.trustedUsers, url, { args -> git(mirror, *args.toTypedArray()) },
            Trust.githubApi(config.githubTokenFile.takeIf(Files::isRegularFile)?.let(Files::readString)?.trim()))
        trust.requireRepository(refresh = true)
        if (spec.work.bench?.replayJobs?.isNotEmpty() == true) {
            val arms = Experiments.arms(spec).map { arm ->
                require(CommitSha.isValid(arm.ref)) { "replay refs must be pinned commits" }
                PlannedArm(arm, arm.ref)
            }
            return publishReplay(job, spec, arms)
        }
        Files.createDirectories(mirror.parent)
        withMirrorLock(mirror) {
            if (!Files.exists(mirror)) checked(mirror.parent, "clone", "--mirror", url, mirror.toString())
            checked(mirror, "remote", "set-url", "origin", url)
            checked(mirror, "fetch", "--prune", "origin", "+refs/heads/*:refs/heads/*", "+refs/tags/*:refs/tags/*")
        }
        val arms = Experiments.arms(spec).map { arm ->
            val (exit, sha) = git(mirror, "rev-parse", "--verify", "${arm.ref}^{commit}")
            require(exit == 0) { "source ref '${arm.ref}' was not found in ${spec.repository} after a successful fetch" }
            require(CommitSha.isValid(sha))
            trust.requireCommit(sha, arm.ref)
            PlannedArm(arm, sha)
        }
        val approved = arms.map { it.sha }.toSet()
        val inputs = SourceInputs({ repository, sha -> require(repository == spec.repository && sha in approved); trust.requireRepository() })
        val requirements = spec.work.bench?.let { bench -> arms.map { it.sha }.distinct().flatMap { sha ->
            val text = checked(mirror, "show", "$sha:${bench.manifest}")
            val value = com.eignex.lab.bench.BenchProtocol.json.parseToJsonElement(text).jsonObject["runtime"]
            val runtime = value?.let { com.eignex.lab.bench.BenchProtocol.json.decodeFromJsonElement(BenchRuns.Runtime.serializer(), it) } ?: BenchRuns.Runtime()
            runtime.validate()
            runtime.requiredTags
        }.toSet() }.orEmpty()
        val build = WorkerBuild(spec.work.profile?.let { ProfileRuns.buildTasks(spec.repository, it) } ?: emptyList(), settings.preparationTimeoutSec,
            benchManifest = spec.work.bench?.manifest, requiredTags = requirements)
        val platforms = targets.distinctBy { it.os to it.arch }
        val bundles = arms.map { it.sha }.distinct().flatMap { sha ->
            val output = Files.createTempFile(directory, ".source-", ".zip"); Files.delete(output)
            try {
                val first = platforms.first()
                val bundle = inputs.publish(mirror, spec.repository, sha, first.os, first.arch, "jdk25", build, output)
                val offset = artifacts.offset(job.id, bundle.sha256)
                Files.newInputStream(output).use { stream -> stream.skipNBytes(offset); artifacts.put(job.id, bundle.sha256, Files.size(output), offset, stream) }
                platforms.map { bundle.copy(os = it.os, arch = it.arch) }
            } finally { Files.deleteIfExists(output) }
        }
        return arms to bundles
    }
    private fun publishReplay(job: Job, spec: ExperimentSpec, arms: List<PlannedArm>): Pair<List<PlannedArm>, List<WorkerBundle>> {
        val replay = replaySources(store, spec, arms)
        require(replay.isNotEmpty())
        run {
            val sources = replay.values.flatMap { source -> source.preparation.bundles.map { source.job to it } + listOfNotNull(source.preparation.corpus?.let { source.job to it }) }
            for ((jobId, bundle) in sources.distinctBy { it.second.sha256 }) {
                // An artifact that is gone refuses the replay; one the store cannot read just now is retried.
                val bytes = try { artifacts.size(jobId, bundle.sha256) }
                catch (failure: java.io.IOException) { throw failure }
                catch (failure: Exception) { throw AdmissionRejected("replay build artifacts are unavailable", failure) }
                val offset = artifacts.offset(job.id, bundle.sha256)
                artifacts.open(jobId, bundle.sha256).use { input ->
                    input.skipNBytes(offset); artifacts.put(job.id, bundle.sha256, bytes, offset, input)
                }
            }
            return arms to replay.values.flatMap { it.preparation.bundles }.filter { bundle -> arms.any { it.sha == bundle.commit } }
                .distinctBy { Triple(it.commit, it.os, it.arch) }
        }
        error("unreachable replay")
    }

}

/** Filter in small database reads so a large catalogue cannot hold the lease/renewal write lock. */
internal fun preparationSelection(store: Store, spec: ExperimentSpec, requested: List<List<Problem>>, active: () -> Unit): List<List<Problem>> {
    when (val work = spec.work) {
        is Work.Bench -> {
            val bench = work.spec
            require(requested.size == 1 && requested.single().isNotEmpty())
            require(requested.single().all { problem -> problem.benchCase?.let { problem == BenchRuns.problem(it) } == true })
            require(requested.single().map { it.benchCase!!.id }.distinct().size == requested.single().size)
            val mode = bench.referenceFilter
            if (mode == ReferenceFilterMode.ANY && bench.referenceFilters.isEmpty() || bench.frozenCases.isNotEmpty()) return requested
            val solver = Experiments.arms(spec).map { it.values["solver"] }.distinct().singleOrNull()
            return requested.map { group ->
                val references = group.map { it.referenceKey }.distinct().chunked(256).flatMap { keys ->
                    active(); store.references.matching(keys, solver).entries.map { it.key to it.value }
                }.toMap()
                group.filter { problem ->
                    val selectedMode = bench.referenceFilters[problem.suite] ?: mode
                    val budget = problem.benchCase?.attributes?.get("budgetMs")?.toLongOrNull()
                        ?: Experiments.arms(spec).maxOf { it.timeoutMs }
                    selectedMode.keeps(references[problem.referenceKey], budget)
                }
            }
        }
        is Work.Profile -> {
            require(requested == listOf(listOf(Experiments.profileProblem(spec))))
            return requested
        }
        is Work.Analysis, is Work.Script, Work.Recipe ->
            throw IllegalArgumentException("preparation requires a repository bench manifest or explicit profile; legacy solver recipes must be migrated")
    }
}

/** A job admission refuses for good, saying why: what [rejectsAdmission] reads as deliberate. */
internal class AdmissionRejected(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Whether this failure refuses a job for good: a deliberate validation ([require], [AdmissionRejected]), not a stored
 * submission that failed to parse or a number that failed to read, which are the lab's own faults and are retried.
 */
internal val IllegalArgumentException.rejectsAdmission: Boolean
    get() = this !is kotlinx.serialization.SerializationException && this !is NumberFormatException
