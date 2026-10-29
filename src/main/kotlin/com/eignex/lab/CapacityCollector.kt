package com.eignex.lab

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
private data class CapacityState(val history: Map<String, CapacityHistory> = emptyMap(), val idleSince: Map<String, Long> = emptyMap())

class CapacityCollector(
    val settings: CapacitySettings, private val inventoryFiles: List<Path>, private val tokenFile: Path,
    private val stateFile: Path, private val store: Store, private val nomad: NomadControl,
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

    @Synchronized
    fun collect() {
        check(allowed()) { "capacity collection held" }
        val at = clock()
        val started = monotonic()
        val inventories = inventoryFiles.map { Json.decodeFromString<CapacityInventory>(Files.readString(it)) }
        require(inventories.isNotEmpty() && inventories.all { at - it.observedAt in -5000..FRESH_MS }) { "provider inventory stale" }
        require(inventories.flatMap { it.pools }.toSet() == pools.keys)
        val instances = inventories.flatMap { it.instances }
        require(instances.all { it.pool in pools } && instances.map { it.pool to it.id }.distinct().size == instances.size)
        val sessions = store.execution.sessions()
        val byJob = sessions.associateBy { it.nomadJobId }
        val classes = instances.map { pools.getValue(it.pool).nodeClass }.toSet()
        val briefs = if (classes.isEmpty()) emptyList() else nomad.nodes().filter {
            it["NodeClass"]?.jsonPrimitive?.content.let { nodeClass -> nodeClass == null || nodeClass in classes }
        }
        nodeCache.keys.retainAll(briefs.map { it.getValue("ID").jsonPrimitive.content }.toSet())
        val nodes = briefs.mapNotNull { brief ->
            val id = brief.getValue("ID").jsonPrimitive.content
            val index = brief["ModifyIndex"]?.jsonPrimitive?.longOrNull
            nodeCache[id]?.takeIf { index != null && it.index == index }?.node ?: nomad.node(id)?.also {
                if (index != null) nodeCache[id] = CachedNode(index, it)
            }
        }
        val byInstance = nodes.filter { (it["Meta"] as? JsonObject)?.get("lab_capacity_id") != null }.associateBy {
            it.getValue("NodeClass").jsonPrimitive.content to it.getValue("Meta").jsonObject.getValue("lab_capacity_id").jsonPrimitive.content
        }
        val idle = state.idleSince.toMutableMap()
        val observations = instances.map { instance ->
            val pool = pools.getValue(instance.pool); val id = "${instance.pool}:${instance.id}"
            val node = byInstance[pool.nodeClass to instance.id]
            val status = node?.get("Status")?.jsonPrimitive?.content
            val eligible = node?.get("SchedulingEligibility")?.jsonPrimitive?.content != "ineligible"
            val draining = instance.draining || node?.get("Drain")?.jsonPrimitive?.booleanOrNull == true ||
                node?.get("DrainStrategy")?.let { it != JsonNull } == true
            val ready = status == "ready" && eligible && !draining
            val reservations = if (node == null) emptyList() else nomad.nodeAllocations(node.getValue("ID").jsonPrimitive.content)
                .filter { it["ClientStatus"]?.jsonPrimitive?.content in setOf("pending", "running", "unknown") }.map { allocation ->
                    val jobId = allocation.getValue("JobID").jsonPrimitive.content
                    val session = byJob[jobId]
                    // Unknown allocations consume the whole node until a known budget is available.
                    val budget = session?.budget ?: ResourceBudget(pool.cpuMillis, pool.availableMemoryMiB)
                    CapacityReservation(allocation.getValue("ID").jsonPrimitive.content, session?.jobId ?: -1, budget,
                        if (session?.executionClass in setOf("build", "prepare")) CapacityWork.BUILD else CapacityWork.SOLVE)
                }
            if (ready && reservations.isEmpty()) idle.putIfAbsent(id, at) else idle.remove(id)
            CapacityNode(id, pool.id, when {
                draining -> CapacityNodeState.DRAINING
                ready -> CapacityNodeState.READY
                status == "down" || !eligible -> CapacityNodeState.UNAVAILABLE
                else -> CapacityNodeState.BOOTING
            }, reservations, idle[id], instance.countedInTarget)
        }
        val pending = sessions.filter { s -> s.desired == "run" && (s.executionClass != "solve" || store.unclaimed(s.jobId) > 0) && store.job(s.jobId)?.let { it.status in setOf(Status.QUEUED, Status.RUNNING) && !it.paused && !it.cancelRequested } == true &&
            nomad.allocations(s.nomadJobId).none { it.active } }
        val demands = pending.mapNotNull { s ->
            val manifest = store.execution.manifest(s.id)
            val spec = Json.parseToJsonElement(store.execution.submission(s.id)).jsonObject
            val nodeClass = spec.getValue("Constraints").jsonArray.map { it.jsonObject }.single { it["LTarget"]?.jsonPrimitive?.content == "${'$'}{node.class}" }.getValue("RTarget").jsonPrimitive.content
            val pool = settings.pools.singleOrNull { it.nodeClass == nodeClass } ?: return@mapNotNull null
            val job = store.job(s.jobId)!!; val experiment = job.experiment!!
            val onDemand = NodeTags.onDemand(false, experiment.nodeTags)
            require(pool.market == if (onDemand) CapacityMarket.ON_DEMAND else CapacityMarket.SPOT) { "session purchasing policy differs from pool" }
            CapacityDemand(s.id, job.id, manifest.budget, job.priority, spec["Meta"]?.jsonObject?.get("lab_per_host_parallel")?.jsonPrimitive?.intOrNull, experiment.nodeTags,
                pool.market, experiment.host != Experiments.AWS_HOST && NodeTags.matches(experiment.nodeTags, localTags),
                when { s.executionClass in setOf("build", "prepare") -> CapacityWork.BUILD; experiment.isProfile -> CapacityWork.PROFILE; experiment.measuresCli -> CapacityWork.MEASUREMENT; else -> CapacityWork.SOLVE }, pool.id)
        }
        val inventoryAge = inventories.maxOf { (at - it.observedAt).coerceAtLeast(0) }
        check(inventoryAge + (monotonic() - started) / 1_000_000 <= FRESH_MS) { "capacity collection exceeded freshness bound" }
        val decision = CapacityPolicy.evaluate(settings, observations, demands, store.execution.capacityLeases(at), state.history, at)
        state = CapacityState(decision.history, idle.filterKeys { id -> observations.any { it.id == id } })
        Files.createDirectories(stateFile.parent)
        val temporary = Files.createTempFile(stateFile.parent, ".capacity-", ".tmp")
        try { Files.writeString(temporary, Json.encodeToString(state)); Files.move(temporary, stateFile, ATOMIC_MOVE, REPLACE_EXISTING) }
        finally { Files.deleteIfExists(temporary) }
        val snapshot = CapacitySnapshot(at - inventoryAge, inventoryAge, decision, observations, inventories.flatMap { it.failures.entries }.associate { it.toPair() }, settings.pools)
        val report = stateFile.resolveSibling("capacity-report.json")
        val pendingReport = Files.createTempFile(report.parent, ".report-", ".tmp")
        try { Files.writeString(pendingReport, Json.encodeToString(snapshot)); Files.move(pendingReport, report, ATOMIC_MOVE, REPLACE_EXISTING) }
        finally { Files.deleteIfExists(pendingReport) }
        latest.set(Sample(snapshot, started - inventoryAge * 1_000_000))
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
            put("schemaVersion", 1); put("pool", pool); put("maxNodes", definition.maxNodes)
            put("desired", sample.decision.desired.getValue(pool)); put("issuedAt", clock())
            put("nonce", java.util.UUID.randomUUID().toString())
        }.toString().toByteArray(Charsets.UTF_8)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        val key = Files.readString(tokenFile).trim().toByteArray(Charsets.UTF_8)
        require(key.size >= 32) { "capacity key too short" }
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        mac.update("eignex-capacity-v1\n".toByteArray(Charsets.UTF_8))
        return CapacityGrant(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload),
            mac.doFinal(payload).joinToString("") { "%02x".format(it.toInt() and 255) })
    }
    fun metrics(): String = snapshot().let { sample -> settings.pools.joinToString("\n", postfix = "\n") { p ->
        "lab_capacity_desired_nodes{pool=\"${p.id}\"} ${sample.decision.desired.getValue(p.id)}\nlab_capacity_sample_timestamp_seconds{pool=\"${p.id}\"} ${sample.generatedAt / 1000.0}"
    } }
    companion object { const val FRESH_MS = 30_000L }
}
