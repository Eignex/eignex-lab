package com.eignex.lab

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Why a job exists: someone submitted it, a schedule queued it, or the lab queued it to confirm a schedule's flip.
 * Stored with the job ([Schema]), so what kind of job it is never depends on its name. [series] is the run of jobs it
 * compares with: a schedule's runs share their schedule's name, its confirmations theirs, and a submitted job is its own.
 */
@Serializable
sealed interface JobOrigin {
    val series: String

    @Serializable
    @SerialName("submitted")
    data class Submitted(override val series: String) : JobOrigin

    @Serializable
    @SerialName("scheduled")
    data class Scheduled(val schedule: String) : JobOrigin {
        override val series: String get() = schedule
    }

    @Serializable
    @SerialName("confirmation")
    data class Confirmation(val schedule: String) : JobOrigin {
        override val series: String get() = schedule + Confirm.SUFFIX
    }

    /** The word the store keeps for the kind of origin. */
    val kind: String get() = when (this) {
        is Submitted -> SUBMITTED
        is Scheduled -> SCHEDULED
        is Confirmation -> CONFIRMATION
    }

    companion object {
        const val SUBMITTED = "submitted"
        const val SCHEDULED = "scheduled"
        const val CONFIRMATION = "confirmation"

        /** The origin as the store keeps it: its [kind] and [series]. */
        fun stored(kind: String, series: String): JobOrigin = when (kind) {
            SCHEDULED -> Scheduled(series)
            CONFIRMATION -> Confirmation(series.removeSuffix(Confirm.SUFFIX))
            SUBMITTED -> Submitted(series)
            else -> error("unknown job origin $kind")
        }

        /**
         * The origin a job named [name] had before origins were stored: a schedule's run is `<schedule>@<sha>`, a
         * confirmation `<schedule>~confirm@…`. Only for jobs from before, and for callers that have nothing but a name;
         * submitted names cannot contain either mark ([Experiments.requirePlainName]).
         */
        fun of(name: String): JobOrigin = when {
            Confirm.SUFFIX in name -> Confirmation(name.substringBefore(Confirm.SUFFIX))
            '@' in name -> Scheduled(name.substringBefore('@'))
            else -> Submitted(name)
        }
    }
}
