package com.eignex.lab.store

import com.eignex.lab.util.*

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Moves ended jobs' artifact directories from the hot store [hot] to the archive volume [archive].
 *
 * Active jobs write to [hot] (the internal SSD); an ended job's directory is copied to `archive/<job>`, then exchanged
 * atomically with a symlink to that copy, so readers resolve through the link without a gap. Files that appear in the
 * old directory after the copy (a late retry) are carried over before it is removed. A failure leaves the hot copy in
 * place; the next pass resumes, skipping archive files already complete. */
class ArtifactArchiver(
    private val hot: Path,
    private val archive: Path,
    private val ended: () -> List<Pair<Long, Long>>,
    private val pending: (Long) -> Boolean,
    private val graceMs: Long = 60 * 60_000L,
    private val swap: (Path, Path) -> Unit = MacRenameSwap::swap,
    private val flush: (Path) -> Unit = { if (MacVolumeSync.supported) MacVolumeSync.flush(it) {} },
    private val clock: () -> Long = ::now,
    private val log: (String) -> Unit = { System.err.println("artifact archive: $it") },
) {
    /** Archives eligible jobs, oldest first, at most [limit] per pass. Returns the jobs archived. */
    fun pass(limit: Int = 8): List<Long> {
        require(Files.isDirectory(archive)) { "artifact archive volume unavailable: $archive" }
        val at = clock()
        val candidates = ended().filter { (_, finished) -> at - finished >= graceMs }.sortedBy { it.second }.map { it.first }
            .filter { job -> hot.resolve(job.toString()).let { Files.isDirectory(it, NOFOLLOW_LINKS) } && !pending(job) }
        val archived = mutableListOf<Long>()
        for (job in candidates.take(limit)) {
            if (Thread.currentThread().isInterrupted) break
            try { archiveJob(job); archived += job }
            catch (failure: Exception) { log("job $job kept on the hot store: ${failure.javaClass.simpleName}: ${failure.message}") }
        }
        return archived
    }

    internal fun archiveJob(job: Long) {
        val source = hot.resolve(job.toString())
        check(Files.isDirectory(source, NOFOLLOW_LINKS)) { "not a hot directory" }
        // The job-scoped store is flat; anything nested is not ours to move, so leave the job alone.
        Files.list(source).use { entries -> check(entries.noneMatch { Files.isDirectory(it, NOFOLLOW_LINKS) }) { "nested directory in job store" } }
        val target = archive.resolve(job.toString())
        Files.createDirectories(target)
        copyMissing(source, target)
        flush(target)
        // Exchange the directory with a link to the archive copy in one step, then retire the old directory.
        val link = hot.resolve(".archive-link-$job")
        Files.deleteIfExists(link)
        Files.createSymbolicLink(link, target)
        swap(source, link)
        val retired = link // After the exchange this name holds the former hot directory.
        check(Files.isSymbolicLink(source) && Files.isDirectory(retired, NOFOLLOW_LINKS)) { "rename swap did not exchange paths" }
        if (copyMissing(retired, target) > 0) flush(target)
        deleteTree(retired)
        log("job $job archived to $target")
    }

    /** Copies complete files absent from [target] (or of a different size); lock files are recreated on demand. */
    private fun copyMissing(source: Path, target: Path): Int {
        var copied = 0
        Files.list(source).use { entries ->
            for (entry in entries.sorted().toList()) {
                val name = entry.fileName.toString()
                if (name.endsWith(".lock") || !Files.isRegularFile(entry, NOFOLLOW_LINKS)) continue
                val destination = target.resolve(name)
                if (Files.isRegularFile(destination, NOFOLLOW_LINKS) && Files.size(destination) == Files.size(entry)) continue
                val temporary = target.resolve(".archiving-$name")
                Files.copy(entry, temporary, REPLACE_EXISTING, COPY_ATTRIBUTES)
                Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
                copied++
            }
        }
        return copied
    }

    private fun deleteTree(path: Path) {
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}
