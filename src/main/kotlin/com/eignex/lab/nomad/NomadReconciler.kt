package com.eignex.lab.nomad

import com.eignex.lab.capacity.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import com.eignex.lab.worker.*
import com.eignex.lab.nomad.NomadBackend.Companion.FULL_PROJECTION_SCAN_MS
import com.eignex.lab.nomad.NomadBackend.Companion.MAX_INTENT_ATTEMPTS

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/** The reconcile/observe loop of [NomadBackend]: intent delivery, job observation and job.log projection. */
internal class NomadReconciler(
    private val store: Store, private val nomad: NomadControl,
    private val jobDirectory: (Long) -> Path, private val workAllowed: () -> Boolean,
    private val checkCase: (Long, Int) -> String?,
    private val placementPolicy: (JsonObject) -> JsonObject,
    private val projectResult: (Long, String, () -> Unit) -> Boolean,
    private val writeLog: (Long, () -> Unit) -> Boolean,
    private val nativeNodeTags: Set<String>?,
    private val mayStartSolve: () -> Boolean,
    private val placement: (JsonObject, ResourceBudget) -> JsonObject,
    private val project: (Long, IterationResult) -> Unit,
    private val replace: (ExecutionSession) -> Unit,
    private val nativeMayRun: (Long) -> Boolean,
    private val nativeLimits: (WorkerPool.Native) -> Pair<Long, Long>?,
) {
    private val pendingLogs = mutableSetOf<Long>()
    /** What each job.log last projected: its newest event, status and error. Unchanged jobs skip the event read and
     * the file comparison; the job files live on the archive volume, so this saves a read per active job per pass. */
    private val projectedLogs = java.util.concurrent.ConcurrentHashMap<Long, Triple<Long?, Status, String?>>()
    /** Consecutive delivery failures of each unacknowledged intent, until [MAX_INTENT_ATTEMPTS] abandons it. */
    private val intentFailures = mutableMapOf<String, Int>()
    private var fullProjectionScanAt = 0L

    /** One pass of [NomadBackend.reconcile], which serializes passes. */
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
        // Recently ended and active jobs each pass; every job at startup and hourly, which covers the rest.
        val at = System.currentTimeMillis()
        val full = at - fullProjectionScanAt >= FULL_PROJECTION_SCAN_MS
        logJobs += store.execution.pendingProjectionJobs(if (full) null else at - FULL_PROJECTION_SCAN_MS)
        if (full) fullProjectionScanAt = at
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
                if (session.executionClass == ExecutionClass.SOLVE && !mayStartSolve()) {
                    // Registration and retirement serialize in the store. If bind won, its idle claim drains;
                    // otherwise STOP releases the reservation and removes this demand from autoscaling.
                    if (store.execution.loseUnallocatedSession(session.id, "journal-pressure")) store.execution.acknowledge(intent.id)
                    return
                }
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
        val checked = store.execution.checksComplete(job.id)
        val ingested = store.execution.ingested(job.id)
        val acceptedIndices = store.execution.acceptedIndices(job.id)
        val prepared = acceptedIndices.any { it.first == ExecutionStore.PREPARATION }
        // Results are large: read only those an iteration still needs, its projection or a case check.
        for ((iteration, indices) in acceptedIndices) {
            if (iteration in ingested && indices.all { it in checked }) continue
            val result = store.execution.accepted(job.id, iteration) ?: continue
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
            if (s.executionClass == ExecutionClass.PREPARE && prepared) {
                store.execution.loseSession(s.id, "prepared")
                continue
            }
            if (s.executionClass == ExecutionClass.SOLVE && s.allocation == null && store.unclaimed(job.id) == 0) {
                store.execution.loseSession(s.id, "no-unclaimed-work")
                continue
            }
            if (s.executionClass == ExecutionClass.SOLVE && !mayStartSolve() &&
                store.execution.loseUnallocatedSession(s.id, "journal-pressure")) continue
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
            if (s.allocation == null && s.executionClass == ExecutionClass.PREPARE) {
                store.execution.preparationNode(s.id, allocations.filter { it.active }.singleOrNull()?.node?.takeIf { it.isNotBlank() })
            }
            if (!current.paused && s.allocation == null && allocations.isEmpty()) {
                val submission = Json.parseToJsonElement(store.execution.submission(s.id)).jsonObject
                val next = placement(submission, s.budget)
                val reason = if (SessionPlacement.of(next).nodeClass != SessionPlacement.of(submission).nodeClass)
                    "placement-spot-unavailable" else "placement-cpu-capacity"
                if (next != submission && store.execution.loseUnallocatedSession(s.id, reason)) continue
            }
            val bound = allocations.firstOrNull { it.id == s.allocation }
            if ((s.allocation != null && (bound == null || !bound.active)) ||
                (s.allocation == null && allocations.isNotEmpty() && allocations.none { it.active })) {
                val exited = bound ?: allocations.maxByOrNull { it.createdAt }
                // Persist a bounded, credential-free receipt before retiring the attempt, including old worker releases.
                val evidence = if ((s.executionClass == ExecutionClass.PREPARE || s.allocation == null) && exited != null)
                    runCatching { nomad.allocation(exited.id) }.getOrNull() ?: exited else exited
                if (s.executionClass == ExecutionClass.PREPARE) {
                    val detail = evidence?.termination?.let { Json.encodeToString(it) } ?: "unavailable"
                    store.execution.note(job.id, s.id, "preparation-termination:$detail")
                }
                if (!current.paused && s.desired == Desired.RUN && s.allocation == null && evidence != null &&
                    startupFailure(s, evidence) >= 3) continue
                if (!current.paused && s.desired == Desired.RUN && s.executionClass == ExecutionClass.PREPARE && exited?.client == "failed") {
                    // List stubs can omit task events. Fetch full evidence before classifying a worker crash.
                    val exit = exited.workerExit ?: evidence?.workerExit
                    if (exit?.code == WORKER_SOFTWARE_EXIT && store.execution.preparationExited(s.id, exit.code)?.count?.let { it >= 3 } == true) continue
                }
                store.execution.loseSession(s.id, when {
                    current.paused || s.desired == Desired.DRAIN -> "drained"
                    bound?.client == "complete" && !store.execution.hasActiveAttempt(s.id) -> "idle"
                    bound?.desired == "stop" && bound.client in setOf("pending", "running", "lost") -> "retired:allocation-stopped"
                    else -> "infrastructure:${bound?.client ?: "failed"}"
                })
            }
        }
        if (store.job(job.id)?.status?.isActive != true) return
        val sessions = store.execution.sessions(job.id)
        val stopped = sessions.isNotEmpty() && sessions.all { it.desired == Desired.STOP }
        // Every real worker must have retired first. Retain elapsed allowance, then stop charging idle
        // queue/projection time; a later bind starts the same clock again, without refunding any costs.
        if (stopped) store.execution.pauseClock(job.id)
        if (current.paused && stopped) return
        if (!current.paused && (store.unclaimed(job.id) > 0 || !current.setupDone && !prepared && sessions.any { it.executionClass == ExecutionClass.PREPARE }) && stopped) {
            // Once preparation is accepted, only admission may create the first solve session. Replacing a
            // stopped preparation here races plan publication and recreates already accepted preparation.
            val previous = sessions.lastOrNull { !prepared || it.executionClass == ExecutionClass.SOLVE } ?: return
            if (previous.executionClass == ExecutionClass.PREPARE) {
                try { Experiments.requireExecutable(store.execution.manifest(previous.id).experiment) }
                catch (failure: IllegalArgumentException) {
                    store.execution.failIfActive(job.id, "Nomad preparation/admission: ${failure.message}")
                    return
                }
            }
            // Queue admission owns intentional cloud-to-native handoff; do not recreate the cloud intent in between.
            if (previous.executionClass == ExecutionClass.SOLVE && store.execution.reports(job.id)
                    .firstOrNull { it.session.id == previous.id }?.observation == "placement-native-capacity") return
            // pauseClock above has retained all accrued allowance once every real session stopped.
            if (previous.executionClass == ExecutionClass.SOLVE && !mayStartSolve()) return
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
        val marker = Triple(store.execution.lastEventId(job), current.status, current.error)
        if (projectedLogs[job] == marker) return
        val events = store.execution.events(job)
        val text = events.joinToString("\n", postfix = "\n") { event ->
            "${java.time.Instant.ofEpochMilli(event.at)} ${event.session ?: "experiment"} ${event.detail}"
        } + "status: ${current.status}\n" + (current.error?.let { "error: $it\n" } ?: "")
        val path = jobDirectory(job).resolve("job.log")
        Files.createDirectories(path.parent)
        if (!Files.isRegularFile(path) || Files.readString(path) != text) path.writeAtomically(text)
        projectedLogs[job] = marker
    }

    private fun startupFailure(session: ExecutionSession, evidence: NomadAllocation): Int {
        val submission = Json.parseToJsonElement(store.execution.submission(session.id)).jsonObject
        val task = submission.getValue("TaskGroups").jsonArray.single().jsonObject.getValue("Tasks").jsonArray
            .map { it.jsonObject }.single { it["Name"]?.jsonPrimitive?.content == "worker" }
        val config = task.getValue("Config").jsonObject
        val image = config["image"]?.jsonPrimitive?.contentOrNull
        val executable = if (image != null) "/opt/lab/worker-entrypoint.sh" else config["command"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        // Do not retain arbitrary environment dumps in ordinary logs. Known credentials and JWTs are redacted.
        val secrets = task["Env"]?.jsonObject.orEmpty().filterKeys {
            Regex("(?i)token|password|secret|credential|key").containsMatchIn(it)
        }.values.mapNotNull { it.jsonPrimitive.contentOrNull }.filter { it.isNotEmpty() }
        fun clean(value: String): String {
            var text = value
            for (secret in secrets) text = text.replace(secret, "[redacted]")
            return text.replace(Regex("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"), "[redacted-jwt]")
                .map { if (it.isISOControl()) ' ' else it }.joinToString("")
        }
        val exit = evidence.workerExit?.code
        val kind = when {
            evidence.client != "failed" || evidence.desired != "run" -> null
            exit in setOf(126, 127) -> "exit-$exit"
            evidence.launchError != null -> when {
                evidence.launchError.contains("permission denied", ignoreCase = true) -> "exec-permission"
                evidence.launchError.contains("exec format error", ignoreCase = true) -> "exec-format"
                else -> "exec-missing"
            }
            else -> null
        }
        val stderr = runCatching { nomad.startupStderr(evidence.id) }.getOrNull()
        val diagnostic = clean("allocation=${evidence.id}, executable=$executable, image=${image ?: "native"}, release=${session.release}, " +
            "exit=${exit ?: evidence.termination?.exitCode ?: "unknown"}; " +
            "launch=${evidence.launchError?.let(::clean)?.take(2048) ?: "unavailable"}; stderr=${stderr?.let(::clean)?.takeLast(4096) ?: "unavailable"}").take(8192)
        val fingerprint = kind?.let { ExecutionStore.digest("${session.release}:$config:$it") }
        return store.execution.workerStartupFailed(session.id, fingerprint, diagnostic)
    }
}
