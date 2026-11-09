package com.eignex.lab.domain

import com.eignex.lab.bench.BenchDescriptor
import com.eignex.lab.bench.BenchPlan
import com.eignex.lab.util.CommitSha

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/* What the coordinator and its workers exchange and record: sessions, claims, results and preparations. */

@Serializable
data class ExecutionSession(
    val id: String, val jobId: Long, val generation: Int, val executionClass: ExecutionClass, val release: String,
    val budget: ResourceBudget, val nomadJobId: String, val desired: Desired, val allocation: String?, val node: String?,
)

/** What a session's worker does: prepare a job's sources and selection once, or solve its cases. Stored as [stored]. */
@Serializable
enum class ExecutionClass(val stored: String) {
    @SerialName("prepare") PREPARE("prepare"),
    @SerialName("solve") SOLVE("solve"),
    @SerialName("build") BUILD("build");

    override fun toString() = stored

    companion object {
        fun of(stored: String): ExecutionClass = entries.firstOrNull { it.stored == stored } ?: error("unknown execution class $stored")
    }
}

/** What the lab wants of a session: keep running, finish its current iteration and leave, or stop now. */
@Serializable
enum class Desired(val stored: String) {
    @SerialName("run") RUN("run"),
    @SerialName("drain") DRAIN("drain"),
    @SerialName("stop") STOP("stop");

    override fun toString() = stored

    companion object {
        fun of(stored: String): Desired = entries.firstOrNull { it.stored == stored } ?: error("unknown session state $stored")
    }
}

/**
 * Where an iteration attempt is. The active states advance in declaration order and never go back; an attempt ends
 * [COMMITTED], or invalidated with its reason stored as its state ([ENDED] here, whatever the reason).
 */
enum class AttemptState(val stored: String) {
    CLAIMED("claimed"), PREPARING("preparing"), EXECUTING("executing"), UPLOADING("uploading"), COMMITTED("committed"), ENDED("");

    val isActive: Boolean get() = this in ACTIVE

    override fun toString() = stored

    companion object {
        val ACTIVE = setOf(CLAIMED, PREPARING, EXECUTING, UPLOADING)

        /** The active states as an SQL list, for `state IN …`. */
        val ACTIVE_SQL = ACTIVE.joinToString(", ", "(", ")") { "'${it.stored}'" }

        fun of(stored: String): AttemptState = entries.firstOrNull { it.stored == stored && it != ENDED } ?: ENDED
    }
}

/** What an outbox intent asks of Nomad. */
@Serializable
enum class IntentOp(val stored: String) {
    @SerialName("submit") SUBMIT("submit"),
    @SerialName("stop") STOP("stop");

    override fun toString() = stored

    companion object {
        fun of(stored: String): IntentOp = entries.firstOrNull { it.stored == stored } ?: error("unknown execution intent $stored")
    }
}

@Serializable
data class AttemptOwner(val session: String, val allocation: String, val iteration: String, val generation: Int, val token: String)

/** [claim] repeats the name the worker gave its request; a coordinator that runs one attempt per session at a time leaves it out. */
@Serializable
data class IterationClaim(val owner: AttemptOwner, val indices: List<Int>, val leaseUntil: Long, val parallel: Int = 1, val claim: String? = null)

@Serializable
data class ExecutionCaseResult(val index: Int, val exitCode: Int, val startedAt: Long, val finishedAt: Long, val record: String? = null, val command: String? = null) {
    init { require(index >= 0 && finishedAt >= startedAt); require(command == null || command.length <= 65536 && '\u0000' !in command) }
}

/**
 * [regionalBlobs] names, by sha256 to size, the outputs among [artifacts] whose bytes a cloud worker left in the regional
 * result store (`result-blobs/<sha256>`) instead of sending them to the coordinator.
 */
@Serializable
data class IterationResult(val cases: List<ExecutionCaseResult>, val artifacts: Map<String, String> = emptyMap(), val runTimedOut: Boolean = false,
                           val preparation: PreparationResult? = null, val regionalBlobs: Map<String, Long> = emptyMap())

@Serializable
data class ExecutionIntent(val id: String, val jobId: Long, val session: String, val operation: IntentOp, val payload: String, val acknowledged: Boolean)

@Serializable data class LiveLog(val session: String, val iteration: String, val generation: Int)
@Serializable data class ExecutionEvent(val id: Long, val session: String?, val detail: String, val at: Long)

class ExecutionFenced(message: String) : IllegalStateException(message)
/**
 * The coordinator cannot accept a result whose outputs are in the regional result store: the store is not configured, or
 * an output is missing there or differs from its declared size. Answered as HTTP 422; the worker then sends every output
 * to the coordinator instead.
 */
class RegionalResultRefused(message: String) : RuntimeException(message)
/** A held solve claim for a worker that waits: answered as HTTP 202 with [SolveWait], never as a drain. */
class SolveAdmissionHeld : IllegalStateException("solve admission held for result journal drain")

/** Public placement facts only; a submission's worker credentials never reach a page. */
internal data class RunningNode(val id: String, val allocation: String?, val nodeClass: String?, val local: Boolean)

/** A running session's node, where its [placement] put it: a node class, or the local machine's native worker. */
internal fun runningNode(id: String, allocation: String?, placement: SessionPlacement): RunningNode =
    RunningNode(id, allocation, placement.nodeClass, placement.native)

@Serializable
data class PreparationResult(
    val selections: List<List<Problem>>, val bundles: List<WorkerBundle>, val corpus: WorkerBundle? = null,
    val features: List<ProblemFeatures> = emptyList(), val artifacts: Map<String, String> = emptyMap(),
    val sharedInputs: List<SharedInput> = emptyList(),
    val benchPlan: BenchPlan? = null,
    val benchDescriptor: BenchDescriptor? = null,
    val armPlans: Map<String, BenchPlan> = emptyMap(),
    /** Planner outputs offered for reuse; the coordinator moves them to its selection cache on acceptance. */
    val selectionCache: List<BenchSelectionEntry> = emptyList(),
    /** The builds behind [bundles], reported only when the manifest offered [WorkManifest.reusableBuilds]. */
    val builds: List<PreparedBuild> = emptyList(),
)

/**
 * The build a preparation compiled, or reused, for [commit] from its source bundle for [os] and [arch]: the worker's
 * build [identity] and, for a bench, its plan source identity, so a later preparation of the same build can reuse it.
 */
@Serializable
data class PreparedBuild(val commit: String, val os: String, val arch: String, val identity: String, val planSource: String? = null) {
    init {
        require(CommitSha.isValid(commit) && os in setOf("linux", "mac") && arch in setOf("amd64", "arm64") && identity.matches(SHA256))
        require(planSource?.let(::validPlanSource) != false)
    }
}

/**
 * One repository arm's accepted planner output, kept by the coordinator under [key] ([BenchSelections.key]) so a later
 * preparation with the same selection inputs reuses it instead of running `describe` and `plan` and hashing the corpus
 * again. [plan] is the pinned plan before arm alignment and coordinator selection, so a reuse selects afresh.
 * [sharedInputs] carry every corpus byte the plan names, by checksum; [sourceDigest] binds its source-root inputs,
 * which a reuse recomputes against its own tree.
 */
@Serializable
data class BenchSelectionEntry(val key: String, val arm: String, val descriptor: BenchDescriptor, val plan: BenchPlan,
                               val sourceDigest: String, val sharedInputs: List<SharedInput> = emptyList()) {
    init { require(key.matches(SHA256) && sourceDigest.matches(SHA256) && arm.isNotBlank()) }
}
