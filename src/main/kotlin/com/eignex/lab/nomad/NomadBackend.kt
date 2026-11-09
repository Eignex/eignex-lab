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
    private val solveAllowed: () -> Boolean = { true },
) {
    val logs = WorkerLogs(store, jobDirectory)
    private val identities = NomadIdentity(nomad::jwks, nomad.namespace)
    private val cpuPlacement = NomadCpuPlacement(nomad)
    private val preparationAdmission = PreparationAdmission(store)
    private val reconciler = NomadReconciler(
        store, nomad, jobDirectory, workAllowed, checkCase, placementPolicy, projectResult, writeLog, nativeNodeTags,
        ::mayStartSolve, ::placement, ::project, ::replace, ::nativeMayRun, ::nativeLimits,
    )

    /** Separate from volume/acceptance health: a hold never revokes already-owned work. */
    internal fun mayStartSolve(): Boolean = solveAllowed()

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
        val timeouts = job.commands.associate { it.index to it.timeoutSec }
        for (case in manifest.cases) {
            val old = planned.getValue(case.index)
            require(old.problem == case.problem && old.seed == case.seed && old.repeat == case.repeat && old.arm == manifest.arms[case.arm].arm.label)
            require(case.index in timeouts && timeouts[case.index] == case.timeoutSec)
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
        if (executionClass == ExecutionClass.SOLVE) check(mayStartSolve()) { "waiting for result journal drain" }
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

    fun claim(id: String, token: String, allocation: String, waitWhenHeld: Boolean = false): IterationClaim? {
        val session = store.execution.session(id) ?: throw ExecutionFenced("unknown session")
        // A retried claim must retain its current iteration even if priorities changed meanwhile.
        if (!store.execution.hasActiveAttempt(id) && store.execution.placement(id).native && !nativeMayRun(session.jobId)) {
            store.execution.drain(id)
            return null
        }
        // Read the cached pressure bit at claim allocation, after replaying any existing owner.
        // Production's callback takes no journal monitor and performs no filesystem IO.
        return store.execution.claim(id, token, allocation, allowNewSolve = ::mayStartSolve, waitWhenHeld = waitWhenHeld)
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
            // Build reports answer an offer of accepted builds, one per built source bundle.
            require(preparation.builds.isEmpty() || manifest.reusableBuilds != null)
            require(preparation.builds.map { it.commit }.distinct().size == preparation.builds.size && preparation.builds.all { built ->
                manifest.bundles.any { it.commit == built.commit && it.os == built.os && it.arch == built.arch && it.build != null }
            }) { "prepared builds differ from the source bundles" }
            val target = manifest.bundles.firstOrNull()?.build?.preparation?.target
            if (target != null) require(preparation.bundles.all(target::matches)) { "prepared output differs from the declared cross-build target" }
            for (bundle in preparation.bundles + listOfNotNull(preparation.corpus)) {
                require(bundle.repository == manifest.experiment.repository && bundle.build == null)
                require(manifest.bundles.any { it.commit == bundle.commit && it.os == bundle.os && it.arch == bundle.arch })
                require(bundle.sha256 in preparation.artifacts.values)
                bundle.tree?.let {
                    require(store.execution.placement(session.id).native && it.job == session.jobId) { "prepared tree must belong to the native job" }
                    var nextOwnershipCheck = Long.MIN_VALUE
                    require(requireNotNull(NativePreparedTrees.environment()).valid(bundle) {
                        val at = System.nanoTime()
                        if (at >= nextOwnershipCheck) {
                            store.execution.authorize(owner)
                            nextOwnershipCheck = at + 1_000_000_000
                        }
                    })
                    store.execution.authorize(owner)
                }
            }
            require(manifest.experiment.isBench == (preparation.benchPlan != null))
            preparation.benchPlan?.let { plan ->
                require(preparation.benchDescriptor != null)
                require(preparation.armPlans.isEmpty() && manifest.arms.size == 1 || preparation.armPlans.keys == manifest.arms.map { it.arm.label }.toSet())
                for (armPlan in preparation.armPlans.values) {
                    target?.let { output -> armPlan.cases.forEach { output.validateRequirements(it.resources.requiredTags) } }
                    BenchCases.resources(armPlan)
                    require(BenchRuns.align(plan.cases, armPlan) == armPlan) { "arm plans changed the frozen workloads" }
                }
                target?.let { output -> plan.cases.forEach { output.validateRequirements(it.resources.requiredTags) } }
                BenchCases.resources(plan)
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
            }, selectionCache = emptyList()))
            // An offered plan must be the one this job accepted, and its corpus inputs ones this job retained.
            require(preparation.selectionCache.map { it.arm }.distinct().size == preparation.selectionCache.size)
            val retained = preparation.sharedInputs.toSet()
            for (entry in preparation.selectionCache) {
                require(manifest.arms.any { it.arm.label == entry.arm && it.arm.values["backend"] != REFERENCE_BACKEND })
                val armPlan = requireNotNull(preparation.armPlans[entry.arm]) { "selection cache names an unplanned arm" }
                require(entry.descriptor.id == preparation.benchDescriptor?.id)
                BenchCases.resources(entry.plan)
                require(BenchRuns.align(armPlan.cases, entry.plan) == armPlan) { "selection cache differs from the accepted plan" }
                require(retained.containsAll(entry.sharedInputs))
                require(BenchSelections.covering(entry.sharedInputs, BenchSelections.corpusPaths(entry.plan)) != null)
            }
            require(preparation.corpus == null || preparation.sharedInputs.isEmpty())
            require(preparation.sharedInputs.map { it.file.path }.distinct().size == preparation.sharedInputs.size)
            require(preparation.sharedInputs.fold(0L) { total, input -> Math.addExact(total, input.file.bytes) } in 0..32L * 1024 * 1024 * 1024)
            preparation.selections.flatten().forEach { p -> (listOfNotNull(p.model, p.data) + p.includeDirs).forEach(::safeRelative) }
        }
        if (result.regionalBlobs.isNotEmpty()) {
            require(session.executionClass == ExecutionClass.SOLVE && result.preparation == null)
            val named = result.artifacts.values.toSet()
            require(result.regionalBlobs.all { (sha, bytes) -> SHA256.matches(sha) && sha in named && bytes in 0..ResultBlobs.MAX_BYTES }) {
                "regional outputs must be this result's own"
            }
        }
        for ((name, sha) in result.artifacts) {
            safeRelative(name)
            val indices = result.cases.map { it.index }
            val allowed = indices.any { index -> name in listOf("$index.out", "$index.err", "$index.exit", "$index.termination") || name.startsWith("cases/$index/") } ||
                name == "nomad/${owner.iteration.replace(':', '-')}-${owner.generation}-resources.json"
            require(allowed || result.preparation?.artifacts?.get(name) == sha && name.startsWith("preparation/")) { "artifact does not belong to this iteration" }
            if (sha !in result.regionalBlobs) artifacts.verify(session.jobId, sha)
        }
        // Only once the cheap checks pass: each costs the regional store a request.
        if (result.regionalBlobs.isNotEmpty()) verifyRegional(result.regionalBlobs)
        val projected = if (session.executionClass == ExecutionClass.SOLVE) {
            val receipt = result.artifacts["nomad/${owner.iteration.replace(':', '-')}-${owner.generation}-resources.json"]
            val observation = receipt?.let { sha -> runCatching {
                artifacts.open(session.jobId, sha).use { input ->
                    val bytes = input.readNBytes(65537)
                    require(bytes.size <= 65536)
                    Json.decodeFromString<ResourceObservation>(bytes.toString(Charsets.UTF_8)).takeIf { it.budget == session.budget }
                }
            }.getOrNull() }
            val measured = store.execution.adapterResources(session.id)
            fun capture(name: String, limit: Int): Pair<String?, String?> {
                val sha = accepted.artifacts[name] ?: return null to null
                val text = runCatching { artifacts.open(session.jobId, sha).use { input ->
                    val bytes = input.readNBytes(limit + 1)
                    require(bytes.size <= limit)
                    bytes.toString(Charsets.UTF_8)
                } }.getOrNull()
                return text to sha
            }
            accepted.cases.associate { case ->
                val (metadata, metadataSha) = if (measured) capture("cases/${case.index}/${AdapterResources.RECEIPT}", AdapterResources.LIMIT * 2) else null to null
                val (raw, rawSha) = if (measured) capture("cases/${case.index}/${AdapterResources.RAW}", AdapterResources.LIMIT) else null to null
                val physical = ComparisonExecution.record(case.record, observation, receipt, session.release)
                case.index to if (measured) AdapterResources.project(physical, true, metadata, metadataSha, raw, rawSha, case.command) else physical
            }
        } else emptyMap()
        return store.execution.commit(owner, accepted, projectRecord = { case ->
            if (case.index in projected) projected[case.index] else case.record
        }, benchSelections = result.preparation?.selectionCache.orEmpty()).also { changed() }
    }

    /**
     * One pass over every session, intent and job. Passes never overlap: the source checks [checkCase] starts are keyed
     * by case and shared between passes (the coordinator's single check thread and its map of checks in flight), and a
     * second pass at once would only start a case's check again, never lose or mix one, but it would race the first on
     * every intent it delivers.
     */
    @Synchronized
    fun reconcile() = reconciler.reconcile()

    /** The native reservation's limits a session on [pool] holds to: its own, else the native worker's capacity. */
    private fun nativeLimits(pool: WorkerPool.Native): Pair<Long, Long>? {
        val cpu = listOfNotNull(pool.cpuLimit, nativeCapacity?.cpuMillis?.toLong()).minOrNull() ?: return null
        val memory = listOfNotNull(pool.memoryLimit, nativeCapacity?.memoryMiB?.toLong()).minOrNull() ?: return null
        return cpu to memory
    }

    /** Whether the native worker may take up job [jobId] now: the runner is idle, no update waits, and it comes first. */
    private fun nativeMayRun(jobId: Long): Boolean =
        !store.localBusy() && nativeMayClaim(jobId) && NativePriority(store).allows(jobId, nativeNodeTags)

    @Synchronized
    private fun replace(previous: ExecutionSession) {
        if (previous.executionClass == ExecutionClass.SOLVE && !mayStartSolve()) return
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
        if (previous.executionClass == ExecutionClass.SOLVE && !mayStartSolve()) return
        store.execution.createSession(id, previous.jobId, previous.executionClass, manifest.release, previous.budget, token, job.toString(), manifest = Json.encodeToString(manifest))
    }

    /**
     * Accept outputs a cloud worker left in the regional result store only once that store answers for each one, by its
     * content hash, with its declared size. An unreachable store is transient (the worker retries); a missing or
     * different object, or no store at all, refuses them ([RegionalResultRefused]) and the worker sends them here.
     */
    private fun verifyRegional(blobs: Map<String, Long>) {
        for ((sha, bytes) in blobs) {
            val stored = try { artifacts.regionalResult(sha) }
                catch (failure: java.io.IOException) { throw failure }
                catch (failure: RegionalResultRefused) { throw failure }
                catch (failure: Exception) { throw RegionalResultRefused("regional result store refused $sha: ${failure.message}") }
            if (stored != bytes) throw RegionalResultRefused(
                if (stored == null) "regional result $sha is missing" else "regional result $sha holds $stored bytes, not $bytes")
        }
    }

    private fun project(job: Long, result: IterationResult) {
        val directory = jobDirectory(job).toAbsolutePath().normalize(); Files.createDirectories(directory)
        for ((name, sha) in result.artifacts) {
            // A build archive kept in the regional store stays there: copying it here is what it avoids.
            if (name.startsWith("preparation/") && name.endsWith(".zip") && artifacts.regional(job, sha)) continue
            val target = directory.resolve(safeRelative(name)); Files.createDirectories(target.parent)
            // Directory symlinks are never supplied by workers and cannot redirect ingestion.
            var parent = target.parent
            while (parent != directory) { require(!Files.isSymbolicLink(parent)); parent = parent.parent }
            if (Files.isRegularFile(target) && !Files.isSymbolicLink(target) && hashFile(target) == sha) continue
            // A regional output is first read here: the job directory is what analyses, checks and file reads use. One the
            // store's lifecycle already expired stays listed but unavailable, rather than holding the job's ingestion.
            val regional = result.regionalBlobs[sha]
            if (regional != null && artifacts.offset(job, sha) != regional &&
                (try { artifacts.regionalResult(sha) } catch (_: RegionalResultRefused) { null }) != regional) {
                store.execution.note(job, null, "regional-result-unavailable:$name:$sha")
                continue
            }
            val staging = Files.createTempFile(target.parent, ".ingest-", ".tmp")
            try { artifacts.open(job, sha).use { Files.copy(it, staging, REPLACE_EXISTING) }; Files.move(staging, target, ATOMIC_MOVE, REPLACE_EXISTING) }
            finally { Files.deleteIfExists(staging) }
        }
    }

    companion object {
        /** How often projection scans every job rather than only active and recently ended ones. */
        internal const val FULL_PROJECTION_SCAN_MS = 3_600_000L
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
