package com.eignex.lab

import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import java.nio.file.Files
import kotlin.test.*

class ArtifactListingMetadataTest {
    private fun entry(name: String, bytes: Long, directory: Boolean = false, regular: Boolean = !directory) =
        MacDirectoryEntries.Entry(name, directory, regular, bytes, "mtime", "ctime", "key", false)

    @Test fun `custom stores retain their size metadata and interrupt behavior through the default fallback`() {
        var interrupt = false
        val store = object : ArtifactStore {
            override fun put(job: Long, sha: String, bytes: Long, offset: Long, input: java.io.InputStream): Long = error("unused")
            override fun offset(job: Long, sha: String): Long = error("unused")
            override fun file(job: Long, sha: String): java.nio.file.Path = error("size metadata opened payload")
            override fun size(job: Long, sha: String): Long {
                if (interrupt) throw InterruptedException()
                if (sha == "missing") throw java.io.IOException("missing")
                return 17
            }
        }
        assertEquals(mapOf("known" to 17L), store.sizes(1, setOf("known", "missing")))
        interrupt = true
        assertFailsWith<InterruptedException> { store.sizes(1, setOf("known")) }
    }

    @Test fun `CAS metadata batches exclude unknown names and links without verifying or opening payloads`() {
        val root = Files.createTempDirectory("artifact-metadata")
        val store = LocalArtifactStore(root)
        val sha = "a".repeat(64); val link = "b".repeat(64)
        var calls = 0
        val sizes = store.sizes(1, setOf(sha, link)) { path, _ ->
            calls++
            assertEquals(root.resolve("1"), path)
            listOf(entry(sha, 17), entry(link, 31, regular = false), entry("unrequested", 99))
        }
        assertEquals(mapOf(sha to 17L), sizes)
        assertEquals(1, calls)
        // A listing hint cannot make missing bytes readable or bypass ordinary checksum validation.
        assertFailsWith<IllegalArgumentException> { store.file(1, sha) }
    }

    @Test fun `unsupported listing metadata falls back without losing legacy files or following links`() {
        val root = Files.createTempDirectory("listing-fallback")
        try {
            Files.createDirectories(root.resolve("cases/0/out"))
            Files.writeString(root.resolve("cases/0/out/legacy.txt"), "legacy")
            Files.writeString(root.resolve("job.log"), "log")
            Files.createDirectories(root.resolve("nomad/prepared-trees/private"))
            Files.writeString(root.resolve("nomad/prepared-trees/private/secret"), "hidden")
            Files.createSymbolicLink(root.resolve("case-link"), root.resolve("cases"))
            val files = jobFiles(root) { _, _ -> null }
            assertEquals(listOf(FileEntry("cases/0/out/legacy.txt", 6), FileEntry("job.log", 3)), files)
            if (!System.getProperty("os.name").lowercase().contains("mac")) assertNull(MacDirectoryEntries.readListing(root, {}))
            val thread = Thread.currentThread()
            thread.interrupt()
            try { assertFailsWith<InterruptedException> { jobFiles(root) { _, _ -> null } } }
            finally { Thread.interrupted() }
        } finally { deleteTree(root) }
    }

    @Test fun `ordinary file inventory reuses directory batches and excludes links and private trees`() {
        val root = Files.createTempDirectory("job-metadata")
        Files.createDirectories(root.resolve("cases"))
        Files.createDirectories(root.resolve("nomad"))
        val visited = mutableListOf<String>()
        fun listing(name: String, bytes: Long, directory: Boolean = false, regular: Boolean = !directory) =
            MacDirectoryEntries.ListingEntry(name, directory, regular, bytes)
        val files = jobFiles(root) { path, _ ->
            val name = root.relativize(path).joinToString("/"); visited += name
            when (name) {
                "" -> listOf(listing("cases", 0, directory = true), listing("nomad", 0, directory = true), listing("log-link", 0, regular = false))
                "cases" -> listOf(listing("ordinary.txt", 21))
                "nomad" -> listOf(listing("prepared-trees", 0, directory = true))
                else -> error("private subtree was visited")
            }
        }
        assertEquals(listOf(FileEntry("cases/ordinary.txt", 21)), files)
        assertEquals(listOf("", "cases", "nomad"), visited)
    }
}
