package com.eignex.lab

import java.nio.file.Path
import kotlin.io.path.Path

/** Server settings, all from the environment so that launchd plists carry them. */
data class Config(
    /** Queue database, per-job outputs and worktrees live here. */
    val dataDir: Path = Path(env("LAB_DATA", "${System.getProperty("user.home")}/klause-lab-data")),
    /** The klause repository to fetch refs from. */
    val repoUrl: String = env("LAB_REPO", "https://github.com/Eignex/klause.git"),
    /** The GitHub users whose branches the lab builds, besides `main` ([Trust]). */
    val trustedUsers: Set<String> = env("LAB_TRUSTED_USERS", "rasros").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
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
    /** Cases at once for an experiment that sets no `parallel`: the lab Mac's performance cores. A solve that lands on
     *  an efficiency core runs at a fraction of the speed, and which ones do changes from run to run, so running
     *  more than there are performance cores trades timing noise for throughput. */
    val defaultParallel: Int = env("LAB_DEFAULT_PARALLEL", "4").toInt().coerceIn(1, maxParallel),
    /** Cores the running cases may hold together, a case holding its arm's `processors`; two are left for the JVMs
     *  around the solves and the machine itself. */
    val cores: Int = env("LAB_CORES", (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(1).toString()).toInt(),
    /** The klause-lab checkout the services were installed from; the runner updates from it between jobs. */
    val sourceDir: Path? = System.getenv("LAB_SOURCE")?.takeIf { it.isNotBlank() }?.let { Path(it) },
    /** Seconds between update checks; `0` turns self-update off. */
    val updateCheckSec: Long = env("LAB_UPDATE_CHECK_SEC", "300").toLong(),
    /** Free space the data directory's disk must have before the runner starts a job. */
    val minFreeBytes: Long = env("LAB_MIN_FREE_GB", "10").toLong() * BYTES_PER_GB,
    /** The longest an experiment may take, every case using its whole budget, before it needs `"confirm": true`. */
    val maxExperimentHours: Long = env("LAB_MAX_EXPERIMENT_HOURS", "24").toLong(),
    /** Wait for `docker info` to succeed before taking jobs: set where reference solvers run in containers. */
    val requireDocker: Boolean = env("LAB_REQUIRE_DOCKER", "false").toBoolean(),
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
    /** Worktree-relative directories every job shares, separated by `:`. */
    val sharedPaths: List<String> = env("LAB_SHARED_PATHS", "klause-bench/build/bench-cache")
        .split(':').map { it.trim() }.filter { it.isNotEmpty() },
) {
    init {
        require(sharedPaths.none { it.startsWith("/") || ".." in it.split('/') }) {
            "LAB_SHARED_PATHS entries must stay inside the worktree: $sharedPaths"
        }
    }

    val sharedDir: Path get() = dataDir.resolve("shared")
    val database: Path get() = dataDir.resolve("lab.db")
    /** A GitHub token the trust check reads the repository's activity with, when the file exists: without one GitHub
     *  allows 60 calls an hour. Read on each use, so adding it needs no reinstall. */
    val githubTokenFile: Path get() = dataDir.resolve("github-token")
    val mirror: Path get() = dataDir.resolve("repo.git")
    /** Written by `lab update`: the runner checks for a lab update at once instead of at its interval. */
    val updateRequest: Path get() = dataDir.resolve("update-requested")
    /** The AWS settings, when `$LAB_DATA/aws/aws.properties` exists: experiments with `"host": "aws"` need them. */
    val aws: AwsConfig? by lazy { AwsConfig.load(dataDir.resolve("aws").resolve("aws.properties")) }
    fun jobDir(id: Long): Path = dataDir.resolve("jobs").resolve(id.toString())
    fun worktree(id: Long): Path = dataDir.resolve("work").resolve(id.toString())
}

private const val MS_PER_SEC = 1000L

private fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
