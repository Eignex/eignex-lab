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
}
