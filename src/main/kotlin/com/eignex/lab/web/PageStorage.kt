package com.eignex.lab.web

import com.eignex.lab.domain.Config
import com.eignex.lab.execution.Volumes
import com.eignex.lab.util.freeBytes
import com.eignex.lab.util.periodic

/** Storage facts for display only; work admission continues to check the volumes itself. */
internal class PageStorage(
    private val read: () -> Snapshot,
    private val everyMs: Long = 5_000,
    private val maxAgeMs: Long = 30_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : AutoCloseable {
    constructor(config: Config) : this({
        Snapshot(runCatching { freeBytes(config.dataDir) }.getOrNull(), Volumes.hold(config.paths, config.minFreeBytes))
    })

    internal data class Snapshot(val free: Long?, val held: String?)

    private data class Sample(val at: Long, val snapshot: Snapshot)
    @Volatile private var sample: Sample? = null

    val latest: Snapshot get() {
        val kept = sample ?: return Snapshot(null, "storage status is being checked")
        return if (clock() - kept.at in 0..maxAgeMs) kept.snapshot
            else Snapshot(null, "storage status is stale; waiting for storage")
    }

    private var worker: Thread? = null

    /** Never run a probe on the caller, including the first page after startup. */
    fun start() {
        check(worker == null)
        worker = periodic("page-storage", everyMs) {
            val at = clock()
            val snapshot = try {
                read()
            } catch (interrupted: InterruptedException) {
                throw interrupted
            } catch (_: Exception) {
                Snapshot(null, "storage status could not be read")
            }
            sample = Sample(at, snapshot)
        }
    }

    override fun close() {
        worker?.interrupt()
    }
}
