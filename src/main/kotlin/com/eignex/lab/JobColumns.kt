package com.eignex.lab

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/** The columns of a job row the store derives from its experiment, for queries to filter on ([Schema]). */
internal data class JobColumns(val host: String, val analysis: Boolean, val bench: Boolean, val measurement: String) {
    companion object {
        fun of(spec: ExperimentSpec) = JobColumns(
            spec.host.stored, spec.isAnalysis, spec.bench != null,
            Json.encodeToJsonElement(Measurement.serializer(), spec.measurement).jsonPrimitive.content,
        )
    }
}
