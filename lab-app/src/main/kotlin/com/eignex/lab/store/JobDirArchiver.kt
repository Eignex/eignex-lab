package com.eignex.lab.store

import com.eignex.lab.domain.JobFileNames
import com.eignex.lab.util.*

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Moves ended jobs' directories from [hot] (the internal SSD, `LAB_JOBS`) to [archive] (the spinning volume,
 * `LAB_JOBS_ARCHIVE`), so active jobs write only to the fast volume and the archive only receives finished jobs, in bulk.
 *
 * A job's directory is resolved hot first, archived otherwise ([com.eignex.lab.domain.DataPaths.jobDir]), so there is no
 * link and no gap: the tree is copied to `archive/<job>` while readers keep using the hot copy, then the hot directory is
 * renamed out of the way in one step, after which every reader resolves the archive copy. Files a late writer added to
 * the renamed directory are carried over before it is removed. A failure leaves the hot copy in place; the next pass
 * resumes, skipping archive files already complete.
 */
class JobDirArchiver(
    private val hot: Path,
    private val archive: Path,
    private val ended: () -> List<Pair<Long, Long>>,
    private val graceMs: Long = 60 * 60_000L,
    /** Whether the hot volume is short of space: ended jobs then leave it at once instead of after [graceMs]. */
    private val urgent: () -> Boolean = { false },
    private val flush: (Path) -> Unit = { if (MacVolumeSync.supported) MacVolumeSync.flush(it) {} },
    private val clock: () -> Long = ::now,
    private val log: (String) -> Unit = { System.err.println("job archive: $it") },
) {
    /** Archives eligible jobs, oldest first, at most [limit] per pass. Returns the jobs archived. */
    fun pass(limit: Int = 8): List<Long> {
        require(Files.isDirectory(archive)) { "job archive volume unavailable: $archive" }
        val at = clock()
        val grace = if (urgent()) 0L else graceMs
        val candidates = ended().filter { (_, finished) -> at - finished >= grace }.sortedBy { it.second }.map { it.first }
            .filter { job -> Files.isDirectory(hot.resolve(job.toString()), NOFOLLOW_LINKS) }
        val archived = mutableListOf<Long>()
        for (job in candidates.take(limit)) {
            if (Thread.currentThread().isInterrupted) break
            try { archiveJob(job); archived += job }
            catch (failure: Exception) { log("job $job kept on the hot volume: ${failure.javaClass.simpleName}: ${failure.message}") }
        }
        return archived
    }

    internal fun archiveJob(job: Long) {
        val source = hot.resolve(job.toString())
        check(Files.isDirectory(source, NOFOLLOW_LINKS)) { "not a hot directory" }
        val target = archive.resolve(job.toString())
        Files.createDirectories(target)
        copyMissing(source, target)
        flush(target)
        // One rename takes the hot directory away: from here on every reader and writer resolves the archive copy.
        val retired = hot.resolve(".archived-$job")
        if (Files.exists(retired, NOFOLLOW_LINKS)) deleteTree(retired, ignoreMissing = true)
        Files.move(source, retired, ATOMIC_MOVE)
        if (copyMissing(retired, target) > 0) flush(target)
        deleteTree(retired, ignoreMissing = true)
        log("job $job archived to $target")
    }

    /** Copies regular files absent from [target] (or of a different size), retaining empty directories. Links and lab
     * locks are omitted; sealed snapshot files, including consumer lock files, are retained. Returns changed entries. */
    private fun copyMissing(source: Path, target: Path): Int {
        var copied = 0
        val files = Files.walk(source).use { paths -> paths.sorted().toList() }
        for (entry in files) {
            val relative = source.relativize(entry)
            val destination = target.resolve(relative.toString())
            if (Files.isDirectory(entry, NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(destination, NOFOLLOW_LINKS)) { Files.createDirectories(destination); copied++ }
                continue
            }
            if (!Files.isRegularFile(entry, NOFOLLOW_LINKS)) continue
            val name = entry.fileName.toString()
            val snapshot = relative.startsWith(JobFileNames.PREPARED_TREES) && relative.nameCount > 4 && relative.getName(3).toString() == "tree"
            if (name.endsWith(".lock") && !snapshot) continue
            if (Files.isRegularFile(destination, NOFOLLOW_LINKS) && Files.size(destination) == Files.size(entry)) continue
            Files.createDirectories(destination.parent)
            val temporary = destination.resolveSibling(".archiving-$name")
            Files.copy(entry, temporary, REPLACE_EXISTING, COPY_ATTRIBUTES)
            Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            copied++
        }
        return copied
    }
}
