package com.eignex.lab.domain

import kotlinx.serialization.Serializable

/** Cumulative worker/container counters, including child processes. Each snapshot is a lower bound,
 * including retirement snapshots: cleanup and delivery can consume CPU after observation. Never sum
 * snapshots from the same session, or treat an absent counter as zero. */
@Serializable
data class ResourceCheckpoint(val elapsedMs: Long, val budget: ResourceBudget, val enforcement: String,
    val cpuMax: String?, val memoryMax: String?, val counters: Map<String, String>, val scope: String, val retirement: String? = null) {
    init {
        require(elapsedMs in 0..604_800_000)
        require(scope == "worker-cumulative-lower-bound")
        require(retirement in setOf(null, "provider-interruption", "worker-shutdown"))
        require(enforcement in setOf("advisory-native", "cgroup-v2-hard"))
        require(listOfNotNull(cpuMax, memoryMax).all { it.length <= 128 })
        require(counters.keys.all { it in setOf("cpu.stat", "memory.events", "memory.current") })
        require(counters.values.all { it.length <= 2048 })
        require("cpu.stat" !in counters || cpuUsec != null)
    }
    val cpuUsec: Long? get() = counters["cpu.stat"]?.lineSequence()?.map { it.split(Regex("\\s+")) }
        ?.firstOrNull { it.size == 2 && it[0] == "usage_usec" }?.get(1)?.toLongOrNull()?.takeIf { it >= 0 }
}
