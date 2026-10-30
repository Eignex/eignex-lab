package com.eignex.lab.capacity

import com.eignex.lab.analysis.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.util.concurrent.atomic.AtomicReference

/** Provider adapters supply inventory only. This contract contains no credentials or provider API concepts. */
@Serializable
data class CapacityInstance(val id: String, val pool: String, val draining: Boolean = false, val countedInTarget: Boolean = true)
@Serializable
data class CapacityInventory(val observedAt: Long, val pools: Set<String>, val instances: List<CapacityInstance>, val failures: Map<String, String> = emptyMap())
@Serializable
data class CapacityIntegration(val settingsFile: String, val inventoryFiles: List<String>, val healthTokenFile: String)
@Serializable
data class CapacitySnapshot(val generatedAt: Long, val ageMs: Long, val decision: CapacityDecision, val nodes: List<CapacityNode>, val failures: Map<String, String>, val pools: List<CapacityPool> = emptyList(), val healthy: Boolean = true)
@Serializable
data class CapacityGrant(val payload: String, val signature: String)
@Serializable
private data class CapacityState(
    val history: Map<String, CapacityHistory> = emptyMap(),
    val idleSince: Map<String, Long> = emptyMap(),
    /** Each pool's last desired size: what a pool whose inventory goes stale holds. */
    val desired: Map<String, Int> = emptyMap(),
)

/**
 * The pure part of collecting capacity: what each provider instance is, as its Nomad node says, and what each session
 * waiting for capacity asks of which pool. No I/O; the collector reads Nomad and the store and hands the views in.
 */
internal object CapacityObservation {
    /** What a Nomad node says of itself. */
    data class NodeView(val id: String, val status: String?, val eligible: Boolean, val draining: Boolean) {
        companion object {
            fun of(node: JsonObject) = NodeView(
                node.getValue("ID").jsonPrimitive.content,
                node["Status"]?.jsonPrimitive?.content,
                node["SchedulingEligibility"]?.jsonPrimitive?.content != "ineligible",
                node["Drain"]?.jsonPrimitive?.booleanOrNull == true || node["DrainStrategy"]?.let { it != JsonNull } == true,
            )
        }
    }

    /** An allocation on a node, and the Nomad job it belongs to. */
    data class AllocationView(val id: String, val job: String, val status: String?) {
        val holds get() = status in setOf("pending", "running", "unknown")

        companion object {
            fun of(allocation: JsonObject) = AllocationView(allocation.getValue("ID").jsonPrimitive.content,
                allocation.getValue("JobID").jsonPrimitive.content, allocation["ClientStatus"]?.jsonPrimitive?.content)
        }
    }

    /**
     * Each provider instance as a [CapacityNode]: its state from its Nomad node ([byInstance], by node class and instance
     * id), and what the allocations on it hold. Returns the nodes and when each ready, empty one became idle.
     */
    fun observeNodes(
        instances: List<CapacityInstance>,
        pools: Map<String, CapacityPool>,
        byInstance: Map<Pair<String, String>, NodeView>,
        allocations: (String) -> List<AllocationView>,
        sessions: Map<String, ExecutionSession>,
        idleSince: Map<String, Long>,
        at: Long,
    ): Pair<List<CapacityNode>, Map<String, Long>> {
        val idle = idleSince.toMutableMap()
        val nodes = instances.map { instance ->
            val pool = pools.getValue(instance.pool); val id = "${instance.pool}:${instance.id}"
            val node = byInstance[pool.nodeClass to instance.id]
            val draining = instance.draining || node?.draining == true
            val eligible = node?.eligible != false
            val ready = node?.status == "ready" && eligible && !draining
            val reservations = if (node == null) emptyList() else allocations(node.id).filter { it.holds }.map { allocation ->
                val session = sessions[allocation.job]
                // Unknown allocations consume the whole node until a known budget is available.
                val budget = session?.budget ?: ResourceBudget(pool.cpuMillis, pool.availableMemoryMiB)
                CapacityReservation(allocation.id, session?.jobId ?: -1, budget,
                    if (session?.executionClass in setOf(ExecutionClass.BUILD, ExecutionClass.PREPARE)) CapacityWork.BUILD else CapacityWork.SOLVE)
            }
            if (ready && reservations.isEmpty()) idle.putIfAbsent(id, at) else idle.remove(id)
            CapacityNode(id, pool.id, when {
                draining -> CapacityNodeState.DRAINING
                ready -> CapacityNodeState.READY
                node?.status == "down" || !eligible -> CapacityNodeState.UNAVAILABLE
                else -> CapacityNodeState.BOOTING
            }, reservations, idle[id], instance.countedInTarget)
        }
        return nodes to idle
    }

