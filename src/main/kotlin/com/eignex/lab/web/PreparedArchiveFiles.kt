package com.eignex.lab.web

import com.eignex.lab.domain.*
import com.eignex.lab.execution.JobTrim
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import java.nio.file.Files
import java.nio.file.Path

/** Accepted preparation archives remain listable without copying regional bytes onto the coordinator. */
internal class PreparedArchiveFiles(private val config: Config, private val store: Store, private val artifacts: ArtifactStore,
                                   private val nativeTrees: () -> NativePreparedTrees? = { NativePreparedTrees.environment() }) {
    data class Archive(val file: Path, val sha256: String)

    private fun accepted(job: Long): Map<String, String> {
        // A fetch must not resurrect bulk that the existing retention policy has already trimmed.
        if (Files.exists(config.jobDir(job).resolve(JobFileNames.TRIMMED))) return emptyMap()
        return store.execution.acceptedPreparation(job)?.artifacts.orEmpty().filter { (name, sha) ->
            name.startsWith("preparation/") && name.endsWith(".zip") && sha.matches(SHA256)
        }.also { archives -> archives.keys.forEach { safeRelative(it) } }
    }

    /**
     * The accepted archives as listed files. One whose size cannot be read just now (its regional store unreachable)
     * is listed with unknown size rather than failing the whole listing: every other file of the job stays fetchable,
     * and a fetch of that archive reports its own failure.
     */
    fun entries(job: Long): List<FileEntry> = accepted(job).map { (name, sha) ->
        val projected = jobFile(config.jobDir(job), name.split('/'))
        val bytes = try { projected?.let(Files::size) ?: artifacts.size(job, sha) } catch (failure: Exception) {
            System.err.println("job $job archive $name size unavailable: ${failure.javaClass.simpleName}: ${failure.message}")
            FileEntry.UNKNOWN_BYTES
        }
        FileEntry(name, bytes, sha)
    }

    fun resolve(job: Long, segments: List<String>): Archive? {
        if (segments.firstOrNull() != "preparation" || segments.lastOrNull()?.endsWith(".zip") != true) return null
        val projected = jobFile(config.jobDir(job), segments) // Validate request paths before looking up any blob.
        val sha = accepted(job)[segments.joinToString("/")]
        if (sha == null) {
            if (Files.exists(config.jobDir(job).resolve(JobFileNames.TRIMMED))) return null
            val snapshot = store.execution.acceptedPreparation(job)?.bundles?.singleOrNull {
                it.tree != null && segments == listOf("preparation", "${it.commit}.zip")
            } ?: return null
            // An explicit export stays available without making every ordinary result fetch create a ZIP.
            val hold = store.holds.take(job, JobTrim.HOLD_MS)
            try { return available {
                val (file, digest) = requireNotNull(nativeTrees()).archiveIdentity(snapshot)
                Archive(file, digest)
            } } finally { store.holds.release(job, hold.id) }
        }
        val file = available {
            projected?.also { check(hashFile(it) == sha) { "archive checksum mismatch" } } ?: artifacts.file(job, sha)
        }
        return Archive(file, sha)
    }

    private fun <T> available(read: () -> T): T = try { read() } catch (failure: Exception) {
        System.err.println("accepted preparation archive unavailable: ${failure.javaClass.simpleName}: ${failure.message}")
        throw ApiError.Unavailable("accepted preparation archive unavailable")
    }
}
