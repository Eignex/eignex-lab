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
import kotlinx.html.*

/**
 * What takes the lab machine's disk, largest first, as [DiskUsage] last measured it, and the
 * [database] as it is now.
 */
internal fun diskPage(
    report: DiskReport?,
    holds: List<Hold> = emptyList(),
    database: DatabaseReport? = null,
    volumes: List<VolumeReport> = emptyList(),
    /** Why the runner holds its queue ([Volumes.hold]); null when it does not. */
    held: String? = null,
): Html =
    page("disk · eignex lab") {
        header { h1 { text("Disk") } }
        if (held != null)
            p(classes = "FAILED") {
                text("The queue is held: ")
                emit(held)
            }
        if (volumes.isNotEmpty()) emit(volumesSection(volumes))
        if (database != null) emit(databaseSection(database))
        if (holds.isNotEmpty()) {
            h2 { text("Held for download") }
            p(classes = "muted") {
                text("The trim leaves these jobs' files alone until each hold is ")
                text("released or lapses, however low the disk.")
            }
            div(classes = "scroll") {
                table {
                    tr {
                        th { text("job") }
                        th { text("hold") }
                        th { text("taken") }
                        th { text("until") }
                    }
                    for (hold in holds) {
                        tr {
                            td {
                                a(href = "/jobs/${hold.job}") {
                                    emit(hold.job)
                                }
                            }
                            td { code { emit(hold.id.take(8)) } }
                            td { emit(ago(hold.createdAt)) }
                            td { emit(clockTime(hold.expiresAt)) }
                        }
                    }
                }
            }
        }
        if (report == null) {
            p(classes = "muted") {
                text(
                    "Not measured yet: the first measurement runs 10 minutes after the API starts and can take a while.",
                )
            }
            return@page
        }
        h2 { text("What uses it") }
        p(classes = "muted") {
            emit(size(report.freeBytes))
            text(" free on the data directory's volume")
            text(" · measured ")
            emit(ago(report.at))
            text(
                ", every 6 hours; the corpus and what is on another volume (the external drive) ",
            )
            text("once a day, finished jobs once")
        }
        div(classes = "scroll") {
            table {
                tr {
                    th { text("what") }
                    th { text("path") }
                    th(classes = "num") {
                        text("size")
                    }
                    th { text("as of") }
                }
                for (place in report.places) {
                    tr {
                        td { emit(place.label) }
                        td { code { emit(place.path) } }
                        td(classes = "num") {
                            emit(place.bytes?.let(::size) ?: "?")
                        }
                        td { emit(ago(place.at ?: report.at)) }
                    }
                }
            }
        }
        if (report.largestJobs.isNotEmpty()) {
            h2 { text("Largest jobs") }
            div(classes = "scroll") {
                table {
                    tr {
                        th { text("job") }
                        th(classes = "num") {
                            text("size")
                        }
                    }
                    for ((id, bytes) in report.largestJobs) {
                        tr {
                            td {
                                a(href = "/jobs/${id}/files") {
                                    emit(id)
                                }
                            }
                            td(classes = "num") {
                                emit(size(bytes))
                            }
                        }
                    }
                }
            }
        }
        emit(dockerSection(report))
    }

/**
 * The disk page's volumes: each file system the lab writes to, its free and total space, and the
 * lab's directories on it.
 */
