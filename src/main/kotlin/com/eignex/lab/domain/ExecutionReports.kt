package com.eignex.lab.domain

import com.eignex.lab.util.*

import kotlinx.serialization.Serializable
import com.eignex.lab.store.ExecutionSession

@Serializable
data class ExecutionSessionReport(val session: ExecutionSession, val phase: String?, val generation: Int?, val committed: Int, val observation: String?, val observedAt: Long?, val preparation: PreparationStatus? = null,
    val resources: ResourceCheckpoint? = null)
