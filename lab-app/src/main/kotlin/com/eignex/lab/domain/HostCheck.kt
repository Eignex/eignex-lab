package com.eignex.lab.domain

import com.eignex.lab.util.*

import kotlinx.serialization.Serializable

/** Coordinator host facts, independent of the runtimes selected by repository adapters. */
@Serializable
data class HostReport(
    val host: String,
    val cores: Int,
    val java: String,
    val cpu: CpuCapabilities = CpuCapabilities(),
    val nodeTags: Set<String> = emptySet(),
)

/** This host, its CPU as [cpu] says: what a service probed once, when it started ([NodeTags.probeLocal]). */
fun hostReport(cpu: CpuCapabilities): HostReport = HostReport(
    host = "${System.getProperty("os.name")}/${System.getProperty("os.arch")}",
    cores = Runtime.getRuntime().availableProcessors(),
    java = "${System.getProperty("java.vendor")} ${System.getProperty("java.runtime.version")}",
    cpu = cpu,
)
