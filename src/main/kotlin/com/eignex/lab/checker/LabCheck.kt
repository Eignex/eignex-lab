package com.eignex.lab.checker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The lab's own verdict on one Klause case's solution claim: an established external checker run over the original
 * source and the final witness the case's record kept. It is stored beside the case (the `lab_check` column), never in
 * the bench's record, and every reader sees it as the record's `labCheck` ([com.eignex.lab.Store]).
 */
@Serializable
data class LabCheck(
    /** The policy the verdict was reached under, the checker and its version in its name ([Policies]). */
    val policy: String,
    /** [VALID], [INVALID], [UNKNOWN], [NOT_RUN] or [UNSUPPORTED]. */
    val state: String,
    /** Why, in the checker's own words where it gave any. */
    val reason: String,
    /** The external tool, as named in [policy]. */
    val checker: String,
    val checkerVersion: String? = null,
    /** SHA-256 of the checker binary or jar that ran; null when none ran. */
    val checkerSha256: String? = null,
    /** SHA-256 of the record's `finalWitness` as checked. */
    val witnessSha256: String? = null,
    /** The plain-byte SHA-256 of each source file checked against, keyed as the record's `sourceHashes` are. */
    val sourceSha256: Map<String, String> = emptyMap(),
    /** When the check began, in epoch ms, and how long it took. */
    val checkedAt: Long = 0,
    val elapsedMs: Long = 0,
) {
    fun json(): String = JSON.encodeToString(serializer(), this)

    companion object {
        /** The checker accepted the witness against the original source: the solution is valid. */
        const val VALID = "valid"
        /** The checker rejected the witness, or the claimed objective. */
        const val INVALID = "invalid"
        /** The checker ran and could not decide. */
        const val UNKNOWN = "unknown"
        /** The checker could not run: missing tool or source, a download that failed, a crash or a timeout. */
        const val NOT_RUN = "not-run"
        /** No external checker covers this claim: floats, lexicographic objectives, input a checker cannot read. */
        const val UNSUPPORTED = "unsupported"

        val STATES = setOf(VALID, INVALID, UNKNOWN, NOT_RUN, UNSUPPORTED)

        private val JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        fun parse(text: String): LabCheck? = runCatching { JSON.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/** The lab's checking policies, one per format and checker version: a new checker or a new rule is a new policy. */
object Policies {
    const val XCSP3 = "lab-xcsp3-solution-checker-2.5-v1"
    const val MPS = "lab-mps-solchecker-1.0.4-v1"
}
