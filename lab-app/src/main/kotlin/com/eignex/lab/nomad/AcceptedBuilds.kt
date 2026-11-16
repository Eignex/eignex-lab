package com.eignex.lab.nomad

import com.eignex.lab.domain.*
import com.eignex.lab.util.*
import com.eignex.lab.worker.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/**
 * Accepted cloud build archives by build recipe ([buildRecipeKey]), so that a later preparation of the same build reuses
 * one ([ReusableBuild]) instead of compiling it again on another node. A bounded, disposable index on the coordinator's
 * disk: it holds no archive bytes, only hints the worker checks against its own build identity and the archive's hashes.
 * Losing it, or a stale entry, costs one build and never a job.
 *
 * The regional store expires build archives a fixed time after they were written ([MAX_AGE_MS] stays well within it),
 * so an entry is offered only for a while after its archive was first accepted, however often it is reused since.
 */
internal class AcceptedBuilds(private val root: Path, private val maxAgeMs: Long = MAX_AGE_MS, private val perKey: Int = 4) {
    @Serializable internal data class Entry(val hint: ReusableBuild, val job: Long, val firstAcceptedAt: Long, val acceptedAt: Long)

    init { require(maxAgeMs > 0 && perKey > 0) }
    private var maintainedAt = 0L

    /** The most recently accepted archive of [key]'s build that is still young enough to reuse. */
    fun find(key: String, at: Long = now()): ReusableBuild? = read(key).firstOrNull { fresh(it, at) }?.hint

    /**
     * Records [job]'s accepted builds: each build that [preparation] reports for a source bundle of [recipe], whose
     * archive is the accepted bundle of that commit and is held in the regional store ([regional]).
     */
    fun remember(job: Long, recipe: WorkManifest, preparation: PreparationResult, regional: (String) -> Boolean, at: Long = now()) {
        for (built in preparation.builds) {
            val source = recipe.bundles.firstOrNull { it.commit == built.commit && it.os == built.os && it.arch == built.arch && it.build != null } ?: continue
            val bundle = preparation.bundles.singleOrNull { it.commit == built.commit } ?: continue
            if (bundle.tree != null || bundle.build != null || preparation.artifacts[preparationArtifact(bundle)] != bundle.sha256) continue
            if (!runCatching { regional(bundle.sha256) }.getOrDefault(false)) continue
            val key = buildRecipeKey(source)
            val previous = read(key).filter { fresh(it, at) }
            val first = previous.filter { it.hint.bundle.sha256 == bundle.sha256 }.minOfOrNull { it.firstAcceptedAt } ?: at
            val entry = Entry(ReusableBuild(built.identity, bundle, built.planSource), job, first, at)
            write(key, (listOf(entry) + previous.filter { it.hint.identity != built.identity }).take(perKey))
        }
        maintain(at)
    }

    private fun fresh(entry: Entry, at: Long) = at - entry.firstAcceptedAt in 0..maxAgeMs

    private fun file(key: String): Path { require(SHA256.matches(key)); return root.resolve("$key.json") }

    private fun read(key: String): List<Entry> {
        val file = file(key)
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) return emptyList()
        return runCatching { Json.decodeFromString<List<Entry>>(Files.readString(file)) }.getOrDefault(emptyList())
    }

    private fun write(key: String, entries: List<Entry>) {
        Files.createDirectories(root)
        file(key).writeAtomically(Json.encodeToString(entries), durable = false)
    }

    /** Drops the files no entry of which may be offered any more, at most hourly. */
    private fun maintain(at: Long) {
        if (at - maintainedAt < 3_600_000L || !Files.isDirectory(root)) return
        maintainedAt = at
        Files.list(root).use { files -> files.toList() }.forEach { file ->
            runCatching { if (at - Files.getLastModifiedTime(file).toMillis() > maxAgeMs) Files.deleteIfExists(file) }
        }
    }

    companion object {
        /** A week: the regional store keeps build archives for 30 days from their upload. */
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
