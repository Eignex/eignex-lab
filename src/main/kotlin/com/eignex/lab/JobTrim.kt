package com.eignex.lab

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.name

/**
 * Trims ended jobs' directories down to what is worth keeping once their bulk is no longer looked at: a job's logs,
 * its commands' exits, its selections and each case's record (a `.json` file in `cases/<i>`) stay, and everything else goes,
 * raw solver output, case stdout and stderr, profiles and `$OUT` among it. The results stay readable either way, since
 * every case's record is in the database too. A job is trimmed [MAX_AGE_MS] after it ended, and sooner, largest
 * first, whenever the disk has less than [TARGET_FREE_BYTES] free. A trimmed job gets a `trimmed` file saying when and
 * how much, and is never trimmed again; its log says so too. Queued and running jobs are never touched.
 */
class JobTrim(
    private val jobsDir: Path,
    /** Every ended job: its id and when it ended. */
    private val ended: () -> List<Pair<Long, Long>>,
    private val freeBytes: () -> Long,
    private val log: (Long, String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Trim what is due now; returns the bytes freed. */
    fun run(): Long {
        val now = clock()
        val candidates = ended().filter { (id, _) -> jobDir(id).let { Files.isDirectory(it) && !Files.exists(it.resolve(MARKER)) } }
        var freed = candidates.filter { (_, at) -> now - at >= MAX_AGE_MS }.sumOf { (id, _) -> trim(id, "it ended over a week ago") }
        if (freeBytes() < TARGET_FREE_BYTES) {
            val rest = candidates.filter { (id, _) -> !Files.exists(jobDir(id).resolve(MARKER)) }
                .map { (id, _) -> id to removable(jobDir(id)).sumOf { size(it) } }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
            for ((id, _) in rest) {
                if (freeBytes() >= TARGET_FREE_BYTES) break
                freed += trim(id, "the disk had under ${TARGET_FREE_BYTES / BYTES_PER_GB} GB free")
            }
        }
        return freed
    }

    /** Run [run] now and then every [INTERVAL_MIN] minutes, on a daemon thread. */
    fun start() {
        Executors.newSingleThreadScheduledExecutor { Thread(it, "job-trim").apply { isDaemon = true } }
            .scheduleWithFixedDelay({ runCatching { run() }.onFailure { println("job trim: ${it.message}") } }, 1, INTERVAL_MIN, TimeUnit.MINUTES)
    }

    private fun jobDir(id: Long) = jobsDir.resolve(id.toString())

    private fun trim(id: Long, why: String): Long {
        val dir = jobDir(id)
        val files = removable(dir)
        val bytes = files.sumOf { size(it) }
        files.forEach { Files.deleteIfExists(it) }
        // Directories the removed files leave empty go too, deepest first.
        Files.walk(dir).use { paths -> paths.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && it != dir }.toList() }
            .sortedByDescending { it.nameCount }
            .forEach { runCatching { Files.delete(it) } }
        Files.writeString(dir.resolve(MARKER), "${clock()} $bytes\n")
        log(id, "trimmed to its logs and records because $why: ${bytes / BYTES_PER_MB} MB freed")
        return bytes
    }

    private fun size(path: Path): Long = runCatching { Files.size(path) }.getOrDefault(0)

    /** The files of job directory [dir] a trim removes: everything but what [keeps] keeps. Links are removed, never followed. */
    internal fun removable(dir: Path): List<Path> = Files.walk(dir).use { paths ->
        paths.filter { !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && !keeps(dir.relativize(it)) }.toList()
    }

    internal companion object {
        const val MARKER = "trimmed"
        const val MAX_AGE_MS = 7 * 24 * 3_600_000L
        const val TARGET_FREE_BYTES = 25 * BYTES_PER_GB
        const val INTERVAL_MIN = 30L
        private const val BYTES_PER_MB = 1_000_000L

        /** Whether a trim keeps [relative], a path inside a job directory. */
        fun keeps(relative: Path): Boolean {
            val name = relative.name
            return when (relative.nameCount) {
                1 -> name in setOf("job.log", "setup.log", "sha", MARKER) || name.endsWith(".exit") ||
                    (name.startsWith("selection-") && name.endsWith(".jsonl"))
                3 -> relative.getName(0).toString() == "cases" && name.endsWith(".json")
                else -> false
            }
        }
    }
}
