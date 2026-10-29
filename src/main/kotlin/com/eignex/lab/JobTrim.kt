package com.eignex.lab

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.name

/**
 * Trims ended jobs' directories down to what is worth keeping once their bulk is no longer looked at: a job's logs,
 * its commands' exits and termination reasons ([Termination]), its selections and each case's record (a `.json` file in
 * `cases/<i>`) stay, as do profiled cases' compilation reports and measurement manifests, and everything else goes, raw
 * solver output, case stdout and stderr, profiles' raw captures and `$OUT` among it. The results stay readable either way, since
 * every case's record is in the database too. A job is trimmed [maxAgeMs] after it ended, and sooner, largest first,
 * whenever the volume [jobsDir] is on has less than [targetFreeBytes] free ([freeBytes] reads that volume, not the data
 * directory's). A trimmed job gets a `trimmed` file saying when and how much, and is never trimmed again; its log says so
 * too. Queued and running jobs are never touched, and while [jobsDir] is missing, its volume not mounted, nothing is.
 *
 * A low disk leaves a job alone for [graceMs] after it ended, so a download soon after the end finds every file, unless
 * the disk has less than [criticalFreeBytes] free. A job a download holds ([Hold], [held]) is never trimmed, neither by
 * age nor on a low disk, critically low included: a hold is short and explicit, and lapses on its own, so it bounds how
 * long a job is kept, and the queue is held meanwhile ([DiskGuard]) so no new job fills the disk. Each run names the
 * held jobs it would otherwise have trimmed.
 */
class JobTrim(
    private val jobsDir: Path,
    /** Every ended job: its id and when it ended. */
    private val ended: () -> List<Pair<Long, Long>>,
    private val freeBytes: () -> Long,
    private val log: (Long, String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The jobs a download holds now; asked again just before each trim, so a hold taken meanwhile still counts. */
    private val held: () -> Set<Long> = { emptySet() },
    private val graceMs: Long = DEFAULT_GRACE_MS,
    private val criticalFreeBytes: Long = DEFAULT_CRITICAL_FREE_BYTES,
    /** Below this much free, ended jobs past their grace are trimmed early, largest first: `LAB_JOBS_TARGET_FREE_GB`. */
    private val targetFreeBytes: Long = TARGET_FREE_BYTES,
    /** How long after its end a job is trimmed whatever the free space: `LAB_TRIM_AGE_DAYS`. */
    private val maxAgeMs: Long = MAX_AGE_MS,
    /** The trim's own log: the runner's output. */
    private val report: (String) -> Unit = ::println,
) {
    /** Trim what is due now; returns the bytes freed. */
    fun run(): Long {
        if (!Files.isDirectory(jobsDir)) return 0
        val now = clock()
        val skipped = sortedSetOf<Long>()
        val candidates = ended().filter { (id, _) -> jobDir(id).let { Files.isDirectory(it) && !Files.exists(it.resolve(MARKER)) } }
        var freed = candidates.filter { (_, at) -> now - at >= maxAgeMs }
            .sumOf { (id, _) -> if (id in held()) 0L.also { skipped += id } else trim(id, "it ended over ${maxAgeMs / MS_PER_DAY} days ago") }
        if (freeBytes() < targetFreeBytes) {
            val (past, recent) = candidates.filter { (id, _) -> !Files.exists(jobDir(id).resolve(MARKER)) }
                .map { (id, at) -> Triple(id, at, removable(jobDir(id)).sumOf { size(it) }) }
                .filter { it.third > 0 }
                .sortedByDescending { it.third }
                .partition { (_, at, _) -> now - at >= graceMs }
            for ((id, _, _) in past) {
                if (freeBytes() >= targetFreeBytes) break
                if (id in held()) skipped += id else freed += trim(id, "the disk had under ${targetFreeBytes / BYTES_PER_GB} GB free")
            }
            // Jobs that ended within the grace go only when the disk is critically low, and then only until it is not.
            for ((id, _, _) in recent) {
                if (freeBytes() >= criticalFreeBytes) break
                if (id in held()) {
                    skipped += id
                } else {
                    freed += trim(id, "the disk had under ${criticalFreeBytes / BYTES_PER_GB} GB free, too little to wait out its grace")
                }
            }
        }
        if (skipped.isNotEmpty()) report("job trim: skipped held job${if (skipped.size == 1) "" else "s"} ${skipped.joinToString()}: held for download")
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
        const val MS_PER_DAY = 24 * 3_600_000L
        /** `LAB_TRIM_AGE_DAYS`'s default. */
        const val MAX_AGE_MS = 7 * MS_PER_DAY
        /** `LAB_JOBS_TARGET_FREE_GB`'s default, and the free space below which a Docker cleanup comes sooner. */
        const val TARGET_FREE_BYTES = 25 * BYTES_PER_GB
        const val INTERVAL_MIN = 30L
        /** `LAB_TRIM_GRACE_HOURS`'s default. */
        const val DEFAULT_GRACE_MS = 6 * 3_600_000L
        /** `LAB_TRIM_CRITICAL_FREE_GB`'s default, that of `LAB_MIN_FREE_GB`. */
        const val DEFAULT_CRITICAL_FREE_BYTES = 10 * BYTES_PER_GB
        /** How long a download's hold lasts, unless renewed: both its default and the longest one may ask for. */
        const val HOLD_MS = 2 * 3_600_000L
        private const val BYTES_PER_MB = 1_000_000L

        /**
         * Whether a trim keeps [relative], a path inside a job directory. A profiled case's compilation report and its
         * measurement manifest stay with its records, while its raw captures go: an analysis job then refuses it.
         */
        fun keeps(relative: Path): Boolean {
            val name = relative.name
            val reports = setOf(CompilationReports.REPORT, CompilationReports.TASKS, CliMeasurements.MANIFEST)
            // An archive analysis's reports, at their captures' paths in the archive.
            if (relative.nameCount >= 4 && relative.getName(0).toString() == CaptureArchives.ROOT) {
                return relative.parent.name.startsWith("profile-") && name in reports
            }
            return when (relative.nameCount) {
                1 -> name in setOf("job.log", "setup.log", "sha", MARKER, CompilationReports.ANALYSIS, CaptureArchives.MANIFEST,
                    com.eignex.lab.CaseChecks.RESULT) ||
                    name.endsWith(".exit") || name.endsWith(".termination") || (name.startsWith("selection-") && name.endsWith(".jsonl"))
                3 -> relative.getName(0).toString() == "cases" && name.endsWith(".json")
                4 -> relative.getName(0).toString() == "cases" && relative.getName(2).toString().startsWith("profile-") && name in reports
                else -> false
            }
        }
    }
}
