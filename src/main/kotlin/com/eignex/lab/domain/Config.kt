package com.eignex.lab.domain

import com.eignex.lab.util.*

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import com.eignex.lab.execution.VolumeAccess
import com.eignex.lab.execution.Volumes

/**
 * Server settings. A service reads them from the environment once, at startup, through [fromEnv], so that launchd
 * plists carry them and a bad value stops it there, naming the variable. A test builds one directly: every setting
 * has its default from [Defaults], and none is read from the environment.
 */
data class Config(
    /** Queue database, worktrees and Gradle live here, and per-job outputs unless [jobsDir] puts them elsewhere. */
    val dataDir: Path = Path("${System.getProperty("user.home")}/eignex-lab-data"),
    /** Each job's files, one directory per job: on a volume of its own, such as an external drive, when set. */
    val jobsDir: Path = dataDir.resolve("jobs"),
    /** Legacy repository URL, used only to render historical result links. */
    val repoUrl: String = "",
    /**
     * The password every page and API call needs ([Password]): the one the lab ships with unless `LAB_PASSWORD_HASH`
     * gives another. Only a test turns it off, and it has to say so ([Auth.Disabled]).
     */
    val auth: Auth = Auth.Required(Password.bundled()),
    /** The GitHub users whose branches the lab builds, besides `main` ([Trust]). */
    val trustedUsers: Set<String> = Defaults.TRUSTED_USERS,
    /** The lab's own launcher, which a reference arm's cases run ([com.eignex.lab.reference.ReferenceCli]): the
     *  current release, independent of any consumer build. */
    val launcher: String = "$dataDir/releases/current/bin/eignex-lab",
    /** Benchmark instances, rsynced from the dev PC; the bench's own default cache location. */
    val corpusDir: Path = Path("${System.getProperty("user.home")}/.cache/eignex-lab/corpus"),
    val port: Int = Defaults.PORT,
    /** Default JDK for committed build and launch recipes. */
    val javaHome: String = System.getenv("JAVA_HOME") ?: "",
    /** JVM flags for lab-owned reference solver processes. */
    val solveJavaOpts: String = Defaults.SOLVE_JAVA_OPTS,
    /** The most cases one native worker session runs at once, whatever vCPUs its job has: each holds its own solve
     *  heap, so memory bounds this, where [cores] bounds the threads and a job's `vcpus`. */
    val maxParallel: Int = Defaults.MAX_PARALLEL,
    /** vCPUs for an experiment that sets no `vcpus`: the local machine's performance cores, one of which its worker
     *  keeps, so it runs one case fewer. A solve that lands on an efficiency core runs at a fraction of the speed, and
     *  which ones do changes from run to run, so running more than there are performance cores trades timing noise
     *  for throughput. */
    val defaultParallel: Int = Defaults.DEFAULT_PARALLEL.coerceAtMost(maxParallel),
    /** Cores the running cases may hold together, a case holding its arm's `processors`; two are left for the JVMs
     *  around the solves and the machine itself. */
    val cores: Int = Defaults.cores(),
    /** The eignex-lab checkout the services were installed from; the runner updates from it between jobs. */
    val sourceDir: Path? = null,
    /** Seconds between update checks; `0` turns self-update off. */
    val updateCheckSec: Long = Defaults.UPDATE_CHECK_SEC,
    /** Free space every volume the lab writes to ([Volumes]) must have before the runner starts a job. */
    val minFreeBytes: Long = Defaults.MIN_FREE_GB * BYTES_PER_GB,
    /** How long after a job ended a low disk leaves its files alone ([JobTrim]), so a download soon after it ends,
     *  held or not, finds them all. */
    val trimGraceMs: Long = Defaults.TRIM_GRACE_HOURS * MS_PER_HOUR,
    /** Below this much free, a low disk trims jobs still in their grace too, though never a held one: the queue's floor
     *  ([minFreeBytes]) unless set, so a queue held for space is never kept waiting on a grace. */
    val trimCriticalFreeBytes: Long = minFreeBytes,
    /** Below this much free on the job files' volume, [JobTrim] trims ended jobs early, largest first. */
    val jobsTargetFreeBytes: Long = Defaults.JOBS_TARGET_FREE_GB * BYTES_PER_GB,
    /** How long after a job ended [JobTrim] trims it whatever the free space: longer on a roomy volume of its own. */
    val trimAgeMs: Long = Defaults.TRIM_AGE_DAYS * MS_PER_DAY,
    /** How long after a job ended [JobRetention] forgets it, record and directory; 0 keeps every job. */
    val jobRetentionMs: Long = 0,
    /** The longest an experiment may take, every case using its whole budget, before it needs `"confirm": true`. */
    val maxExperimentHours: Long = Defaults.MAX_EXPERIMENT_HOURS,
    /** Wait for `docker info` to succeed before taking jobs: set where reference solvers run in containers. */
    val requireDocker: Boolean = false,
    /** The reference image tags a Docker cleanup ([DockerCleanup]) always keeps; their repositories are the ones it
     *  removes superseded tags from, never any other's. */
    val dockerKeep: Set<String> = list(Defaults.DOCKER_KEEP),
    /** Days between Docker cleanups, sooner on a low disk; `0` turns the automatic cleanup off. */
    val dockerCleanupDays: Long = Defaults.DOCKER_CLEANUP_DAYS,
    /** Retries of a step that talks to the network (fetching the mirror, adding a worktree, a schedule's check): 15 s,
     *  then doubling to at most 5 min, six tries in all, so a network outage of several minutes is ridden out. */
    val retry: Backoff = Backoff(Defaults.RETRY_ATTEMPTS, Defaults.RETRY_BASE_SEC * MS_PER_SEC, Defaults.RETRY_MAX_SEC * MS_PER_SEC),
    /** The largest saved-capture archive an analysis job reads ([CaptureArchives]). */
    val archiveMaxBytes: Long = Defaults.ARCHIVE_MAX_MB * BYTES_PER_MB,
    /** The most an archive may unpack to, by its manifest, which also bounds what an analysis job extracts of it. */
    val archiveMaxUnpackedBytes: Long = Defaults.ARCHIVE_MAX_UNPACKED_MB * BYTES_PER_MB,
    /** Where external volumes mount, as macOS mounts them: a directory under it needs its volume mounted ([Volumes]). */
    val volumesRoot: Path = Path("/Volumes"),
    /** Whether the lab's directories answer within a timeout ([VolumeAccess]): one per process, which [fromEnv] is given. */
    val volumeAccess: VolumeAccess = VolumeAccess(),
    /** Operator labels on the local node; CPU and placement facts are detected rather than overridden. */
    val nodeTags: Set<String> = emptySet(),
    /** This machine's CPU ([NodeTags.probeLocal]), which a service probes once and [fromEnv] is given; undetected otherwise. */
    val cpuCapabilities: CpuCapabilities = CpuCapabilities(),
    val nomadQueueSettings: Path? = null,
    val nomadCapacitySettings: Path? = null,
    /** The lock the native worker and the runner take turns on the local machine with. */
    val nativeExecutionLock: Path = dataDir.resolve("nomad/native.lock"),
) {
    /** The Nomad queue's settings ([NomadQueueSettings]), parsed from [nomadQueueSettings] once, when first read. */
    val nomadQueue: NomadQueueSettings? by lazy {
        nomadQueueSettings?.let { kotlinx.serialization.json.Json.decodeFromString<NomadQueueSettings>(Files.readString(it)) }
    }
    /** Where the lab keeps its files. */
    val paths: DataPaths get() = DataPaths(dataDir, jobsDir, corpusDir, volumesRoot, volumeAccess)
    /** The local machine as the lab schedules work on it. */
    val local: LocalNode get() = LocalNode(cores, maxParallel, defaultParallel, localNodeTags)
    /** What the status pages and the queue's read side show of the settings. */
    val status: StatusSettings get() = StatusSettings(repoUrl, paths, local, minFreeBytes, nomadCapacitySettings)
    /** The workers the lab places experiments on ([Placement]). */
    val workers: Workers get() = Workers(local, nomadQueue)
    /** How the lab keeps its disks from filling. */
    val disk: DiskPolicy get() = DiskPolicy(minFreeBytes, trimGraceMs, trimCriticalFreeBytes, jobsTargetFreeBytes, trimAgeMs)
    /** Whether and how the lab keeps Docker in check. */
    val docker: DockerPolicy get() = DockerPolicy(requireDocker, dockerKeep, dockerCleanupDays)
    /** The most a saved-capture archive may hold ([CaptureArchives]). */
    val archives: ArchiveLimits get() = ArchiveLimits(archiveMaxBytes, archiveMaxUnpackedBytes)

    /** The queue and its capacity pools, checked together ([ClusterSettings]) once, when first read. */
    val cluster: ClusterSettings? by lazy { nomadQueue?.let { ClusterSettings(it, nomadCapacitySettings?.let(CapacitySettings::read)) } }
    val nomadCapacityReport: Path get() = paths.capacityReport
    val nativeYield: Path get() = paths.nativeYield

    val localNodeTags: Set<String> get() = NodeTags.observed(cpuCapabilities, nodeTags)
    val archiveDir: Path get() = paths.archives

    val database: Path get() = paths.database
    val githubTokenFile: Path get() = paths.githubToken
    val mirror: Path get() = paths.mirror
    val updateRequest: Path get() = paths.updateRequest
    val dockerCleanupRequest: Path get() = paths.dockerCleanupRequest
    val dockerCleanupState: Path get() = paths.dockerCleanupState
    fun jobDir(id: Long): Path = paths.jobDir(id)
    fun createJobDir(id: Long): Path = paths.createJobDir(id)
    fun worktree(id: Long): Path = paths.worktree(id)

    companion object {
        /**
         * The settings [env] gives, every one checked: a number out of its range, a flag that is not one, settings that
         * contradict each other, or a Nomad queue file that does not parse. All of them are reported at once, each
         * naming its variable, as a [ConfigException].
         */
        fun fromEnv(
            env: Map<String, String> = System.getenv(),
            /** This machine's CPU, probed once by the caller ([NodeTags.probeLocal]). */
            cpu: CpuCapabilities = CpuCapabilities(),
            /** This process's volume probes ([VolumeAccess]). */
            volumeAccess: VolumeAccess = VolumeAccess(),
        ): Config {
            val read = EnvReader(env)
            val dataDir = read.path("LAB_DATA", Path("${System.getProperty("user.home")}/eignex-lab-data"))
            val maxParallel = read.int("LAB_MAX_PARALLEL", Defaults.MAX_PARALLEL, 1..1024)
            val minFreeGb = read.long("LAB_MIN_FREE_GB", Defaults.MIN_FREE_GB, 0L..100_000)
            val retryBaseMs = read.long("LAB_RETRY_BASE_SEC", Defaults.RETRY_BASE_SEC, 1L..3600) * MS_PER_SEC
            val retryMaxMs = read.long("LAB_RETRY_MAX_SEC", Defaults.RETRY_MAX_SEC, 1L..86_400) * MS_PER_SEC
            read.check(retryBaseMs <= retryMaxMs) { "LAB_RETRY_BASE_SEC must not exceed LAB_RETRY_MAX_SEC" }
            val config = Config(
                dataDir = dataDir,
                jobsDir = read.path("LAB_JOBS", dataDir.resolve("jobs")),
                repoUrl = read.string("LAB_REPO", ""),
                auth = Auth.Required(read.string("LAB_PASSWORD_HASH", "").ifEmpty { Password.bundled() }),
                trustedUsers = list(read.string("LAB_TRUSTED_USERS", Defaults.TRUSTED_USERS.joinToString(","))),
                launcher = read.string("LAB_LAUNCHER", "$dataDir/releases/current/bin/eignex-lab"),
                corpusDir = read.path("LAB_CORPUS", Path("${System.getProperty("user.home")}/.cache/eignex-lab/corpus")),
                port = read.int("LAB_PORT", Defaults.PORT, 1..65535),
                javaHome = read.string("LAB_JAVA_HOME", env["JAVA_HOME"].orEmpty()),
                solveJavaOpts = read.string("LAB_SOLVE_JAVA_OPTS", Defaults.SOLVE_JAVA_OPTS),
                maxParallel = maxParallel,
                defaultParallel = read.int("LAB_DEFAULT_PARALLEL", Defaults.DEFAULT_PARALLEL.coerceAtMost(maxParallel), 1..maxParallel.coerceAtLeast(1)),
                cores = read.int("LAB_CORES", Defaults.cores(), 1..4096),
                sourceDir = read.optionalPath("LAB_SOURCE"),
                updateCheckSec = read.long("LAB_UPDATE_CHECK_SEC", Defaults.UPDATE_CHECK_SEC, 0L..86_400),
                minFreeBytes = minFreeGb * BYTES_PER_GB,
                trimGraceMs = read.long("LAB_TRIM_GRACE_HOURS", Defaults.TRIM_GRACE_HOURS, 0L..24 * 365) * MS_PER_HOUR,
                trimCriticalFreeBytes = read.long("LAB_TRIM_CRITICAL_FREE_GB", minFreeGb, 0L..100_000) * BYTES_PER_GB,
                jobsTargetFreeBytes = read.long("LAB_JOBS_TARGET_FREE_GB", Defaults.JOBS_TARGET_FREE_GB, 0L..100_000) * BYTES_PER_GB,
                trimAgeMs = read.long("LAB_TRIM_AGE_DAYS", Defaults.TRIM_AGE_DAYS, 1L..3650) * MS_PER_DAY,
                jobRetentionMs = read.long("LAB_JOB_RETENTION_DAYS", 0L, 0L..3650) * MS_PER_DAY,
                maxExperimentHours = read.long("LAB_MAX_EXPERIMENT_HOURS", Defaults.MAX_EXPERIMENT_HOURS, 1L..24 * 365),
                requireDocker = read.flag("LAB_REQUIRE_DOCKER", false),
                dockerKeep = list(read.string("LAB_DOCKER_KEEP", Defaults.DOCKER_KEEP)),
                dockerCleanupDays = read.long("LAB_DOCKER_CLEANUP_DAYS", Defaults.DOCKER_CLEANUP_DAYS, 0L..365),
                // A maximum below the base is reported below; the backoff itself takes the base for both meanwhile.
                retry = Backoff(read.int("LAB_RETRY_ATTEMPTS", Defaults.RETRY_ATTEMPTS, 1..100), retryBaseMs, retryMaxMs.coerceAtLeast(retryBaseMs)),
                archiveMaxBytes = read.long("LAB_ARCHIVE_MAX_MB", Defaults.ARCHIVE_MAX_MB, 1L..10_000_000) * BYTES_PER_MB,
                archiveMaxUnpackedBytes = read.long("LAB_ARCHIVE_MAX_UNPACKED_MB", Defaults.ARCHIVE_MAX_UNPACKED_MB, 1L..100_000_000) * BYTES_PER_MB,
                nodeTags = list(read.string("LAB_NODE_TAGS", "")),
                cpuCapabilities = cpu,
                volumeAccess = volumeAccess,
                nomadQueueSettings = read.optionalPath("LAB_NOMAD_QUEUE_SETTINGS"),
                nomadCapacitySettings = read.optionalPath("LAB_NOMAD_CAPACITY_SETTINGS"),
                nativeExecutionLock = read.path("LAB_NATIVE_EXECUTION_LOCK", dataDir.resolve("nomad/native.lock")),
            )
            read.check(config.trimCriticalFreeBytes <= config.jobsTargetFreeBytes) {
                "LAB_TRIM_CRITICAL_FREE_GB (${config.trimCriticalFreeBytes / BYTES_PER_GB}) must not exceed LAB_JOBS_TARGET_FREE_GB " +
                    "(${config.jobsTargetFreeBytes / BYTES_PER_GB}): the critical trim would stop at the target"
            }
            config.nomadQueueSettings?.let { path ->
                runCatching { config.nomadQueue }.onFailure { read.fail("LAB_NOMAD_QUEUE_SETTINGS ($path) does not parse: ${it.message?.lineSequence()?.first()}") }
                    .onSuccess {
                        runCatching { config.cluster }.onFailure {
                            read.fail("LAB_NOMAD_QUEUE_SETTINGS and LAB_NOMAD_CAPACITY_SETTINGS (${config.nomadCapacitySettings}) disagree: " +
                                "${it.message?.lineSequence()?.first()}")
                        }
                    }
            }
            read.finish()
            return config
        }
    }
}

