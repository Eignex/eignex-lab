package com.eignex.lab

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
data class NomadQueueTarget(val template: NomadBatchJob, val tags: Set<String>, val market: CapacityMarket = CapacityMarket.SPOT) {
    init {
        require(tags.count { it.startsWith("arch:") } == 1)
        require(tags.none { it in setOf("local", "cloud", "spot", "on-demand") })
        NodeTags.validate(NodeSelector(require = tags))
    }
    val os get() = if (template.platform == NomadPlatform.MAC) "mac" else "linux"
    val arch get() = when (tags.single { it.startsWith("arch:") }) {
        "arch:arm64" -> "arm64"
        "arch:x86_64" -> "amd64"
        else -> error("worker architecture must be arm64 or x86_64")
    }
    val capabilities get() = tags + if (os == "mac") setOf("local") else setOf("cloud", market.tag)
}

/** Provider-neutral, pinned workers and bounded per-allocation budgets; no provider credentials. */
@Serializable
data class NomadQueueSettings(
    val release: String, val workerApi: String, val targets: List<NomadQueueTarget>,
    val preparationBudget: ResourceBudget = ResourceBudget(2000, 4096), val preparationTimeoutSec: Long = 3600,
    val caseMemoryMiB: Int = 4096, val sessionMemoryMiB: Int = 512, val maxSessionsPerJob: Int = 64,
    /** Infrastructure policy, independent of frozen experiment inputs and per-worker parallelism. */
    val maxVcpusPerJob: Int = 16, val perHostParallel: Int? = null, val maxConcurrentBuildsPerNode: Int = 2,
) {
    init {
        require(release.isNotBlank() && targets.isNotEmpty() && targets.map { it.template.nodeClass }.distinct().size == targets.size)
        require(preparationTimeoutSec in 1..7200 && preparationBudget.parallel == 1)
        require(maxVcpusPerJob in 1..1024 && (perHostParallel == null || perHostParallel > 0) && maxConcurrentBuildsPerNode in 1..64)
        require(caseMemoryMiB >= 512 && sessionMemoryMiB >= 256 && maxSessionsPerJob in 1..1024)
        val api = java.net.URI.create(workerApi)
        require(api.scheme in setOf("https", "http") && api.userInfo == null && api.rawQuery == null)
        targets.forEach { it.arch }
        require(targets.groupBy { it.os to it.arch }.values.all { group -> group.map { it.template.image }.distinct().size == 1 }) {
            "workers sharing prepared binaries must use the same pinned image for each platform"
        }
    }
    fun matching(spec: ExperimentSpec, name: String, cloudSchedule: Boolean = false): List<NomadQueueTarget> = targets.filter { target ->
        NodeTags.matches(spec.nodeTags, target.capabilities) &&
            (target.os == "mac" || Placement.localOnlyReason(spec, name, cloudSchedule) == null &&
                target.market == if (NodeTags.onDemand(false, spec.nodeTags)) CapacityMarket.ON_DEMAND else CapacityMarket.SPOT)
    }
    fun solveOptions(options: String): String {
        val heap = Regex("(?:^|\\s)-Xmx([1-9][0-9]*)([kKmMgG]?)(?=\\s|$)").findAll(options).lastOrNull()
        if (heap == null) return "$options -Xmx${caseMemoryMiB - 256}m".trim()
        val units = when (heap.groupValues[2].lowercase()) { "g" -> 1024L * 1024 * 1024; "m" -> 1024L * 1024; "k" -> 1024L; else -> 1L }
        val bytes = Math.multiplyExact(heap.groupValues[1].toLong(), units)
        require(bytes <= (caseMemoryMiB - 256).toLong() * 1024 * 1024) { "configured solve heap exceeds the per-case memory budget" }
        return options
    }
    fun solveBudget(target: NomadQueueTarget, job: Job): ResourceBudget? {
        val largest = Experiments.arms(checkNotNull(job.experiment)).maxOf { it.cores }
        val maximum = target.template.budget
        val parallel = minOf(job.parallel, Experiments.arms(job.experiment).size, maximum.parallel, maximum.cpuMillis / 1000 / largest,
            (maximum.memoryMiB - sessionMemoryMiB) / caseMemoryMiB)
        if (parallel < 1) return null
        return ResourceBudget(Math.multiplyExact(Math.multiplyExact(largest, parallel), 1000), sessionMemoryMiB + parallel * caseMemoryMiB, parallel)
    }
}

