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

import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val clock = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

/** A time to come, as a clock time; the exact time is in the tooltip. */
internal fun clockTime(millis: Long): Html =
    (Html("<span title=\"") + (stamp.format(Instant.ofEpochMilli(millis))) + Html("\">") + (clock.format(Instant.ofEpochMilli(millis))) + Html("</span>"))

/** How long ago, for the last day, then the date; the exact time is in the tooltip. */
internal fun ago(millis: Long?): Html {
    if (millis == null) return Html("")
    val seconds = (now() - millis) / 1000
    val text = if (seconds < 86_400) "${span(seconds)} ago" else clock.format(Instant.ofEpochMilli(millis))
    return (Html("<span title=\"") + (stamp.format(Instant.ofEpochMilli(millis))) + Html("\">") + text + Html("</span>"))
}

/** A job's running time as h:mm:ss, its waits in the queue left out. */
internal fun elapsed(job: Job): String = if (job.startedAt == null) "" else clock(job.elapsedMs(now()) / 1000)

internal fun clock(seconds: Long) = "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)

internal fun span(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m"
    seconds < 86_400 -> "${seconds / 3600}h${if (seconds / 60 % 60 > 0) " ${seconds / 60 % 60}m" else ""}"
    else -> "${seconds / 86_400}d${if (seconds / 3600 % 24 > 0) " ${seconds / 3600 % 24}h" else ""}"
}