/**
 * Where the lab keeps its files ([Config.paths]): what a part that only reads and writes them needs of its settings,
 * with the volumes they are on, which [createJobDir] checks first.
 */
data class DataPaths(
    val dataDir: Path,
    val jobsDir: Path,
    /** The benchmark instances. */
    val corpusDir: Path,
    val volumesRoot: Path = Path.of("/Volumes"),
    val volumeAccess: VolumeAccess = VolumeAccess(),
) {
    val database: Path get() = dataDir.resolve("lab.db")
    /** Saved-capture archives an analysis job may name by a path under it ([CaptureArchives]). */
    val archives: Path get() = dataDir.resolve("archives")
    val capacityReport: Path get() = dataDir.resolve("nomad/capacity-report.json")
    val nativeYield: Path get() = dataDir.resolve("nomad/update-pending")
    /** A GitHub token the trust check reads the repository's activity with, when the file exists: without one GitHub
     *  allows 60 calls an hour. Read again when it changes, so adding it needs no reinstall. */
    val githubToken: Path get() = dataDir.resolve("github-token")
    /** Written by `lab update`: the runner checks for a lab update at once instead of at its interval. */
    val updateRequest: Path get() = dataDir.resolve("update-requested")
    /** Written by `lab docker-cleanup`: the runner cleans Docker up at its next free moment. */
    val dockerCleanupRequest: Path get() = dataDir.resolve("docker-cleanup-requested")
    /** The last Docker cleanup, as [DockerCleanup] wrote it. */
    val dockerCleanupState: Path get() = dataDir.resolve("docker-cleanup.json")
    val mirror: Path get() = dataDir.resolve("repo.git")
    /** The lab's mirrors of the repositories it builds, one bare repository each ([Repositories.mirror]). */
    val repositories: Path get() = dataDir.resolve("repos")
    fun jobDir(id: Long): Path = jobsDir.resolve(id.toString())
    fun worktree(id: Long): Path = dataDir.resolve("work").resolve(id.toString())

    /** Job [id]'s directory, made when missing; fails at once, making nothing, while the job files' volume is missing or
     *  does not answer ([Volumes]). */
    fun createJobDir(id: Long): Path {
        Volumes.ensure(Volumes.JOB_FILES, jobsDir, volumesRoot, volumeAccess)
        return Files.createDirectories(jobDir(id))
    }
}

