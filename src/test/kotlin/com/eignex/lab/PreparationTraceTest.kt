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

import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PreparationTraceTest {
    @Test fun `last child keeps its arm and checkout identity after the next arm unwinds`() {
        val root = java.nio.file.Files.createTempDirectory("preparation-child-evidence")
        val out = root.resolve("build.out"); val err = root.resolve("build.err")
        java.nio.file.Files.writeString(out, "x".repeat(5000) + "first arm compiled")
        java.nio.file.Files.writeString(err, "")
        val trace = PreparationTrace()
        val budget = ResourceBudget(1000, 512)
        val recorder = PreparationRecorder(ResourceObservation("advisory-native", budget, null, null, "test", "test", "test", emptyMap()), trace)
        try {
            trace.step("compilation") {
                recorder.build(out, err, PreparationBuildDiagnostic(0, kind = "compilation", stage = trace.snapshot().stage, sourceCommit = "a".repeat(40)))
            }
            val first = recorder.snapshot(1000, 2000).build!!
            assertEquals("a".repeat(40), first.sourceCommit); assertEquals("compilation", first.kind)
            assertEquals(4096, first.stdout.length)
            java.nio.file.Files.writeString(out, "second arm checkout succeeded")
            assertFailsWith<IllegalStateException> {
                trace.step("checkout") {
                    recorder.build(out, err, PreparationBuildDiagnostic(0, kind = "checkout", stage = trace.snapshot().stage, sourceCommit = "b".repeat(40)))
                    error("second arm failed before compilation")
                }
            }
            deleteTree(root)
            val final = recorder.snapshot(1000, 2000)
            assertEquals("preparing", final.stage)
            assertEquals("b".repeat(40), final.build!!.sourceCommit)
            assertEquals("checkout", final.build.kind); assertEquals("checkout", final.build.stage)
            assertEquals(0, final.build.exitCode)
            assertEquals("second arm checkout succeeded", final.build.stdout)
        } finally { deleteTree(root) }
    }

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
    @Test fun `cache work accumulates across arms and publication without hiding earlier rehashes`() {
        val trace = PreparationTrace()
        trace.cache("input", "hit", "receipt-missing", verificationMs = 100, waitingMs = 20, checkedFiles = 4096, hashedFiles = 4096, hashedBytes = 268_000_000)
        trace.cache("input", "hit", "verified-local", verificationMs = 5, checkedFiles = 4096)
        val event = trace.snapshot().caches.getValue("input")
        assertEquals("verified-local", event.reason)
        assertEquals(2, event.hits); assertEquals(8192, event.checkedFiles)
        assertEquals(4096, event.hashedFiles); assertEquals(268_000_000, event.hashedBytes)
        assertEquals(105, event.verificationMs); assertEquals(20, event.waitingMs)
        repeat(3) { trace.cache("input", "hit", "verified", verificationMs = 86_400_000, checkedFiles = Int.MAX_VALUE, hashedBytes = Long.MAX_VALUE) }
        val bounded = trace.snapshot().caches.getValue("input")
        assertEquals(Int.MAX_VALUE, bounded.checkedFiles); assertEquals(Long.MAX_VALUE, bounded.hashedBytes)
        assertEquals(86_400_000, bounded.verificationMs)
    }
}
