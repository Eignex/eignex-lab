package com.eignex.lab

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CapacityMarket(val tag: String) { @SerialName("spot") SPOT("spot"), @SerialName("on-demand") ON_DEMAND("on-demand") }
@Serializable
enum class CapacityWork { SOLVE, MEASUREMENT, PROFILE, BUILD }
@Serializable
enum class CapacityNodeState { READY, BOOTING, DRAINING }

/** A homogeneous, provider-neutral pool. Physical logical-vCPU quota and schedulable budgets stay distinct. */
@Serializable
data class CapacityPool(
    val id: String, val nodeClass: String, val vcpus: Int, val memoryMiB: Int, val maxNodes: Int,
    val tags: Set<String>, val market: CapacityMarket = CapacityMarket.SPOT,
    val reserveCpuMillis: Int = 2000, val reserveMemoryMiB: Int = 1024,
    val minPriority: Int = 0, val sustainedMs: Long = 30_000, val cooldownMs: Long = 60_000, val idleMs: Long = 300_000,
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9-]{0,47}")) && nodeClass.isNotBlank())
        require(vcpus in 1..1024 && memoryMiB > 0 && maxNodes in 1..1000)
        require(reserveCpuMillis in 0 until vcpus * 1000 && reserveMemoryMiB in 0 until memoryMiB)
        require(sustainedMs >= 0 && cooldownMs >= 0 && idleMs >= 0)
        NodeTags.validate(NodeSelector(require = tags))
        require("hardware:unknown" !in tags && tags.count { it.startsWith("arch:") } == 1)
        require(tags.none { it in setOf("spot", "on-demand", "local") })
    }
    val cpuMillis get() = vcpus * 1000 - reserveCpuMillis
    val availableMemoryMiB get() = memoryMiB - reserveMemoryMiB
    val capabilities get() = tags + setOf("cloud", market.tag)
}

@Serializable
data class CapacityReservation(val id: String, val job: Long, val budget: ResourceBudget, val work: CapacityWork = CapacityWork.SOLVE) {
    val caseParallel get() = if (work == CapacityWork.BUILD) 0 else budget.parallel
}
@Serializable
data class CapacityNode(val id: String, val pool: String, val state: CapacityNodeState, val reservations: List<CapacityReservation> = emptyList(), val emptySince: Long? = null, val countedInTarget: Boolean = true)
@Serializable
data class CapacityDemand(
    val id: String, val job: Long, val budget: ResourceBudget, val coreHours: Double, val priority: Int,
    val perHostParallel: Int, val selector: NodeSelector? = null, val market: CapacityMarket = CapacityMarket.SPOT,
    val localEligible: Boolean = true, val work: CapacityWork = CapacityWork.SOLVE, val pool: String? = null,
) {
    init { require(id.isNotBlank() && coreHours.isFinite() && coreHours >= 0 && perHostParallel > 0); NodeTags.validate(selector) }
    val caseParallel get() = if (work == CapacityWork.BUILD) 0 else budget.parallel
}
@Serializable
data class ManualCapacityLease(val id: String, val pool: String, val nodes: Int, val expiresAt: Long) { init { require(nodes >= 0) } }
@Serializable
data class CapacityHistory(val pressureSince: Long? = null, val lastIncrease: Long? = null)
@Serializable
data class CapacityPlacement(val request: String, val node: String, val pool: String, val ready: Boolean)
@Serializable
data class CapacityWait(val request: String, val reason: String)
@Serializable
data class CapacityDecision(val desired: Map<String, Int>, val placements: List<CapacityPlacement>, val waiting: List<CapacityWait>, val history: Map<String, CapacityHistory>)
@Serializable
data class CapacitySettings(val pools: List<CapacityPool>, val maxVcpus: Int, val localCores: Int = 1, val localPressureHours: Double = .25) {
    init { require(pools.isNotEmpty() && pools.map { it.id }.distinct().size == pools.size && pools.map { it.nodeClass }.distinct().size == pools.size); require(maxVcpus > 0 && localCores > 0 && localPressureHours.isFinite() && localPressureHours >= 0) }
}

