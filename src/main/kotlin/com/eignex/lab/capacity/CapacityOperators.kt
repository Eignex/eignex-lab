package com.eignex.lab.capacity

import com.eignex.lab.analysis.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files

/** An operator's request for [nodes] of [pool] for [ttlSec] ([capacityRoutes]). */
@Serializable data class CapacityRequest(val pool: String, val nodes: Int, val ttlSec: Long = 900)

/** The capacity snapshot the coordinator last reported, with its age; null before it has reported. */
internal fun readCapacityReport(config: Config): CapacitySnapshot? = runCatching {
    Json.decodeFromString<CapacitySnapshot>(Files.readString(config.nomadCapacityReport)).let { it.copy(ageMs = (now() - it.generatedAt).coerceAtLeast(0)) }
}.getOrNull()