    /**
     * What session [session] of [job], placed as [placement] with [budget], asks of its pool, the one of its node
     * class; null when no configured pool has it. Its experiment's market must be its pool's.
     */
    fun deriveDemand(
        session: ExecutionSession,
        budget: ResourceBudget,
        placement: SessionPlacement,
        job: Job,
        settings: CapacitySettings,
        localTags: Set<String>,
    ): CapacityDemand? {
        val pool = settings.pools.singleOrNull { it.nodeClass == placement.nodeClass } ?: return null
        val experiment = job.experiment
        require(pool.market == NodeTags.market(experiment.nodeTags)) { "session purchasing policy differs from pool" }
        val work = when {
            session.executionClass in setOf(ExecutionClass.BUILD, ExecutionClass.PREPARE) -> CapacityWork.BUILD
            experiment.isProfile -> CapacityWork.PROFILE
            experiment.measuresCli -> CapacityWork.MEASUREMENT
            else -> CapacityWork.SOLVE
        }
        return CapacityDemand(session.id, job.id, budget, job.priority, placement.perHostParallel, experiment.nodeTags,
            pool.market, Placement.localEligible(experiment, localTags), work, pool.id)
    }
}

/**
 * What capacity collection reads of the cluster: its nodes and what runs on them. The capacity layer owns this port and
 * the Nomad client implements it ([NomadControl]), so collecting capacity depends on no Nomad type.
 */
interface ClusterNodes {
    /** Every node, briefly: its id, class and modify index. */
    fun nodes(): List<JsonObject>
    /** Node [id] whole, or null when it is gone. */
    fun node(id: String): JsonObject?
    /** The allocations on node [id]. */
    fun nodeAllocations(id: String): List<JsonObject>
    /** Whether Nomad job [job] has an allocation that is pending or running. */
    fun hasActiveAllocation(job: String): Boolean
}