/** Pure packing/admission policy. No provider types, credentials, SDKs, instance names or cloud commands. */
object CapacityPolicy {
    fun evaluate(settings: CapacitySettings, nodes: List<CapacityNode>, demands: List<CapacityDemand>,
                 manual: List<ManualCapacityLease> = emptyList(), previous: Map<String, CapacityHistory> = emptyMap(), at: Long = now()): CapacityDecision {
        require(nodes.map { it.id }.distinct().size == nodes.size && demands.map { it.id }.distinct().size == demands.size)
        val pools = settings.pools.associateBy { it.id }
        require(nodes.all { it.pool in pools } && manual.all { it.pool in pools && it.nodes <= pools.getValue(it.pool).maxNodes })
        data class Bin(val node: CapacityNode, val allocations: MutableList<CapacityReservation>)
        val bins = nodes.filter { it.state != CapacityNodeState.DRAINING }.map { Bin(it, it.reservations.toMutableList()) }.toMutableList()
        val counts = settings.pools.associate { it.id to nodes.count { node -> node.pool == it.id && node.countedInTarget } }.toMutableMap()
        val history = previous.toMutableMap()
        val placements = mutableListOf<CapacityPlacement>(); val waiting = mutableListOf<CapacityWait>()
        var quota = settings.maxVcpus.toLong() - nodes.sumOf { pools.getValue(it.pool).vcpus.toLong() }
        fun compatible(d: CapacityDemand, p: CapacityPool) = (d.pool == null || d.pool == p.id) && d.market == p.market && NodeTags.matches(d.selector, p.capabilities) && d.budget.cpuMillis <= p.cpuMillis && d.budget.memoryMiB <= p.availableMemoryMiB
        fun fits(d: CapacityDemand, b: Bin): Boolean {
            val p = pools.getValue(b.node.pool)
            return compatible(d, p) && b.allocations.sumOf { it.budget.cpuMillis.toLong() } + d.budget.cpuMillis <= p.cpuMillis &&
                b.allocations.sumOf { it.budget.memoryMiB.toLong() } + d.budget.memoryMiB <= p.availableMemoryMiB &&
                b.allocations.filter { it.job == d.job }.sumOf { it.caseParallel.toLong() } + d.caseParallel <= d.perHostParallel
        }
        val ordered = demands.sortedWith(compareByDescending<CapacityDemand> { it.priority }.thenBy { it.id })
        val remaining = ordered.toMutableList()
        for (p in settings.pools) {
            val eligible = ordered.filter { compatible(it, p) && (it.priority >= p.minPriority || !it.localEligible) }
            val pressure = eligible.sumOf { it.coreHours }
            val threshold = NodePool.nextThreshold(NodePool.threshold(settings.localCores, settings.localPressureHours), counts.getValue(p.id))
            val qualified = eligible.any { !it.localEligible } || pressure >= threshold && eligible.isNotEmpty()
            val old = previous[p.id] ?: CapacityHistory()
            history[p.id] = old.copy(pressureSince = if (qualified) old.pressureSince ?: at else null)
        }
        for (d in ordered) {
            // Fit warm and booting bins before considering growth. Best fit fills partial nodes across experiments.
            var bin = bins.filter { fits(d, it) }.minWithOrNull(compareBy<Bin> { if (it.node.state == CapacityNodeState.READY) 0 else 1 }
                .thenBy { pools.getValue(it.node.pool).cpuMillis.toLong() - it.allocations.sumOf { a -> a.budget.cpuMillis.toLong() } - d.budget.cpuMillis }
                .thenBy { it.node.id })
            if (bin == null) {
                val candidates = settings.pools.filter { compatible(d, it) }.sortedBy { it.id }
                val p = candidates.firstOrNull { p ->
                    val h = history.getValue(p.id)
                    val pressure = remaining.filter { compatible(it, p) && (it.priority >= p.minPriority || !it.localEligible) }.sumOf { it.coreHours }
                    val enough = !d.localEligible || pressure >= NodePool.nextThreshold(NodePool.threshold(settings.localCores, settings.localPressureHours), counts.getValue(p.id))
                    enough && (d.priority >= p.minPriority || !d.localEligible) && h.pressureSince != null && at - h.pressureSince >= p.sustainedMs &&
                        (h.lastIncrease == null || at - h.lastIncrease >= p.cooldownMs) && counts.getValue(p.id) < p.maxNodes && quota >= p.vcpus && d.caseParallel <= d.perHostParallel
                }
                if (p != null) {
                    val node = CapacityNode("new:${p.id}:${counts.getValue(p.id)}", p.id, CapacityNodeState.BOOTING)
                    bin = Bin(node, mutableListOf()); bins += bin
                    counts[p.id] = counts.getValue(p.id) + 1; quota -= p.vcpus
                    history[p.id] = history.getValue(p.id).copy(lastIncrease = at)
                } else waiting += CapacityWait(d.id, when {
                    candidates.isEmpty() -> "no compatible ${d.market.tag} pool"
                    candidates.all { counts.getValue(it.id) >= it.maxNodes || quota < it.vcpus } -> "capacity or vCPU limit"
                    else -> "sustained pressure or cooldown"
                })
            }
            if (bin != null) {
                bin.allocations += CapacityReservation(d.id, d.job, d.budget, d.work)
                placements += CapacityPlacement(d.id, bin.node.id, bin.node.pool, bin.node.state == CapacityNodeState.READY)
            }
            remaining.remove(d)
        }
        for (p in settings.pools) {
            val floor = manual.filter { it.pool == p.id && it.expiresAt > at }.maxOfOrNull { it.nodes } ?: 0
            val idle = bins.count { it.node.pool == p.id && it.node.state == CapacityNodeState.READY && it.allocations.isEmpty() && it.node.emptySince?.let { since -> at - since >= p.idleMs } == true }
            counts[p.id] = maxOf(counts.getValue(p.id) - idle, minOf(floor, counts.getValue(p.id)))
            // Manual capacity is still finite, quota-bounded and expires. It cannot change purchasing market.
            while (counts.getValue(p.id) < floor && counts.getValue(p.id) < p.maxNodes && quota >= p.vcpus) {
                counts[p.id] = counts.getValue(p.id) + 1; quota -= p.vcpus
            }
        }
        return CapacityDecision(counts, placements, waiting, history)
    }
}
