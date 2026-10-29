package com.eignex.lab

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PreparationTraceTest {
    @Test fun `nested phase timings are exclusive and failures restore the parent phase`() {
        var clock = 0L
        val trace = PreparationTrace { clock }
        trace.step("inputs") {
            clock += 10_000_000
            trace.step("download") { clock += 30_000_000 }
            clock += 20_000_000
            val live = trace.snapshot()
            assertEquals("inputs", live.stage); assertEquals(60, live.elapsedMs)
            assertEquals(30, live.timings["inputs"]); assertEquals(30, live.timings["download"])
            assertEquals(20, live.stageElapsedMs)
            assertFailsWith<IllegalStateException> { trace.step("compilation") { clock += 5_000_000; error("failed") } }
            assertEquals("inputs", trace.snapshot().stage)
        }
        trace.ready()
        assertEquals("ready", trace.snapshot().stage)
        assertEquals(65, trace.snapshot().timings.values.sum())
        assertFailsWith<IllegalArgumentException> { PreparationProgress("arbitrary-command", 1, 0, emptyMap()) }
    }
    @Test fun `cache observations are bounded immutable and optional for older workers`() {
        val legacy = """{"stage":"inputs","elapsedMs":10,"stageElapsedMs":10,"timings":{"inputs":10}}"""
        assertTrue(Json.decodeFromString<PreparationProgress>(legacy).caches.isEmpty())
        val trace = PreparationTrace()
        trace.cache("input", "miss", "absent")
        val first = trace.snapshot()
        trace.cache("input", "hit", "verified-local", verificationMs = 123, waitingMs = 456, bytes = 200_000_000, files = 4096)
        assertEquals("miss", first.caches.getValue("input").outcome)
        val second = trace.snapshot()
        assertEquals(1, second.caches.size)
        assertEquals(123, second.caches.getValue("input").verificationMs)
        assertEquals(second, Json.decodeFromString<PreparationProgress>(Json.encodeToString(second)))
        assertTrue(Json.encodeToString(second).length < 2000)
        assertFailsWith<IllegalArgumentException> { trace.cache("/secret/path", "miss", "absent") }
        assertFailsWith<IllegalArgumentException> { trace.cache("build", "miss", "arbitrary command") }
        assertFailsWith<IllegalArgumentException> { trace.cache("build", "hit", "verified", waitingMs = -1) }
    }
}
