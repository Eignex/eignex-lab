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

import kotlinx.serialization.json.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ComparisonExecutionTest {
    private val observation = ResourceObservation("cgroup-v2-hard", ResourceBudget(1000, 4608), "100000 100000",
        "4831838208", "25", "Linux", "amd64", mapOf("cpu.stat" to "usage_usec 1"), "c".repeat(64), 1, 2)
    private val source = """{"solver":"klause","comparisonControls":{}}"""

    @Test
    fun `different resource counters retain distinct receipts and the same comparison frame`() {
        val first = Json.parseToJsonElement(ComparisonExecution.record(source, observation, "a".repeat(64), "release")!!) as JsonObject
        val next = Json.parseToJsonElement(ComparisonExecution.record(source,
            observation.copy(counters = mapOf("cpu.stat" to "usage_usec 200")), "b".repeat(64), "release")!!) as JsonObject

        assertNotEquals(first, next)
        assertNotNull(ComparisonExecution.frame(first))
        assertEquals(ComparisonExecution.frame(first), ComparisonExecution.frame(next))
        assertEquals(ComparisonExecution.series(first), ComparisonExecution.series(next))
    }

    @ParameterizedTest
    @ValueSource(strings = ["cpuMax", "memoryMax", "cpuIdentity", "iterationParallel", "iterationCases", "javaVersion", "release"])
    fun `missing physical controls cannot qualify a comparison`(field: String) {
        val record = Json.parseToJsonElement(ComparisonExecution.record(source, observation, "a".repeat(64), "release")!!) as JsonObject
        val receipt = record.getValue("labExecution") as JsonObject
        val frame = receipt.getValue("frame") as JsonObject
        val incomplete = JsonObject(record + ("labExecution" to JsonObject(receipt + ("frame" to JsonObject(frame - field)))))

        assertNull(ComparisonExecution.frame(incomplete))
    }

    @Test
    fun `changed actual concurrency splits the comparison series`() {
        val first = Json.parseToJsonElement(ComparisonExecution.record(source,
            observation.copy(budget = ResourceBudget(2000, 8704, 2), cpuMax = "200000 100000", memoryMax = "9126805504"),
            "a".repeat(64), "release")!!) as JsonObject
        val next = Json.parseToJsonElement(ComparisonExecution.record(source,
            observation.copy(budget = ResourceBudget(2000, 8704, 2), cpuMax = "200000 100000", memoryMax = "9126805504", iterationParallel = 2),
            "b".repeat(64), "release")!!) as JsonObject

        assertNotNull(ComparisonExecution.frame(first))
        assertNotNull(ComparisonExecution.frame(next))
        assertNotEquals(ComparisonExecution.series(first), ComparisonExecution.series(next))
    }

    @Test
    fun `an advisory or inconsistent kernel quota cannot qualify`() {
        for (changed in listOf(observation.copy(enforcement = "advisory-native"),
            observation.copy(cpuMax = "max 100000"), observation.copy(cpuMax = "200000 100000"),
            observation.copy(memoryMax = "1"))) {
            val record = Json.parseToJsonElement(ComparisonExecution.record(source, changed, "a".repeat(64), "release")!!) as JsonObject

            assertNull(ComparisonExecution.frame(record))
        }
    }

    @Test
    fun `repository supplied execution metadata cannot replace a missing worker receipt`() {
        val spoofed = """{"solver":"klause","labExecution":{"policy":"worker-receipt-v1"},"labAdapterResources":{"state":"complete"}}"""

        val record = Json.parseToJsonElement(ComparisonExecution.record(spoofed, null, null, "release")!!) as JsonObject

        assertNull(record["labExecution"])
        assertNull(record["labAdapterResources"])
    }
}
