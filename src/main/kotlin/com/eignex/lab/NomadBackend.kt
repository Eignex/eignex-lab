package com.eignex.lab

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.util.UUID

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
) {
    val logs = WorkerLogs(store, jobDirectory)
    private val identities = NomadIdentity(nomad::jwks, nomad.namespace)
    private val cpuPlacement = NomadCpuPlacement(nomad)

    internal fun hasCapacity(template: NomadBatchJob, budget: ResourceBudget): Boolean = cpuPlacement.hasCapacity(template, budget)

    /** Admission is operator-only until source preparation and the canary gates are connected to normal submissions. */
    fun enqueue(jobId: Long, manifest: WorkManifest, template: NomadBatchJob, api: String): ExecutionSession {
        check(workAllowed())
        admit(manifest, template)
        require(if (template.platform == NomadPlatform.LINUX) template.command == listOf("worker") else template.command.size == 2 && template.command[1] == "worker")
        if (template.platform == NomadPlatform.MAC) require(Path.of(template.command.first()).isAbsolute && "current" !in Path.of(template.command.first()).map { it.toString() }) { "native workers require a pinned release launcher" }
        val job = store.job(jobId) ?: error("missing experiment")
        require(job.backend == "nomad" && job.experiment == manifest.experiment)
        if (template.platform == NomadPlatform.LINUX) {
            val reason = Placement.localOnlyReason(manifest.experiment, job.name, Placement.cloudSchedule(job.name, store.schedules()))
            require(reason == null && Experiments.storedHost(manifest.experiment.host) != Experiments.LAB_HOST) { reason ?: "experiment is placed on the local host" }
        } else require(Experiments.storedHost(manifest.experiment.host) != Experiments.AWS_HOST) { "experiment is cloud-only" }
        if (manifest.preparationTimeoutSec == null) require(store.arms(jobId) == manifest.arms && job.commands.map { it.index }.toSet() == manifest.cases.map { it.index }.toSet())
        else require(!job.setupDone && job.commands.isEmpty() && manifest.arms.map { it.arm } == Experiments.arms(manifest.experiment))
        require(template.budget == manifest.budget)
        val planned = store.casePlan(jobId).associateBy { it.index }
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
        val executionClass = if (manifest.preparationTimeoutSec == null) "solve" else "prepare"
        val nomadId = "lab-$jobId-$executionClass-$id-1"
        val original = template.copy(id = nomadId, environment = template.environment + mapOf(
            "LAB_WORKER_EXPERIMENT" to jobId.toString(), "LAB_WORKER_SESSION" to id, "LAB_WORKER_TOKEN" to token, "LAB_WORKER_API" to api,
            "LAB_WORKER_RELEASE" to manifest.release, "LAB_WORKER_IDENTITY_FILE" to "${'$'}{NOMAD_TASK_DIR}/lab-worker.jwt",
        )).render()
        val calibrated = cpuPlacement.calibrate(placementPolicy(original), manifest.budget)
        val payload = authenticatedJob(JsonObject(calibrated + ("Namespace" to JsonPrimitive(nomad.namespace))), id, manifest.release)
        return store.execution.createSession(id, jobId, executionClass, manifest.release, manifest.budget, token, payload.toString(), manifest = Json.encodeToString(manifest))
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
        val spec = Json.parseToJsonElement(store.execution.submission(id)).jsonObject
        if (spec["Meta"]?.jsonObject?.get("lab_cpu_enforcement")?.jsonPrimitive?.content == "advisory-native" &&
            (store.localBusy() || !nativeMayClaim(session.jobId))) {
            store.execution.drain(id)
            return null
        }
        return store.execution.claim(id, token, allocation)
    }

    fun selectPreparation(owner: AttemptOwner, requested: List<List<Problem>>): List<List<Problem>> {
        check(workAllowed())
        val hash = ExecutionStore.digest(Json.encodeToString(requested))
        store.execution.preparationSelection(owner, hash)?.let { return it }
        val spec = store.execution.manifest(owner.session).experiment
        val selected = try {
            preparationSelection(store, spec, requested) { store.execution.authorize(owner) }.also { groups ->
                require(groups.any { it.isNotEmpty() }) { "the selection matched no problems the reference filter keeps" }
            }
        } catch (failure: IllegalArgumentException) {
            store.execution.rejectBuild(owner, "problem selection: ${failure.message?.take(1024)}")
            throw ExecutionFenced("problem selection refused")
        }
        check(workAllowed())
        return store.execution.approvePreparationSelection(owner, hash, selected)
    }

    fun commit(owner: AttemptOwner, result: IterationResult): Boolean {
        check(workAllowed()) { "coordinator volumes unavailable" }
        var accepted = result
        val session = store.execution.session(owner.session) ?: throw ExecutionFenced("unknown session")
        require((session.executionClass == "prepare") == (result.preparation != null))
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
            val selected = store.execution.acceptedPreparationSelection(owner)
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
            artifacts.file(session.jobId, sha)
        }
        return store.execution.commit(owner, accepted)
    }

    fun reconcile() {
        // Stops remain deliverable during holds; growth and acceptance do not.
        val logJobs = store.active(withCommands = false).filter { it.backend == "nomad" && it.experiment?.isAnalysis != true }.map { it.id }.toMutableSet()
        store.execution.expireRunDeadlines()
        store.execution.expire()
        for (intent in store.execution.intents()) {
            val session = store.execution.session(intent.session) ?: continue
            logJobs += intent.jobId
            when (intent.operation) {
                "submit" -> {
                    if (session.desired == "stop") { store.execution.acknowledge(intent.id); continue }
                    if (!workAllowed()) continue
                    val planned = Json.parseToJsonElement(intent.payload).jsonObject
                    val existing = nomad.job(session.nomadJobId)
                    if (existing == null) nomad.submit(planned)
                    else require(existing["Meta"]?.jsonObject?.get("lab_spec_hash") == planned["Meta"]?.jsonObject?.get("lab_spec_hash")) { "Nomad job identity collision" }
                }
                "stop" -> nomad.stop(intent.payload)
                else -> error("unknown execution intent")
            }
            store.execution.acknowledge(intent.id)
        }
        if (!workAllowed()) return
        logJobs += store.execution.pendingProjectionJobs()
        for (job in store.jobsByIds(logJobs.toList()).filter { it.backend == "nomad" && it.experiment?.isAnalysis != true }) {
            val accepted = store.execution.accepted(job.id)
            val checked = store.execution.checksComplete(job.id)
            val ingested = store.execution.ingested(job.id)
            for ((iteration, result) in accepted) {
                if (iteration !in ingested) { project(job.id, result); store.execution.markIngested(job.id, iteration) }
                for (case in result.cases) if (case.index !in checked) {
                    val detail = checkCase(job.id, case.index) ?: continue
                    store.execution.checkComplete(job.id, case.index, detail)
                }
            }
            val current = store.job(job.id)!!
            if (current.status !in setOf(Status.QUEUED, Status.RUNNING)) continue
            if (current.commands.isNotEmpty() && current.commands.all { it.status in setOf(Status.DONE, Status.FAILED) } &&
                current.commands.all { it.index in store.execution.checksComplete(job.id) }) {
                // finishIfComplete rechecks cancellation and projections under the store's write lock.
                store.execution.finishIfComplete(job.id)
                for (s in store.execution.sessions(job.id).filter { it.desired != "stop" }) store.execution.loseSession(s.id, "completed")
                continue
            }
            for (s in store.execution.sessions(job.id).filter { it.desired != "stop" }) {
                if (s.executionClass == "prepare" && accepted.any { it.second.preparation != null }) {
                    store.execution.loseSession(s.id, "prepared")
                    continue
                }
                if (s.executionClass == "solve" && s.allocation == null && store.unclaimed(job.id) == 0) {
                    store.execution.loseSession(s.id, "no-unclaimed-work")
                    continue
                }
                if (current.paused) store.execution.drain(s.id)
                if (!current.paused && s.desired == "run" && s.allocation != null) {
                    val submitted = Json.parseToJsonElement(store.execution.submission(s.id)).jsonObject
                    // A sharing-policy change retires the worker after its accepted iteration, preserving compilation.
                    if (placementPolicy(submitted) != submitted) store.execution.drain(s.id)
                }
                val allocations = nomad.allocations(s.nomadJobId)
                store.execution.observed(s.id, allocations.joinToString { "${it.id}:${it.client}:${it.node}" })
                if (!current.paused && s.allocation == null && allocations.isEmpty()) {
                    val submission = Json.parseToJsonElement(store.execution.submission(s.id)).jsonObject
                    if (cpuPlacement.calibrate(placementPolicy(submission), s.budget) != submission &&
                        store.execution.loseUnallocatedSession(s.id, "placement-cpu-capacity")) continue
                }
                val bound = allocations.firstOrNull { it.id == s.allocation }
                if ((s.allocation != null && (bound == null || !bound.active)) ||
                    (s.allocation == null && allocations.isNotEmpty() && allocations.none { it.active })) {
                    store.execution.loseSession(s.id, when {
                        current.paused || s.desired == "drain" -> "drained"
                        bound?.client == "complete" && !store.execution.hasActiveAttempt(s.id) -> "idle"
                        else -> "infrastructure:${bound?.client ?: "failed"}"
                    })
                }
            }
            val sessions = store.execution.sessions(job.id)
            if (!current.paused && (store.unclaimed(job.id) > 0 || !current.setupDone && accepted.none { it.second.preparation != null } && sessions.any { it.executionClass == "prepare" }) && sessions.isNotEmpty() && sessions.all { it.desired == "stop" }) {
                store.execution.pauseClock(job.id)
                // Once preparation is accepted, only admission may create the first solve session. Replacing a
                // stopped preparation here races plan publication and recreates already accepted preparation.
                val previous = sessions.lastOrNull { accepted.none { it.second.preparation != null } || it.executionClass == "solve" } ?: continue
                val native = Json.parseToJsonElement(store.execution.submission(previous.id)).jsonObject["Meta"]?.jsonObject?.get("lab_cpu_enforcement")?.jsonPrimitive?.content == "advisory-native"
                if (native && (store.localBusy() || !nativeMayClaim(job.id))) continue
                if (native) {
                    val meta = Json.parseToJsonElement(store.execution.submission(previous.id)).jsonObject["Meta"]?.jsonObject
                    val limit = nativeLimits(meta)
                    if (limit == null) {
                        if (store.execution.nativeOwnedByAnother(job.id)) continue
                    } else {
                        val held = store.execution.nativeReservation()
                        if (held.first + previous.budget.cpuMillis > limit.first || held.second + previous.budget.memoryMiB > limit.second) continue
                    }
                }
                // Worker/node loss revokes ownership, never the experiment. Keep its frozen inputs and
                // accepted results, and let a fresh session wait for any compatible replacement node.
                replace(previous)
            }
        }
        for (job in logJobs) {
            val current = store.job(job) ?: continue
            val events = store.execution.events(job)
            val text = events.joinToString("\n", postfix = "\n") { event ->
                "${java.time.Instant.ofEpochMilli(event.at)} ${event.session ?: "experiment"} ${event.detail}"
            } + "status: ${current.status}\n" + (current.error?.let { "error: $it\n" } ?: "")
            val path = jobDirectory(job).resolve("job.log")
            Files.createDirectories(path.parent)
            if (!Files.isRegularFile(path) || Files.readString(path) != text) path.writeAtomically(text)
        }
    }

    private fun nativeLimits(meta: JsonObject?): Pair<Long, Long>? {
        val cpu = listOfNotNull(meta?.get("lab_native_cpu_limit")?.jsonPrimitive?.longOrNull, nativeCapacity?.cpuMillis?.toLong()).minOrNull() ?: return null
        val memory = listOfNotNull(meta?.get("lab_native_memory_limit")?.jsonPrimitive?.longOrNull, nativeCapacity?.memoryMiB?.toLong()).minOrNull() ?: return null
        return cpu to memory
    }

    private fun replace(previous: ExecutionSession) {
        val id = UUID.randomUUID().toString(); val token = ExecutionStore.token()
        val (runtime, manifest) = workerRuntime(Json.parseToJsonElement(store.execution.submission(previous.id)).jsonObject,
            store.execution.manifest(previous.id).copy(session = id))
        val original = cpuPlacement.calibrate(placementPolicy(runtime), previous.budget)
        val groups = original.getValue("TaskGroups").jsonArray.map { group -> JsonObject(group.jsonObject + ("Tasks" to JsonArray(group.jsonObject.getValue("Tasks").jsonArray.map { task ->
            val t = task.jsonObject
            JsonObject(t + ("Env" to JsonObject(t.getValue("Env").jsonObject + mapOf("LAB_WORKER_SESSION" to JsonPrimitive(id), "LAB_WORKER_TOKEN" to JsonPrimitive(token)))))
        }))) }
        val originalMeta = (original.getValue("Meta").jsonObject - "lab_spec_hash").toMutableMap()
        if (originalMeta["lab_cpu_enforcement"]?.jsonPrimitive?.content == "advisory-native") {
            nativeLimits(JsonObject(originalMeta))?.let { (cpu, memory) ->
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
            try { Files.copy(artifacts.file(job, sha), staging, REPLACE_EXISTING); Files.move(staging, target, ATOMIC_MOVE, REPLACE_EXISTING) }
            finally { Files.deleteIfExists(staging) }
        }
    }

    companion object {
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