class CapacityCollector(
    val settings: CapacitySettings, private val inventoryFiles: List<Path>, private val tokenFile: Path,
    private val stateFile: Path, private val store: Store, private val nomad: ClusterNodes,
    private val allowed: () -> Boolean, private val localTags: Set<String>,
    private val clock: () -> Long = ::now, private val monotonic: () -> Long = System::nanoTime,
) {
    private data class Sample(val snapshot: CapacitySnapshot, val nanos: Long)
    private val latest = AtomicReference<Sample?>()
    private var state = if (Files.isRegularFile(stateFile)) Json.decodeFromString<CapacityState>(Files.readString(stateFile)) else CapacityState()
    private val pools = settings.pools.associateBy { it.id }
    private data class CachedNode(val index: Long, val node: JsonObject)
    private val nodeCache = mutableMapOf<String, CachedNode>()
    fun authorized(token: String): Boolean = java.security.MessageDigest.isEqual(Files.readString(tokenFile).trim().toByteArray(), token.toByteArray()) && token.length >= 32

    /**
     * Observe the providers' inventories, the Nomad nodes and the sessions waiting for capacity, decide each pool's
     * desired size ([CapacityPolicy]) and publish it. Each provider's inventory is judged on its own: a pool whose
     * inventory is stale holds the size it last had, so one slow provider neither blocks the others nor scales its own
     * pools down. With every inventory stale nothing is decided.
     */
    @Synchronized
    fun collect() {
        check(allowed()) { "capacity collection held" }
        val at = clock()
        val started = monotonic()
        val inventories = inventoryFiles.map { Json.decodeFromString<CapacityInventory>(Files.readString(it)) }
        require(inventories.isNotEmpty()) { "no provider inventory" }
        require(inventories.flatMap { it.pools }.toSet() == pools.keys)
        val (fresh, stale) = inventories.partition { at - it.observedAt in -CapacityWire.CLOCK_SKEW_MS..FRESH_MS }
        require(fresh.isNotEmpty()) { "provider inventory stale" }
        val stalePools = stale.flatMap { it.pools }.toSet()
        val instances = fresh.flatMap { it.instances }
        require(instances.all { it.pool in pools } && instances.map { it.pool to it.id }.distinct().size == instances.size)
        val sessions = store.execution.sessions()
        val byJob = sessions.associateBy { it.nomadJobId }
        val classes = instances.map { pools.getValue(it.pool).nodeClass }.toSet()
        val nodes = nomadNodes(classes)
        val byInstance = nodes.filter { (it["Meta"] as? JsonObject)?.get(CapacityWire.CAPACITY_ID_META) != null }.associate {
            (it.getValue("NodeClass").jsonPrimitive.content to it.getValue("Meta").jsonObject.getValue(CapacityWire.CAPACITY_ID_META).jsonPrimitive.content) to
                CapacityObservation.NodeView.of(it)
        }
        val (observations, idle) = CapacityObservation.observeNodes(instances, pools, byInstance, { node ->
            nomad.nodeAllocations(node).map(CapacityObservation.AllocationView::of)
        }, byJob, state.idleSince, at)
        val pending = sessions.filter { s -> s.desired == Desired.RUN && (s.executionClass != ExecutionClass.SOLVE || store.unclaimed(s.jobId) > 0) && store.job(s.jobId)?.let { it.status.isActive && !it.paused && !it.cancelRequested } == true &&
            !nomad.hasActiveAllocation(s.nomadJobId) }
        val demands = pending.mapNotNull { s ->
            val job = store.job(s.jobId) ?: return@mapNotNull null
            CapacityObservation.deriveDemand(s, store.execution.manifest(s.id).budget, store.execution.placement(s.id), job, settings, localTags)
        }.filter { it.pool !in stalePools }
        val inventoryAge = fresh.maxOf { (at - it.observedAt).coerceAtLeast(0) }
        check(inventoryAge + (monotonic() - started) / 1_000_000 <= FRESH_MS) { "capacity collection exceeded freshness bound" }
        val evaluated = CapacityPolicy.evaluate(settings, observations, demands, store.execution.capacityLeases(at), state.history, at)
        // A stale pool keeps what it last had: neither grown on demand it cannot see nor shrunk to nothing.
        val held = stale.flatMap { inventory -> inventory.pools.map { pool ->
            pool to (state.desired[pool] ?: inventory.instances.count { it.pool == pool && it.countedInTarget })
        } }.toMap()
        val decision = evaluated.copy(desired = evaluated.desired + held)
        state = CapacityState(decision.history, idle.filterKeys { id -> observations.any { it.id == id } }, decision.desired)
        Files.createDirectories(stateFile.parent)
        val temporary = Files.createTempFile(stateFile.parent, ".capacity-", ".tmp")
        try { Files.writeString(temporary, Json.encodeToString(state)); Files.move(temporary, stateFile, ATOMIC_MOVE, REPLACE_EXISTING) }
        finally { Files.deleteIfExists(temporary) }
        val failures = inventories.flatMap { it.failures.entries }.associate { it.toPair() } +
            stalePools.associateWith { "inventory stale: holding ${held[it]} nodes" }
        val snapshot = CapacitySnapshot(at - inventoryAge, inventoryAge, decision, observations, failures, settings.pools)
        val report = stateFile.resolveSibling("capacity-report.json")
        val pendingReport = Files.createTempFile(report.parent, ".report-", ".tmp")
        try { Files.writeString(pendingReport, Json.encodeToString(snapshot)); Files.move(pendingReport, report, ATOMIC_MOVE, REPLACE_EXISTING) }
        finally { Files.deleteIfExists(pendingReport) }
        latest.set(Sample(snapshot, started - inventoryAge * 1_000_000))
    }

    /** The Nomad nodes of [classes], read whole only when they changed since the last read. */
    private fun nomadNodes(classes: Set<String>): List<JsonObject> {
        val briefs = if (classes.isEmpty()) emptyList() else nomad.nodes().filter {
            it["NodeClass"]?.jsonPrimitive?.content.let { nodeClass -> nodeClass == null || nodeClass in classes }
        }
        nodeCache.keys.retainAll(briefs.map { it.getValue("ID").jsonPrimitive.content }.toSet())
        return briefs.mapNotNull { brief ->
            val id = brief.getValue("ID").jsonPrimitive.content
            val index = brief["ModifyIndex"]?.jsonPrimitive?.longOrNull
            nodeCache[id]?.takeIf { index != null && it.index == index }?.node ?: nomad.node(id)?.also {
                if (index != null) nodeCache[id] = CachedNode(index, it)
            }
        }
    }
    fun snapshot(): CapacitySnapshot {
        check(allowed()) { "capacity held" }
        val sample = latest.get() ?: error("capacity not sampled")
        val age = (monotonic() - sample.nanos) / 1_000_000
        check(age in 0..FRESH_MS) { "capacity sample expired" }
        return sample.snapshot.copy(ageMs = age)
    }
    /** Only a fresh reconciliation may renew provider capacity. The provider verifies with its own clock. */
    fun grant(pool: String): CapacityGrant {
        val sample = snapshot()
        val definition = requireNotNull(pools[pool]) { "unknown capacity pool" }
        val payload = buildJsonObject {
            put("schemaVersion", CapacityWire.GRANT_SCHEMA_VERSION); put("pool", pool); put("maxNodes", definition.maxNodes)
            put("desired", sample.decision.desired.getValue(pool)); put("issuedAt", clock())
            put("nonce", java.util.UUID.randomUUID().toString())
        }.toString().toByteArray(Charsets.UTF_8)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val key = Files.readString(tokenFile).trim().toByteArray(Charsets.UTF_8)
        require(key.size >= 32) { "capacity key too short" }
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        mac.update(CapacityWire.GRANT_DOMAIN.toByteArray(Charsets.UTF_8))
        return CapacityGrant(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload),
            mac.doFinal(payload).joinToString("") { "%02x".format(it.toInt() and 255) })
    }
    fun metrics(): String = snapshot().let { sample -> settings.pools.joinToString("\n", postfix = "\n") { p ->
        "${CapacityWire.DESIRED_METRIC}{pool=\"${p.id}\"} ${sample.decision.desired.getValue(p.id)}\nlab_capacity_sample_timestamp_seconds{pool=\"${p.id}\"} ${sample.generatedAt / 1000.0}"
    } }
    companion object { const val FRESH_MS = CapacityWire.FRESH_MS }
}
