package com.eignex.lab.execution

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.FileStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText

/** One place on the lab machine's disk and the space it takes, as `du` counts it (allocated, so sparse files count
 *  what they hold). [bytes] is null when it could not be measured; [at] is when it was. */
@Serializable
data class DiskPlace(val label: String, val path: String, val bytes: Long?, val at: Long? = null)

@Serializable
data class DiskReport(
    val at: Long,
    val freeBytes: Long,
    val places: List<DiskPlace>,
    /** The largest job directories, [TOP_JOBS] of them, largest first: job id and bytes. */
    val largestJobs: List<Pair<Long, Long>> = emptyList(),
    /** `docker system df` when the lab uses Docker; empty when it does not or Docker did not answer. */
    val docker: List<DockerUsage> = emptyList(),
    /** The last Docker cleanup ([DockerCleanup]), null before the first. */
    val dockerCleanup: DockerCleanupRun? = null,
    /** The downloads' holds that have not lapsed ([Hold]), read when the report is served, not measured. */
    val holds: List<Hold> = emptyList(),
    /** The database as SQLite counts it and its compaction ([DbCompaction]), read when the report is asked for. */
    val database: DatabaseReport? = null,
    /** Each volume the lab writes to and the lab's directories on it ([Volumes.report]), read when the report is served. */
    val volumes: List<VolumeReport> = emptyList(),
    /** Why the runner holds its queue ([Volumes.hold]), read when the report is served; null when it does not. */
    val held: String? = null,
)

/** One job directory's size as last measured: its own mtime then, when, and whether the job was still active. */
@Serializable
internal data class JobSize(val bytes: Long, val mtime: Long, val at: Long, val active: Boolean)

/** What [DiskUsage] keeps across restarts in [DiskUsage.STATE_FILE]: the last report and each job directory's size. */
@Serializable
internal data class DiskUsageState(val report: DiskReport? = null, val jobs: Map<Long, JobSize> = emptyMap())

/**
 * What takes the lab machine's disk: the lab's own data, part by part, and the caches and tools beside it that grow as
 * the lab runs (the corpus, Gradle, the reference solvers' Docker VM and what Docker holds in it, other checkouts).
 * Measured with `du` ([DuRunner]: each walk under a timeout, never outliving the lab) on a background thread, first
 * [START_DELAY_MIN] minutes after startup, so restarts on updates do not each start a walk, then every [INTERVAL_MIN]
 * minutes. At most one measurement runs at a time, and its walks run one after another with [PAUSE_MS] between them,
 * so the disk load is spread out rather than a burst that could skew the timings of cases running then.
 *
 * A walk is cheap only where few files are, so most is not walked every time:
 * - Job directories are measured one by one and each size kept; a directory is measured again only when it is new,
 *   its job is active (per [active]) or was when last measured, or its mtime changed (a trim writes its `trimmed`
 *   file there) or was within [settleMs] of the last measurement. The job files' total is their sum.
 * - The places on another volume than the home directory (the external drive) and the corpus, millions of files, are
 *   walked at most every [SLOW_INTERVAL_H] hours; each place says when it was measured.
 * Volumes' free and total space come from statfs ([Volumes.report]), never `du`. The last report and the job sizes are
 * kept in [STATE_FILE] under `LAB_DATA`, so a restart shows the last numbers at once and measures no job again.
 */
