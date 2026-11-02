package com.eignex.lab

import com.eignex.lab.domain.*
import com.eignex.lab.execution.Repositories
import com.eignex.lab.nomad.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.*

class SourceBundleCacheTest {
    private val root = Files.createTempDirectory("approved-source-reuse")
    private val blobs = root.resolve("artifacts")
    private val hints = root.resolve("hints")
    private val artifacts = LocalArtifactStore(blobs)
    private val bytes = "approved source bytes".toByteArray()
    private val bundle = WorkerBundle(ExecutionStore.digest(String(bytes)),
        listOf(InputFile("source.git.bundle", ExecutionStore.digest(String(bytes)), bytes.size.toLong())),
        Repositories.DEFAULT, "a".repeat(40), "mac", "arm64", "jdk25", build = WorkerBuild(gitBundle = "source.git.bundle"))
    private fun cache(maxEntries: Int = 256, ttlMs: Long = 604800000) = SourceBundleCache(hints, artifacts, maxEntries, ttlMs)
    private fun publish(job: Long, value: WorkerBundle = bundle) {
        artifacts.put(job, value.sha256, bytes.size.toLong(), 0, bytes.inputStream())
    }
    private fun reuse(job: Long, cache: SourceBundleCache = cache(), value: WorkerBundle = bundle,
                      build: WorkerBuild = WorkerBuild(), authorize: () -> Unit = {},
                      recover: () -> Sequence<Pair<Long, WorkerBundle>> = { emptySequence() }) =
        cache.reuse(job, value.repository, value.commit, "linux", "amd64", "jdk25", build, authorize, recover)

    @Test fun `restart reuses job-owned bytes without export and applies the current build and platform`() {
        publish(1); cache().remember(1, bundle, bytes.size.toLong())
        val build = WorkerBuild(listOf(":bench:installDist"), timeoutSec = 321, benchManifest = "bench.json", requiredTags = setOf("java:25"))
        val result = assertNotNull(reuse(2, build = build, recover = { error("a metadata hit must not rediscover or export source") }))
        assertEquals(bundle.copy(os = "linux", arch = "amd64", build = build.copy(gitBundle = "source.git.bundle")), result)
        assertTrue(Files.isSameFile(artifacts.file(1, bundle.sha256), artifacts.file(2, bundle.sha256)))
        deleteTree(blobs.resolve("1"))
        assertNotNull(reuse(3)); assertContentEquals(bytes, Files.readAllBytes(artifacts.file(3, bundle.sha256)))
    }

    @Test fun `cache hits still require fresh trust before any artifact or recovery access`() {
        publish(1); cache().remember(1, bundle, bytes.size.toLong())
        assertFailsWith<IllegalStateException> {
            reuse(2, authorize = { error("repository trust revoked") }, recover = { error("must not recover") })
        }
        assertFalse(Files.exists(blobs.resolve("2")))
    }

    @Test fun `upgrade can recover a retained active source before the metadata index exists`() {
        publish(1)
        val first = assertNotNull(reuse(2, recover = { sequenceOf(1L to bundle) }))
        assertEquals(bundle.sha256, first.sha256)
        assertTrue(Files.isSameFile(artifacts.file(1, bundle.sha256), artifacts.file(2, bundle.sha256)))
        assertNotNull(reuse(3, recover = { error("recovery must populate the disposable hint") }))
    }

    @Test fun `different commits repositories and archives with extra inputs cannot share a source hint`() {
        publish(1); cache().remember(1, bundle, bytes.size.toLong())
        assertNull(reuse(2, value = bundle.copy(commit = "b".repeat(40)), recover = { sequenceOf(1L to bundle) }))
        assertNull(reuse(3, value = bundle.copy(repository = "Eignex/example"), recover = { sequenceOf(1L to bundle) }))
        val extra = bundle.copy(files = bundle.files + InputFile("lab-inputs/model", "b".repeat(64), 1))
        assertNull(reuse(4, recover = { sequenceOf(1L to extra) }))
        assertFailsWith<IllegalArgumentException> { cache().remember(1, extra, bytes.size.toLong()) }
    }

    @Test fun `missing or corrupt retained source is a miss and never becomes a new job artifact`() {
        publish(1); cache().remember(1, bundle, bytes.size.toLong())
        val original = blobs.resolve("1/${bundle.sha256}")
        Files.write(original, ByteArray(bytes.size))
        assertNull(reuse(2)); assertFalse(Files.exists(blobs.resolve("2/${bundle.sha256}")))
        Files.delete(original); publish(1); cache().remember(1, bundle, bytes.size.toLong())
        deleteTree(blobs.resolve("1"))
        assertNull(reuse(3)); assertFalse(Files.exists(blobs.resolve("3/${bundle.sha256}")))
    }

    @Test fun `corrupt parseable or truncated metadata falls back without trusting the hint`() {
        publish(1); cache().remember(1, bundle, bytes.size.toLong())
        fun hint() = Files.list(hints).use { paths -> paths.filter { it.fileName.toString().matches(SHA256) }.toList().single() }
        val path = hint()
        Files.writeString(path, Files.readString(path).replace("\"bytes\":${bytes.size}", "\"bytes\":999"))
        assertNull(reuse(2))
        cache().remember(1, bundle, bytes.size.toLong()); Files.writeString(hint(), "{")
        assertNull(reuse(3))
        assertContentEquals(bytes, Files.readAllBytes(artifacts.file(1, bundle.sha256)))
    }

    @Test fun `hint retention is bounded and never retains or removes source data`() {
        publish(1)
        val cache = cache(maxEntries = 2, ttlMs = 10000)
        val values = (1..3).map { bundle.copy(commit = it.toString().repeat(40)) }
        for ((index, value) in values.withIndex()) {
            cache.remember(1, value, bytes.size.toLong())
            val key = ExecutionStore.digest("approved-git-bundle-v1\n${value.repository}\n${value.commit}")
            Files.setLastModifiedTime(hints.resolve(key), FileTime.fromMillis(now() - 100 + index))
        }
        assertEquals(2, Files.list(hints).use { paths -> paths.filter { it.fileName.toString().matches(SHA256) }.count() })
        assertNull(reuse(2, cache, values[0]))
        val lastKey = ExecutionStore.digest("approved-git-bundle-v1\n${values.last().repository}\n${values.last().commit}")
        Files.setLastModifiedTime(hints.resolve(lastKey), FileTime.fromMillis(now() - 11000))
        assertNull(reuse(3, cache, values.last()))
        assertContentEquals(bytes, Files.readAllBytes(artifacts.file(1, bundle.sha256)))
    }
}
