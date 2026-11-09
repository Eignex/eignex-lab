package com.eignex.lab.util

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Whether a directory can be used now, for any directory: its volume under a root such as `/Volumes` mounted, and the
 * directory read and written within a timeout ([VolumeAccess]). The lab's own directories are execution's Volumes.
 */
object VolumeProbes {
    /** The label a hold names the job files by. */
    const val JOB_FILES = "job files"

    /** The label a hold names the corpus by. */
    const val CORPUS = "corpus"

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
     * [access] finds that it cannot be read and written in time, or that it exists and is not a writable directory
     * ([probe]). A directory not made yet is fine when the directory it would be made in is; [ensure] makes it.
     */
    fun problem(
        label: String,
        dir: Path,
        root: Path,
        access: VolumeAccess,
        mounted: (Path) -> Boolean = ::isMounted,
    ): String? {
        mountPointOf(dir, root)?.let { mount -> if (!mounted(mount)) return "$label volume $mount is not mounted" }
        return access.check(label, dir)
    }

    /**
     * Whether [label]'s directory [dir] answers, as [VolumeAccess] runs it on a thread of its own: why not, or null when
     * it does. It is listed, or the nearest directory above it that exists when it does not yet, and a small file is
     * made there and removed. A call that fails throws, and [VolumeAccess] says what that means.
     */
    fun probe(label: String, dir: Path): String? {
        if (Files.exists(dir) && !(Files.isDirectory(dir) && Files.isWritable(dir))) return "$label directory $dir is not writable"
        var at = dir.toAbsolutePath().normalize()
        while (!Files.isDirectory(at)) at = at.parent ?: return "$label directory $dir has no directory above it to be made in"
        Files.list(at).use { it.findFirst() }
        Files.delete(Files.createTempFile(at, ".lab-access-", ".probe"))
        return null
    }

    /** [dir], made when it is missing, unless [problem] says it cannot be used: then it fails with that, making nothing. */
    fun ensure(
        label: String,
        dir: Path,
        root: Path,
        access: VolumeAccess,
        mounted: (Path) -> Boolean = ::isMounted,
    ): Path {
        problem(label, dir, root, access, mounted)?.let { throw IllegalStateException(it) }
        return Files.createDirectories(dir)
    }

}

/**
 * Whether each of the lab's directories can be read and written: each probe ([VolumeProbes.probe], unless [io] is given)
 * runs on a daemon thread of its own and is given up on after [timeoutMs]. macOS's privacy protection for removable
 * volumes does not fail a call it has not been allowed, it leaves the call blocked, so a directory on such a volume
 * would otherwise stop whatever thread touched it for good: the runner's startup, the API's request threads.
 *
 * A probe's outcome is kept for [freshMs], so a check on every request or log line does not probe again. At most one
 * probe per directory is in flight: while one is stuck past its timeout, every check answers at once that the directory
 * cannot be accessed, and the stuck probe, once the call it waits on returns (access was granted), records the outcome
 * it then has.
 */
class VolumeAccess(
    val timeoutMs: Long = TIMEOUT_MS,
    private val freshMs: Long = FRESH_MS,
    /** The binary the lab runs on, which Full Disk Access has to be granted to ([javaBinary]). */
    private val java: String = javaBinary(),
    private val io: (label: String, dir: Path) -> String? = VolumeProbes::probe,
) {
    /** Whether a watcher keeps every directory probed, so readers only read outcomes ([com.eignex.lab.execution.Volumes.watch]). */
    @Volatile var watching = false

    private class Probe(val started: Long) {
        val done = CountDownLatch(1)
        @Volatile var problem: String? = null
    }

    private class Outcome(val at: Long, val problem: String?)

    private val inFlight = HashMap<Path, Probe>()
    private val outcomes = ConcurrentHashMap<Path, Outcome>()

    /** How many probes were started, every directory's together. */
    @Volatile var probes = 0
        private set

    /**
     * Why [label]'s directory [dir] cannot be read and written, or null when it can: as last probed when that was
     * recent, else probed now, waiting at most until [timeoutMs] after the probe in flight started.
     */
    fun check(label: String, dir: Path): String? {
        val key = dir.toAbsolutePath().normalize()
        fresh(key)?.let { return it.problem }
        val probe = begin(label, key)
        val left = timeoutMs - (now() - probe.started)
        if (left > 0) probe.done.await(left, TimeUnit.MILLISECONDS)
        if (probe.done.count == 0L) return probe.problem
        return blocked(label, key).also { outcomes[key] = Outcome(now(), it) }
    }

    /** Start probing [label]'s directory [dir], unless its last outcome is recent or a probe of it is in flight. */
    fun start(label: String, dir: Path) {
        val key = dir.toAbsolutePath().normalize()
        if (fresh(key) == null) begin(label, key)
    }

    private fun fresh(key: Path): Outcome? = outcomes[key]?.takeIf { now() - it.at < freshMs }

    private fun begin(label: String, key: Path): Probe = synchronized(inFlight) {
        inFlight[key] ?: Probe(now()).also { probe ->
            inFlight[key] = probe
            probes++
            thread(isDaemon = true, name = "volume-probe $key") {
                val problem = try {
                    io(label, key)
                } catch (e: Throwable) {
                    described(label, key, e)
                }
                probe.problem = problem
                outcomes[key] = Outcome(now(), problem)
                synchronized(inFlight) { inFlight.remove(key, probe) }
                probe.done.countDown()
            }
        }
    }

    /** What a probe that did not end within [timeoutMs] means, and how to fix it. */
    private fun blocked(label: String, dir: Path): String =
        "cannot access $label directory $dir within ${duration(timeoutMs)}: ${hint()}"

    /** What a probe that failed means: a refusal is macOS's privacy protection as often as the directory's permissions. */
    private fun described(label: String, dir: Path, e: Throwable): String {
        val detail = e.message ?: e.toString()
        val refused = e is AccessDeniedException || detail.contains("not permitted", ignoreCase = true)
        return if (refused) "cannot access $label directory $dir ($detail): ${hint()}" else "cannot access $label directory $dir: $detail"
    }

    private fun hint() = "macOS may be blocking the lab's java from removable volumes; grant Full Disk Access to $java " +
        "in System Settings → Privacy & Security → Full Disk Access"

    private fun now() = System.nanoTime() / NANOS_PER_MS

    companion object {
        const val TIMEOUT_MS = 10_000L
        const val FRESH_MS = 15_000L
        private const val NANOS_PER_MS = 1_000_000L

        /** The one this process checks its directories with. */

        /** The java binary this process runs, its links resolved: the one macOS's privacy settings must name. */
        fun javaBinary(): String {
            val command = ProcessHandle.current().info().command().orElse(null)
                ?: Path.of(System.getProperty("java.home"), "bin", "java").toString()
            return runCatching { Path.of(command).toRealPath().toString() }.getOrDefault(command)
        }

        private fun duration(ms: Long) = if (ms % 1000 == 0L) "${ms / 1000} s" else "$ms ms"
    }
}
