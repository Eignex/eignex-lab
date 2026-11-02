package com.eignex.lab.worker

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/** Cumulative worker/container counters, including child processes. Each snapshot is a lower bound,
 * including retirement snapshots: cleanup and delivery can consume CPU after observation. Never sum
 * snapshots from the same session, or treat an absent counter as zero. */
@Serializable
data class ResourceCheckpoint(val elapsedMs: Long, val observation: ResourceObservation, val scope: String, val retirement: String? = null) {
    init {
        require(elapsedMs in 0..604_800_000)
        require(scope == "worker-cumulative-lower-bound")
        require(retirement in setOf(null, "provider-interruption", "worker-shutdown"))
        require(observation.enforcement in setOf("advisory-native", "cgroup-v2-hard"))
        require(listOfNotNull(observation.cpuMax, observation.memoryMax, observation.cpuIdentity).all { it.length <= 128 })
        require(listOf(observation.javaVersion, observation.os, observation.arch).all { it.length <= 256 })
        require(observation.counters.keys.all { it in setOf("cpu.stat", "memory.events", "memory.current") })
        require(observation.counters.values.all { it.length <= 2048 })
        require("cpu.stat" !in observation.counters || cpuUsec != null)
    }
    val cpuUsec: Long? get() = observation.counters["cpu.stat"]?.lineSequence()?.map { it.split(Regex("\\s+")) }
        ?.firstOrNull { it.size == 2 && it[0] == "usage_usec" }?.get(1)?.toLongOrNull()?.takeIf { it >= 0 }
}

/** Stop new claims on the host's read-only warning. Existing work has at most 45 seconds, leaving
 * 15 seconds of Nomad's 60-second drain for child cleanup and evidence. Removing the flag cannot
 * extend the first observed monotonic deadline. */
internal class WorkerInterruption(private val file: Path? = System.getenv("LAB_WORKER_DRAIN_FILE")?.let(Path::of),
                                  private val clock: () -> Long = System::nanoTime) {
    private val deadline = AtomicLong(Long.MAX_VALUE)
    fun requested(): Boolean {
        if (deadline.get() != Long.MAX_VALUE) return true
        if (file != null && Files.exists(file)) deadline.compareAndSet(Long.MAX_VALUE, clock() + 45_000_000_000)
        return deadline.get() != Long.MAX_VALUE
    }
    fun remainingMs(): Long = if (requested()) ((deadline.get() - clock()) / 1_000_000).coerceAtLeast(0) else Long.MAX_VALUE
}