/**
 * What the lab's status views read of its settings ([Config.status]): the pages, the queue's waiting reasons and the
 * nodes each job runs on.
 */
data class StatusSettings(
    val repoUrl: String,
    val paths: DataPaths,
    val local: LocalNode,
    /** The free space under which the queue holds ([Volumes.hold]). */
    val minFreeBytes: Long,
    /** The capacity pools' settings file, when the lab has cloud pools. */
    val capacitySettings: Path?,
)

/** The workers the lab can place an experiment on ([Config.workers]): the local machine, and the queue's cloud targets when it has one. */
data class Workers(val local: LocalNode, val queue: NomadQueueSettings?)

/** Whether the lab needs Docker, and which images and how many days of them its cleanup keeps ([DockerCleanup]). */
data class DockerPolicy(val required: Boolean, val keep: Set<String>, val cleanupDays: Long)

/** The most a saved-capture archive may hold, packed and unpacked ([CaptureArchives]). */
data class ArchiveLimits(val maxBytes: Long, val maxUnpackedBytes: Long)

/** The local machine as the lab schedules work on it ([Config.local]). */
data class LocalNode(val cores: Int, val maxParallel: Int, val defaultParallel: Int, val tags: Set<String>)

/** How the lab keeps its disks from filling ([Config.disk]): the queue's floor and the trim's thresholds ([JobTrim]). */
data class DiskPolicy(
    val minFreeBytes: Long,
    val trimGraceMs: Long,
    val trimCriticalFreeBytes: Long,
    val jobsTargetFreeBytes: Long,
    val trimAgeMs: Long,
)

