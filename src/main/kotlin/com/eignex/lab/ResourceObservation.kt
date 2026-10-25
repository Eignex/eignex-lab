package com.eignex.lab

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class ResourceObservation(val enforcement: String, val budget: ResourceBudget, val cpuMax: String?, val memoryMax: String?,
                               val javaVersion: String, val os: String, val arch: String, val counters: Map<String, String>) {
    companion object {
        private val cgroup = Path.of("/sys/fs/cgroup")
        fun counters(): Map<String, String> = listOf("cpu.stat", "memory.events", "memory.current").mapNotNull { name ->
            runCatching { name to Files.readString(cgroup.resolve(name)).trim() }.getOrNull()
        }.toMap()
        fun read(budget: ResourceBudget): ResourceObservation {
            val native = CliMeasurements.localMacOs
            val cpu = if (native) null else Files.readString(cgroup.resolve("cpu.max")).trim()
            val memory = if (native) null else Files.readString(cgroup.resolve("memory.max")).trim()
            if (!native) {
                val parts = cpu!!.split(Regex("\\s+")); require(parts.size == 2 && parts[0] != "max") { "worker requires a finite cgroup CPU quota" }
                val millis = parts[0].toLong() * 1000.0 / parts[1].toLong()
                require(millis > 0 && millis <= budget.cpuMillis + .01 && millis >= budget.cpuMillis * .95) { "runtime CPU quota differs from session budget" }
                require(memory!!.toLong() == budget.memoryMiB * 1024L * 1024) { "runtime memory ceiling differs from session budget" }
            }
            return ResourceObservation(if (native) "advisory-native" else "cgroup-v2-hard", budget, cpu, memory,
                System.getProperty("java.runtime.version"), System.getProperty("os.name"), workerArch(), counters())
        }
    }
}
