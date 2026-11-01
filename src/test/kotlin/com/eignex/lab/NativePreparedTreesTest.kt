package com.eignex.lab

import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class NativePreparedTreesTest {
    private val root = Files.createTempDirectory("native-trees-")
    private val jobs = root.resolve("jobs")
    private val store = NativePreparedTrees(jobs)
    private fun publish(job: Long = 1): Pair<Path, WorkerBundle> {
        val source = Files.createTempDirectory(root, "workspace-")
        Files.createDirectories(source.resolve("bin")); Files.createDirectories(source.resolve("empty"))
        Files.writeString(source.resolve("bin/run"), "echo original\n")
        source.resolve("bin/run").toFile().setExecutable(true, true)
        Files.writeString(source.resolve("model.txt"), "source")
        val proof = source.resolveSibling("${source.fileName}.verified.json")
        val files = TreeVerification().capture(source, proof)
        val identity = WorkerBundle("0".repeat(64), files, Repositories.DEFAULT, "a".repeat(40), "mac", "arm64", "jdk25")
        val inode = Files.readAttributes(source, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        return store.publish(job, source, proof, identity, {}).also { (_, bundle) ->
            assertFalse(Files.exists(source), "handoff moves rather than copies the workspace")
            store.use(bundle, {}) { tree, _ -> assertEquals(inode, Files.readAttributes(tree, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()) }
        }
    }

    @Test fun `handoff preserves identity without ZIP and survives a fresh worker and empty caches`() {
        val (manifest, bundle) = publish()
        assertEquals(bundle.sha256, hashFile(manifest))
        assertEquals("preparation/${bundle.commit}.tree.json", preparationArtifact(bundle))
        assertFalse(Files.exists(manifest.parent.resolve("export-data")))
        assertTrue(jobFiles(jobs.resolve("1")).isEmpty(), "internal inputs must not become thousands of result downloads")
        assertNull(jobFile(jobs.resolve("1"), listOf("nomad", "prepared-trees", bundle.sha256, "tree", "model.txt")))
        val restarted = NativePreparedTrees(jobs)
        assertTrue(restarted.valid(bundle, {}))
        val target = root.resolve("new-worker")
        val receipt = root.resolve("new-worker.json")
        restarted.materialize(bundle, bundle.files, target, receipt, {})
        Files.writeString(target.resolve("model.txt"), "private mutation")
        restarted.use(bundle, {}) { original, _ -> assertEquals("source", Files.readString(original.resolve("model.txt"))) }
        assertTrue(Files.isDirectory(target.resolve("empty")))
        assertTrue(Files.isExecutable(target.resolve("bin/run")))
    }

    @Test fun `portable export verifies every file without treating a tree digest as a ZIP digest`() {
        val (_, bundle) = publish()
        val archive = store.archive(bundle)
        assertNotEquals(bundle.sha256, hashFile(archive))
        val staged = VerifiedCache(root.resolve("remote-worker")).stage(bundle) { Files.newInputStream(archive) }
        assertEquals("source", Files.readString(staged.resolve("model.txt")))
        assertTrue(Files.isDirectory(staged.resolve("empty")))
        assertEquals(archive, NativePreparedTrees(jobs).archive(bundle))
        Files.write(archive, byteArrayOf(0))
        assertEquals("source", Files.readString(VerifiedCache(root.resolve("remote-retry")).stage(bundle) { Files.newInputStream(store.archive(bundle)) }.resolve("model.txt")))
    }

    @Test fun `selected source checking copies only declared source files without downloading runtime`() {
        val (_, bundle) = publish()
        val cache = VerifiedCache(root.resolve("checks"), materialize = store::materialize)
        val checked = cache.stageSelected(bundle, { it == "model.txt" }) { error("native source checking must not download a ZIP") }
        assertEquals("source", Files.readString(checked.resolve("model.txt")))
        assertFalse(Files.exists(checked.resolve("bin")))
    }

    @Test fun `corruption restored timestamps and directory changes fail closed`() {
        val (manifest, bundle) = publish()
        val file = manifest.parent.resolve("tree/model.txt")
        val mtime = Files.getLastModifiedTime(file)
        Files.writeString(file, "broken"); Files.setLastModifiedTime(file, mtime)
        assertFalse(store.valid(bundle, {}))
        assertFailsWith<IllegalArgumentException> { store.use(bundle, {}) { _, _ -> error("corrupt snapshot used") } }
        val (secondManifest, second) = publish(2)
        Files.delete(secondManifest.parent.resolve("tree/empty"))
        assertFalse(store.valid(second, {}))
        assertFailsWith<IllegalArgumentException> { second.copy(sha256 = "f".repeat(64)) }
    }

    @Test fun `a replay owns its retained files and follows ordinary job trimming`() {
        val (_, bundle) = publish()
        val replay = store.retain(bundle, 2)
        assertEquals(bundle.sha256, replay.sha256)
        deleteTree(jobs.resolve("1"))
        assertTrue(store.valid(replay, {}))
        JobTrim(jobs, { listOf(2L to 0L) }, { Long.MAX_VALUE }, { _, _ -> }, clock = { 100L * MS_PER_DAY }).run()
        assertFalse(store.valid(replay, {}))
    }

    @Test fun `a failed durability barrier never publishes a snapshot`() {
        val source = Files.createDirectories(root.resolve("unflushed"))
        Files.writeString(source.resolve("file"), "bytes")
        val proof = root.resolve("unflushed.json")
        val files = TreeVerification().capture(source, proof)
        val identity = WorkerBundle("0".repeat(64), files, Repositories.DEFAULT, "a".repeat(40), "mac", "arm64", "jdk25")
        assertFailsWith<java.io.IOException> { store.publish(1, source, proof, identity, {}, force = { throw java.io.IOException("flush failed") }) }
        assertTrue(Files.exists(source))
        assertEquals(0L, Files.list(jobs.resolve("1/nomad/prepared-trees")).use { it.filter { path -> Files.isDirectory(path) }.count() })
    }
}
