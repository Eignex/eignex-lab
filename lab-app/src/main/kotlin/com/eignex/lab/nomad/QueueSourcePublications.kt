package com.eignex.lab.nomad

import com.eignex.lab.domain.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Resource/priority edits remain live; only the inputs actually passed to source publication identify its result. */
@Serializable
internal data class QueueSourceKey(val job: Long, val experiment: ExperimentSpec,
    val targets: List<NomadQueueTarget>, val settings: NomadQueueSettings)

@Serializable
private data class PublishedSource(val arms: List<PlannedArm>, val bundles: List<WorkerBundle>)

/** Published bytes remain in the durable artifact store; only admission metadata is retained here. */
internal class QueueSourcePublications(private val maxEntries: Int = 256, private val maxBytes: Long = 8L * 1024 * 1024) {
    private data class Entry(val source: Pair<List<PlannedArm>, List<WorkerBundle>>, val bytes: Long)
    private val entries = LinkedHashMap<QueueSourceKey, Entry>(16, .75f, true)
    private var bytes = 0L

    init { require(maxEntries > 0 && maxBytes > 0) }

    fun get(key: QueueSourceKey): Pair<List<PlannedArm>, List<WorkerBundle>>? {
        removeIf { it.job == key.job && it != key }
        return entries[key]?.source
    }

    fun put(key: QueueSourceKey, source: Pair<List<PlannedArm>, List<WorkerBundle>>) {
        forget(key.job)
        // Charge serialized characters conservatively plus collection/object overhead; never retain payload bytes.
        val charge = 2L * (Json.encodeToString(key).length + Json.encodeToString(PublishedSource(source.first, source.second)).length) +
            1024L + source.first.size * 512L + source.second.sumOf { 512L + it.files.size * 256L + it.tree?.directories.orEmpty().size * 128L }
        if (charge > maxBytes) return
        while (entries.size >= maxEntries || bytes + charge > maxBytes) removeIfFirst()
        entries[key] = Entry(source, charge)
        bytes += charge
    }

    fun forget(job: Long) = removeIf { it.job == job }

    fun retain(jobs: List<Job>) {
        val active = jobs.associateBy { it.id }
        removeIf { key -> active[key.job]?.let { it.status.isActive && !it.paused && !it.cancelRequested && it.experiment == key.experiment } != true }
    }

    private fun removeIfFirst() {
        val iterator = entries.iterator()
        bytes -= iterator.next().value.bytes
        iterator.remove()
    }

    private fun removeIf(predicate: (QueueSourceKey) -> Boolean) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val (key, entry) = iterator.next()
            if (predicate(key)) { bytes -= entry.bytes; iterator.remove() }
        }
    }
}
