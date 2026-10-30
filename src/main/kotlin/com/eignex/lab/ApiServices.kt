package com.eignex.lab

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the API's routes use, built once when it starts: the store, the caches its pages read from, the services
 * that act on jobs, experiments and schedules, and the background threads that keep the caches warm. [close] stops
 * every thread; the API closes it when the application stops.
 */
class ApiServices(val config: Config, val store: Store, val host: HostReport) : AutoCloseable {
    val trends = TrendCache()
    internal val references = ReferenceCache(store)

    /** The finished runs the queue page's estimates learn from, reread at most once a minute. */
    val estimates = EstimateHistory(store, config.cores)

    val disk = DiskUsage(config, active = { store.active(withCommands = false).map { it.id }.toSet() })

    /** The Nomad capacity settings, read again only when their file changes. */
    val capacitySettings = FileCache({ config.nomadCapacitySettings }, CapacitySettings::read)

    private val githubToken = FileCache({ config.githubTokenFile }) { it.toFile().readText().trim() }

    // Only branches are asked about here, from GitHub; the runner checks each commit on its mirror when it plans.
    private val trusts = ConcurrentHashMap<String, Trust>()

    /** Who may push to [repository]'s branches the lab runs. */
    fun trust(repository: String): Trust = trusts.computeIfAbsent(repository) {
        Trust(config.trustedUsers, Repositories.source(repository), git = { 1 to "" },
            github = { path -> Trust.githubApi(githubToken.get())(path) })
    }

    val placement = PlacementPolicy(config) { store.schedules.all() }
    val signals = RunnerSignals(config)
    val experiments = ExperimentService(this)
    val jobs = JobService(this)
    val schedules = ScheduleService(this)

    /** Start the disk measurements and compute the pages' slow reads before anyone asks, so the first load is quick. */
    fun start() {
        disk.start()
        references.warm()
        Trend.warm(store, trends)
    }

    override fun close() {
        disk.close()
        trends.close()
        references.close()
    }
}

/**
 * A file's contents as [parse] reads them, read again only when the file changes (its modification time or size); null
 * while [path] names none, or the file is missing or does not parse.
 */
class FileCache<T : Any>(private val path: () -> Path?, private val parse: (Path) -> T) {
    private class Kept<T>(val path: Path, val stamp: Pair<Long, Long>, val value: T?)

    @Volatile private var kept: Kept<T>? = null

    fun get(): T? {
        val file = path() ?: return null
        val stamp = runCatching { Files.getLastModifiedTime(file).toMillis() to Files.size(file) }.getOrNull() ?: return null
        kept?.takeIf { it.path == file && it.stamp == stamp }?.let { return it.value }
        val value = runCatching { parse(file) }.getOrNull()
        kept = Kept(file, stamp, value)
        return value
    }
}
