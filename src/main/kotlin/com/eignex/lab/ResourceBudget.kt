package com.eignex.lab

import kotlinx.serialization.Serializable

/** Total budget of an execution session, including every concurrent case, helper JVM, and build it starts. */
@Serializable
data class ResourceBudget(val cpuMillis: Int, val memoryMiB: Int, val parallel: Int = 1) {
    init {
        require(cpuMillis > 0) { "cpuMillis must be positive (1000 means one logical vCPU)" }
        require(memoryMiB > 0) { "memoryMiB must be positive" }
        require(parallel > 0) { "parallel must be positive" }
    }

    /**
     * Nomad takes MHz, while our contract uses thousandths of a logical vCPU. Nomad 2.0.7's Docker hard limit uses
     * PercentTicks = allocated MHz / topology.UsableCompute(), then multiplies by the driver's logical CPU count.
     * Use cpu.usablecompute, including the host reserve deduction, and round down to stay within the quota. Using
     * cpu.totalcompute here would exceed the intended vCPU limit whenever the client reserves CPU for the host.
     */
    fun nomadCpuMHz(node: NomadCpuCapacity): Int {
        require(cpuMillis.toLong() <= node.logicalCpus.toLong() * 1000) { "CPU budget exceeds the node's logical CPUs" }
        val mhz = node.usableMHz.toLong() * cpuMillis / (node.logicalCpus.toLong() * 1000)
        require(mhz > 0) { "CPU budget is smaller than one Nomad MHz on this node" }
        return mhz.toInt()
    }
}

/** Fingerprinted CPU capacity of the homogeneous pool this task targets; physical cores are separate provenance. */
@Serializable
data class NomadCpuCapacity(val usableMHz: Int, val logicalCpus: Int) {
    init {
        require(usableMHz > 0) { "usableMHz must be positive" }
        require(logicalCpus > 0) { "logicalCpus must be positive" }
    }
}
