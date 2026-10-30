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

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WorkerArchiveCacheTest {
    private val root = Files.createTempDirectory("worker-archive-cache")
    private val source = Files.createDirectory(root.resolve("source"))
    private val file = Files.writeString(source.resolve("code"), "hello")
    private val cacheRoot = root.resolve("archives")
    private val identity = WorkerBundle("a".repeat(64), listOf(InputFile("placeholder", "b".repeat(64), 0)), "Eignex/klause", "a".repeat(40), "linux", "amd64", "fixture")
    private var created = 0
    private fun cache(limit: Long = 1024 * 1024) = WorkerArchiveCache(cacheRoot, limit) { tree, output, bundle, runtime, check ->
        created++; archive(tree, output, bundle, runtime, check)
    }
    private fun use(cache: WorkerArchiveCache = cache(), runtime: Boolean = false): WorkerBundle =
        cache.use(source, identity, runtime, {}) { zip, bundle ->
            assertEquals(bundle.sha256, hashFile(zip)); bundle
        }

    @Test fun `identical verified inventories reuse ZIPs across workers and preserve caller identities`() {
        val first = use()
        val second = cache().use(source, identity.copy(os = "mac", arch = "arm64", toolchain = "other"), false, {}) { zip, bundle ->
            assertEquals(first.sha256, hashFile(zip)); bundle
        }
        assertEquals(1, created); assertEquals(first.files, second.files)
        assertEquals("mac", second.os); assertEquals("other", second.toolchain)
    }

    @Test fun `same length changes with restored mtime and directory layout invalidate archives`() {
        val first = use(); val mtime = Files.getLastModifiedTime(file)
        Files.writeString(file, "wrong"); Files.setLastModifiedTime(file, mtime)
        val changed = use(); assertNotEquals(first.sha256, changed.sha256); assertEquals(2, created)
        Files.createDirectory(source.resolve("empty"))
        assertNotEquals(changed.sha256, use().sha256); assertEquals(3, created)
    }

    @Test fun `corrupt archives rebuild and missing proof receipts force checksum verification`() {
        val first = use()
        val entry = Files.list(cacheRoot).use { paths -> paths.filter { SHA256.matches(it.fileName.toString()) }.toList().single() }
        val zip = entry.resolve("data/archive.zip"); val modified = Files.getLastModifiedTime(zip)
        Files.write(zip, ByteArray(Files.size(zip).toInt())); Files.setLastModifiedTime(zip, modified)
        assertEquals(first.sha256, use().sha256); assertEquals(2, created)
        Files.delete(entry.resolve("verified.json"))
        assertEquals(first.sha256, use().sha256); assertEquals(2, created)
    }

    @Test fun `runtime keys omit compiler changes while custom bundles preserve full contents`() {
        val scratch = Files.createDirectories(source.resolve("module/build/classes"))
        val compiled = Files.writeString(scratch.resolve("Main.class"), "compiled")
        val first = use(runtime = true)
        Files.writeString(compiled, "changed")
        assertEquals(first.sha256, use(runtime = true).sha256); assertEquals(1, created)
        val full = use(runtime = false)
        assertNotEquals(first.sha256, full.sha256)
        assertTrue(full.files.any { it.path.endsWith("Main.class") }); assertEquals(2, created)
    }

    @Test fun `active archive pins survive eviction and unused entries obey the compressed byte limit`() {
        val cache = cache(limit = 0)
        cache.use(source, identity, false, {}) { zip, _ ->
            cache.maintain(); assertTrue(Files.exists(zip))
            val pin = Files.list(cacheRoot.resolve("pins")).use { it.toList().single() }
            val process = ProcessBuilder("python3", "-c", "import fcntl,sys; f=open(sys.argv[1],'r+'); fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)", pin.toString()).redirectErrorStream(true).start()
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)); assertNotEquals(0, process.exitValue())
        }
        cache.maintain()
        assertTrue(Files.list(cacheRoot).use { paths -> paths.noneMatch { SHA256.matches(it.fileName.toString()) } })
    }

    @Test fun `failed archive creation leaves no staging and abandoned staging follows crash grace`() {
        val failing = WorkerArchiveCache(cacheRoot) { _, _, _, _, _ -> error("interrupted") }
        assertFailsWith<IllegalStateException> { use(failing) }
        assertFalse(Files.list(cacheRoot).use { paths -> paths.anyMatch { it.fileName.toString().startsWith(".build-") } })
        val staging = WorkerBuildStaging(cacheRoot).create({}); val directory = staging.directory; staging.close()
        val at = now(); cache().maintain(at = at)
        assertTrue(Files.exists(directory))
        cache().maintain(at = at + 5 * 60_000)
        assertFalse(Files.exists(directory))
    }
    @Test fun `concurrent identical private trees publish one archive and keep both readers pinned`() {
        val other = Files.createDirectory(root.resolve("other-source")); Files.writeString(other.resolve("code"), "hello")
        val count = java.util.concurrent.atomic.AtomicInteger()
        val cache = WorkerArchiveCache(cacheRoot) { tree, output, bundle, runtime, check ->
            count.incrementAndGet(); Thread.sleep(100); archive(tree, output, bundle, runtime, check)
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        val readers = java.util.concurrent.CountDownLatch(2)
        try {
            val futures = listOf(source, other).map { tree -> pool.submit<String> {
                cache.use(tree, identity, false, {}) { zip, bundle ->
                    readers.countDown(); assertTrue(readers.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    cache.maintain(); assertEquals(bundle.sha256, hashFile(zip)); bundle.sha256
                }
            } }
            assertEquals(futures[0].get(20, java.util.concurrent.TimeUnit.SECONDS), futures[1].get(20, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, count.get())
        } finally { pool.shutdownNow() }
    }

    @Test fun `cold archives retain checksums executables and empty directories with mixed compression`() {
        val random = ByteArray(2 * 1024 * 1024).also { java.util.Random(42).nextBytes(it) }
        Files.write(source.resolve("dependency.jar"), random)
        Files.writeString(source.resolve("compressible.kt"), "source content\n".repeat(10000))
        Files.createDirectories(source.resolve("empty/nested"))
        assertTrue(file.toFile().setExecutable(true, true))
        val cache = WorkerArchiveCache(cacheRoot)
        val trace = PreparationTrace()
        val first = cache.use(source, identity, false, {}, trace) { zip, bundle ->
            val extracted = VerifiedCache(root.resolve("unpacked")).stage(bundle) { Files.newInputStream(zip) }
            assertContentEquals(random, Files.readAllBytes(extracted.resolve("dependency.jar")))
            assertTrue(Files.isExecutable(extracted.resolve("code")))
            assertTrue(Files.isDirectory(extracted.resolve("empty/nested")))
            assertEquals(bundle.sha256, hashFile(zip))
            assertTrue(Files.size(zip) < random.size + 20_000) // Source still compresses; binary framing adds little.
            bundle
        }
        assertEquals("miss", trace.snapshot().caches.getValue("archive").outcome)
        val hit = PreparationTrace()
        cache.use(source, identity, false, {}, hit) { _, bundle -> assertEquals(first.sha256, bundle.sha256) }
        assertEquals("hit", hit.snapshot().caches.getValue("archive").outcome)
        assertEquals(0, hit.snapshot().caches.getValue("archive").hashedFiles)
        assertEquals(0L, hit.snapshot().caches.getValue("archive").hashedBytes)
        assertTrue(compressedArchivePath(".git/objects/pack/object.pack"))
        assertFalse(compressedArchivePath("src/Main.kt"))
    }

}
