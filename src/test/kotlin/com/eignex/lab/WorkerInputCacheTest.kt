package com.eignex.lab

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WorkerInputCacheTest {
    private val root = Files.createTempDirectory("worker-inputs")
    private var downloads = 0
    private fun bundle(name: String): Pair<WorkerBundle, Path> {
        val tree = Files.createDirectory(root.resolve(name)); Files.writeString(tree.resolve("file"), name)
        val identity = WorkerBundle("a".repeat(64), listOf(InputFile("file", ExecutionStore.digest(name), name.length.toLong())),
            Repositories.DEFAULT, "a".repeat(40), "linux", "amd64", "fixture")
        val zip = root.resolve("$name.zip")
        return archive(tree, zip, identity) {} to zip
    }
    private fun <T> use(cache: WorkerInputCache, value: Pair<WorkerBundle, Path>, action: (Path) -> T): T =
        cache.use(value.first, {}, { downloads++; Files.newInputStream(value.second) }, action)

    @Test fun `separate allocations reuse verified inputs while private copies stay independent`() {
        val value = bundle("fixture")
        use(WorkerInputCache(root.resolve("shared")), value) { source ->
            WorkerTrees.copy(source, root.resolve("attempt-one")) {}
        }
        Files.writeString(root.resolve("attempt-one/file"), "changed")
        use(WorkerInputCache(root.resolve("shared")), value) { source ->
            assertEquals("fixture", Files.readString(source.resolve("file")))
            WorkerTrees.copy(source, root.resolve("attempt-two")) {}
        }
        assertEquals("fixture", Files.readString(root.resolve("attempt-two/file")))
        assertEquals(1, downloads)
    }

    @Test fun `eviction preserves active process pins and reclaims inputs after readers finish`() {
        val cache = WorkerInputCache(root.resolve("shared"), maxBytes = 0)
        val one = bundle("one"); val two = bundle("two")
        lateinit var first: Path; lateinit var second: Path
        use(cache, one) { a ->
            first = a
            use(WorkerInputCache(root.resolve("shared"), maxBytes = 0), two) { b ->
                second = b; cache.maintain()
                assertTrue(Files.exists(a)); assertTrue(Files.exists(b))
            }
            cache.maintain()
            assertTrue(Files.exists(a)); assertFalse(Files.exists(second))
        }
        cache.maintain(); assertFalse(Files.exists(first))
        use(cache, one) { assertEquals("one", Files.readString(it.resolve("file"))) }
        assertEquals(3, downloads)
    }

    @Test fun `corrupt shared input is reverified and refreshed before another allocation uses it`() {
        val value = bundle("fixture"); val cache = WorkerInputCache(root.resolve("shared"))
        use(cache, value) { source ->
            val file = source.resolve("file"); val modified = Files.getLastModifiedTime(file)
            Files.writeString(file, "corrupt"); Files.setLastModifiedTime(file, modified)
        }
        use(cache, value) { assertEquals("fixture", Files.readString(it.resolve("file"))) }
        assertEquals(2, downloads)
    }
    @Test fun `direct publication seeds a verified private tree without downloading and preserves empty directories`() {
        val value = bundle("seeded"); val source = root.resolve("seeded")
        Files.createDirectories(source.resolve("empty/nested"))
        val receipt = root.resolve("seeded.verified.json")
        TreeVerification().capture(source, receipt)
        val cache = WorkerInputCache(root.resolve("shared"))
        val publicationTrace = PreparationTrace()
        publicationTrace.cache("input", "bypass", "build-hit")
        cache.seed(value.first, source, receipt, {}, publicationTrace)
        val published = publicationTrace.snapshot().caches.getValue("prepared-input")
        assertEquals(1, published.misses); assertEquals(0, published.hits)
        assertEquals(5, published.checkedFiles); assertEquals(0, published.hashedFiles)
        assertEquals(0L, published.waitingMs)
        assertEquals("bypass", publicationTrace.snapshot().caches["input"]?.outcome)
        Files.writeString(source.resolve("file"), "change")
        cache.seed(value.first, source, receipt, {}, publicationTrace)
        val reused = publicationTrace.snapshot().caches.getValue("prepared-input")
        assertEquals(1, reused.misses); assertEquals(1, reused.hits)
        assertEquals(6, reused.checkedFiles); assertEquals(0, reused.hashedFiles)
        assertEquals("build-hit", publicationTrace.snapshot().caches["input"]?.reason)
        use(cache, value) { input ->
            assertEquals("seeded", Files.readString(input.resolve("file")))
            assertTrue(Files.isDirectory(input.resolve("empty/nested")))
        }
        assertEquals(0, downloads)
        val proof = root.resolve("shared/entries/${inputCacheKey(value.first)}.verified.json")
        val trace = PreparationTrace()
        cache.use(value.first, {}, { error("warm seed must not download") }, trace) {}
        assertEquals("hit", trace.snapshot().caches["input"]?.outcome)
        assertEquals(0, trace.snapshot().caches["input"]?.hashedFiles)
        assertTrue(Files.isRegularFile(proof))
    }

    @Test fun `direct publication checks missing receipts and rejects corrupt source before publication`() {
        val value = bundle("seeded"); val cache = WorkerInputCache(root.resolve("shared"))
        val source = root.resolve("seeded"); val receipt = root.resolve("missing.json")
        Files.writeString(source.resolve("file"), "broken")
        assertFailsWith<IllegalArgumentException> { cache.seed(value.first, source, receipt, {}) }
        assertFalse(Files.exists(root.resolve("shared/entries/${inputCacheKey(value.first)}")))
        Files.writeString(source.resolve("file"), "seeded")
        cache.seed(value.first, source, receipt, {})
        use(cache, value) { assertEquals("seeded", Files.readString(it.resolve("file"))) }
        assertEquals(0, downloads)
    }

}
