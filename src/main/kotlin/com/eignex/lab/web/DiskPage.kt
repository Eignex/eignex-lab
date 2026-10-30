package com.eignex.lab.web

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.nomad.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import com.eignex.lab.worker.*

/** What takes the lab machine's disk, largest first, as [DiskUsage] last measured it, and the [database] as it is now. */
internal fun diskPage(
    report: DiskReport?,
    holds: List<Hold> = emptyList(),
    database: DatabaseReport? = null,
    volumes: List<VolumeReport> = emptyList(),
    /** Why the runner holds its queue ([Volumes.hold]); null when it does not. */
    held: String? = null,
): String = buildString {
    append(head("disk · eignex lab", live = false))
    append("<header><h1>Disk</h1></header>")
    if (held != null) append("<p class=\"FAILED\">The queue is held: ${esc(held)}</p>")
    if (volumes.isNotEmpty()) append(volumesSection(volumes))
    if (database != null) append(databaseSection(database))
    if (holds.isNotEmpty()) {
        append("<h2>Held for download</h2><p class=\"muted\">The trim leaves these jobs' files alone until each hold is ")
        append("released or lapses, however low the disk.</p>")
        append("<div class=\"scroll\"><table><tr><th>job</th><th>hold</th><th>taken</th><th>until</th></tr>")
        for (hold in holds) {
            append("<tr><td><a href=\"/jobs/${hold.job}\">${hold.job}</a></td><td><code>${esc(hold.id.take(8))}</code></td>")
            append("<td>${ago(hold.createdAt)}</td><td>${clockTime(hold.expiresAt)}</td></tr>")
        }
        append("</table></div>")
    }
    if (report == null) {
        append("<p class=\"muted\">Not measured yet: the first measurement runs 10 minutes after the API starts and can take a while.</p></body></html>")
        return@buildString
    }
    append("<h2>What uses it</h2><p class=\"muted\">${size(report.freeBytes)} free on the data directory's volume")
    append(" · measured ${ago(report.at)}, every 6 hours; the corpus and what is on another volume (the external drive) ")
    append("once a day, finished jobs once</p>")
    append("<div class=\"scroll\"><table><tr><th>what</th><th>path</th><th class=\"num\">size</th><th>as of</th></tr>")
    for (place in report.places) {
        append("<tr><td>${esc(place.label)}</td><td><code>${esc(place.path)}</code></td>")
        append("<td class=\"num\">${place.bytes?.let(::size) ?: "?"}</td><td>${ago(place.at ?: report.at)}</td></tr>")
    }
    append("</table></div>")
    if (report.largestJobs.isNotEmpty()) {
        append("<h2>Largest jobs</h2><div class=\"scroll\"><table><tr><th>job</th><th class=\"num\">size</th></tr>")
        for ((id, bytes) in report.largestJobs) {
            append("<tr><td><a href=\"/jobs/$id/files\">$id</a></td><td class=\"num\">${size(bytes)}</td></tr>")
        }
        append("</table></div>")
    }
    append(dockerSection(report))
    append("</body></html>")
}

/** The disk page's volumes: each file system the lab writes to, its free and total space, and the lab's directories on it. */
private fun volumesSection(volumes: List<VolumeReport>): String = buildString {
    append("<h2>Volumes</h2><div class=\"scroll\"><table><tr><th>volume</th><th class=\"num\">free</th><th class=\"num\">total</th>")
    append("<th>lab directories</th></tr>")
    for (volume in volumes) {
        append("<tr><td><code>${esc(volume.mount)}</code>")
        volume.problem?.let { append(" <span class=\"FAILED\">${esc(it)}</span>") }
        append("</td><td class=\"num\">${volume.freeBytes?.let(::size) ?: "?"}</td>")
        append("<td class=\"num\">${volume.totalBytes?.let(::size) ?: "?"}</td><td>")
        append(volume.paths.joinToString("<br>") { "${esc(it.label)} (${esc(it.name)}) <code>${esc(it.path)}</code>" })
        append("</td></tr>")
    }
    append("</table></div>")
}

/** The disk page's database part: the file's size and free pages, its kept provenances and the [DbCompaction]'s progress. */
private fun databaseSection(database: DatabaseReport): String = buildString {
    val compaction = database.compaction
    append("<h2>Database</h2><p>${size(database.bytes)}, ${size(database.freeBytes)} of it free pages a vacuum gives back")
    append(" · ${database.provenances} distinct build provenances kept once</p>")
    val progress = when {
        compaction.migratedAt != null -> "done ${ago(compaction.migratedAt)}"
        compaction.startedAt == null -> "not started"
        else -> "${compaction.scanned} of ${compaction.total ?: "?"} case records" +
            (compaction.total?.takeIf { it > 0 }?.let { " (${minOf(100, compaction.scanned * 100 / it)}%)" } ?: "")
    }
    append("<ul><li>compaction: ${esc(progress)}")
    if (compaction.changed > 0) {
        append(", ${compaction.changed} records ${size(compaction.bytesBefore)} → ${size(compaction.bytesAfter)}")
    }
    append("</li>")
    compaction.vacuum?.let { append("<li>vacuum ${ago(compaction.vacuumAt)}: ${esc(it)}</li>") }
    append("</ul>")
}

/** The disk page's Docker part: what Docker holds and what the last [DockerCleanup] did. Empty when neither is known. */
private fun dockerSection(report: DiskReport): String = buildString {
    val cleanup = report.dockerCleanup
    if (report.docker.isEmpty() && cleanup == null) return@buildString
    append("<h2>Docker</h2>")
    if (report.docker.isNotEmpty()) {
        append("<div class=\"scroll\"><table><tr><th>what</th><th class=\"num\">size</th><th class=\"num\">reclaimable</th></tr>")
        for (usage in report.docker) {
            append("<tr><td>${esc(usage.type)}</td><td class=\"num\">${usage.bytes?.let(::size) ?: "?"}</td>")
            append("<td class=\"num\">${usage.reclaimableBytes?.let(::size) ?: "?"}</td></tr>")
        }
        append("</table></div>")
    }
    if (cleanup == null) {
        append("<p class=\"muted\">No cleanup has run yet.</p>")
        return@buildString
    }
    val before = DockerCleanup.total(cleanup.dockerBefore)
    val after = DockerCleanup.total(cleanup.dockerAfter)
    append("<p>Last cleanup ${ago(cleanup.at)}, because ${esc(cleanup.why)}")
    if (before != null && after != null) append(": Docker ${size(before)} → ${size(after)}")
    if (cleanup.hostDiskBefore != null && cleanup.hostDiskAfter != null) {
        append(", Colima's disks on the host ${size(cleanup.hostDiskBefore)} → ${size(cleanup.hostDiskAfter)}")
    }
    append(".</p><ul>")
    append("<li>tags removed: ${if (cleanup.removedTags.isEmpty()) "none" else cleanup.removedTags.joinToString { "<code>${esc(it)}</code>" }}</li>")
    append("<li>dangling images pruned: ${cleanup.prunedImages}</li>")
    append("<li>build cache reclaimed: ${esc(cleanup.buildCacheReclaimed ?: "?")}</li>")
    cleanup.trimmed?.let { append("<li>fstrim: ${esc(it)}</li>") }
    for (note in cleanup.notes) append("<li class=\"muted\">${esc(note)}</li>")
    append("</ul>")
}
