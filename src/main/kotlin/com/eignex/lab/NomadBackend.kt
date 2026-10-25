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
    private val checkCase: (Long, Int) -> String = { _, _ -> "not-required" },
    private val nativeMayClaim: (Long) -> Boolean = { true },
    private val admit: (WorkManifest, NomadBatchJob) -> Unit = { _, _ -> },
) : ExecutionBackend {
    override val kind = ExecutionBackendKind.NOMAD
    val logs = WorkerLogs(store, jobDirectory)
    private val identities = NomadIdentity(nomad::jwks, nomad.namespace)

    /** Admission is operator-only until source preparation and the canary gates are connected to normal submissions. */
    fun enqueue(jobId: Long, manifest: WorkManifest, template: NomadBatchJob, api: String): ExecutionSession {
        require(workAllowed())
        admit(manifest, template)
        require(if (template.platform == NomadPlatform.LINUX) template.command == listOf("worker") else template.command.size == 2 && template.command[1] == "worker")
        if (template.platform == NomadPlatform.MAC) require(Path.of(template.command.first()).isAbsolute && "current" !in Path.of(template.command.first()).map { it.toString() }) { "native workers require a pinned release launcher" }
        val job = store.job(jobId) ?: error("missing experiment")
        require(job.backend == ExecutionBackendKind.NOMAD && job.experiment == manifest.experiment)
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
        val payload = authenticatedJob(JsonObject(original + ("Namespace" to JsonPrimitive(nomad.namespace))), id, manifest.release)
        return store.execution.createSession(id, jobId, executionClass, manifest.release, manifest.budget, token, payload.toString(), manifest = Json.encodeToString(manifest))
    }

    fun register(id: String, token: String, allocation: String, jwt: String, release: String): WorkManifest {
        require(workAllowed()) { "coordinator volumes unavailable" }
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

    fun commit(owner: AttemptOwner, result: IterationResult): Boolean {
        require(workAllowed()) { "coordinator volumes unavailable" }
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
        return store.execution.commit(owner, result)
    }

    override fun reconcile() {
        // Stops remain deliverable during holds; growth and acceptance do not.
        val logJobs = store.active(withCommands = false).filter { it.backend == ExecutionBackendKind.NOMAD }.map { it.id }.toMutableSet()
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
        for (job in store.jobsByIds(logJobs.toList()).filter { it.backend == ExecutionBackendKind.NOMAD }) {
            val accepted = store.execution.accepted(job.id)
            val checked = store.execution.checksComplete(job.id)
            val ingested = store.execution.ingested(job.id)
            for ((iteration, result) in accepted) {
                if (iteration !in ingested) { project(job.id, result); store.execution.markIngested(job.id, iteration) }
                for (case in result.cases) if (case.index !in checked) {
                    val detail = checkCase(job.id, case.index)
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
                if (current.paused) store.execution.drain(s.id)
                val allocations = nomad.allocations(s.nomadJobId)
                store.execution.observed(s.id, allocations.joinToString { "${it.id}:${it.client}:${it.node}" })
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
                val previous = sessions.last()
                val native = Json.parseToJsonElement(store.execution.submission(previous.id)).jsonObject["Meta"]?.jsonObject?.get("lab_cpu_enforcement")?.jsonPrimitive?.content == "advisory-native"
                if (native && (store.localBusy() || !nativeMayClaim(job.id))) continue
                if (store.execution.failureCount(job.id) > 3) store.execution.failIfActive(job.id, "Nomad infrastructure retry budget exhausted")
                else replace(sessions.last())
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

    private fun replace(previous: ExecutionSession) {
        val id = UUID.randomUUID().toString(); val token = ExecutionStore.token()
        val manifest = store.execution.manifest(previous.id).copy(session = id)
        val original = Json.parseToJsonElement(store.execution.submission(previous.id)).jsonObject
        val groups = original.getValue("TaskGroups").jsonArray.map { group -> JsonObject(group.jsonObject + ("Tasks" to JsonArray(group.jsonObject.getValue("Tasks").jsonArray.map { task ->
            val t = task.jsonObject
            JsonObject(t + ("Env" to JsonObject(t.getValue("Env").jsonObject + mapOf("LAB_WORKER_SESSION" to JsonPrimitive(id), "LAB_WORKER_TOKEN" to JsonPrimitive(token)))))
        }))) }
        val originalMeta = original.getValue("Meta").jsonObject - "lab_spec_hash"
        val jobId = "lab-${previous.jobId}-${previous.executionClass}-$id-1"
        val job = authenticatedJob(JsonObject(original + mapOf("ID" to JsonPrimitive(jobId), "Name" to JsonPrimitive(jobId), "TaskGroups" to JsonArray(groups), "Meta" to JsonObject(originalMeta))), id, previous.release)
        store.execution.createSession(id, previous.jobId, previous.executionClass, previous.release, previous.budget, token, job.toString(), manifest = Json.encodeToString(manifest))
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