private fun volumesSection(volumes: List<VolumeReport>): Html = buildHtml {
    h2 { text("Volumes") }
    div(classes = "scroll") {
        table {
            tr {
                th { text("volume") }
                th(classes = "num") {
                    text("free")
                }
                th(classes = "num") {
                    text("total")
                }
                th { text("lab directories") }
            }
            for (volume in volumes) {
                tr {
                    td {
                        code { emit(volume.mount) }
                        volume.problem?.let {
                            text(" ")
                            span(classes = "FAILED") {
                                emit(it)
                            }
                        }
                    }
                    td(classes = "num") {
                        emit(volume.freeBytes?.let(::size) ?: "?")
                    }
                    td(classes = "num") {
                        emit(volume.totalBytes?.let(::size) ?: "?")
                    }
                    td {
                        emit(
                            volume.paths.joinHtml(buildHtml { br {} }) {
                                buildHtml {
                                    emit(it.label)
                                    text(" (")
                                    emit(it.name)
                                    text(") ")
                                    code { emit(it.path) }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The disk page's database part: the file's size and free pages, its kept provenances and the
 * [DbCompaction]'s progress.
 */
private fun databaseSection(database: DatabaseReport): Html = buildHtml {
    val compaction = database.compaction
    h2 { text("Database") }
    p {
        emit(size(database.bytes))
        text(", ")
        emit(size(database.freeBytes))
        text(" of it free pages a vacuum gives back")
        text(" · ")
        emit(database.provenances)
        text(" distinct build provenances kept once")
    }
    val progress =
        when {
            compaction.migratedAt != null -> "done ${ago(compaction.migratedAt)}"
            compaction.startedAt == null -> "not started"
            else ->
                "${compaction.scanned} of ${compaction.total ?: "?"} case records${compaction.total
                        ?.takeIf { it > 0 }
                        ?.let { " (${minOf(100, compaction.scanned * 100 / it)}%)" } ?: ""}"
        }
    ul {
        li {
            text("compaction: ")
            emit(progress)
            if (compaction.changed > 0) {
                text(", ")
                emit(compaction.changed)
                text(" records ")
                emit(size(compaction.bytesBefore))
                text(" → ")
                emit(size(compaction.bytesAfter))
            }
        }
        compaction.vacuum?.let {
            li {
                text("vacuum ")
                emit(ago(compaction.vacuumAt))
                text(": ")
                emit(it)
            }
        }
    }
}

/**
 * The disk page's Docker part: what Docker holds and what the last [DockerCleanup] did. Empty when
 * neither is known.
 */
private fun dockerSection(report: DiskReport): Html = buildHtml {
    val cleanup = report.dockerCleanup
    if (report.docker.isEmpty() && cleanup == null) return@buildHtml
    h2 { text("Docker") }
    if (report.docker.isNotEmpty()) {
        div(classes = "scroll") {
            table {
                tr {
                    th { text("what") }
                    th(classes = "num") {
                        text("size")
                    }
                    th(classes = "num") {
                        text("reclaimable")
                    }
                }
                for (usage in report.docker) {
                    tr {
                        td { emit(usage.type) }
                        td(classes = "num") {
                            emit(usage.bytes?.let(::size) ?: "?")
                        }
                        td(classes = "num") {
                            emit(usage.reclaimableBytes?.let(::size) ?: "?")
                        }
                    }
                }
            }
        }
    }
    if (cleanup == null) {
        p(classes = "muted") {
            text("No cleanup has run yet.")
        }
        return@buildHtml
    }
    val before = DockerCleanup.total(cleanup.dockerBefore)
    val after = DockerCleanup.total(cleanup.dockerAfter)
    p {
        text("Last cleanup ")
        emit(ago(cleanup.at))
        text(", because ")
        emit(cleanup.why)
        if (before != null && after != null) {
            text(": Docker ")
            emit(size(before))
            text(" → ")
            emit(size(after))
        }
        if (cleanup.hostDiskBefore != null && cleanup.hostDiskAfter != null) {
            text(", Colima's disks on the host ")
            emit(size(cleanup.hostDiskBefore))
            text(" → ")
            emit(size(cleanup.hostDiskAfter))
        }
        text(".")
    }
    ul {
        li {
            text("tags removed: ")
            emit(
                if (cleanup.removedTags.isEmpty()) "none"
                else cleanup.removedTags.joinToString { buildHtml { code { emit(it) } } },
            )
        }
        li {
            text("dangling images pruned: ")
            emit(cleanup.prunedImages)
        }
        li {
            text("build cache reclaimed: ")
            emit(cleanup.buildCacheReclaimed ?: "?")
        }
        cleanup.trimmed?.let {
            li {
                text("fstrim: ")
                emit(it)
            }
        }
        for (note in cleanup.notes) li(classes = "muted") {
            emit(note)
        }
    }
}
