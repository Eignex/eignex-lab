package com.eignex.lab

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

/** Where a file system is mounted and the space on it, as [Volumes.space] reads it for a path on it. */
data class VolumeSpace(val mount: String, val freeBytes: Long, val totalBytes: Long)

/** One of the lab's configured directories ([name] is its setting) and the [path] it points to. */
@Serializable
data class VolumePath(val name: String, val label: String, val path: String)

/**
 * One file system the lab writes to, with the lab's directories on it. [problem] says why the lab cannot use it, a
 * volume that is not mounted, and then the space is unknown.
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
 * creates nothing there, since `/Volumes/<name>` would otherwise become a plain folder on the internal disk.
 */
object Volumes {
    /** The label a hold names [Config.jobsDir] by. */
    const val JOB_FILES = "job files"

    /** The label a hold names [Config.corpusDir] by. */
    const val CORPUS = "corpus"

    /** The lab's directories, each with its setting and the label a hold names it by. */
    fun paths(config: Config): List<VolumePath> = listOf(
        VolumePath("LAB_DATA", "data", config.dataDir.toString()),
        VolumePath("LAB_JOBS", JOB_FILES, config.jobsDir.toString()),
        VolumePath("LAB_CORPUS", CORPUS, config.corpusDir.toString()),
    )

    /** The volume `<root>/<name>` that [path] lies under, or null when it lies elsewhere. */
    fun mountPointOf(path: Path, root: Path): Path? {
        val normal = path.toAbsolutePath().normalize()
        if (!normal.startsWith(root) || normal.nameCount <= root.nameCount) return null
        return root.resolve(normal.getName(root.nameCount))
    }

    /**
     * Whether [dir] is a mounted volume's root: it exists and lies on another device than its parent. A folder left
     * at a volume's mount point after it went away lies on the parent's device, so it does not count.
     */
    fun isMounted(dir: Path): Boolean = runCatching {
        Files.isDirectory(dir) && Files.getAttribute(dir, "unix:dev") != Files.getAttribute(dir.parent, "unix:dev")
    }.getOrDefault(false)

    /**
     * Why [label]'s directory [dir] cannot be used now, or null when it can: its volume under [root] is not mounted, or
     * it exists and is not a writable directory. A directory not made yet is fine; [ensure] makes it.
     */
    fun problem(label: String, dir: Path, root: Path, mounted: (Path) -> Boolean = ::isMounted): String? {
        mountPointOf(dir, root)?.let { mount -> if (!mounted(mount)) return "$label volume $mount is not mounted" }
        if (Files.exists(dir) && !(Files.isDirectory(dir) && Files.isWritable(dir))) return "$label directory $dir is not writable"
        return null
    }

    /** [dir], made when it is missing, unless [problem] says it cannot be used: then it fails with that, making nothing. */
    fun ensure(label: String, dir: Path, root: Path, mounted: (Path) -> Boolean = ::isMounted): Path {
        problem(label, dir, root, mounted)?.let { throw IllegalStateException(it) }
        return Files.createDirectories(dir)
    }

    /** Why the job files or the corpus cannot be used now ([problem]), or null when both can. */
    fun missing(config: Config, mounted: (Path) -> Boolean = ::isMounted): String? =
        problem(JOB_FILES, config.jobsDir, config.volumesRoot, mounted) ?: problem(CORPUS, config.corpusDir, config.volumesRoot, mounted)

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
     * The lab's directories by the file system each is on, in [paths]' order: a volume that is not mounted is one of
     * its own, with its [VolumeReport.problem] and no space.
     */
    fun report(
        config: Config,
        space: (Path) -> VolumeSpace = ::space,
        mounted: (Path) -> Boolean = ::isMounted,
    ): List<VolumeReport> {
        val volumes = LinkedHashMap<String, VolumeReport>()
        for (entry in paths(config)) {
            val dir = Path.of(entry.path)
            val unmounted = mountPointOf(dir, config.volumesRoot)?.takeIf { !mounted(it) }
            val key = unmounted?.toString() ?: runCatching { space(dir).mount }.getOrElse { dir.toString() }
            volumes[key] = volumes[key]?.let { it.copy(paths = it.paths + entry) } ?: if (unmounted != null) {
                VolumeReport(unmounted.toString(), null, null, listOf(entry), "${entry.label} volume $unmounted is not mounted")
            } else {
                runCatching { space(dir) }.map { VolumeReport(it.mount, it.freeBytes, it.totalBytes, listOf(entry)) }
                    .getOrElse { VolumeReport(key, null, null, listOf(entry), "not readable: ${it.message}") }
            }
        }
        return volumes.values.toList()
    }

    /**
     * Why the runner holds its queue now, or null when it may start a job: the job files' or the corpus's volume is
     * missing ([missing]), or a volume the lab writes to has less than [Config.minFreeBytes] free, named with the
     * lab's directories on it.
     */
    fun hold(config: Config, space: (Path) -> VolumeSpace = ::space, mounted: (Path) -> Boolean = ::isMounted): String? {
        missing(config, mounted)?.let { return it }
        for (volume in report(config, space, mounted)) {
            volume.problem?.let { return it }
            val free = volume.freeBytes ?: continue
            if (free < config.minFreeBytes) {
                return "${size(free)} free on ${volume.mount} (${volume.paths.joinToString { it.label }}), under the ${size(config.minFreeBytes)} it keeps"
            }
        }
        return null
    }
}