/** The defaults of [Config]'s settings, one place for each: what a setting is when its variable is not set. */
object Defaults {
    const val PORT = 8420
    const val SOLVE_JAVA_OPTS = "-Xmx3g -XX:+UseSerialGC"
    const val MAX_PARALLEL = 6
    const val DEFAULT_PARALLEL = 4
    const val UPDATE_CHECK_SEC = 300L
    const val MIN_FREE_GB = 10L
    const val TRIM_GRACE_HOURS = 6L
    const val JOBS_TARGET_FREE_GB = 25L
    const val TRIM_AGE_DAYS = 7L
    const val MAX_EXPERIMENT_HOURS = 24L
    const val DOCKER_CLEANUP_DAYS = 7L
    const val RETRY_ATTEMPTS = 6
    const val RETRY_BASE_SEC = 15L
    const val RETRY_MAX_SEC = 300L
    const val ARCHIVE_MAX_MB = 2048L
    const val ARCHIVE_MAX_UNPACKED_MB = 16384L
    /** The reference image tags a Docker cleanup always keeps ([DockerCleanup]). */
    const val DOCKER_KEEP = "eignex-lab-xcsp3-cpsat:source-preserving-v2,eignex-lab-scip:latest,eignex-lab-clasp:latest"
    val TRUSTED_USERS = setOf("rasros")

