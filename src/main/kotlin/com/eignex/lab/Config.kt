package com.eignex.lab

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

/** Server settings, all from the environment so that launchd plists carry them. */
data class Config(
    /** Queue database, worktrees and Gradle live here, and per-job outputs unless [jobsDir] puts them elsewhere. */
    val dataDir: Path = Path(env("LAB_DATA", "${System.getProperty("user.home")}/eignex-lab-data")),
    /** Each job's files, one directory per job: on a volume of its own, such as an external drive, when set. */
    val jobsDir: Path = Path(env("LAB_JOBS", "$dataDir/jobs")),
    /** The klause repository to fetch refs from. */
    val repoUrl: String = env("LAB_REPO", "https://github.com/Eignex/klause.git"),
    /**
     * The hash of the password every page and API call needs ([Password]); the one the lab ships with unless
     * `LAB_PASSWORD_HASH` gives another. Null turns the check off, which only a test does.
     */
    val passwordHash: String? = System.getenv("LAB_PASSWORD_HASH")?.takeIf { it.isNotBlank() } ?: Password.bundled(),
    /** The GitHub users whose branches the lab builds, besides `main` ([Trust]). */
    val trustedUsers: Set<String> = env("LAB_TRUSTED_USERS", "rasros").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
    /** The lab's own launcher, which a reference arm's cases run ([com.eignex.lab.reference.ReferenceCli]): the
     *  current release, so a case needs no klause build. */
    val launcher: String = env("LAB_LAUNCHER", "$dataDir/releases/current/bin/eignex-lab"),
    /** Benchmark instances, rsynced from the dev PC; the bench's own default cache location. */
    val corpusDir: Path = Path(env("LAB_CORPUS", "${System.getProperty("user.home")}/.cache/klause-bench/corpus")),
    val port: Int = env("LAB_PORT", "8420").toInt(),
    /** JDK the klause build and CLI run on. */
    val javaHome: String = env("LAB_JAVA_HOME", System.getenv("JAVA_HOME") ?: ""),
    /** JVM flags for every klause-cli solve a command starts. */
    val solveJavaOpts: String = env("LAB_SOLVE_JAVA_OPTS", "-Xmx3g -XX:+UseSerialGC"),
    val gradleWorkers: Int = env("LAB_GRADLE_WORKERS", "2").toInt(),
    val setupTimeoutSec: Long = env("LAB_SETUP_TIMEOUT_SEC", "3600").toLong(),
    /** The most cases one job may run at once, whatever it asks for: each holds its own solve heap, so memory bounds
     *  this, where [cores] bounds the threads. */
    val maxParallel: Int = env("LAB_MAX_PARALLEL", "6").toInt(),
    /** Cases at once for an experiment that sets no `parallel`: the local machine's performance cores. A solve that
     *  lands on an efficiency core runs at a fraction of the speed, and which ones do changes from run to run, so
     *  running more than there are performance cores trades timing noise for throughput. */
    val defaultParallel: Int = env("LAB_DEFAULT_PARALLEL", "4").toInt().coerceIn(1, maxParallel),
    /** Cores the running cases may hold together, a case holding its arm's `processors`; two are left for the JVMs
     *  around the solves and the machine itself. */
    val cores: Int = env("LAB_CORES", (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(1).toString()).toInt(),
    /** The eignex-lab checkout the services were installed from; the runner updates from it between jobs. */
    val sourceDir: Path? = System.getenv("LAB_SOURCE")?.takeIf { it.isNotBlank() }?.let { Path(it) },
    /** Seconds between update checks; `0` turns self-update off. */
    val updateCheckSec: Long = env("LAB_UPDATE_CHECK_SEC", "300").toLong(),
    /** Free space every volume the lab writes to ([Volumes]) must have before the runner starts a job. */
    val minFreeBytes: Long = env("LAB_MIN_FREE_GB", "10").toLong() * BYTES_PER_GB,
    /** How long after a job ended a low disk leaves its files alone ([JobTrim]), so a download soon after it ends,
     *  held or not, finds them all. */
    val trimGraceMs: Long = env("LAB_TRIM_GRACE_HOURS", "6").toLong() * MS_PER_HOUR,
    /** Below this much free, a low disk trims jobs still in their grace too, though never a held one: the queue's floor
     *  ([minFreeBytes]) unless set, so a queue held for space is never kept waiting on a grace. */
    val trimCriticalFreeBytes: Long = env("LAB_TRIM_CRITICAL_FREE_GB", (minFreeBytes / BYTES_PER_GB).toString()).toLong() * BYTES_PER_GB,
    /** Below this much free on the job files' volume, [JobTrim] trims ended jobs early, largest first. */
    val jobsTargetFreeBytes: Long = env("LAB_JOBS_TARGET_FREE_GB", (JobTrim.TARGET_FREE_BYTES / BYTES_PER_GB).toString()).toLong() * BYTES_PER_GB,
    /** How long after a job ended [JobTrim] trims it whatever the free space: longer on a roomy volume of its own. */
    val trimAgeMs: Long = env("LAB_TRIM_AGE_DAYS", (JobTrim.MAX_AGE_MS / MS_PER_DAY).toString()).toLong() * MS_PER_DAY,
    /** The longest an experiment may take, every case using its whole budget, before it needs `"confirm": true`. */
    val maxExperimentHours: Long = env("LAB_MAX_EXPERIMENT_HOURS", "24").toLong(),
    /** Wait for `docker info` to succeed before taking jobs: set where reference solvers run in containers. */
    val requireDocker: Boolean = env("LAB_REQUIRE_DOCKER", "false").toBoolean(),
    /** The reference image tags a Docker cleanup ([DockerCleanup]) always keeps; their repositories are the ones it
     *  removes superseded tags from, never any other's. */
    val dockerKeep: Set<String> = env("LAB_DOCKER_KEEP", DockerCleanup.DEFAULT_KEEP)
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
    /** Days between Docker cleanups, sooner on a low disk; `0` turns the automatic cleanup off. */
    val dockerCleanupDays: Long = env("LAB_DOCKER_CLEANUP_DAYS", "7").toLong(),
    /** Whether Docker runs in Colima's VM, which `install.sh` points the services' `DOCKER_HOST` into, or, in a shell
     *  with no `DOCKER_HOST`, a Mac with a Colima VM: a cleanup then trims its disk. */
    val colima: Boolean = System.getenv("DOCKER_HOST")?.takeIf { it.isNotBlank() }?.contains("/.colima/")
        ?: (System.getProperty("os.name").orEmpty().startsWith("Mac") && Path(System.getProperty("user.home"), ".colima", "default").toFile().isDirectory),
    /** Retries of a step that talks to the network (fetching the mirror, adding a worktree, a schedule's check): 15 s,
     *  then doubling to at most 5 min, six tries in all, so a network outage of several minutes is ridden out. */
    val retry: Backoff = Backoff(
        env("LAB_RETRY_ATTEMPTS", "6").toInt(),
        env("LAB_RETRY_BASE_SEC", "15").toLong() * MS_PER_SEC,
        env("LAB_RETRY_MAX_SEC", "300").toLong() * MS_PER_SEC,
    ),
    /** Retries of a build or a plan, which can also fail on a download but more often for good: fewer tries. */
    val buildRetry: Backoff = Backoff(
        env("LAB_BUILD_RETRY_ATTEMPTS", "3").toInt(),
        env("LAB_RETRY_BASE_SEC", "15").toLong() * MS_PER_SEC,
        env("LAB_RETRY_MAX_SEC", "300").toLong() * MS_PER_SEC,
    ),
    /** Worktree-relative directories every job shares, separated by `:`; none by default. */
    val sharedPaths: List<String> = env("LAB_SHARED_PATHS", "")
        .split(':').map { it.trim() }.filter { it.isNotEmpty() },
    /** The largest saved-capture archive an analysis job reads ([CaptureArchives]). */
    val archiveMaxBytes: Long = env("LAB_ARCHIVE_MAX_MB", "2048").toLong() * BYTES_PER_MB,
    /** The most an archive may unpack to, by its manifest, which also bounds what an analysis job extracts of it. */
    val archiveMaxUnpackedBytes: Long = env("LAB_ARCHIVE_MAX_UNPACKED_MB", "16384").toLong() * BYTES_PER_MB,
    /** Where external volumes mount, as macOS mounts them: a directory under it needs its volume mounted ([Volumes]). */
    val volumesRoot: Path = Path("/Volumes"),
    /** Whether the lab's directories answer within a timeout ([VolumeAccess]): this process's own, unless a test gives one. */
    val volumeAccess: VolumeAccess = VolumeAccess.shared,
    /** Operator labels on the local node; CPU and placement facts are detected rather than overridden. */
    val nodeTags: Set<String> = env("LAB_NODE_TAGS", "").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
    val cpuCapabilities: CpuCapabilities = NodeTags.localCapabilities,
) {
    val nativeExecutionLock: Path get() = Path(env("LAB_NATIVE_EXECUTION_LOCK", "$dataDir/nomad/native.lock"))
    val nativeYield: Path get() = dataDir.resolve("nomad/update-pending")

    val localNodeTags: Set<String> get() = NodeTags.observed(cpuCapabilities, nodeTags)
    init {
        require(sharedPaths.none { it.startsWith("/") || ".." in it.split('/') }) {
            "LAB_SHARED_PATHS entries must stay inside the worktree: $sharedPaths"
        }
    }

    /** Saved-capture archives an analysis job may name by a path under it ([CaptureArchives]). */
    val archiveDir: Path get() = dataDir.resolve("archives")

    val sharedDir: Path get() = dataDir.resolve("shared")
    /**
     * The Gradle home the lab's builds use, its own: `~/.gradle` is shared with whatever else builds on the machine,
     * whose daemons' cache cleanups have left its transform records pointing at files they deleted.
     */
    val gradleHome: Path get() = dataDir.resolve("gradle")
    val database: Path get() = dataDir.resolve("lab.db")
    /** A GitHub token the trust check reads the repository's activity with, when the file exists: without one GitHub
     *  allows 60 calls an hour. Read on each use, so adding it needs no reinstall. */
    val githubTokenFile: Path get() = dataDir.resolve("github-token")
    val mirror: Path get() = dataDir.resolve("repo.git")
    /** Written by `lab update`: the runner checks for a lab update at once instead of at its interval. */
    val updateRequest: Path get() = dataDir.resolve("update-requested")
    /** Written by `lab docker-cleanup`: the runner cleans Docker up at its next free moment. */
    val dockerCleanupRequest: Path get() = dataDir.resolve("docker-cleanup-requested")
    /** The last Docker cleanup, as [DockerCleanup] wrote it. */
    val dockerCleanupState: Path get() = dataDir.resolve("docker-cleanup.json")
    /** Colima's Lima directory on the host, which holds its VM's disks: `_disks` with the data disk Docker writes to. */
    val colimaDisks: Path get() = Path(System.getProperty("user.home"), ".colima", "_lima")
    /** The AWS settings, when `$LAB_DATA/aws/aws.properties` exists: experiments with `"host": "aws"` need them. */
    val aws: AwsConfig? get() = AwsConfig.load(dataDir.resolve("aws").resolve("aws.properties"))
    fun jobDir(id: Long): Path = jobsDir.resolve(id.toString())

    /** Job [id]'s directory, made when missing; fails at once, making nothing, while the job files' volume is missing or
     *  does not answer ([Volumes]). */
    fun createJobDir(id: Long): Path {
        Volumes.ensure(Volumes.JOB_FILES, jobsDir, volumesRoot, volumeAccess)
        return Files.createDirectories(jobDir(id))
    }
    fun worktree(id: Long): Path = dataDir.resolve("work").resolve(id.toString())
}

private const val MS_PER_SEC = 1000L
private const val MS_PER_HOUR = 3_600_000L
private const val MS_PER_DAY = 24 * MS_PER_HOUR
private const val BYTES_PER_MB = 1024L * 1024

private fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
