package com.eignex.lab.domain

import com.eignex.lab.util.*

import kotlinx.serialization.Serializable

@Serializable
data class PreparationProgress(val stage: String, val elapsedMs: Long, val stageElapsedMs: Long,
                               val timings: Map<String, Long>, val caches: Map<String, PreparationCacheEvent> = emptyMap()) {
    init {
        require(stage in PreparationTrace.labels && elapsedMs in 0..86_400_000 && stageElapsedMs in 0..elapsedMs)
        require(timings.all { (key, value) -> key in PreparationTrace.labels && value in 0..elapsedMs })
        require(caches.keys.all { it in PreparationTrace.cacheLabels })
    }
}

/** One bounded observation per cache, without node paths, source identifiers or arbitrary worker text.
 * An absent entry may be new, evicted, changed or on another node: it deliberately makes no stronger claim. */
@Serializable
data class PreparationCacheEvent(val outcome: String, val reason: String, val verificationMs: Long = 0,
                                 val waitingMs: Long = 0, val bytes: Long? = null, val files: Int? = null,
                                 val checkedFiles: Int = 0, val hashedFiles: Int = 0, val hashedBytes: Long = 0,
                                 val hits: Int = 0, val misses: Int = 0, val bypasses: Int = 0) {
    init {
        require(outcome in setOf("hit", "miss", "bypass"))
        require(reason in PreparationTrace.cacheReasons)
        require(verificationMs in 0..86_400_000 && waitingMs in 0..86_400_000)
        require(bytes == null || bytes in 0..(64L * 1024 * 1024 * 1024))
        require(files == null || files in 0..1_000_000)
        require(checkedFiles >= 0 && hashedFiles >= 0 && hashedBytes >= 0 && hits >= 0 && misses >= 0 && bypasses >= 0)
    }
}

@Serializable
data class PreparationStatus(val progress: PreparationProgress, val updatedAt: Long, val diagnostics: PreparationDiagnostics? = null)

/** Exclusive monotonic timings: nested download/build operations do not double-count their parent. */
class PreparationTrace(private val nanoTime: () -> Long = System::nanoTime) {
    private val started = nanoTime()
    private var changed = started
    private var stage = "preparing"
    private val totals = linkedMapOf<String, Long>()
    private val caches = linkedMapOf<String, PreparationCacheEvent>()
    @Synchronized fun cache(name: String, outcome: String, reason: String, verificationMs: Long = 0,
                            waitingMs: Long = 0, bytes: Long? = null, files: Int? = null,
                            checkedFiles: Int = 0, hashedFiles: Int = 0, hashedBytes: Long = 0) {
        require(name in cacheLabels)
        val observation = PreparationCacheEvent(outcome, reason, verificationMs, waitingMs, bytes, files, checkedFiles, hashedFiles, hashedBytes)
        val previous = caches[name]
        fun sum(a: Int, b: Int): Int = (a.toLong() + b).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        fun sum(a: Long, b: Long, limit: Long = Long.MAX_VALUE): Long = if (a >= limit - b) limit else a + b
        caches[name] = observation.copy(
            verificationMs = sum(previous?.verificationMs ?: 0, verificationMs, 86_400_000),
            waitingMs = sum(previous?.waitingMs ?: 0, waitingMs, 86_400_000),
            checkedFiles = sum(previous?.checkedFiles ?: 0, checkedFiles), hashedFiles = sum(previous?.hashedFiles ?: 0, hashedFiles),
            hashedBytes = sum(previous?.hashedBytes ?: 0, hashedBytes), hits = sum(previous?.hits ?: 0, if (outcome == "hit") 1 else 0),
            misses = sum(previous?.misses ?: 0, if (outcome == "miss") 1 else 0), bypasses = sum(previous?.bypasses ?: 0, if (outcome == "bypass") 1 else 0))
    }
    @Synchronized fun snapshot(): PreparationProgress {
        val at = nanoTime()
        val live = (at - changed).coerceAtLeast(0)
        val times = totals.toMutableMap(); times[stage] = (times[stage] ?: 0) + live
        return PreparationProgress(stage, (at - started).coerceAtLeast(0) / 1_000_000, live / 1_000_000,
            times.mapValues { it.value / 1_000_000 }, caches.toMap())
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
        val cacheLabels = linkedMapOf("build" to "Build", "input" to "Inputs", "prepared-input" to "Prepared inputs", "archive" to "Archive",
            "upload" to "Upload", "workspace" to "Workspace", "dependencies" to "Gradle dependencies", "selection" to "Bench selection", "handoff" to "Local handoff")
        val cacheReasons = linkedMapOf("absent" to "entry absent", "verification-failed" to "verification failed",
            "verified" to "verified local entry", "verified-local" to "verified local entry",
            "coordinator-present" to "identical artifact already on coordinator", "unsupported" to "reuse unsupported",
            "lock-busy" to "cache busy", "identity-changed" to "identity changed", "local-build" to "copied verified local build",
            "partial-upload" to "resuming partial upload", "not-reusable" to "artifact is not reusable",
            "build-hit" to "verified build makes source acquisition unnecessary",
            "receipt-missing" to "missing verification receipt rebuilt", "receipt-rebuilt" to "missing or stale verification receipt rebuilt",
            "regional-present" to "identical build archive already in the regional store", "regional-absent" to "build archive published to the regional store",
            "accepted-selection" to "reused an accepted plan with the same selection inputs", "job-owned-snapshot" to "prepared files retained with the job")
        val labels: Map<String, String> = linkedMapOf("preparing" to "Preparing", "inputs" to "Verifying inputs", "download" to "Downloading inputs",
            "build-cache" to "Checking build cache", "cache-cleanup" to "Cleaning build cache", "checkout" to "Checking out source", "compiler-wait" to "Waiting for compiler",
            "compilation" to "Compiling", "build-verification" to "Verifying build outputs", "workspace" to "Creating workspace",
            "selection" to "Selecting problems", "classification" to "Classifying problems", "archive" to "Creating archives",
            "local-handoff" to "Keeping prepared files locally", "cache-publication" to "Publishing local inputs", "shared-inputs" to "Publishing shared inputs", "upload" to "Uploading artifacts",
            "ready" to "Ready")
    }
}
