package com.eignex.lab

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path

/** One place on the lab machine's disk and the space it takes, as `du` counts it (allocated, so sparse files count
 *  what they hold). [bytes] is null when it could not be measured. */
@Serializable
data class DiskPlace(val label: String, val path: String, val bytes: Long?)

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
)

/**
 * What takes the lab machine's disk: the lab's own data, part by part, and the caches and tools beside it that grow as
 * the lab runs (the corpus, Gradle, the reference solvers' Docker VM and what Docker holds in it, other checkouts). Measured with `du` on a
 * background thread every [INTERVAL_MIN] minutes, at background priority, since a walk over a hundred gigabytes takes a
 * while; [latest] is the last measurement, null until the first is done.
 */
class DiskUsage(private val config: Config) {
    @Volatile var latest: DiskReport? = null
        private set

    private val home = System.getProperty("user.home")

    /** The places measured, each with a label; ones that do not exist are left out. */
    internal fun places(): List<Pair<String, Path>> {
        val data = config.dataDir
        val known = listOf("jobs", "work", "shared", "releases", "repo.git", "logs", "lab.db", "lab.db-wal")
        val rest = data.toFile().listFiles().orEmpty().map { it.name }.filter { it !in known && it != "lab.db-shm" }.sorted()
        return listOf(
            "job files" to data.resolve("jobs"),
            "job worktrees" to data.resolve("work"),
            "shared results cache" to data.resolve("shared"),
            "releases" to data.resolve("releases"),
            "repository mirror" to data.resolve("repo.git"),
            "logs" to data.resolve("logs"),
            "database" to data.resolve("lab.db"),
            "database log" to data.resolve("lab.db-wal"),
        ) + rest.map { "data: $it" to data.resolve(it) } + listOf(
            "corpus cache" to config.corpusDir,
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

    fun measure(): DiskReport {
        val places = places().filter { (_, path) -> Files.exists(path) }.map { (label, path) ->
            DiskPlace(label, path.toString(), du(path))
        }
        // The corpus sits under ~/.cache: the other caches are what is left of it.
        val corpus = places.firstOrNull { it.label == "corpus cache" }?.bytes ?: 0
        val adjusted = places.map { p ->
            if (p.label == "other caches" && config.corpusDir.startsWith(Path(p.path))) p.copy(bytes = p.bytes?.let { maxOf(0, it - corpus) }) else p
        }
        return DiskReport(
            System.currentTimeMillis(), freeBytes(config.dataDir), adjusted.sortedByDescending { it.bytes ?: -1 }, largestJobs(),
            docker(), DockerCleanup.last(config.dockerCleanupState),
        )
            .also { latest = it }
    }

    /** What Docker holds, by `docker system df`, when the lab uses it. */
    private fun docker(): List<DockerUsage> = if (!config.requireDocker) emptyList() else runCatching {
        val process = ProcessBuilder(Background.prefix + listOf("docker", "system", "df", "--format", "{{json .}}"))
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = process.inputStream.bufferedReader().readText()
        if (process.waitFor() == 0) DockerCleanup.parseDf(out) else emptyList()
    }.getOrDefault(emptyList())

    /** Each job directory's size in one `du` over them all, the [TOP_JOBS] largest. */
    private fun largestJobs(): List<Pair<Long, Long>> = runCatching {
        val jobs = config.dataDir.resolve("jobs").toFile()
        val dirs = jobs.listFiles { f -> f.isDirectory && f.name.toLongOrNull() != null }.orEmpty().map { it.path }
        if (dirs.isEmpty()) return@runCatching emptyList()
        val process = ProcessBuilder(Background.prefix + listOf("du", "-sk") + dirs).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor()
        lines.mapNotNull { line ->
            val (kib, path) = line.split('\t', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val id = path.substringAfterLast('/').toLongOrNull() ?: return@mapNotNull null
            kib.trim().toLongOrNull()?.let { id to it * KIB }
        }.sortedByDescending { it.second }.take(TOP_JOBS)
    }.getOrDefault(emptyList())

    /** Measure now and then every [INTERVAL_MIN] minutes, on a daemon thread. */
    fun start() {
        Executors.newSingleThreadScheduledExecutor { Thread(it, "disk-usage").apply { isDaemon = true } }
            .scheduleWithFixedDelay({ runCatching { measure() }.onFailure { println("disk usage: ${it.message}") } }, 0, INTERVAL_MIN, TimeUnit.MINUTES)
    }

    private fun du(path: Path): Long? = runCatching {
        val process = ProcessBuilder(Background.prefix + listOf("du", "-sk", path.toString()))
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        out.trim().substringBefore('\t').trim().toLong() * KIB
    }.getOrNull()

    private companion object {
        const val INTERVAL_MIN = 30L
        const val TOP_JOBS = 30
        const val KIB = 1024L
    }
}
