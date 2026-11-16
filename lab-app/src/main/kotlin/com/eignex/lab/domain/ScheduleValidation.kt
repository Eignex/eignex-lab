package com.eignex.lab.domain

import com.eignex.lab.util.*

/** Saved schedules can predate the current submission contract. Keep them editable without creating bad jobs. */
internal fun Schedule.blockedReason(): String? = try {
    Experiments.validate(experiment, Int.MAX_VALUE)
    null
} catch (failure: IllegalArgumentException) {
    failure.message ?: "invalid saved experiment"
}
