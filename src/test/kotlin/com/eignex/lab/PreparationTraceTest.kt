package com.eignex.lab

import kotlin.test.*

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
}
