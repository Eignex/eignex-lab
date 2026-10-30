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

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.util.UUID
import com.eignex.lab.util.size

/** Durable outbox delivery and observation. Nomad success alone never completes an experiment. */
class NomadBackend(
    private val store: Store, private val nomad: NomadControl, private val artifacts: ArtifactStore,
    private val jobDirectory: (Long) -> Path, private val workAllowed: () -> Boolean = { true },
    private val checkCase: (Long, Int) -> String? = { _, _ -> "not-required" },
    private val nativeMayClaim: (Long) -> Boolean = { true },
    private val nativeCapacity: ResourceBudget? = null,
    private val admit: (WorkManifest, NomadBatchJob) -> Unit = { _, _ -> },
    private val placementPolicy: (JsonObject) -> JsonObject = { it },
    private val workerRuntime: (JsonObject, WorkManifest) -> Pair<JsonObject, WorkManifest> = { job, manifest -> job to manifest },
    private val projectResult: (Long, String, () -> Unit) -> Boolean = { _, _, work -> work(); true },
    private val writeLog: (Long, () -> Unit) -> Boolean = { _, work -> work(); true },
    private val changed: () -> Unit = {},
    private val nativeNodeTags: Set<String>? = null,
    private val selectProblems: (Store, ExperimentSpec, List<List<Problem>>, () -> Unit) -> List<List<Problem>> = ::preparationSelection,
) {
    private val pendingLogs = mutableSetOf<Long>()
    /** Consecutive delivery failures of each unacknowledged intent, until [MAX_INTENT_ATTEMPTS] abandons it. */
    private val intentFailures = mutableMapOf<String, Int>()
    val logs = WorkerLogs(store, jobDirectory)
    private val identities = NomadIdentity(nomad::jwks, nomad.namespace)
    private val cpuPlacement = NomadCpuPlacement(nomad)
    private val preparationAdmission = PreparationAdmission(store)

    internal fun hasCapacity(template: NomadBatchJob, budget: ResourceBudget): Boolean = cpuPlacement.hasCapacity(template, budget)

    internal fun canPrepare(template: NomadBatchJob, limit: Int = template.environment["LAB_WORKER_MAX_CONCURRENT_BUILDS"]?.toIntOrNull() ?: 2): Boolean =
        preparationAdmission.allows(template.nodeClass, template.platform == NomadPlatform.MAC, limit)

    private fun placement(job: JsonObject, budget: ResourceBudget): JsonObject = cpuPlacement.calibrate(
        preparationAdmission.place(placementPolicy(PreparationAdmission.clear(job))), budget)

    /** Admission is operator-only until source preparation and the canary gates are connected to normal submissions. */
    @Synchronized
    fun enqueue(jobId: Long, manifest: WorkManifest, template: NomadBatchJob, api: String): ExecutionSession {
        check(workAllowed())
        if (manifest.preparationTimeoutSec != null && store.execution.session(manifest.session) == null)
            check(canPrepare(template)) { "waiting for preparation placement" }
        admit(manifest, template)
        require(if (template.platform == NomadPlatform.LINUX) template.command == listOf("worker") else template.command.size == 2 && template.command[1] == "worker")
        if (template.platform == NomadPlatform.MAC) require(Path.of(template.command.first()).isAbsolute && "current" !in Path.of(template.command.first()).map { it.toString() }) { "native workers require a pinned release launcher" }
        val job = store.job(jobId) ?: error("missing experiment")
        if (manifest.preparationTimeoutSec != null) Experiments.requireExecutable(manifest.experiment)
        require(job.backend == Backend.NOMAD && job.experiment == manifest.experiment)
        Placement.refusal(template.platform, manifest.experiment, job.origin, Placement.cloudSchedule(job.origin, store.schedules.all()))
            ?.let { throw IllegalArgumentException(it) }
        if (manifest.preparationTimeoutSec == null) require(store.plans.arms(jobId) == manifest.arms && job.commands.map { it.index }.toSet() == manifest.cases.map { it.index }.toSet())
        else require(!job.setupDone && !job.planned && manifest.arms.map { it.arm } == Experiments.arms(manifest.experiment))
        require(template.budget == manifest.budget)
        val planned = store.plans.casePlan(jobId).associateBy { it.index }
        for (case in manifest.cases) {
            val old = planned.getValue(case.index)
            require(old.problem == case.problem && old.seed == case.seed && old.repeat == case.repeat && old.arm == manifest.arms[case.arm].arm.label)
            require(job.commands.single { it.index == case.index }.timeoutSec == case.timeoutSec)
        }
        store.execution.session(manifest.session)?.let { existing ->
            require(existing.jobId == jobId && store.execution.manifest(existing.id) == manifest)
            return existing
        }
        val token = ExecutionStore.token()
        val id = manifest.session
        val executionClass = if (manifest.preparationTimeoutSec == null) ExecutionClass.SOLVE else ExecutionClass.PREPARE
        val nomadId = "lab-$jobId-$executionClass-$id-1"
        val original = template.copy(id = nomadId, environment = template.environment + mapOf(
            "LAB_WORKER_EXPERIMENT" to jobId.toString(), "LAB_WORKER_SESSION" to id, "LAB_WORKER_TOKEN" to token,
            "LAB_WORKER_API" to (template.environment["LAB_WORKER_API"] ?: api),
            "LAB_WORKER_RELEASE" to manifest.release, "LAB_WORKER_IDENTITY_FILE" to "${'$'}{NOMAD_TASK_DIR}/lab-worker.jwt",
        )).render()
        val preparation = if (manifest.preparationTimeoutSec == null) original else PreparationAdmission.mark(original,
            template.environment["LAB_WORKER_MAX_CONCURRENT_BUILDS"]?.toIntOrNull() ?: 2)
        val calibrated = placement(preparation, manifest.budget)
        val payload = authenticatedJob(JsonObject(calibrated + ("Namespace" to JsonPrimitive(nomad.namespace))), id, manifest.release)
        return store.execution.createSession(id, jobId, executionClass, manifest.release, manifest.budget, token, payload.toString(), manifest = Json.encodeToString(manifest)).also { changed() }
    }

    fun register(id: String, token: String, allocation: String, jwt: String, release: String): WorkManifest {
        check(workAllowed()) { "coordinator volumes unavailable" }
        val session = store.execution.credential(id, token)
        identities.verify(jwt, session, allocation)
        val observed = nomad.allocation(allocation) ?: throw ExecutionFenced("allocation not found")
        if (observed.job != session.nomadJobId || !observed.active) throw ExecutionFenced("allocation inactive")
        store.execution.bind(id, token, allocation, observed.node, release)
        return store.execution.manifest(id)
    }

    fun authenticate(id: String, token: String, allocation: String, jwt: String): ExecutionSession {
        val session = store.execution.credential(id, token, allocation)
        identities.verify(jwt, session, allocation)
        if (session.allocation != allocation) throw ExecutionFenced("allocation is not bound")
        return session
    }

    fun claim(id: String, token: String, allocation: String): IterationClaim? {
        val session = store.execution.session(id) ?: throw ExecutionFenced("unknown session")
        // A retried claim must retain its current iteration even if priorities changed meanwhile.
        if (!store.execution.hasActiveAttempt(id) && store.execution.placement(id).native && !nativeMayRun(session.jobId)) {
            store.execution.drain(id)
            return null
        }
        return store.execution.claim(id, token, allocation)
    }

    fun selectPreparation(owner: AttemptOwner, requested: List<List<Problem>>): List<List<Problem>> {
        check(workAllowed())
        val hash = ExecutionStore.digest(Json.encodeToString(requested))
        store.execution.preparationSelection(owner, hash)?.let { return it }
        val manifest = store.execution.manifest(owner.session)
        val spec = manifest.experiment
        val selected = try {
            selectProblems(store, spec, requested) { store.execution.authorize(owner) }.also { groups ->
                require(groups.any { it.isNotEmpty() }) { "the selection matched no problems the reference filter keeps" }
            }
        } catch (failure: RuntimeException) {
            if (failure is IllegalArgumentException && failure.rejectsAdmission) {
                store.execution.rejectBuild(owner, "problem selection: ${failure.message?.take(1024)}")
                throw ExecutionFenced("problem selection refused")
            }
            if (generateSequence<Throwable>(failure) { it.cause }.any {
                it is ExecutionFenced || it is java.io.IOException || it is java.io.UncheckedIOException ||
                    it is InterruptedException || it is java.util.concurrent.CancellationException || it is WorkerInfrastructureFailure
            }) throw failure
            val location = failure.stackTrace.firstOrNull { it.className.startsWith("com.eignex.lab.") }
            val fingerprint = ExecutionStore.digest("${manifest.release}:$hash:${failure.javaClass.name}:$location")
            val count = store.execution.preparationError(owner, fingerprint)
            if (count >= 3) {
                store.execution.rejectBuild(owner, "problem selection failed $count times with the same coordinator error: ${failure.javaClass.simpleName}: ${failure.message?.take(1024)} at $location; retry after fixing the recipe or coordinator")
                throw ExecutionFenced("repeated problem selection failure")
            }
            throw failure
        }
        check(workAllowed())
        return store.execution.approvePreparationSelection(owner, hash, selected)
    }

    fun commit(owner: AttemptOwner, result: IterationResult): Boolean {
        check(workAllowed()) { "coordinator volumes unavailable" }
        var accepted = result
        val session = store.execution.session(owner.session) ?: throw ExecutionFenced("unknown session")
        require((session.executionClass == ExecutionClass.PREPARE) == (result.preparation != null))
        result.preparation?.let { preparation ->
            val manifest = store.execution.manifest(session.id)
            require(result.cases.isEmpty() && !result.runTimedOut)
            require(preparation.artifacts == result.artifacts.filterKeys { it.startsWith("preparation/") })
            require(preparation.bundles.map { it.commit }.toSet() == manifest.arms.map { it.sha }.toSet())
            require(preparation.bundles.map { Triple(it.commit, it.os, it.arch) }.distinct().size == preparation.bundles.size)
            for (bundle in preparation.bundles + listOfNotNull(preparation.corpus)) {
                require(bundle.repository == manifest.experiment.repository && bundle.build == null)
                require(manifest.bundles.any { it.commit == bundle.commit && it.os == bundle.os && it.arch == bundle.arch })
                require(bundle.sha256 in preparation.artifacts.values)
            }
            require(manifest.experiment.isBench == (preparation.benchPlan != null))
            preparation.benchPlan?.let { plan ->
                require(preparation.benchDescriptor != null)
                require(preparation.armPlans.isEmpty() && manifest.arms.size == 1 || preparation.armPlans.keys == manifest.arms.map { it.arm.label }.toSet())
                for (armPlan in preparation.armPlans.values) {
                    BenchRuns.resources(armPlan)
                    require(BenchRuns.align(plan.cases, armPlan) == armPlan) { "arm plans changed the frozen workloads" }
                }
                BenchRuns.resources(plan)
                require(preparation.selections == listOf(plan.cases.map(BenchRuns::problem)))
            }
            val selected = store.execution.acceptedPreparationSelection(owner)
            if (manifest.experiment.isBench) require(selected == preparation.selections) { "bench plan changed after approval" }
            fun identities(groups: List<List<Problem>>) = groups.map { group -> group.map { Triple(it.suite, it.problem, it.collection) } }
            require(identities(selected) == identities(preparation.selections)) { "prepared files differ from the approved selection" }
            val approvedKeys = selected.flatten().map { it.collection to it.problem }.toSet()
            // Consumer name filters may also classify substring matches; annotations never expand the approved work.
            accepted = result.copy(preparation = preparation.copy(features = preparation.features.filter {
                (it.collection to it.problem) in approvedKeys
            }))
            require(preparation.corpus == null || preparation.sharedInputs.isEmpty())
            require(preparation.sharedInputs.map { it.file.path }.distinct().size == preparation.sharedInputs.size)
            require(preparation.sharedInputs.fold(0L) { total, input -> Math.addExact(total, input.file.bytes) } in 0..32L * 1024 * 1024 * 1024)
            preparation.selections.flatten().forEach { p -> (listOfNotNull(p.model, p.data) + p.includeDirs).forEach(::safeRelative) }
        }
        for ((name, sha) in result.artifacts) {
            safeRelative(name)
            val indices = result.cases.map { it.index }
            val allowed = indices.any { index -> name in listOf("$index.out", "$index.err", "$index.exit", "$index.termination") || name.startsWith("cases/$index/") } ||
                name == "nomad/${owner.iteration.replace(':', '-')}-${owner.generation}-resources.json"
            require(allowed || result.preparation?.artifacts?.get(name) == sha && name.startsWith("preparation/")) { "artifact does not belong to this iteration" }
            artifacts.verify(session.jobId, sha)
        }
        val projected = if (session.executionClass == ExecutionClass.SOLVE) {
            val receipt = result.artifacts["nomad/${owner.iteration.replace(':', '-')}-${owner.generation}-resources.json"]
            val observation = receipt?.let { sha -> runCatching {
                artifacts.open(session.jobId, sha).use { input ->
                    val bytes = input.readNBytes(65537)
                    require(bytes.size <= 65536)
                    Json.decodeFromString<ResourceObservation>(bytes.toString(Charsets.UTF_8)).takeIf { it.budget == session.budget }
                }
            }.getOrNull() }
            accepted.cases.associate { case -> case.index to ComparisonExecution.record(case.record, observation, receipt, session.release) }
        } else emptyMap()
        return store.execution.commit(owner, accepted, projectRecord = { case ->
            if (case.index in projected) projected[case.index] else case.record
        }).also { changed() }
    }

    /**
     * One pass over every session, intent and job. Passes never overlap: the source checks [checkCase] starts are keyed
     * by case and shared between passes (the coordinator's single check thread and its map of checks in flight), and a
     * second pass at once would only start a case's check again, never lose or mix one, but it would race the first on
     * every intent it delivers.
     */
    @Synchronized
    fun reconcile() {
        // Stops remain deliverable during holds; growth and acceptance do not.
        val logJobs = store.active(withCommands = false).filter { it.runsOnNomad }.map { it.id }.toMutableSet()
        store.execution.expireRunDeadlines()
        store.execution.expire()
        // Each intent and each job is isolated: one that keeps failing must not hold back delivery or observation of the rest.
        for (intent in store.execution.intents()) {
            logJobs += intent.jobId
            try {
                deliver(intent)
                intentFailures.remove(intent.id)
            } catch (failure: Exception) {
                val attempts = intentFailures.merge(intent.id, 1, Int::plus)!!
                val reason = describeFailure(failure)
                println("nomad intent ${intent.id} (${intent.operation}) failed, attempt $attempts: $reason")
                if (attempts >= MAX_INTENT_ATTEMPTS) {
                    store.execution.note(intent.jobId, intent.session, "intent-abandoned:${intent.operation}: $reason")
                    store.execution.acknowledge(intent.id)
                    intentFailures.remove(intent.id)
                }
            }
        }
        if (!workAllowed()) return
        logJobs += store.execution.pendingProjectionJobs()
        for (job in store.jobsByIds(logJobs.toList()).filter { it.runsOnNomad }) {
            try { observe(job) }
            catch (failure: Exception) { println("nomad job ${job.id} observation failed: ${describeFailure(failure)}") }
        }
        pendingLogs += logJobs
        pendingLogs.removeAll { job -> writeLog(job) { projectLog(job) } }
    }

    /** Deliver [intent] to Nomad and acknowledge it; returns without acknowledging while work is held. */
    private fun deliver(intent: ExecutionIntent) {
        val session = store.execution.session(intent.session) ?: return
        when (intent.operation) {
            IntentOp.SUBMIT -> {
                if (session.desired == Desired.STOP) { store.execution.acknowledge(intent.id); return }
                if (!workAllowed()) return
                val planned = Json.parseToJsonElement(intent.payload).jsonObject
                val existing = nomad.job(session.nomadJobId)
                if (existing == null) nomad.submit(planned)
                else require(existing["Meta"]?.jsonObject?.get("lab_spec_hash") == planned["Meta"]?.jsonObject?.get("lab_spec_hash")) { "Nomad job identity collision" }
            }
            IntentOp.STOP -> nomad.stop(intent.payload)
        }
        store.execution.acknowledge(intent.id)
    }

    private fun observe(job: Job) {
        val accepted = store.execution.accepted(job.id)
        val checked = store.execution.checksComplete(job.id)
        val ingested = store.execution.ingested(job.id)
        for ((iteration, result) in accepted) {
            if (iteration !in ingested) {
                if (!projectResult(job.id, iteration) { project(job.id, result) }) continue
                store.execution.markIngested(job.id, iteration)
            }
            for (case in result.cases) if (case.index !in checked) {
                val detail = checkCase(job.id, case.index) ?: continue
                store.execution.checkComplete(job.id, case.index, detail)
            }
        }
        val current = store.job(job.id)!!
        if (current.status.isTerminal) return
        if (current.planned && current.commands.all { it.status in setOf(Status.DONE, Status.FAILED) } &&
            current.commands.all { it.index in store.execution.checksComplete(job.id) }) {
            // finishIfComplete rechecks cancellation and projections under the store's write lock.
            if (store.execution.finishIfComplete(job.id)) {
                for (s in store.execution.sessions(job.id).filter { it.desired != Desired.STOP }) store.execution.loseSession(s.id, "completed")
            }
            return
        }
        for (s in store.execution.sessions(job.id).filter { it.desired != Desired.STOP }) {
            if (s.executionClass == ExecutionClass.PREPARE && accepted.any { it.second.preparation != null }) {
                store.execution.loseSession(s.id, "prepared")
                continue
            }
            if (s.executionClass == ExecutionClass.SOLVE && s.allocation == null && store.unclaimed(job.id) == 0) {
                store.execution.loseSession(s.id, "no-unclaimed-work")
                continue
            }
            // Pending allocations cannot reach the claim boundary themselves. Release their reservation
            // so higher-priority native work can be admitted; bound workers yield on their next claim.
            if (s.allocation == null && store.execution.placement(s.id).native &&
                !NativePriority(store).allows(job.id, nativeNodeTags) &&
                store.execution.loseUnallocatedSession(s.id, "priority")) continue
            if (current.paused) store.execution.drain(s.id)
            if (!current.paused && s.desired == Desired.RUN && s.allocation != null) {
                val submitted = Json.parseToJsonElement(store.execution.submission(s.id)).jsonObject
                // A sharing-policy change retires the worker after its accepted iteration, preserving compilation.
                if (placementPolicy(submitted) != submitted) store.execution.drain(s.id)
            }
            val allocations = nomad.allocations(s.nomadJobId)
            store.execution.observed(s.id, allocations.joinToString { "${it.id}:${it.client}:${it.node}" })
            if (!current.paused && s.allocation == null && allocations.isEmpty()) {
                val submission = Json.parseToJsonElement(store.execution.submission(s.id)).jsonObject
                if (placement(submission, s.budget) != submission &&
                    store.execution.loseUnallocatedSession(s.id, "placement-cpu-capacity")) continue
            }
            val bound = allocations.firstOrNull { it.id == s.allocation }
            if ((s.allocation != null && (bound == null || !bound.active)) ||
                (s.allocation == null && allocations.isNotEmpty() && allocations.none { it.active })) {
                val exited = bound ?: allocations.singleOrNull()
                if (!current.paused && s.desired == Desired.RUN && s.executionClass == ExecutionClass.PREPARE && exited?.client == "failed") {
                    // List stubs can omit task events. Fetch full evidence before classifying a worker crash.
                    val exit = exited.workerExit ?: nomad.allocation(exited.id)?.workerExit
                    if (exit?.code == WORKER_SOFTWARE_EXIT && store.execution.preparationExited(s.id, exit.code)?.count?.let { it >= 3 } == true) continue
                }
                store.execution.loseSession(s.id, when {
                    current.paused || s.desired == Desired.DRAIN -> "drained"
                    bound?.client == "complete" && !store.execution.hasActiveAttempt(s.id) -> "idle"
                    else -> "infrastructure:${bound?.client ?: "failed"}"
                })
            }
        }
        if (store.job(job.id)?.status?.isActive != true) return
        val sessions = store.execution.sessions(job.id)
        if (!current.paused && (store.unclaimed(job.id) > 0 || !current.setupDone && accepted.none { it.second.preparation != null } && sessions.any { it.executionClass == ExecutionClass.PREPARE }) && sessions.isNotEmpty() && sessions.all { it.desired == Desired.STOP }) {
            store.execution.pauseClock(job.id)
            // Once preparation is accepted, only admission may create the first solve session. Replacing a
            // stopped preparation here races plan publication and recreates already accepted preparation.
            val previous = sessions.lastOrNull { accepted.none { it.second.preparation != null } || it.executionClass == ExecutionClass.SOLVE } ?: return
            if (previous.executionClass == ExecutionClass.PREPARE) {
                try { Experiments.requireExecutable(store.execution.manifest(previous.id).experiment) }
                catch (failure: IllegalArgumentException) {
                    store.execution.failIfActive(job.id, "Nomad preparation/admission: ${failure.message}")
                    return
                }
            }
            val pool = store.execution.placement(previous.id).pool
            if (pool is WorkerPool.Native) {
                if (!nativeMayRun(job.id)) return
                val limit = nativeLimits(pool)
                if (limit == null) {
                    if (store.execution.nativeOwnedByAnother(job.id)) return
                } else {
                    val held = store.execution.nativeReservation()
                    if (held.first + previous.budget.cpuMillis > limit.first || held.second + previous.budget.memoryMiB > limit.second) return
                }
            }
            // Worker/node loss revokes ownership, never the experiment. Keep its frozen inputs and
            // accepted results, and let a fresh session wait for any compatible replacement node.
            replace(previous)
        }
    }

    private fun projectLog(job: Long) {
        val current = store.job(job) ?: return
        val events = store.execution.events(job)
        val text = events.joinToString("\n", postfix = "\n") { event ->
            "${java.time.Instant.ofEpochMilli(event.at)} ${event.session ?: "experiment"} ${event.detail}"
        } + "status: ${current.status}\n" + (current.error?.let { "error: $it\n" } ?: "")
        val path = jobDirectory(job).resolve("job.log")
        Files.createDirectories(path.parent)
        if (!Files.isRegularFile(path) || Files.readString(path) != text) path.writeAtomically(text)
    }

    /** The native reservation's limits a session on [pool] holds to: its own, else the native worker's capacity. */
    private fun nativeLimits(pool: WorkerPool.Native): Pair<Long, Long>? {
        val cpu = listOfNotNull(pool.cpuLimit, nativeCapacity?.cpuMillis?.toLong()).minOrNull() ?: return null
        val memory = listOfNotNull(pool.memoryLimit, nativeCapacity?.memoryMiB?.toLong()).minOrNull() ?: return null
        return cpu to memory
    }

    /** Whether the native worker may take up job [jobId] now: the runner is idle, no update waits, and it comes first. */
    private fun nativeMayRun(jobId: Long): Boolean =
        !store.localBusy() && nativeMayClaim(jobId) && NativePriority(store).allows(jobId, nativeNodeTags)

    private fun replace(previous: ExecutionSession) {
        val submitted = Json.parseToJsonElement(store.execution.submission(previous.id)).jsonObject
        val priorPlacement = SessionPlacement.of(submitted)
        val limit = priorPlacement.preparationSlots ?: 2
        if (previous.executionClass == ExecutionClass.PREPARE &&
            !preparationAdmission.allows(priorPlacement.nodeClass, priorPlacement.native, limit)) return
        val id = UUID.randomUUID().toString(); val token = ExecutionStore.token()
        val (runtime, manifest) = workerRuntime(submitted,
            store.execution.manifest(previous.id).copy(session = id))
        val preparation = if (previous.executionClass == ExecutionClass.PREPARE) PreparationAdmission.mark(runtime, limit) else runtime
        val original = placement(preparation, previous.budget)
        val groups = original.getValue("TaskGroups").jsonArray.map { group -> JsonObject(group.jsonObject + ("Tasks" to JsonArray(group.jsonObject.getValue("Tasks").jsonArray.map { task ->
            val t = task.jsonObject
            JsonObject(t + ("Env" to JsonObject(t.getValue("Env").jsonObject + mapOf("LAB_WORKER_SESSION" to JsonPrimitive(id), "LAB_WORKER_TOKEN" to JsonPrimitive(token)))))
        }))) }
        val originalMeta = (original.getValue("Meta").jsonObject - "lab_spec_hash").toMutableMap()
        val pool = SessionPlacement.of(original).pool
        if (pool is WorkerPool.Native) {
            nativeLimits(pool)?.let { (cpu, memory) ->
                // New intent carries the same limits into the atomic reservation check; history remains immutable.
                originalMeta["lab_native_cpu_limit"] = JsonPrimitive(cpu.toString())
                originalMeta["lab_native_memory_limit"] = JsonPrimitive(memory.toString())
            }
        }
        val jobId = "lab-${previous.jobId}-${previous.executionClass}-$id-1"
        val job = authenticatedJob(JsonObject(original + mapOf("ID" to JsonPrimitive(jobId), "Name" to JsonPrimitive(jobId), "TaskGroups" to JsonArray(groups), "Meta" to JsonObject(originalMeta))), id, manifest.release)
        store.execution.createSession(id, previous.jobId, previous.executionClass, manifest.release, previous.budget, token, job.toString(), manifest = Json.encodeToString(manifest))
    }

    private fun project(job: Long, result: IterationResult) {
        val directory = jobDirectory(job).toAbsolutePath().normalize(); Files.createDirectories(directory)
        for ((name, sha) in result.artifacts) {
            val target = directory.resolve(safeRelative(name)); Files.createDirectories(target.parent)
            // Directory symlinks are never supplied by workers and cannot redirect ingestion.
            var parent = target.parent
            while (parent != directory) { require(!Files.isSymbolicLink(parent)); parent = parent.parent }
            if (Files.isRegularFile(target) && !Files.isSymbolicLink(target) && hashFile(target) == sha) continue
            val staging = Files.createTempFile(target.parent, ".ingest-", ".tmp")
            try { artifacts.open(job, sha).use { Files.copy(it, staging, REPLACE_EXISTING) }; Files.move(staging, target, ATOMIC_MOVE, REPLACE_EXISTING) }
            finally { Files.deleteIfExists(staging) }
        }
    }

    companion object {
        /** Delivery attempts after which an intent Nomad keeps refusing is recorded and given up on. */
        internal const val MAX_INTENT_ATTEMPTS = 20

        internal fun authenticatedJob(original: JsonObject, session: String, release: String): JsonObject {
            val groups = original.getValue("TaskGroups").jsonArray.map { group ->
                val g = group.jsonObject
                JsonObject(g + ("Disconnect" to buildJsonObject { put("LostAfter", 0); put("Replace", true); put("Reconcile", "keep_replacement") }) +
                    ("Tasks" to JsonArray(g.getValue("Tasks").jsonArray.map { task -> JsonObject(task.jsonObject + ("Identities" to buildJsonArray {
                        add(buildJsonObject { put("Name", "lab-worker"); putJsonArray("Audience") { add("eignex-lab-worker") }; put("File", true); put("Filepath", "local/lab-worker.jwt"); put("TTL", 600_000_000_000L); put("ChangeMode", "noop") })
                    })) })))
            }
            val meta = original.getValue("Meta").jsonObject + mapOf("lab_execution_session" to JsonPrimitive(session), "lab_worker_release" to JsonPrimitive(release))
            val job = JsonObject(original + ("TaskGroups" to JsonArray(groups)) + ("Meta" to JsonObject(meta)))
            return JsonObject(job + ("Meta" to JsonObject(meta + ("lab_spec_hash" to JsonPrimitive(ExecutionStore.digest(job.toString()))))))
        }
    }
}
