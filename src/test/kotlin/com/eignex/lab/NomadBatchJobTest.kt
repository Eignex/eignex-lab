package com.eignex.lab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class NomadBatchJobTest {
    private val capacity = NomadCpuCapacity(usableMHz = 10_000, logicalCpus = 4)
    private val budget = ResourceBudget(cpuMillis = 500, memoryMiB = 128)
    private val spec = NomadBatchJob("lab-probe", NomadPlatform.LINUX, "lab-probe-linux", capacity, budget,
        listOf("python3", "-c", "print('a b; $')"), image = "python@sha256:${"a".repeat(64)}")

    @Test
    fun `CPU conversion respects logical CPUs and rounds down instead of exceeding the quota`() {
        assertEquals(1250, budget.nomadCpuMHz(capacity))
        // A 500 MHz host reserve on the real 44,400 MHz / 20 CPU prototype must reduce the conversion denominator.
        assertEquals(1097, budget.nomadCpuMHz(NomadCpuCapacity(43_900, 20)))
        assertEquals(166, budget.copy(cpuMillis = 500).nomadCpuMHz(NomadCpuCapacity(1000, 3)))
        assertEquals(Int.MAX_VALUE, ResourceBudget(1000, 1).nomadCpuMHz(NomadCpuCapacity(Int.MAX_VALUE, 1)))
        assertFailsWith<IllegalArgumentException> { budget.copy(cpuMillis = 4001).nomadCpuMHz(capacity) }
        assertFailsWith<IllegalArgumentException> { budget.copy(cpuMillis = 1).nomadCpuMHz(NomadCpuCapacity(1, 4)) }
        assertFailsWith<IllegalArgumentException> { ResourceBudget(0, 1) }
        assertFailsWith<IllegalArgumentException> { ResourceBudget(1, 0) }
        assertFailsWith<IllegalArgumentException> { ResourceBudget(1, 1, 0) }
        assertFailsWith<IllegalArgumentException> { NomadCpuCapacity(1, 0) }
    }

    @Test
    fun `Linux tasks cap the whole session without taking the whole node`() {
        val job = spec.render()
        val group = job.getValue("TaskGroups").jsonArray.single().jsonObject
        val task = group.getValue("Tasks").jsonArray.single().jsonObject
        val config = task.getValue("Config").jsonObject
        val resources = task.getValue("Resources").jsonObject
        assertEquals("batch", job.getValue("Type").jsonPrimitive.content)
        assertEquals("docker", task.getValue("Driver").jsonPrimitive.content)
        assertEquals("true", config.getValue("cpu_hard_limit").jsonPrimitive.content)
        assertEquals("128", config.getValue("memory_hard_limit").jsonPrimitive.content)
        assertEquals("1250", resources.getValue("CPU").jsonPrimitive.content)
        assertEquals(resources.getValue("MemoryMB"), config.getValue("memory_hard_limit"))
        assertFalse("MemoryMaxMB" in resources) // No memory oversubscription is required for the hard limit.
        assertFalse("Cores" in resources)
        assertEquals("print('a b; $')", config.getValue("args").jsonArray.last().jsonPrimitive.content)
        assertEquals("0", group.getValue("RestartPolicy").jsonObject.getValue("Attempts").jsonPrimitive.content)
        assertFalse(job.toString().contains("distinct_hosts"))
        assertFalse(job.toString().contains("affinity"))
        val constraints = job.getValue("Constraints").jsonArray.map { it.jsonObject }
        assertTrue(constraints.any { it.getValue("LTarget").jsonPrimitive.content == "${'$'}{attr.cpu.usablecompute}" &&
            it.getValue("RTarget").jsonPrimitive.content == "10000" })
        assertTrue(constraints.any { it.getValue("LTarget").jsonPrimitive.content == "${'$'}{attr.cpu.numcores}" &&
            it.getValue("RTarget").jsonPrimitive.content == "4" })
    }

    @Test
    fun `native Mac uses raw execution and declares advisory enforcement`() {
        val job = spec.copy(platform = NomadPlatform.MAC, nodeClass = "lab-probe-mac", image = null).render()
        val task = job.getValue("TaskGroups").jsonArray.single().jsonObject.getValue("Tasks").jsonArray.single().jsonObject
        assertEquals("raw_exec", task.getValue("Driver").jsonPrimitive.content)
        assertFalse("cpu_hard_limit" in task.getValue("Config").jsonObject)
        assertFalse("image" in task.getValue("Config").jsonObject)
        assertEquals("advisory-native", job.getValue("Meta").jsonObject.getValue("lab_cpu_enforcement").jsonPrimitive.content)
        assertTrue(job.getValue("Constraints").toString().contains("darwin"))
    }

    @Test
    fun `invalid or misleading session definitions fail before submission`() {
        assertFailsWith<IllegalArgumentException> { spec.copy(image = "python:latest") }
        assertFailsWith<IllegalArgumentException> { spec.copy(platform = NomadPlatform.MAC) }
        assertFailsWith<IllegalArgumentException> { spec.copy(command = emptyList()) }
        assertFailsWith<IllegalArgumentException> { spec.copy(id = "../other-job") }
        assertFailsWith<IllegalArgumentException> { spec.copy(schemaVersion = 2) }
        assertFailsWith<IllegalArgumentException> { spec.copy(environment = mapOf("LAB_SESSION_PARALLEL" to "999")) }
    }
}
