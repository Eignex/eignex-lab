package com.eignex.lab

import java.nio.file.Files
import kotlin.test.*

class WarmBuildSourceTest {
    @Test fun `verified warm build bypasses missing source cache and download while corrupt output reacquires pinned source`() {
        val root = Files.createTempDirectory("lazy-build-inputs")
        val caches = mutableListOf<WorkerBuildCache>()
        fun cache() = WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(root.resolve("unused.sock"))).also { caches += it }
        try {
            val source = Files.createDirectory(root.resolve("source"))
            Files.writeString(source.resolve("code"), "trusted")
            val bundle = WorkerBundle("a".repeat(64), listOf(InputFile("code", ExecutionStore.digest("trusted"), 7)),
                "Eignex/example", "b".repeat(40), "linux", "amd64", "fixture", build = WorkerBuild())
            val cache = cache()
            val budget = ResourceBudget(1000, 512)
            var acquisitions = 0; var pinned = false
            fun acquire(consume: (java.nio.file.Path) -> Unit) {
                acquisitions++; pinned = true
                try { consume(source) } finally { pinned = false }
            }
            val coldTrace = PreparationTrace()
            val cold = cache.withPreparedSource(bundle, budget, emptyMap(), { null }, { 10000 }, coldTrace, source = ::acquire) {
                assertFalse(pinned, "source pin should be released before compilation/use")
                assertEquals("trusted", Files.readString(it.resolve("code"))); it
            }
            assertEquals(1, acquisitions)
            assertEquals(1, coldTrace.snapshot().caches.getValue("build").misses)
            assertEquals("absent", coldTrace.snapshot().caches.getValue("build").reason)
            assertEquals(0L, coldTrace.snapshot().caches.getValue("build").waitingMs)
            Files.delete(source.resolve("code")); Files.delete(source)
            val trace = PreparationTrace()
            cache().withPreparedSource(bundle, budget, emptyMap(), { null }, { 10000 }, trace,
                    source = { error("warm build must never acquire or download absent source inputs") }) {
                    assertEquals(cold, it); assertEquals("trusted", Files.readString(it.resolve("code")))
                }
            assertEquals("bypass", trace.snapshot().caches["input"]?.outcome)
            assertEquals("build-hit", trace.snapshot().caches["input"]?.reason)
            assertEquals(0, trace.snapshot().caches["build"]?.hashedFiles)
            assertEquals(1, trace.snapshot().caches.getValue("build").hits)
            assertEquals(0, trace.snapshot().caches.getValue("build").misses)
            assertEquals(1, acquisitions)
            val file = cold.resolve("code"); val modified = Files.getLastModifiedTime(file)
            Files.writeString(file, "corrupt"); Files.setLastModifiedTime(file, modified)
            Files.createDirectory(source); Files.writeString(source.resolve("code"), "trusted")
            cache.withPreparedSource(bundle, budget, emptyMap(), { null }, { 10000 }, source = ::acquire) {
                assertEquals("trusted", Files.readString(it.resolve("code")))
            }
            assertEquals(2, acquisitions)
        } finally {
            caches.forEach { assertTrue(it.awaitMaintenanceIdle(), "cache cleanup did not finish before fixture removal") }
            deleteTree(root)
        }
    }
}