    /** The machine's processors less two, for the JVMs around the solves and the machine itself; at least one. */
    fun cores(): Int = (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(1)
}

/** Settings that stopped a service from starting, each naming its variable. */
class ConfigException(val problems: List<String>) :
    IllegalArgumentException("invalid configuration:\n" + problems.joinToString("\n") { "  $it" })

/** Reads [env], noting each bad value instead of failing at the first, for [Config.fromEnv] to report them all. */
private class EnvReader(private val env: Map<String, String>) {
    private val problems = ArrayList<String>()

    private fun raw(name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }

    fun string(name: String, default: String): String = raw(name) ?: default

    fun path(name: String, default: Path): Path = raw(name)?.let(::Path) ?: default

    fun optionalPath(name: String): Path? = raw(name)?.let(::Path)

    fun int(name: String, default: Int, range: IntRange): Int {
        val value = raw(name) ?: return default
        val parsed = value.toIntOrNull()
        if (parsed == null || parsed !in range) {
            problems += "$name=$value: must be a whole number from ${range.first} to ${range.last}"
            return default
        }
        return parsed
    }

    fun long(name: String, default: Long, range: LongRange): Long {
        val value = raw(name) ?: return default
        val parsed = value.toLongOrNull()
        if (parsed == null || parsed !in range) {
            problems += "$name=$value: must be a whole number from ${range.first} to ${range.last}"
            return default
        }
        return parsed
    }

    /** `true`, `yes`, `1` or `on`; `false`, `no`, `0` or `off`; anything else is a mistake, not false. */
    fun flag(name: String, default: Boolean): Boolean {
        val value = raw(name) ?: return default
        return when (value.lowercase()) {
            "true", "yes", "1", "on" -> true
            "false", "no", "0", "off" -> false
            else -> default.also { problems += "$name=$value: must be true or false" }
        }
    }

    fun check(ok: Boolean, problem: () -> String) {
        if (!ok) problems += problem()
    }

    fun fail(problem: String) {
        problems += problem
    }

    fun finish() {
        if (problems.isNotEmpty()) throw ConfigException(problems)
    }
}

/** A comma-separated list's items, trimmed, without blanks. */
private fun list(text: String): Set<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
