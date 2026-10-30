package com.eignex.lab.web

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.nomad.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import com.eignex.lab.worker.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Run [block], which reads the store or the disk, on the IO dispatcher, off the call's own threads. */
internal suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

/** What the queue page shows ([indexPage]). */
internal class IndexView(
    val active: List<Job>,
    val schedules: List<Schedule>,
    val history: List<Job>,
    val before: Long?,
    val name: String?,
    val queue: List<Long>,
    val hosts: Map<Long, List<HostProgress>>,
    val nodes: Map<Long, List<JobNode>>,
    /** The cloud instances each shown job ran on, read only while the jobs volume answers. */
    val instances: Map<Long, List<String>>,
    val free: Long?,
    val held: String?,
)

/** What the disk page shows ([diskPage]). */
internal class DiskView(
    val disk: DiskReport?,
    val freeBytes: Long,
    val holds: List<Hold>,
    val database: DatabaseReport?,
    val volumes: List<VolumeReport>,
    val held: String?,
)

/**
 * What each page shows, read in one place, which every route calls on the IO dispatcher ([io]): the store, the caches,
 * and the files of the jobs volume, but only while it answers ([Volumes.jobFiles]). A page's renderer only formats
 * what it is given.
 */
internal class PageQueries(private val services: ApiServices) {
    private val config get() = services.config
    private val store get() = services.store

    fun index(before: Long?, name: String?): IndexView {
        val active = store.active(withCommands = false)
        val history = store.jobs(PAGE_SIZE, before, name, withCommands = false)
        // Only a job that may have run on the cloud has instances to read.
        val instances = if (Volumes.jobFiles(config.paths) != null) emptyMap() else (active + history)
            .filter { it.experiment.host == Experiments.AWS_HOST || it.experiment.host == Experiments.AUTO_HOST }
            .associate { it.id to JobDirView.instances(config.paths, it) }.filterValues { it.isNotEmpty() }
        return IndexView(
            active, store.schedules.all(), history, before, name, store.queue(),
            store.hostProgress(active.filter { it.status == Status.RUNNING }.map { it.id }),
            jobNodes(config.status, store, active.filter { it.backend == Backend.NOMAD }.map { it.id }, services.capacitySettings.get()),
            instances, runCatching { freeBytes(config.dataDir) }.getOrNull(), runCatching { Volumes.hold(config.paths, config.minFreeBytes) }.getOrNull(),
        )
    }

    fun disk() = DiskView(
        services.disk.latest, freeBytes(config.dataDir), store.holds.active(),
        runCatching { store.maintenance.database() }.getOrNull(), Volumes.report(config.paths), runCatching { Volumes.hold(config.paths, config.minFreeBytes) }.getOrNull(),
    )

    /** The latest finished experiment queued by the same schedule before [job]. */
    fun previousRun(job: Job): Long? =
        store.jobs(limit = PAGE_SIZE, before = job.id, name = job.origin.series, withCommands = false)
            .firstOrNull { it.status == Status.DONE && it.origin is JobOrigin.Scheduled }?.id
}
