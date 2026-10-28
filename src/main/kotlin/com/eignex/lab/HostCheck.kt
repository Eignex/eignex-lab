package com.eignex.lab

import kotlinx.serialization.Serializable

/** Coordinator host facts, independent of the runtimes selected by repository adapters. */
@Serializable
data class HostReport(
    val host: String,
    val cores: Int,
    val java: String,
    val cpu: CpuCapabilities = NodeTags.localCapabilities,
    val nodeTags: Set<String> = emptySet(),
)

fun hostReport(): HostReport = HostReport(
    host = "${System.getProperty("os.name")}/${System.getProperty("os.arch")}",
    cores = Runtime.getRuntime().availableProcessors(),
    java = "${System.getProperty("java.vendor")} ${System.getProperty("java.runtime.version")}",
)
