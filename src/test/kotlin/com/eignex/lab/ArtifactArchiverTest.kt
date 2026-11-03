package com.eignex.lab

import com.eignex.lab.store.*
import com.eignex.lab.util.*

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.test.*

class ArtifactArchiverTest {
    private val root = Files.createTempDirectory("archiver")
    private val hot = Files.createDirectories(root.resolve("hot"))
    private val archive = Files.createDirectories(root.resolve("archive"))
    private val sha = "a".repeat(64)
    private val other = "b".repeat(64)
    private val logs = mutableListOf<String>()

    /** Portable stand-in for renamex_np(RENAME_SWAP): the test only needs its end state. */
    private fun swap(first: Path, second: Path) {
        val parked = first.resolveSibling(".swap-${first.fileName}")
        Files.move(first, parked); Files.move(second, first); Files.move(parked, second)
    }

    private fun archiver(ended: List<Pair<Long, Long>>, pending: Set<Long> = emptySet(), at: Long = 10_000_000, swap: (Path, Path) -> Unit = ::swap) =
        ArtifactArchiver(hot, archive, { ended }, { it in pending }, graceMs = 60_000, swap = swap, flush = { }, clock = { at }, log = { logs += it })

    private fun job(id: Long, vararg files: Pair<String, String>): Path = Files.createDirectories(hot.resolve("$id")).also { dir ->
        for ((name, body) in files) Files.writeString(dir.resolve(name), body)
    }

    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    @Test fun `an ended job moves to the archive and its hot path becomes a link`() {
        job(7, sha to "result", "$sha.lock" to "")
        assertEquals(listOf(7L), archiver(listOf(7L to 0L)).pass())
        val path = hot.resolve("7")
        assertTrue(Files.isSymbolicLink(path))
        assertEquals(archive.resolve("7"), Files.readSymbolicLink(path))
        assertEquals("result", Files.readString(path.resolve(sha)), "readers resolve through the link")
        assertFalse(Files.exists(archive.resolve("7/$sha.lock")), "lock files are recreated on demand, not archived")
        assertFalse(Files.exists(hot.resolve(".archive-link-7"), NOFOLLOW_LINKS), "the former hot directory is removed")
    }

    @Test fun `recent, pending, linked and nested jobs stay on the hot store`() {
        job(1, sha to "x"); job(2, sha to "x"); job(4, sha to "x")
        Files.createDirectories(hot.resolve("4/nested"))
        Files.createDirectories(archive.resolve("3")); Files.createSymbolicLink(hot.resolve("3"), archive.resolve("3"))
        val archived = archiver(listOf(1L to 9_990_000L, 2L to 0L, 3L to 0L, 4L to 0L), pending = setOf(2L)).pass()
        assertEquals(emptyList(), archived)
        listOf(1, 2, 4).forEach { assertTrue(Files.isDirectory(hot.resolve("$it"), NOFOLLOW_LINKS)) }
        assertTrue(logs.any { "job 4 kept" in it })
    }

    @Test fun `an existing archive copy is completed rather than replaced`() {
        job(9, sha to "complete", other to "new")
        Files.createDirectories(archive.resolve("9")); Files.writeString(archive.resolve("9/$sha"), "complete")
        assertEquals(listOf(9L), archiver(listOf(9L to 0L)).pass())
        assertEquals("new", Files.readString(archive.resolve("9/$other")))
    }

    @Test fun `files written during the copy are carried over before the hot directory is removed`() {
        job(5, sha to "first")
        val late: (Path, Path) -> Unit = { first, second -> Files.writeString(first.resolve(other), "late"); swap(first, second) }
        assertEquals(listOf(5L), archiver(listOf(5L to 0L), swap = late).pass())
        assertEquals("late", Files.readString(archive.resolve("5/$other")))
    }

    @Test fun `a failed exchange keeps the hot directory`() {
        job(6, sha to "x")
        assertEquals(emptyList(), archiver(listOf(6L to 0L), swap = { _, _ -> throw java.io.IOException("unsupported") }).pass())
        assertTrue(Files.isDirectory(hot.resolve("6"), NOFOLLOW_LINKS))
        assertEquals("x", Files.readString(hot.resolve("6/$sha")))
    }

    @Test fun `the native rename swap exchanges a directory and a symlink`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(MacRenameSwap.supported, "renamex_np is macOS-only")
        val dir = job(11, sha to "hot")
        val target = Files.createDirectories(archive.resolve("11"))
        val link = Files.createSymbolicLink(hot.resolve(".archive-link-11"), target)
        MacRenameSwap.swap(dir, link)
        assertTrue(Files.isSymbolicLink(hot.resolve("11")))
        assertEquals("hot", Files.readString(hot.resolve(".archive-link-11/$sha")))
    }
}
