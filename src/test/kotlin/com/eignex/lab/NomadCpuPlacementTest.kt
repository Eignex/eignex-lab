package com.eignex.lab

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class NomadCpuPlacementTest {
    private val budget = ResourceBudget(4000, 8192, 2)
    private val asked = NomadCpuCapacity(36_000, 16)
    private val job = NomadBatchJob("lab-1", NomadPlatform.LINUX, "general", asked, budget, listOf("worker"),
        "registry/worker@sha256:" + "d".repeat(64), statusHostVolume = "lab-status").render()

    private fun cpu(job: kotlinx.serialization.json.JsonObject) =
        job.getValue("TaskGroups").jsonArray.single().jsonObject.getValue("Tasks").jsonArray.single().jsonObject
            .getValue("Resources").jsonObject.getValue("CPU").jsonPrimitive.int

    @Test
    fun `a job that fits a node as its constraints say is left as it is`() {
        val nodes = listOf(NomadCpuPlacement.Node("n1", "general", asked, 36_000, 65_536))
        assertSame(job, NomadCpuPlacement.calibrate(job, budget, nodes))
    }

    @Test
    fun `a job asks for the CPU of the node of its class that it fits on`() {
        val faster = NomadCpuCapacity(44_800, 16)
        val nodes = listOf(NomadCpuPlacement.Node("n1", "general", faster, 44_800, 65_536),
            NomadCpuPlacement.Node("n2", "other", asked, 36_000, 65_536))
        val calibrated = NomadCpuPlacement.calibrate(job, budget, nodes)
        assertEquals(budget.nomadCpuMHz(faster), cpu(calibrated))
        assertEquals(faster, SessionPlacement.of(calibrated).cpuCapacity)
    }
}
