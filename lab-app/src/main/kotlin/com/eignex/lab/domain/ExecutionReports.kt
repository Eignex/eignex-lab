package com.eignex.lab.domain

import com.eignex.lab.util.*

import kotlinx.serialization.Serializable

@Serializable
data class ExecutionSessionReport(val session: ExecutionSession, val phase: String?, val generation: Int?, val committed: Int, val observation: String?, val observedAt: Long?, val preparation: PreparationStatus? = null,
    val resources: ResourceCheckpoint? = null, val termination: AllocationTerminationReceipt? = null)

/** Controller observation retained before retiring an allocation. A missing task receipt means unknown, not exit 0. */
@Serializable
data class AllocationTerminationReceipt(val allocation: String?, val node: String?, val observedAt: Long,
    val task: NomadTaskTermination?)

/** Bounded, allowlisted Nomad evidence. Free-form messages, environment and task configuration are never retained. */
@Serializable
data class NomadTaskTermination(val client: String?, val desired: String?, val task: String?,
    val events: List<String>, val exitCode: Int?, val signal: Int?, val oomKilled: Boolean?,
    val killReported: Boolean, val driverErrorReported: Boolean, val setupErrorReported: Boolean)
