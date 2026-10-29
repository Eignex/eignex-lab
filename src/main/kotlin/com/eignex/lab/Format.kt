package com.eignex.lab

import java.util.Locale

/* How the lab writes sizes and hours for people: pages, logs and reports share these. */

/** Core-hours, one decimal under ten and whole above. */
internal fun coreHoursText(hours: Double): String = if (hours < 10) "%.1f".format(Locale.ROOT, hours) else "%.0f".format(Locale.ROOT, hours)

/** A wall time of [hours], rounded up to the minute: `14 min`, `3 h 20 min`, `2 d 5 h`. */
internal fun wallText(hours: Double): String {
    val minutes = kotlin.math.ceil(hours * 60).toLong()
    return when {
        minutes < 60 -> "$minutes min"
        minutes < 48 * 60 -> "${minutes / 60} h" + if (minutes % 60 != 0L) " ${minutes % 60} min" else ""
        else -> "${minutes / (24 * 60)} d ${minutes / 60 % 24} h"
    }
}

internal fun size(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    else -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
}