/** Admission is repeatable from accepted preparation and persisted sessions, without a second queue database. */
class NomadQueue(
    private val config: Config, private val store: Store, private val backend: NomadBackend,
    private val artifacts: ArtifactStore, private val settings: NomadQueueSettings,
    private val allowed: () -> Boolean,
    private val completed: (Job) -> Unit = Runner(config, store)::queueConfirmation,
    private val nativeAllowed: (Long) -> Boolean = { job -> !store.localBusy() && !Files.exists(config.nativeYield) },
    private val source: (Job, List<NomadQueueTarget>, NomadQueueSettings) -> Pair<List<PlannedArm>, List<WorkerBundle>> =
        TrustedQueueSources(config, artifacts)::publish,
) {
    fun reconcile() {
        if (!allowed()) return
        for (id in store.execution.pendingDomainCompletion()) {
            val job = store.job(id) ?: continue
            completed(job)
            store.execution.domainCompleted(id)
        }
        val schedules = store.schedules()
        for (job in store.active().filter { it.backend == "nomad" && it.experiment?.isAnalysis != true && !it.paused && !it.cancelRequested }
            .sortedWith(compareByDescending<Job> { it.priority }.thenBy { it.id })) {
            try {
                val settings = store.execution.queueRecipe(job.id, this.settings)
                val spec = checkNotNull(job.experiment)
                val targets = settings.matching(spec, job.name, Placement.cloudSchedule(job.name, schedules))
                    .filter { settings.solveBudget(it, job) != null }
                    .filter { if (it.os == "mac") spec.host != Experiments.AWS_HOST else spec.host != Experiments.LAB_HOST }
                require(targets.isNotEmpty()) { "no configured Nomad worker can run this experiment within its resource budget" }
                var sessions = store.execution.sessions(job.id)
                if (sessions.isEmpty()) {
                    val fitting = targets.filter {
                        it.template.budget.cpuMillis >= settings.preparationBudget.cpuMillis && it.template.budget.memoryMiB >= settings.preparationBudget.memoryMiB
                    }
                    require(fitting.isNotEmpty()) { "no target fits the preparation budget" }
                    val target = fitting.sortedBy { if (it.os == "mac") 0 else 1 }.firstOrNull {
                        it.os != "mac" || nativeAllowed(job.id) && store.execution.nativeReservation().let { held ->
                            held.first + settings.preparationBudget.cpuMillis <= config.cores * 1000L &&
                                held.second + settings.preparationBudget.memoryMiB <= it.template.budget.memoryMiB
                        }
                    } ?: continue
                    val (arms, bundles) = source(job, targets, settings)
                    val manifest = WorkManifest(UUID.randomUUID().toString(), settings.release, spec, arms, emptyList(), bundles,
                        settings.preparationBudget, solveJavaOptions = settings.solveOptions(config.solveJavaOpts), preparationTimeoutSec = settings.preparationTimeoutSec)
                    backend.enqueue(job.id, manifest, target.template.copy(budget = manifest.budget, nativeCapacity = nativeCapacity(target), environment = target.template.environment + ("LAB_WORKER_MAX_CONCURRENT_BUILDS" to this.settings.maxConcurrentBuildsPerNode.toString())), settings.workerApi)
                    continue
                }
                val accepted = store.execution.accepted(job.id).singleOrNull { it.second.preparation != null } ?: continue
                val preparation = checkNotNull(accepted.second.preparation)
                val recipe = store.execution.manifest(sessions.first { it.executionClass == "prepare" }.id)
                if (!job.setupDone) plan(job, recipe, preparation)
                val current = store.job(job.id) ?: continue
                if (current.cancelRequested || current.paused || current.status !in setOf(Status.QUEUED, Status.RUNNING)) continue
                if (store.unclaimed(job.id) == 0) continue
                val cases = store.casePlan(job.id).map { row ->
                    WorkCase(row.index, recipe.arms.indexOfFirst { it.arm.label == row.arm }, row.problem, row.seed, row.repeat,
                        current.commands.single { it.index == row.index }.timeoutSec)
                }
                val pending = store.casePlan(job.id).filter { it.status in setOf(Status.QUEUED, Status.RUNNING) }
                    .map { Triple(it.problem, it.seed, it.repeat) }.distinct().size
                val minimumCpu = targets.minOf { checkNotNull(settings.solveBudget(it, current)).cpuMillis }
                val cpuLimit = maxOf(this.settings.maxVcpusPerJob * 1000, minimumCpu)
                val limit = minOf(this.settings.maxSessionsPerJob, pending, cpuLimit / minimumCpu)
                val active = sessions.filter { it.executionClass == "solve" && it.desired != "stop" }
                if (active.size >= limit) continue
                val bundles = recipe.bundles.map { original -> preparation.bundles.singleOrNull {
                    it.commit == original.commit && it.os == original.os && it.arch == original.arch
                } ?: original }
                val ordered = targets.sortedBy { if (it.os == "mac") 0 else 1 }
                fun cloudSessions() = sessions.filter { session -> session.executionClass == "solve" && session.desired != "stop" &&
                    Json.parseToJsonElement(store.execution.submission(session.id)).jsonObject["Meta"]?.jsonObject
                        ?.get("lab_cpu_enforcement")?.jsonPrimitive?.content == "hard-quota" }
                for (index in active.size until limit) {
                    val remainingCpu = cpuLimit - sessions.filter { it.executionClass == "solve" && it.desired != "stop" }.sumOf { it.budget.cpuMillis }
                    val available = ordered.filter { checkNotNull(settings.solveBudget(it, current)).cpuMillis <= remainingCpu }.filter { target ->
                        // Wait for the previous cloud placement before deciding which hosts still have parallel slots.
                        if (target.os != "mac") cloudSessions().none { it.node == null } else {
                            val budget = checkNotNull(settings.solveBudget(target, current))
                            val nativeSessions = sessions.filter { session -> session.executionClass == "solve" && session.desired != "stop" &&
                                Json.parseToJsonElement(store.execution.submission(session.id)).jsonObject["Meta"]?.jsonObject
                                    ?.get("lab_cpu_enforcement")?.jsonPrimitive?.content == "advisory-native" }
                            val maximum = this.settings.perHostParallel?.let { (it + budget.parallel - 1) / budget.parallel } ?: Int.MAX_VALUE
                            nativeAllowed(job.id) && nativeSessions.size < maximum
                        }
                    }
                    val reserved = store.execution.nativeReservation()
                    val target = available.firstOrNull { target ->
                        if (target.os != "mac") true else {
                            val budget = checkNotNull(settings.solveBudget(target, current))
                            reserved.first + budget.cpuMillis <= config.cores * 1000L &&
                                reserved.second + budget.memoryMiB <= target.template.budget.memoryMiB
                        }
                    } ?: break
                    val budget = checkNotNull(settings.solveBudget(target, current))
                    val manifest = WorkManifest(UUID.randomUUID().toString(), settings.release, spec, recipe.arms, cases, bundles, budget,
                        solveJavaOptions = recipe.solveJavaOptions, inputCorpus = preparation.corpus, sharedInputs = preparation.sharedInputs)
                    val excluded = if (target.os == "mac" || this.settings.perHostParallel == null) emptySet() else cloudSessions().filter { it.node != null }
                        .groupBy { checkNotNull(it.node) }.filterValues { hosted ->
                            hosted.sumOf { it.budget.parallel.toLong() } + budget.parallel > this.settings.perHostParallel!!
                        }.keys
                    backend.enqueue(job.id, manifest, target.template.copy(budget = budget, nativeCapacity = nativeCapacity(target), excludedNodes = target.template.excludedNodes + excluded, perHostParallel = this.settings.perHostParallel, environment = target.template.environment + ("LAB_WORKER_MAX_CONCURRENT_BUILDS" to this.settings.maxConcurrentBuildsPerNode.toString())), settings.workerApi)
                    sessions = store.execution.sessions(job.id)
                }
            } catch (failure: IllegalArgumentException) {
                store.execution.failIfActive(job.id, "Nomad preparation/admission: ${failure.message?.take(1024)}")
                store.execution.sessions(job.id).filter { it.desired != "stop" }.forEach { store.execution.loseSession(it.id, "admission-failed") }
            } catch (failure: Exception) {
                if (failure is InterruptedException) throw failure
                System.err.println("nomad queue job ${job.id}: ${failure.javaClass.simpleName}; will retry")
            }
        }
    }

    private fun nativeCapacity(target: NomadQueueTarget): ResourceBudget? =
        if (target.os == "mac") ResourceBudget(config.cores * 1000, target.template.budget.memoryMiB) else null

    private fun plan(job: Job, recipe: WorkManifest, preparation: PreparationResult) {
        val spec = recipe.experiment
        // The selection was filtered and approved before the worker resolved/downloaded its files.
        val selections = preparation.selections
        val problems = interleave(selections).distinctBy { it.suite to it.problem }
        require(problems.isNotEmpty()) { "the selection matched no problems the reference filter keeps" }
        val cases = Experiments.cases(problems.size, recipe.arms.size, spec.seeds, spec.repeats)
        val hours = Experiments.estimateHours(cases, recipe.arms.map { it.arm }, job.parallel, config.cores)
        require(spec.confirm || hours <= config.maxExperimentHours) { "${cases.size} cases could take $hours h; resubmit with confirm=true" }
        preparation.corpus?.let { bundle ->
            val root = VerifiedCache(config.dataDir.resolve("nomad/check-inputs")).stage(bundle) { Files.newInputStream(artifacts.file(job.id, bundle.sha256)) }
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
        val commands = cases.mapIndexed { index, case ->
            val arm = recipe.arms[case.arm].arm
            val problem = problems[case.problem]
            val command = spec.profile?.let { Experiments.profileCommand("lab-source", it, index, "lab-corpus", arm.debug, spec.repository) }
                ?: if (arm.values["backend"] == REFERENCE_BACKEND) Experiments.referenceCommand(config.launcher, problem, arm, case.seed, index)
                else Experiments.command("lab-source", problem, arm, case.seed, index, "lab-corpus")
            CaseCommand(command, if (spec.isProfile) Experiments.profileTimeoutSec(arm) else Experiments.caseTimeoutSec(arm), arm.cores)
        }
        store.prepareNomad(job.id, recipe.arms, problems, cases, commands)
        if (preparation.features.isNotEmpty()) {
            val known = store.features(preparation.features.map { it.collection to it.problem }).keys
            store.putFeatures(preparation.features.filter { (it.collection to it.problem) !in known })
        }
    }
}

/** Resolves and verifies source on the coordinator; all builds and selection run in preparation allocations. */
private class TrustedQueueSources(private val config: Config, private val artifacts: ArtifactStore) {
    fun publish(job: Job, targets: List<NomadQueueTarget>, settings: NomadQueueSettings): Pair<List<PlannedArm>, List<WorkerBundle>> {
        val spec = checkNotNull(job.experiment); val mirror = Repositories.mirror(config, spec.repository)
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
        Files.createDirectories(mirror.parent)
        val url = Repositories.source(config, spec.repository)
        if (!Files.exists(mirror)) checked(mirror.parent, "clone", "--mirror", url, mirror.toString())
        checked(mirror, "remote", "set-url", "origin", url)
        checked(mirror, "fetch", "--prune", "origin", "+refs/heads/*:refs/heads/*", "+refs/tags/*:refs/tags/*")
        val trust = Trust(config.trustedUsers, url, { args -> git(mirror, *args.toTypedArray()) },
            Trust.githubApi(config.githubTokenFile.takeIf(Files::isRegularFile)?.let(Files::readString)?.trim()))
        trust.requireRepository(refresh = true)
        val arms = Experiments.arms(spec).map { arm ->
            val sha = checked(mirror, "rev-parse", "--verify", "${arm.ref}^{commit}")
            require(sha.matches(Regex("[a-f0-9]{40}")))
            trust.requireCommit(sha, arm.ref)
            PlannedArm(arm, sha)
        }
        val approved = arms.map { it.sha }.toSet()
        val inputs = SourceInputs({ repository, sha -> require(repository == spec.repository && sha in approved); trust.requireRepository() })
        val build = WorkerBuild(spec.profile?.let { ProfileRuns.buildTasks(spec.repository, it) }
            ?: klauseBuildTasks(arms.all { it.arm.values["backend"] == REFERENCE_BACKEND }).split(' '), settings.preparationTimeoutSec)
        val platforms = targets.distinctBy { it.os to it.arch }
        val bundles = arms.map { it.sha }.distinct().flatMap { sha ->
            val output = Files.createTempFile(directory, ".source-", ".zip"); Files.delete(output)
            try {
                val first = platforms.first()
                val bundle = inputs.publish(mirror, spec.repository, sha, first.os, first.arch, "jdk25:${settings.release}", build, output)
                val offset = artifacts.offset(job.id, bundle.sha256)
                Files.newInputStream(output).use { stream -> stream.skipNBytes(offset); artifacts.put(job.id, bundle.sha256, Files.size(output), offset, stream) }
                platforms.map { bundle.copy(os = it.os, arch = it.arch) }
            } finally { Files.deleteIfExists(output) }
        }
        return arms to bundles
    }
}

/** Filter in small database reads so a large catalogue cannot hold the lease/renewal write lock. */
internal fun preparationSelection(store: Store, spec: ExperimentSpec, requested: List<List<Problem>>, active: () -> Unit): List<List<Problem>> {
    if (spec.isProfile) {
        require(requested == listOf(listOf(Experiments.profileProblem(spec))))
        return requested
    }
    if (spec.problemList.isNotEmpty()) {
        require(requested.size == 1 && requested.single().map { it.suite to it.problem } == spec.problemList.map { it.suite to it.problem })
        return requested
    }
    require(requested.size == spec.problems.size)
    val arms = Experiments.arms(spec)
    val solver = arms.takeIf { all -> all.all { it.values["backend"] == REFERENCE_BACKEND } }
        ?.map { it.values["solver"] }?.distinct()?.singleOrNull()
    return spec.problems.mapIndexed { index, selection ->
        val selected = requested[index]
        val references = selected.map { it.collection to it.problem }.distinct().chunked(256).flatMap { keys ->
            active()
            store.references(keys, solver).entries.map { it.key to it.value }
        }.toMap()
        val mode = Experiments.referenceFilter(spec, selection)
        val kept = selected.filter { mode.keeps(references[it.collection to it.problem], arms.maxOf { it.timeoutMs }) }
        if (Experiments.refills(mode, selection)) Experiments.cap(kept, selection["per-family"]?.toIntOrNull(), selection["max"]?.toIntOrNull()) else kept
    }
}
