package com.eignex.lab.worker

import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.nomad.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class ResourceObservation(val enforcement: String, val budget: ResourceBudget, val cpuMax: String?, val memoryMax: String?,
                               val javaVersion: String, val os: String, val arch: String, val counters: Map<String, String>,
                               val cpuIdentity: String? = null, val iterationParallel: Int? = null, val iterationCases: Int? = null) {
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
                System.getProperty("java.runtime.version"), System.getProperty("os.name"), workerArch(), counters(),
                if (native) null else runCatching {
                    Files.newBufferedReader(Path.of("/proc/cpuinfo")).use { reader ->
                        cpuIdentity(reader.lineSequence().take(256).takeWhile(String::isNotBlank).joinToString("\n"))
                    }
                }.getOrNull())
        }

        internal fun cpuIdentity(text: String): String? {
            val fields = text.lineSequence().mapNotNull { line ->
                val pair = line.split(':', limit = 2)
                if (pair.size != 2 || pair[0].trim() !in CPU_FIELDS) null else {
                    val name = pair[0].trim()
                    val value = pair[1].trim().split(Regex("\\s+")).let { if (name == "flags") it.sorted() else it }.joinToString(" ")
                    name to value
                }
            }.toMap()
            if (CPU_FIELDS.any { fields[it].isNullOrBlank() }) return null
            return ExecutionStore.digest(fields.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value}" })
        }

        private val CPU_FIELDS = setOf("vendor_id", "cpu family", "model", "model name", "stepping", "flags")
    }
}
