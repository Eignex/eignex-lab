package com.eignex.lab

import kotlinx.serialization.Serializable

@Serializable
data class PreparationProgress(val stage: String, val elapsedMs: Long, val stageElapsedMs: Long,
                               val timings: Map<String, Long>) {
    init {
        require(stage in PreparationTrace.labels && elapsedMs in 0..86_400_000 && stageElapsedMs in 0..elapsedMs)
        require(timings.all { (key, value) -> key in PreparationTrace.labels && value in 0..elapsedMs })
    }
}

@Serializable
data class PreparationStatus(val progress: PreparationProgress, val updatedAt: Long)

/** Exclusive monotonic timings: nested download/build operations do not double-count their parent. */
class PreparationTrace(private val nanoTime: () -> Long = System::nanoTime) {
    private val started = nanoTime()
    private var changed = started
    private var stage = "preparing"
    private val totals = linkedMapOf<String, Long>()
    @Synchronized fun snapshot(): PreparationProgress {
        val at = nanoTime()
        val live = (at - changed).coerceAtLeast(0)
        val times = totals.toMutableMap(); times[stage] = (times[stage] ?: 0) + live
        return PreparationProgress(stage, (at - started).coerceAtLeast(0) / 1_000_000, live / 1_000_000,
            times.mapValues { it.value / 1_000_000 })
    }
    @Synchronized private fun change(next: String): String {
        require(next in labels)
        val at = nanoTime(); val old = stage
        totals[old] = (totals[old] ?: 0) + (at - changed).coerceAtLeast(0)
        stage = next; changed = at
        return old
    }
    fun <T> step(name: String, action: () -> T): T {
        val prior = change(name)
        try { return action() } finally { change(prior) }
    }
    fun ready() { change("ready") }
    companion object {
        val labels: Map<String, String> = linkedMapOf("preparing" to "Preparing", "inputs" to "Verifying inputs", "download" to "Downloading inputs",
            "build-cache" to "Checking build cache", "cache-cleanup" to "Cleaning build cache", "checkout" to "Checking out source", "compiler-wait" to "Waiting for compiler",
            "compilation" to "Compiling", "build-verification" to "Verifying build outputs", "workspace" to "Creating workspace",
            "selection" to "Selecting problems", "classification" to "Classifying problems", "archive" to "Creating archives",
            "cache-publication" to "Publishing local inputs", "shared-inputs" to "Publishing shared inputs", "upload" to "Uploading artifacts",
            "ready" to "Ready")
    }
}
