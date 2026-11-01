package com.eignex.lab.web

import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import java.nio.file.Files
import java.nio.file.Path

/** Accepted preparation archives remain listable without copying regional bytes onto the coordinator. */
internal class PreparedArchiveFiles(private val config: Config, private val store: Store, private val artifacts: ArtifactStore) {
    data class Archive(val file: Path, val sha256: String)

    private fun accepted(job: Long): Map<String, String> {
        // A fetch must not resurrect bulk that the existing retention policy has already trimmed.
        if (Files.exists(config.jobDir(job).resolve(JobFileNames.TRIMMED))) return emptyMap()
        return store.execution.acceptedPreparation(job)?.artifacts.orEmpty().filter { (name, sha) ->
            name.startsWith("preparation/") && name.endsWith(".zip") && sha.matches(SHA256)
        }.also { archives -> archives.keys.forEach { safeRelative(it) } }
    }

    fun entries(job: Long): List<FileEntry> = accepted(job).map { (name, sha) ->
        val projected = jobFile(config.jobDir(job), name.split('/'))
        val bytes = available { projected?.let(Files::size) ?: artifacts.size(job, sha) }
        FileEntry(name, bytes, sha)
    }

    fun resolve(job: Long, segments: List<String>): Archive? {
        if (segments.firstOrNull() != "preparation" || segments.lastOrNull()?.endsWith(".zip") != true) return null
        val projected = jobFile(config.jobDir(job), segments) // Validate request paths before looking up any blob.
        val sha = accepted(job)[segments.joinToString("/")] ?: return null
        val file = available {
            projected?.also { check(hashFile(it) == sha) { "archive checksum mismatch" } } ?: artifacts.file(job, sha)
        }
        return Archive(file, sha)
    }

    private fun <T> available(read: () -> T): T = try { read() } catch (_: Exception) {
        throw ApiError.Unavailable("accepted preparation archive unavailable")
    }
}
