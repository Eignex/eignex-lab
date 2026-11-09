package com.eignex.lab.nomad

import com.eignex.lab.domain.*
import com.eignex.lab.util.*

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/* How a [NomadBatchJob] is registered: Nomad's own job JSON. */

/** Nomad's native Job JSON, accepted by `nomad job run -json`; the registration API wraps this in `Job`. */
internal fun NomadBatchJob.render(): JsonObject = buildJsonObject {
    put("ID", id)
    put("Name", id)
    put("Type", "batch")
    putJsonArray("Datacenters") { add(JsonPrimitive(datacenter)) }
    putJsonObject("Meta") {
        put("lab_session_schema", schemaVersion.toString())
        perHostParallel?.let { put("lab_per_host_parallel", it.toString()) }
        put("lab_cpu_millis", budget.cpuMillis.toString())
        put("lab_memory_mib", budget.memoryMiB.toString())
        put("lab_cpu_enforcement", if (platform == NomadPlatform.LINUX) "hard-quota" else "advisory-native")
        nativeCapacity?.let { put("lab_native_cpu_limit", it.cpuMillis.toString()); put("lab_native_memory_limit", it.memoryMiB.toString()) }
    }
    put("Constraints", buildJsonArray {
        add(constraint("${'$'}{node.class}", nodeClass))
        add(constraint("${'$'}{attr.kernel.name}", if (platform == NomadPlatform.LINUX) "linux" else "darwin"))
        // A job converted for one pool must not land on a node with a different quota denominator.
        add(constraint("${'$'}{attr.cpu.usablecompute}", cpuCapacity.usableMHz.toString()))
        add(constraint("${'$'}{attr.cpu.numcores}", cpuCapacity.logicalCpus.toString()))
        excludedNodes.sorted().forEach { add(constraint("${'$'}{node.unique.id}", it, "!=")) }
    })
    putJsonArray("TaskGroups") {
        add(buildJsonObject {
            put("Name", "session")
            put("Count", 1)
            if (statusHostVolume != null || cacheHostVolume != null) putJsonObject("Volumes") {
                statusHostVolume?.let { source -> putJsonObject("status") { put("Type", "host"); put("Source", source); put("ReadOnly", true) } }
                cacheHostVolume?.let { source -> putJsonObject("cache") {
                    put("Type", "host"); put("Source", source); put("ReadOnly", false)
                    put("AccessMode", "single-node-multi-writer"); put("AttachmentMode", "file-system")
                } }
            }
            putJsonObject("RestartPolicy") {
                put("Attempts", 0)
                put("Mode", "fail")
                put("Interval", 86_400_000_000_000L)
                put("Delay", 15_000_000_000L)
            }
            putJsonObject("ReschedulePolicy") {
                put("Attempts", 3)
                put("Interval", 3_600_000_000_000L)
                put("Delay", 15_000_000_000L)
                put("DelayFunction", "exponential")
                put("MaxDelay", 120_000_000_000L)
                put("Unlimited", false)
            }
            putJsonArray("Tasks") { add(task()) }
        })
    }
}

private fun NomadBatchJob.task() = buildJsonObject {
    put("Name", "worker")
    put("Driver", if (platform == NomadPlatform.LINUX) "docker" else "raw_exec")
    put("KillTimeout", 30_000_000_000L)
    putJsonObject("Config") {
        put("command", command.first())
        putJsonArray("args") { command.drop(1).forEach { add(JsonPrimitive(it)) } }
        if (platform == NomadPlatform.LINUX) {
            put("image", image)
            networkMode?.let { put("network_mode", it) }
            put("cpu_hard_limit", true)
            put("cpu_cfs_period", 100_000)
            put("cgroupns", "private")
            put("memory_hard_limit", budget.memoryMiB)
            put("init", true)
        }
    }
    if (statusHostVolume != null || cacheHostVolume != null) putJsonArray("VolumeMounts") {
        statusHostVolume?.let { add(buildJsonObject { put("Volume", "status"); put("Destination", "/lab-status"); put("ReadOnly", true) }) }
        cacheHostVolume?.let { add(buildJsonObject { put("Volume", "cache"); put("Destination", "/lab-cache"); put("ReadOnly", false) }) }
    }
    putJsonObject("Resources") {
        put("CPU", budget.nomadCpuMHz(cpuCapacity))
        put("MemoryMB", budget.memoryMiB)
    }
    putJsonObject("Env") {
        environment.forEach { (key, value) -> put(key, value) }
        statusHostVolume?.let { put("LAB_WORKER_DRAIN_FILE", "/lab-status/draining") }
        cacheHostVolume?.let { put("LAB_WORKER_CACHE_ROOT", "/lab-cache") }
        put("LAB_SESSION_CPU_MILLIS", budget.cpuMillis.toString())
        put("LAB_SESSION_MEMORY_MIB", budget.memoryMiB.toString())
        put("LAB_SESSION_PARALLEL", budget.parallel.toString())
    }
}

private fun constraint(target: String, value: String, operand: String = "=") = buildJsonObject {
    put("LTarget", target)
    put("Operand", operand)
    put("RTarget", value)
}
