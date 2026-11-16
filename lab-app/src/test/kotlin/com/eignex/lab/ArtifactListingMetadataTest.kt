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

    @Test fun `exact logical replacements avoid physical lengths while legacy directories and changed links remain visible safely`() {
        val root = Files.createTempDirectory("logical-size-selection")
        try {
            Files.createDirectory(root.resolve("accepted-dir"))
            Files.createDirectory(root.resolve("legacy-dir"))
            Files.writeString(root.resolve("legacy"), "legacy")
            Files.writeString(root.resolve("exact"), "projection")
            Files.createSymbolicLink(root.resolve("changed-link"), root.resolve("exact"))
            val sizedDirectories = mutableListOf<String>()
            val statted = mutableListOf<String>()
            val files = jobFiles(root, setOf("exact", "accepted-dir"), names = { path, _ ->
                assertEquals(root, path)
                listOf(MacDirectoryEntries.NameEntry("exact", false, true),
                    MacDirectoryEntries.NameEntry("accepted-dir", true, false),
                    MacDirectoryEntries.NameEntry("legacy-dir", true, false),
                    MacDirectoryEntries.NameEntry("legacy", false, true),
                    MacDirectoryEntries.NameEntry("changed-link", false, true), // Changed to a symlink after enumeration.
                    MacDirectoryEntries.NameEntry("ignored-link", false, false))
            }, entries = { path, _ ->
                val relative = root.relativize(path).joinToString("/"); sizedDirectories += relative
                when (relative) {
                    "accepted-dir" -> listOf(MacDirectoryEntries.ListingEntry("child", false, true, 7))
                    "legacy-dir" -> listOf(MacDirectoryEntries.ListingEntry("child", false, true, 9))
                    else -> error("unexpected sized directory")
                }
            }, attributes = { path ->
                val relative = root.relativize(path).joinToString("/"); statted += relative
                assertTrue(relative !in setOf("exact", "accepted-dir"), "accepted size is not physical authority")
                Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
            })
            assertEquals(listOf(FileEntry("accepted-dir/child", 7), FileEntry("exact", FileEntry.UNKNOWN_BYTES),
                FileEntry("legacy", 6), FileEntry("legacy-dir/child", 9)), files)
            assertEquals(listOf("accepted-dir", "legacy-dir"), sizedDirectories)
            assertEquals(listOf("legacy", "changed-link"), statted)
        } finally { deleteTree(root) }
    }

    @Test fun `unknown physical metadata failures stay visible and unsupported narrow listings retain ordinary sizes`() {
        val root = Files.createTempDirectory("logical-size-fallback")
        try {
            Files.writeString(root.resolve("accepted"), "projection")
            Files.writeString(root.resolve("legacy"), "legacy")
            val ordinary = jobFiles(root) { _, _ -> null }
            val fallback = jobFiles(root, setOf("accepted"), names = { _, _ -> null })
            assertEquals(ordinary, fallback)
            assertFailsWith<java.io.IOException> {
                jobFiles(root, setOf("accepted"), names = { _, _ -> listOf(MacDirectoryEntries.NameEntry("legacy", false, true)) },
                    attributes = { throw java.io.IOException("unreadable legacy metadata") })
            }
            val sized = mutableListOf<java.nio.file.Path>()
            assertEquals(listOf(FileEntry("legacy", 6)), jobFiles(root, emptySet(),
                names = { _, _ -> error("legacy-only directories must keep sized batching") },
                entries = { path, _ -> sized.add(path); listOf(MacDirectoryEntries.ListingEntry("legacy", false, true, 6)) }))
            assertEquals(listOf(root), sized)
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