class DiskUsage internal constructor(
    private val paths: DataPaths,
    private val docker: DockerPolicy,
    /** The ids of the queued and running jobs. */
    private val active: () -> Set<Long>,
    private val du: DuRunner,
    private val settleMs: Long,
    private val clock: () -> Long,
    /** Waits between two walks. */
    private val pause: () -> Unit = {},
) : AutoCloseable {
    constructor(paths: DataPaths, docker: DockerPolicy, active: () -> Set<Long> = { emptySet() }, pauseMs: Long = PAUSE_MS) :
        this(paths, docker, active, DuRunner(paths.dataDir.resolve(DuRunner.PID_DIR)), SETTLE_MS, System::currentTimeMillis,
            { if (pauseMs > 0) Thread.sleep(pauseMs) })

    @Volatile var latest: DiskReport? = null
        private set

    private val home = System.getProperty("user.home")
    private val stateFile = paths.dataDir.resolve(STATE_FILE)
    private val lock = ReentrantLock()
    private var loaded = false
    private var jobs: MutableMap<Long, JobSize> = mutableMapOf()
    private val placesAt: MutableMap<String, DiskPlace> = mutableMapOf()

    /** The places measured, each with a label; ones that do not exist are left out. */
    internal fun places(): List<Pair<String, Path>> {
        val data = paths.dataDir
        // A `jobs` directory left in the data directory once LAB_JOBS points elsewhere is shown on its own.
        val known = listOfNotNull("jobs".takeIf { paths.jobsDir == data.resolve("jobs") }, "work", "shared", "releases", "repo.git", "logs",
            "lab.db", "lab.db-wal")
        val rest = data.toFile().listFiles().orEmpty().map { it.name }
            .filter { it !in known && it != "lab.db-shm" && it != DuRunner.PID_DIR && !it.startsWith(STATE_FILE) }.sorted()
        return listOf(
            "job files" to paths.jobsDir,
            "job worktrees" to data.resolve("work"),
            "shared worktree directories" to data.resolve("shared"),
            "releases" to data.resolve("releases"),
            "repository mirror" to data.resolve("repo.git"),
            "logs" to data.resolve("logs"),
            "database" to data.resolve("lab.db"),
            "database log" to data.resolve("lab.db-wal"),
        ) + rest.map { "data: $it" to data.resolve(it) } + listOf(
            "corpus cache" to paths.corpusDir,
            "other caches" to Path(home, ".cache"),
            "Gradle" to Path(home, ".gradle"),
            "Colima VM" to Path(home, ".colima"),
            // Part of the VM's, shown on its own: the data disk Docker's images and build cache live on.
            "Colima VM disks" to Path(home, ".colima", "_lima", "_disks"),
            "Lima VMs" to Path(home, ".lima"),
            "Docker Desktop" to Path(home, "Library/Containers/com.docker.docker"),
            "Workspaces" to Path(home, "Workspaces"),
            "Codex worktrees" to Path(home, ".codex"),
        )
    }

    /** Measure what is due; null, without waiting, while another measurement runs. */
    fun measure(): DiskReport? {
        if (!lock.tryLock()) return null
        try {
            load()
            val now = clock()
            val activeIds = runCatching(active).getOrDefault(emptySet())
            val jobTotal = measureJobs(activeIds, now)
            val homeStore = store(Path(home))
            val places = places().filter { (_, path) -> Files.exists(path) }.map { (label, path) ->
                if (label == "job files") DiskPlace(label, path.toString(), jobTotal + otherJobEntries(homeStore, now), now)
                else place(label, path, homeStore, now)
            }
            // The corpus sits under ~/.cache: the other caches are what is left of it.
            val corpus = places.firstOrNull { it.label == "corpus cache" }?.bytes ?: 0
            val adjusted = places.map { p ->
                if (p.label == "other caches" && paths.corpusDir.startsWith(Path(p.path))) p.copy(bytes = p.bytes?.let { maxOf(0, it - corpus) }) else p
            }
            val largest = jobs.entries.sortedByDescending { it.value.bytes }.take(TOP_JOBS).map { it.key to it.value.bytes }
            val report = DiskReport(
                now, freeBytes(paths.dataDir), adjusted.sortedByDescending { it.bytes ?: -1 }, largest,
                docker(), DockerCleanup.last(paths.dockerCleanupState),
            )
            latest = report
            save()
            return report
        } finally {
            lock.unlock()
        }
    }

    /** The places' last measurements and the job sizes from [stateFile], once; nothing when it cannot be read. */
    private fun load() {
        if (loaded) return
        loaded = true
        val state = runCatching {
            if (stateFile.exists()) JSON.decodeFromString(DiskUsageState.serializer(), stateFile.readText()) else null
        }.getOrNull() ?: return
        jobs = state.jobs.toMutableMap()
        state.report?.places?.forEach { placesAt[it.label] = it }
        if (latest == null) latest = state.report
    }

    private fun save() {
        runCatching {
            stateFile.writeAtomically(JSON.encodeToString(DiskUsageState.serializer(), DiskUsageState(latest, jobs)))
        }.onFailure { println("disk usage: could not save ${stateFile.fileName}: ${it.message}") }
    }

    /**
     * Each job directory measured again when due, the rest kept as they were; directories gone are dropped. Measured in
     * chunks of [JOB_CHUNK], each saved, so a long first pass is not lost to a restart. The job directories' total.
     */
    private fun measureJobs(activeIds: Set<Long>, now: Long): Long {
        val dirs = paths.jobsDir.toFile().listFiles { f -> f.isDirectory && f.name.toLongOrNull() != null }.orEmpty()
            .associate { it.name.toLong() to it }
        jobs.keys.retainAll(dirs.keys)
        val due = dirs.filter { (id, dir) ->
            val cached = jobs[id]
            cached == null || id in activeIds || cached.active || dir.lastModified() != cached.mtime ||
                cached.at - cached.mtime < settleMs
        }
        for (chunk in due.entries.sortedBy { it.key }.chunked(JOB_CHUNK)) {
            // The mtime before the walk: a change during it is seen as one next time.
            val mtimes = chunk.associate { (id, dir) -> id to dir.lastModified() }
            pause()
            val sizes = parse(du.run(chunk.map { it.value.path }) ?: continue)
            for ((id, dir) in chunk) {
                val bytes = sizes[dir.path] ?: continue
                jobs[id] = JobSize(bytes, mtimes.getValue(id), now, id in activeIds)
            }
            if (due.size > JOB_CHUNK) save()
        }
        return jobs.values.sumOf { it.bytes }
    }

    /** What in the job files' directory is not a job directory, measured as a place. */
    private fun otherJobEntries(homeStore: FileStore?, now: Long): Long =
        paths.jobsDir.toFile().listFiles { f -> !(f.isDirectory && f.name.toLongOrNull() != null) }.orEmpty()
            .sumOf { place("job files: ${it.name}", it.toPath(), homeStore, now).bytes ?: 0 }

    /** [path]'s last measurement when it is slow to walk and not yet [SLOW_INTERVAL_H] old, otherwise a new one. */
    private fun place(label: String, path: Path, homeStore: FileStore?, now: Long): DiskPlace {
        val last = placesAt[label]?.takeIf { it.path == path.toString() && it.bytes != null }
        val slow = path.startsWith(paths.corpusDir) || paths.corpusDir.startsWith(path) || store(path) != homeStore
        if (last != null && slow && now - (last.at ?: 0) < SLOW_INTERVAL_H * HOUR_MS) return last
        pause()
        val bytes = du.run(listOf(path.toString()))?.let { parse(it).values.firstOrNull() }
        // A walk that timed out or failed keeps the last number, with its time.
        return (if (bytes == null && last != null) last else DiskPlace(label, path.toString(), bytes, now)).also { placesAt[label] = it }
    }

    private fun store(path: Path): FileStore? = runCatching { Files.getFileStore(path) }.getOrNull()

    /** What Docker holds, by `docker system df`, when the lab uses it. */
    private fun docker(): List<DockerUsage> = if (!docker.required) emptyList() else runCatching {
        val process = ProcessBuilder(Background.prefix + listOf("docker", "system", "df", "--format", "{{json .}}"))
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = process.inputStream.bufferedReader().readText()
        if (process.waitFor() == 0) DockerCleanup.parseDf(out) else emptyList()
    }.getOrDefault(emptyList())

    /**
     * Kill the walks a lab process before this one left running, show the last report, and measure [START_DELAY_MIN]
     * minutes from now and then every [INTERVAL_MIN] minutes, on a daemon thread.
     */
    fun start() {
        DuRunner.reapLeftovers(paths.dataDir.resolve(DuRunner.PID_DIR))
        lock.lock()
        try { load() } finally { lock.unlock() }
        schedule.scheduleWithFixedDelay({ runCatching { measure() }.onFailure { println("disk usage: ${it.message}") } },
            START_DELAY_MIN, INTERVAL_MIN, TimeUnit.MINUTES)
    }

    private val schedule = Executors.newSingleThreadScheduledExecutor { Thread(it, "disk-usage").apply { isDaemon = true } }

    /** Stop measuring; a walk under way is interrupted. */
    override fun close() {
        schedule.shutdownNow()
    }

    internal companion object {
        const val START_DELAY_MIN = 10L
        const val INTERVAL_MIN = 360L
        const val SLOW_INTERVAL_H = 24L
        const val SETTLE_MS = 3_600_000L
        const val PAUSE_MS = 20_000L
        const val STATE_FILE = "disk-usage.json"
        private const val HOUR_MS = 3_600_000L
        private const val TOP_JOBS = 30
        private const val JOB_CHUNK = 50
        private const val KIB = 1024L
        private val JSON = Json { ignoreUnknownKeys = true }

        /** `du -sk`'s lines: each path and its bytes. */
        fun parse(lines: List<String>): Map<String, Long> = lines.mapNotNull { line ->
            val (kib, path) = line.split('\t', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            kib.trim().toLongOrNull()?.let { path to it * KIB }
        }.toMap()
    }
}

/**
 * Runs `du` so it never outlives its use: each walk leads its own process group ([inSession]) and is killed, group and
 * all, after [timeoutMs]; every walk still running when the JVM exits is killed by a shutdown hook; and each walk is
 * recorded in [pidDir], with the JVM that started it, so a later lab process kills what one that died left behind
 * ([reapLeftovers]). No other `du` is ever touched.
 */
internal class DuRunner(
    private val pidDir: Path,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val command: List<String> = Background.prefix + listOf("du", "-sk"),
) {
    /** What `du` printed for [paths], line by line (whatever its exit, as it says why on stderr); null when it timed out
     *  or could not start. */
    fun run(paths: List<String>): List<String>? {
        val out = runCatching { Files.createTempFile("lab-du", ".out") }.getOrElse { return null }
        try {
            val process = runCatching {
                ProcessBuilder(inSession(*(command + paths).toTypedArray()))
                    .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(out.toFile()).start()
            }.getOrElse { return null }
            RUNNING.add(process)
            val record = record(process)
            var finished = false
            try {
                finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            } finally {
                if (!finished) {
                    println("du over ${paths.size} path(s) ran past ${timeoutMs / 1000} s; killed")
                    kill(process)
                }
                RUNNING.remove(process)
                record?.let { Files.deleteIfExists(it) }
            }
            return if (finished) out.readLines() else null
        } finally {
            Files.deleteIfExists(out)
        }
    }

    private fun record(process: Process): Path? = runCatching {
        Files.createDirectories(pidDir)
        val self = ProcessHandle.current()
        pidDir.resolve(process.pid().toString()).also {
            it.writeAtomically("${process.pid()} ${started(process.toHandle())} ${self.pid()} ${started(self)}\n")
        }
    }.getOrNull()

    companion object {
        const val PID_DIR = "du-pids"
        const val TIMEOUT_MS = 10 * 60_000L
        private val RUNNING: MutableSet<Process> = ConcurrentHashMap.newKeySet()

        init {
            Runtime.getRuntime().addShutdownHook(Thread({ RUNNING.toList().forEach(::kill) }, "du-cleanup"))
        }

        private fun started(handle: ProcessHandle): Long = handle.info().startInstant().map { it.toEpochMilli() }.orElse(0)

        private fun kill(process: Process) {
            runCatching { killGroup(process.pid()) }
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(KILL_WAIT_SEC, TimeUnit.SECONDS)
        }

        /**
         * Kill each walk recorded in [pidDir] whose JVM is gone (dead, or its pid reused): only a process still under the
         * recorded pid with the recorded start time, so a reused pid is left alone. A record of a live JVM's walk is
         * kept. The pids killed.
         */
        fun reapLeftovers(pidDir: Path): List<Long> {
            val killed = mutableListOf<Long>()
            for (file in pidDir.toFile().listFiles().orEmpty()) {
                val fields = runCatching { file.toPath().readLines().first().trim().split(" ").map { it.toLong() } }.getOrNull()
                if (fields == null || fields.size != 4) { file.delete(); continue }
                val (pid, start, owner, ownerStart) = fields
                val ownerAlive = ProcessHandle.of(owner).map { started(it) == ownerStart }.orElse(false)
                if (ownerAlive) continue
                ProcessHandle.of(pid).filter { started(it) == start }.ifPresent { handle ->
                    println("killing leftover du $pid from a lab process that is gone")
                    runCatching { killGroup(pid) }
                    handle.descendants().forEach { it.destroyForcibly() }
                    handle.destroyForcibly()
                    runCatching { handle.onExit().get(KILL_WAIT_SEC, TimeUnit.SECONDS) }
                    killed += pid
                }
                file.delete()
            }
            return killed
        }

        private const val KILL_WAIT_SEC = 10L
    }
}
