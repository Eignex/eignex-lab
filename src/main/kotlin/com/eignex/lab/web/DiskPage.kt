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
): Html = buildHtml {
    append(head("disk · eignex lab", live = false))
    append(Html("<header><h1>Disk</h1></header>"))
    if (held != null) append(Html("<p class=\"FAILED\">The queue is held: ") + esc(held) + Html("</p>"))
    if (volumes.isNotEmpty()) append(volumesSection(volumes))
    if (database != null) append(databaseSection(database))
    if (holds.isNotEmpty()) {
        append(Html("<h2>Held for download</h2><p class=\"muted\">The trim leaves these jobs' files alone until each hold is "))
        append(Html("released or lapses, however low the disk.</p>"))
        append(Html("<div class=\"scroll\"><table><tr><th>job</th><th>hold</th><th>taken</th><th>until</th></tr>"))
        for (hold in holds) {
            append(Html("<tr><td><a href=\"/jobs/") + hold.job + Html("\">") + hold.job + Html("</a></td><td><code>") + (esc(hold.id.take(8))) + Html("</code></td>"))
            append(Html("<td>") + ago(hold.createdAt) + Html("</td><td>") + clockTime(hold.expiresAt) + Html("</td></tr>"))
        }
        append(Html("</table></div>"))
    }
    if (report == null) {
        append(Html("<p class=\"muted\">Not measured yet: the first measurement runs 10 minutes after the API starts and can take a while.</p></body></html>"))
        return@buildHtml
    }
    append(Html("<h2>What uses it</h2><p class=\"muted\">") + size(report.freeBytes) + Html(" free on the data directory's volume"))
    append(Html(" · measured ") + ago(report.at) + Html(", every 6 hours; the corpus and what is on another volume (the external drive) "))
    append(Html("once a day, finished jobs once</p>"))
    append(Html("<div class=\"scroll\"><table><tr><th>what</th><th>path</th><th class=\"num\">size</th><th>as of</th></tr>"))
    for (place in report.places) {
        append(Html("<tr><td>") + esc(place.label) + Html("</td><td><code>") + esc(place.path) + Html("</code></td>"))
        append(Html("<td class=\"num\">") + (place.bytes?.let(::size) ?: "?") + Html("</td><td>") + ago(place.at ?: report.at) + Html("</td></tr>"))
    }
    append(Html("</table></div>"))
    if (report.largestJobs.isNotEmpty()) {
        append(Html("<h2>Largest jobs</h2><div class=\"scroll\"><table><tr><th>job</th><th class=\"num\">size</th></tr>"))
        for ((id, bytes) in report.largestJobs) {
            append(Html("<tr><td><a href=\"/jobs/") + id + Html("/files\">") + id + Html("</a></td><td class=\"num\">") + size(bytes) + Html("</td></tr>"))
        }
        append(Html("</table></div>"))
    }
    append(dockerSection(report))
    append(Html("</body></html>"))
}

/** The disk page's volumes: each file system the lab writes to, its free and total space, and the lab's directories on it. */
private fun volumesSection(volumes: List<VolumeReport>): Html = buildHtml {
    append(Html("<h2>Volumes</h2><div class=\"scroll\"><table><tr><th>volume</th><th class=\"num\">free</th><th class=\"num\">total</th>"))
    append(Html("<th>lab directories</th></tr>"))
    for (volume in volumes) {
        append(Html("<tr><td><code>") + esc(volume.mount) + Html("</code>"))
        volume.problem?.let { append(Html(" <span class=\"FAILED\">") + esc(it) + Html("</span>")) }
        append(Html("</td><td class=\"num\">") + (volume.freeBytes?.let(::size) ?: "?") + Html("</td>"))
        append(Html("<td class=\"num\">") + (volume.totalBytes?.let(::size) ?: "?") + Html("</td><td>"))
        append(volume.paths.joinHtml(Html("<br>")) { (Html("") + esc(it.label) + Html(" (") + esc(it.name) + Html(") <code>") + esc(it.path) + Html("</code>")) })
        append(Html("</td></tr>"))
    }
    append(Html("</table></div>"))
}

