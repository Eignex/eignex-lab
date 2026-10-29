package com.eignex.lab

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
                                 val checkedFiles: Int = 0, val hashedFiles: Int = 0, val hashedBytes: Long = 0) {
    init {
        require(outcome in setOf("hit", "miss", "bypass"))
        require(reason in PreparationTrace.cacheReasons)
        require(verificationMs in 0..86_400_000 && waitingMs in 0..86_400_000)
        require(bytes == null || bytes in 0..(64L * 1024 * 1024 * 1024))
        require(files == null || files in 0..1_000_000)
        require(checkedFiles in 0..1_000_000 && hashedFiles in 0..1_000_000 && hashedBytes in 0..(64L * 1024 * 1024 * 1024))
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
    private val caches = linkedMapOf<String, PreparationCacheEvent>()
    @Synchronized fun cache(name: String, outcome: String, reason: String, verificationMs: Long = 0,
                            waitingMs: Long = 0, bytes: Long? = null, files: Int? = null,
                            checkedFiles: Int = 0, hashedFiles: Int = 0, hashedBytes: Long = 0) {
        require(name in cacheLabels)
        caches[name] = PreparationCacheEvent(outcome, reason, verificationMs, waitingMs, bytes, files, checkedFiles, hashedFiles, hashedBytes)
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
        val cacheLabels = linkedMapOf("build" to "Build", "input" to "Inputs", "archive" to "Archive",
            "upload" to "Upload", "workspace" to "Workspace", "dependencies" to "Gradle dependencies")
        val cacheReasons = linkedMapOf("absent" to "entry absent", "verification-failed" to "verification failed",
            "verified" to "verified local entry", "verified-local" to "verified local entry",
            "coordinator-present" to "identical artifact already on coordinator", "unsupported" to "reuse unsupported",
            "lock-busy" to "cache busy", "identity-changed" to "identity changed", "local-build" to "copied verified local build",
            "partial-upload" to "resuming partial upload", "not-reusable" to "artifact is not reusable",
            "receipt-missing" to "missing verification receipt rebuilt", "receipt-rebuilt" to "missing or stale verification receipt rebuilt")
        val labels: Map<String, String> = linkedMapOf("preparing" to "Preparing", "inputs" to "Verifying inputs", "download" to "Downloading inputs",
            "build-cache" to "Checking build cache", "cache-cleanup" to "Cleaning build cache", "checkout" to "Checking out source", "compiler-wait" to "Waiting for compiler",
            "compilation" to "Compiling", "build-verification" to "Verifying build outputs", "workspace" to "Creating workspace",
            "selection" to "Selecting problems", "classification" to "Classifying problems", "archive" to "Creating archives",
            "cache-publication" to "Publishing local inputs", "shared-inputs" to "Publishing shared inputs", "upload" to "Uploading artifacts",
            "ready" to "Ready")
    }
}
