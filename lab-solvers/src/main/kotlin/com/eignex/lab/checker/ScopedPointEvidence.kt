package com.eignex.lab.checker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A point checker attests all constraints and assumptions in [scope], and the recomputed original [objective].
 * An original-model check alone cannot emit this for additional assumptions. This supplies no optimum authority.
 */
@Serializable
data class ScopedPointEvidence(val scope: JsonObject, val objective: String)
