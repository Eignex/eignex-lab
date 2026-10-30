package com.eignex.lab

import com.eignex.lab.util.*
import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.execution.*
import com.eignex.lab.worker.*
import com.eignex.lab.nomad.*
import com.eignex.lab.web.*
import com.eignex.lab.cli.*

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClusterSettingsTest {
    private fun target(nodeClass: String, cpus: Int, memoryMiB: Int, tags: Set<String> = setOf("arch:x86_64", "simd:avx2")) =
        NomadQueueTarget(NomadBatchJob("template", NomadPlatform.LINUX, nodeClass, NomadCpuCapacity(cpus * 2000, cpus),
            ResourceBudget(cpus * 1000, memoryMiB), listOf("worker"), "registry/worker@sha256:" + "d".repeat(64),
            statusHostVolume = "lab-status"), tags)

    private fun queue(vararg targets: NomadQueueTarget) = NomadQueueSettings("release", "https://lab.example", targets.toList())

    private val pool = CapacityPool("avx2", "linux-avx2-spot", 16, 32768, 8, setOf("arch:x86_64", "simd:avx2"), reserveMemoryMiB = 2048)

    @Test
    fun `every cloud target fits the pool of its node class`() {
        val cluster = ClusterSettings(queue(target("linux-avx2-spot", 16, 30720)), CapacitySettings(listOf(pool), 64))
        assertEquals(pool, cluster.poolOf(cluster.queue.targets.single().template))
    }

    @Test
    fun `a target the coordinator would refuse is refused when the settings load`() {
        val settings = CapacitySettings(listOf(pool), 64)
        for ((target, why) in listOf(
            target("linux-other-spot", 16, 30720) to "has no capacity pool",
            target("linux-avx2-spot", 16, 31744) to "asks for more than pool avx2 has",
            target("linux-avx2-spot", 8, 16384) to "counts 8 CPUs, pool avx2 has 16",
            target("linux-avx2-spot", 16, 30720, setOf("arch:x86_64", "simd:avx512")) to "offers tags pool avx2 does not have",
        )) {
            val failure = assertFailsWith<IllegalArgumentException> { ClusterSettings(queue(target), settings) }
            assertTrue(why in failure.message.orEmpty(), failure.message)
        }
    }

    @Test
    fun `a queue without capacity settings is not checked against pools`() {
        ClusterSettings(queue(target("linux-other-spot", 16, 30720)), null)
    }
}