/** The disk page's database part: the file's size and free pages, its kept provenances and the [DbCompaction]'s progress. */
private fun databaseSection(database: DatabaseReport): Html = buildHtml {
    val compaction = database.compaction
    append(Html("<h2>Database</h2><p>") + size(database.bytes) + Html(", ") + size(database.freeBytes) + Html(" of it free pages a vacuum gives back"))
    append(Html(" · ") + database.provenances + Html(" distinct build provenances kept once</p>"))
    val progress = when {
        compaction.migratedAt != null -> "done ${ago(compaction.migratedAt)}"
        compaction.startedAt == null -> "not started"
        else -> "${compaction.scanned} of ${compaction.total ?: "?"} case records" +
            (compaction.total?.takeIf { it > 0 }?.let { " (${minOf(100, compaction.scanned * 100 / it)}%)" } ?: "")
    }
    append(Html("<ul><li>compaction: ") + esc(progress))
    if (compaction.changed > 0) {
        append(Html(", ") + compaction.changed + Html(" records ") + size(compaction.bytesBefore) + Html(" → ") + size(compaction.bytesAfter))
    }
    append(Html("</li>"))
    compaction.vacuum?.let { append(Html("<li>vacuum ") + ago(compaction.vacuumAt) + Html(": ") + esc(it) + Html("</li>")) }
    append(Html("</ul>"))
}

/** The disk page's Docker part: what Docker holds and what the last [DockerCleanup] did. Empty when neither is known. */
private fun dockerSection(report: DiskReport): Html = buildHtml {
    val cleanup = report.dockerCleanup
    if (report.docker.isEmpty() && cleanup == null) return@buildHtml
    append(Html("<h2>Docker</h2>"))
    if (report.docker.isNotEmpty()) {
        append(Html("<div class=\"scroll\"><table><tr><th>what</th><th class=\"num\">size</th><th class=\"num\">reclaimable</th></tr>"))
        for (usage in report.docker) {
            append(Html("<tr><td>") + esc(usage.type) + Html("</td><td class=\"num\">") + (usage.bytes?.let(::size) ?: "?") + Html("</td>"))
            append(Html("<td class=\"num\">") + (usage.reclaimableBytes?.let(::size) ?: "?") + Html("</td></tr>"))
        }
        append(Html("</table></div>"))
    }
    if (cleanup == null) {
        append(Html("<p class=\"muted\">No cleanup has run yet.</p>"))
        return@buildHtml
    }
    val before = DockerCleanup.total(cleanup.dockerBefore)
    val after = DockerCleanup.total(cleanup.dockerAfter)
    append(Html("<p>Last cleanup ") + ago(cleanup.at) + Html(", because ") + esc(cleanup.why))
    if (before != null && after != null) append(Html(": Docker ") + size(before) + Html(" → ") + size(after))
    if (cleanup.hostDiskBefore != null && cleanup.hostDiskAfter != null) {
        append(Html(", Colima's disks on the host ") + size(cleanup.hostDiskBefore) + Html(" → ") + size(cleanup.hostDiskAfter))
    }
    append(Html(".</p><ul>"))
    append(Html("<li>tags removed: ") + (if (cleanup.removedTags.isEmpty()) "none" else cleanup.removedTags.joinToString { (Html("<code>") + esc(it) + Html("</code>")) }) + Html("</li>"))
    append(Html("<li>dangling images pruned: ") + cleanup.prunedImages + Html("</li>"))
    append(Html("<li>build cache reclaimed: ") + esc(cleanup.buildCacheReclaimed ?: "?") + Html("</li>"))
    cleanup.trimmed?.let { append(Html("<li>fstrim: ") + esc(it) + Html("</li>")) }
    for (note in cleanup.notes) append(Html("<li class=\"muted\">") + esc(note) + Html("</li>"))
    append(Html("</ul>"))
}
