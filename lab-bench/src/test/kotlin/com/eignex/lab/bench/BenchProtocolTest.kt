package com.eignex.lab.bench

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BenchProtocolTest {
    @Test
    fun `a plan retains exact workload settings and resource requirements`() {
        val plan = BenchPlan(listOf(BenchCase(
            "dot-scalar-65",
            mapOf("operation" to "dot", "backend" to "scalar", "size" to "65", "seed" to "139"),
            BenchResources(cpus = 2, memoryMiB = 512, timeoutSeconds = 30, requiredTags = setOf("arch:arm64")),
        )))

        val decoded = BenchProtocol.json.decodeFromString<BenchPlan>(BenchProtocol.json.encodeToString(plan))

        assertEquals(plan, decoded)
    }

    @Test
    fun `additive request fields do not change suite parameters`() {
        val request = BenchProtocol.json.decodeFromString<BenchRequest>(
            """{"protocolVersion":1,"parameters":{"suite":"cholesky"},"futureField":true}""",
        )

        assertEquals(mapOf("suite" to "cholesky"), request.parameters)
    }

    @Test
    fun `incompatible protocol versions are rejected on decode`() {
        for (version in listOf(0, 2)) {
            assertFailsWith<IllegalArgumentException> {
                BenchProtocol.json.decodeFromString<BenchRequest>("""{"protocolVersion":$version}""")
            }
        }
    }

    @Test
    fun `result round trips domain details and explicitly directed metrics`() {
        val result = BenchResult(
            "factor-65", metrics = listOf(BenchMetric("latency", 4.2, "us/op", MetricDirection.LOWER)),
            artifacts = listOf(BenchArtifact("captures/raw.json", "application/json")),
            details = buildJsonObject { put("backend", "simd"); put("relativeError", 1e-12) },
        )

        assertEquals(result, BenchProtocol.json.decodeFromString<BenchResult>(BenchProtocol.json.encodeToString(result)))
    }

    @Test
    fun `a plan cannot ambiguously identify two workloads`() {
        assertFailsWith<IllegalArgumentException> {
            BenchPlan(listOf(BenchCase("same", mapOf("backend" to "scalar")), BenchCase("same", mapOf("backend" to "simd"))))
        }
    }

    @Test
    fun `resources and metrics reject values that cannot be scheduled or represented`() {
        for (value in listOf(0, -1)) {
            assertFailsWith<IllegalArgumentException> { BenchResources(cpus = value) }
            assertFailsWith<IllegalArgumentException> { BenchResources(memoryMiB = value.toLong()) }
            assertFailsWith<IllegalArgumentException> { BenchResources(timeoutSeconds = value.toLong()) }
        }
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { BenchMetric("latency", value, "ns/op") }
        }
    }

    @Test
    fun `failed and skipped executions need an explicit reason`() {
        for (status in listOf(BenchStatus.FAILED, BenchStatus.SKIPPED)) {
            assertFailsWith<IllegalArgumentException> { BenchResult("case", status) }
        }
    }
}
