package com.eignex.lab.web

import com.eignex.lab.domain.Config
import com.eignex.lab.execution.Volumes
import com.eignex.lab.util.freeBytes
import com.eignex.lab.util.periodic

/** Storage facts for display only; work admission continues to check the volumes itself. */
internal class PageStorage(
    private val read: () -> Snapshot,
    private val everyMs: Long = 5_000,
) : AutoCloseable {
    constructor(config: Config) : this({
        Snapshot(runCatching { freeBytes(config.dataDir) }.getOrNull(), Volumes.hold(config.paths, config.minFreeBytes))
    })

    internal data class Snapshot(val free: Long?, val held: String?)

    @Volatile var latest = Snapshot(null, "storage status is being checked")
        private set

    private var worker: Thread? = null

    /** Never run a probe on the caller, including the first page after startup. */
    fun start() {
        check(worker == null)
        worker = periodic("page-storage", everyMs) {
            latest = try {
                read()
            } catch (interrupted: InterruptedException) {
                throw interrupted
            } catch (_: Exception) {
                Snapshot(latest.free, "storage status could not be read")
            }
        }
    }

    override fun close() {
        worker?.interrupt()
    }
}
