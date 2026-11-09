package com.eignex.lab.web

import com.eignex.lab.domain.*
import com.eignex.lab.execution.JobTrim
import com.eignex.lab.store.*
import com.eignex.lab.util.hashFile
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import java.util.Locale

/**
 * Accepted names belong to the result manifest, even when their projection's filesystem folds case. Outputs a cloud
 * worker left in the regional result store are listed with the size their result declared, without asking that store,
 * and read through [regional], which copies one here on its first read.
 */
internal class AcceptedArtifactFiles(private val config: Config, private val store: Store, private val artifacts: ArtifactStore,
                                     private val maxNames: Int = MAX_NAMES, private val regional: ArtifactStore? = null) {
    data class Artifact(val file: Path, val sha256: String)
    private data class Entry(val version: Int, val names: Map<String, String>, val complete: Boolean,
                             val from: String = "", val through: String? = null) {
        fun covers(name: String?) = complete || name != null && name >= from && (through == null || name <= through)
    }
    private val cache = LinkedHashMap<Long, Entry>(16, 0.75f, true)
    private var cachedNames = 0
    private val sizes = LinkedHashMap<Pair<Long, String>, Long>(16, 0.75f, true)
    init { require(maxNames in 1..MAX_NAMES) }

    private fun accepted(job: Long, wanted: String? = null): Map<String, String> {
        val version = store.execution.acceptedArtifactVersion(job)
        synchronized(cache) { cache[job]?.takeIf { it.version == version && it.covers(wanted) }?.let { return it.names } }
        val names = store.execution.acceptedArtifacts(job)
        val complete = names.size <= maxNames
        // An oversized job uses sorted windows instead of defeating the cache on every download.
        val window = if (complete) emptyList() else names.entries.filter { wanted == null || it.key >= wanted }
            .sortedBy { it.key }.take(maxNames + 1)
        val kept = if (complete) names else window.take(maxNames).associate { it.key to it.value }
        val through = if (window.size > maxNames) window[maxNames - 1].key else null
        synchronized(cache) {
            if ((cache[job]?.version ?: -1) <= version) {
                cachedNames -= cache.remove(job)?.names?.size ?: 0
                cache[job] = Entry(version, kept, complete, wanted ?: "", through); cachedNames += kept.size
                while (cache.size > MAX_JOBS || cachedNames > maxNames) {
                    val first = cache.entries.iterator().next()
                    cachedNames -= first.value.names.size; cache.remove(first.key)
                }
            }
        }
        return names
    }

    private fun visible(name: String, sha: String, trimmed: Boolean): Boolean {
        if (!sha.matches(SHA256)) return false
        val path = safeRelative(name)
        return !path.startsWith(JobFileNames.PREPARED_TREES) && (!trimmed || JobTrim.keeps(path))
    }
    private fun archive(name: String) = name.startsWith("preparation/") && name.endsWith(".zip")
    private fun folded(name: String) = Normalizer.normalize(name, Normalizer.Form.NFC).uppercase(Locale.ROOT).lowercase(Locale.ROOT)

    /** One immutable acceptance/trim view is used for both physical size selection and the logical merge. */
    class Listing internal constructor(internal val job: Long, internal val names: Map<String, String>,
                                      internal val visibleNames: Map<String, String>, internal val trimmed: Boolean,
                                      val replaced: Set<String>)

    fun listing(job: Long): Listing {
        val names = accepted(job)
        val trimmed = Files.exists(config.jobDir(job).resolve(JobFileNames.TRIMMED))
        val visibleNames = names.filter { (name, sha) -> visible(name, sha, trimmed) }
        return Listing(job, names, visibleNames, trimmed, visibleNames.keys.filterNot(::archive).toSet())
    }

    fun entries(job: Long, projected: List<FileEntry>): List<FileEntry> = entries(listing(job), projected)

    fun entries(listing: Listing, projected: List<FileEntry>): List<FileEntry> {
        val job = listing.job
        val names = listing.names
        val visibleNames = listing.visibleNames
        val trimmed = listing.trimmed
        val root = config.jobDir(job)
        val aliases = names.keys.groupBy(::folded)
        val physical = projected.filter { file ->
            if (trimmed && !JobTrim.keeps(safeRelative(file.path))) return@filter false
            if (file.path in names) file.path in visibleNames
            else {
                // Only suppress an alias when the filesystem says it is the same file. Case-sensitive volumes
                // can legitimately hold an unrelated legacy file with a case-equivalent name.
                val candidates = aliases[folded(file.path)].orEmpty()
                if (candidates.isEmpty()) return@filter true
                val path = jobFile(root, file.path.split('/'))
                candidates.none { name ->
                    val accepted = jobFile(root, name.split('/'))
                    path != null && accepted != null && runCatching { Files.isSameFile(path, accepted) }.getOrDefault(false)
                }
            }
        }
        val wanted = visibleNames.filterKeys { !archive(it) }
        val hashes = wanted.values.toSet()
        val known = synchronized(sizes) { hashes.mapNotNull { sha -> sizes[job to sha]?.let { sha to it } }.toMap() }
        val missing = hashes - known.keys
        val local = try { if (missing.isEmpty()) emptyMap() else artifacts.sizes(job, missing) }
            catch (failure: InterruptedException) { throw failure }
            catch (_: Exception) { emptyMap() }
        // The declared size of a regional output, checked when its result was accepted; the store need not answer for a listing.
        val found = if ((missing - local.keys).isEmpty()) local
            else store.execution.acceptedRegionalBlobs(job).filterKeys { it in missing } + local
        synchronized(sizes) {
            found.forEach { (sha, bytes) -> sizes[job to sha] = bytes }
            while (sizes.size > MAX_NAMES) sizes.remove(sizes.keys.first())
        }
        val logical = wanted.map { (name, sha) ->
            val bytes = known[sha] ?: found[sha] ?: FileEntry.UNKNOWN_BYTES
            FileEntry(name, bytes, sha)
        }
        return (physical.associateBy { it.path } + logical.associateBy { it.path }).values.sortedBy { it.path }
    }

    fun resolve(job: Long, segments: List<String>): Artifact? {
        val projected = jobFile(config.jobDir(job), segments) // Keep the existing request-path and symlink checks.
        val name = segments.joinToString("/")
        val trimmed = Files.exists(config.jobDir(job).resolve(JobFileNames.TRIMMED))
        if (trimmed && !JobTrim.keeps(safeRelative(name))) throw ApiError.NotFound("no such file")
        val sha = accepted(job, name)[name] ?: return null
        if (!visible(name, sha, trimmed)) throw ApiError.NotFound("no such file")
        if (archive(name)) return null // Regional archives and native exports have their own resolver.
        val file = try { artifacts.file(job, sha) } catch (_: Exception) {
            // A result may still be draining from the durable journal, or its older CAS may have been collected.
            // A projection is usable only if it contains exactly the accepted bytes, never a folded sibling's bytes.
            projected?.takeIf { runCatching { hashFile(it) == sha }.getOrDefault(false) }
                ?: fetchRegional(job, sha)
                ?: throw ApiError.Unavailable("accepted artifact unavailable")
        }
        return Artifact(file, sha)
    }

    /** An accepted output held only in the regional result store, copied here and checked by its hash on this first read. */
    private fun fetchRegional(job: Long, sha: String): Path? {
        val store = regional ?: return null
        if (sha !in this.store.execution.acceptedRegionalBlobs(job)) return null
        return try { store.file(job, sha) } catch (failure: Exception) {
            System.err.println("job $job regional result $sha unavailable: ${failure.javaClass.simpleName}: ${failure.message}")
            throw ApiError.Unavailable("accepted artifact is kept in the regional result store and could not be fetched from it; the store may have expired it")
        }
    }

    companion object {
        private const val MAX_JOBS = 32
        private const val MAX_NAMES = 65536
    }
}
