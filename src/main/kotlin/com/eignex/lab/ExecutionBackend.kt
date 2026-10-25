package com.eignex.lab

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ExecutionBackendKind(val wire: String) {
    @SerialName("legacy") LEGACY("legacy"),
    @SerialName("nomad") NOMAD("nomad"),
}

/** Lifecycle is reconciled from persisted intent; implementations must tolerate duplicate delivery. */
interface ExecutionBackend {
    val kind: ExecutionBackendKind
    fun reconcile()
}
