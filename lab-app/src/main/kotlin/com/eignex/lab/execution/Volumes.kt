package com.eignex.lab.execution

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import com.eignex.lab.util.VolumeProbes.isMounted
import com.eignex.lab.util.VolumeProbes.mountPointOf
import com.eignex.lab.util.VolumeProbes.problem

import kotlinx.serialization.Serializable
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Where a file system is mounted and the space on it, as [Volumes.space] reads it for a path on it. */
data class VolumeSpace(val mount: String, val freeBytes: Long, val totalBytes: Long)

/** One of the lab's configured directories ([name] is its setting) and the [path] it points to. */
@Serializable
data class VolumePath(val name: String, val label: String, val path: String)

/**
 * One file system the lab writes to, with the lab's directories on it. [problem] says why the lab cannot use it, a
 * volume that is not mounted or a directory that cannot be read and written in time, and then the space is unknown.
 */
@Serializable
data class VolumeReport(
    val mount: String,
    val freeBytes: Long?,
    val totalBytes: Long?,
    val paths: List<VolumePath>,
    val problem: String? = null,
)

/**
 * The file systems the lab writes to: the data directory (`LAB_DATA`, with the database, worktrees and Gradle), the
 * job files (`LAB_JOBS`) and the corpus cache (`LAB_CORPUS`), which may each live on a volume of their own. A directory
 * under `/Volumes/<name>` ([Config.volumesRoot]) needs that volume mounted: while it is not, the lab holds its queue and
 * creates nothing there, since `/Volumes/<name>` would otherwise become a plain folder on the internal disk. A mounted
 * volume must also answer: each directory is read and written within a timeout ([VolumeAccess]), since macOS's privacy
 * protection can leave every call the lab makes on a removable volume blocked rather than failed.
 */
object Volumes {
    /** The label a hold names [Config.jobsDir] by. */
    const val JOB_FILES = VolumeProbes.JOB_FILES

    /** The label a hold names [Config.corpusDir] by. */
    const val CORPUS = VolumeProbes.CORPUS

    /** The lab's directories, each with its setting and the label a hold names it by. */
    fun paths(paths: DataPaths): List<VolumePath> = listOf(
        VolumePath("LAB_DATA", "data", paths.dataDir.toString()),
        VolumePath("LAB_JOBS", JOB_FILES, paths.jobsDir.toString()),
        VolumePath("LAB_CORPUS", CORPUS, paths.corpusDir.toString()),
    )

    /** Why the job files cannot be used now ([problem]), or null when they can. */
    fun jobFiles(paths: DataPaths, mounted: (Path) -> Boolean = ::isMounted): String? =
        problem(JOB_FILES, paths.jobsDir, paths.volumesRoot, paths.volumeAccess, mounted)

    /** Why the job files or the corpus cannot be used now ([problem]), or null when both can. */
    fun missing(paths: DataPaths, mounted: (Path) -> Boolean = ::isMounted): String? {
        if (!paths.volumeAccess.watching) probeAll(paths, mounted)
        return jobFiles(paths, mounted) ?: problem(CORPUS, paths.corpusDir, paths.volumesRoot, paths.volumeAccess, mounted)
    }

    /**
     * Keep every one of the lab's directories probed ([probeAll]) every [everyMs], on a thread of its own, so that
     * [report], [missing] and [hold] only read outcomes and never start a probe themselves. A service starts it once,
     * when it starts; a one-shot caller without it has its directories probed side by side when it first asks.
     */
    fun watch(paths: DataPaths, everyMs: Long = VolumeAccess.FRESH_MS / 2): Thread {
        paths.volumeAccess.watching = true
        return periodic("volume-probes", everyMs) { probeAll(paths, ::isMounted) }
    }

    /**
     * Start probing each of the lab's directories whose volume is mounted ([VolumeAccess.start]), so that directories
     * on a blocked volume wait out their timeouts side by side rather than one after another.
     */
    fun probeAll(paths: DataPaths, mounted: (Path) -> Boolean = ::isMounted) {
        for (entry in paths(paths)) {
            val dir = Path.of(entry.path)
            if (mountPointOf(dir, paths.volumesRoot)?.let(mounted) != false) paths.volumeAccess.start(entry.label, dir)
        }
    }

    /** Where the file system holding [path] is mounted and its space, read at its nearest existing directory. */
    fun space(path: Path): VolumeSpace {
        var at = path.toAbsolutePath().normalize()
        while (!Files.exists(at)) at = at.parent ?: break
        val store = Files.getFileStore(at)
        // A file store reads as `<mount point> (<device>)`.
        val mount = store.toString().substringBeforeLast(" (").ifEmpty { store.name() }
        return VolumeSpace(mount, store.usableSpace, store.totalSpace)
    }

    /**
     * The lab's directories by the file system each is on, in [paths]' order: a volume that is not mounted, or one
     * holding a directory that cannot be used ([problem]), is one of its own, with its [VolumeReport.problem] and no
     * space, which is then not asked for, since reading it could block as well.
     */
    fun report(
        paths: DataPaths,
        space: (Path) -> VolumeSpace = ::space,
        mounted: (Path) -> Boolean = ::isMounted,
    ): List<VolumeReport> {
        if (!paths.volumeAccess.watching) probeAll(paths, mounted)
        val volumes = LinkedHashMap<String, VolumeReport>()
        for (entry in paths(paths)) {
            val dir = Path.of(entry.path)
            val problem = problem(entry.label, dir, paths.volumesRoot, paths.volumeAccess, mounted)
            val measured = if (problem == null) runCatching { space(dir) } else null
            val found = measured?.getOrNull()
            val failure = problem ?: measured?.exceptionOrNull()?.let { "not readable: ${it.message}" }
            val key = if (problem != null) {
                (mountPointOf(dir, paths.volumesRoot) ?: dir).toString()
            } else {
                found?.mount ?: dir.toString()
            }
            val prior = volumes[key]
            val entries = prior?.paths.orEmpty() + entry
            volumes[key] = if (failure != null) {
                VolumeReport(key, null, null, entries, prior?.problem ?: failure)
            } else {
                prior?.copy(paths = entries) ?: checkNotNull(found).let { VolumeReport(it.mount, it.freeBytes, it.totalBytes, entries) }
            }
        }
        return volumes.values.toList()
    }

    /**
     * Why the runner holds its queue now, or null when it may start a job: the job files' or the corpus's volume is
     * missing or cannot be used ([missing]), the data directory cannot be used, or a volume the lab writes to has less
     * than [Config.minFreeBytes] free, named with the lab's directories on it.
     */
    fun hold(paths: DataPaths, minFreeBytes: Long, space: (Path) -> VolumeSpace = ::space, mounted: (Path) -> Boolean = ::isMounted): String? {
        missing(paths, mounted)?.let { return it }
        for (volume in report(paths, space, mounted)) {
            volume.problem?.let { return it }
            val free = volume.freeBytes ?: continue
            if (free < minFreeBytes) {
                return "${size(free)} free on ${volume.mount} (${volume.paths.joinToString { it.label }}), under the ${size(minFreeBytes)} it keeps"
            }
        }
        return null
    }
}

