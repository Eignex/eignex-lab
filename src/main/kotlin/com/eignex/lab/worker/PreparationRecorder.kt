package com.eignex.lab.worker

import com.eignex.lab.domain.*
import java.nio.file.Files
import java.nio.file.Path

/** Bounded in-memory tail, periodically copied to the coordinator while the owning lease is live. */
internal class PreparationRecorder(private val observation: ResourceObservation, private val trace: PreparationTrace) {
    private var sequence = 0L
    private var stdout: Path? = null
    private var stderr: Path? = null
    private var build: PreparationBuildDiagnostic? = null
    private val uploads = ArrayDeque<PreparationUploadDiagnostic>()

    @Synchronized fun build(stdout: Path, stderr: Path, diagnostic: PreparationBuildDiagnostic) {
        this.stdout = stdout; this.stderr = stderr
        build = diagnostic.copy(stdout = tail(stdout), stderr = tail(stderr))
    }
    @Synchronized fun upload(event: PreparationUploadDiagnostic) {
        if (uploads.size == 16) uploads.removeFirst()
        uploads.addLast(event)
    }
    @Synchronized fun snapshot(leaseMs: Long, runMs: Long?): PreparationDiagnostics {
        val progress = trace.snapshot()
        // Keep captured tails after successful cache publication or failed staging cleanup removes the files.
        val latest = build?.copy(stdout = stdout?.takeIf(Files::isRegularFile)?.let(::tail) ?: build!!.stdout,
            stderr = stderr?.takeIf(Files::isRegularFile)?.let(::tail) ?: build!!.stderr)
        build = latest
        return PreparationDiagnostics(progress.elapsedMs, progress.stage, observation.budget, observation.enforcement,
            observation.cpuMax, observation.memoryMax, ResourceObservation.counters().mapValues { it.value.take(2048) },
            leaseMs.coerceAtLeast(0), runMs?.coerceAtLeast(0),
            (System.getenv("LAB_WORKER_MAX_CONCURRENT_BUILDS")?.toIntOrNull() ?: 2).coerceIn(1, 64), latest, uploads.toList(), ++sequence)
    }
    companion object {
        private fun tail(path: Path): String = runCatching {
            java.io.RandomAccessFile(path.toFile(), "r").use { input ->
                input.seek((input.length() - 4096).coerceAtLeast(0))
                String(ByteArray((input.length() - input.filePointer).toInt()).also { input.readFully(it) })
            }.takeLast(4096)
        }.getOrDefault("")
    }
}
