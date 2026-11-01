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

import com.eignex.lab.bench.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.test.*

class BenchInputDigestsTest {
    @Test fun `shared compiler and include bytes are hashed once without changing pinned identities`() {
        val root = Files.createTempDirectory("bench-shared-digests")
        try {
            Files.createDirectories(root.resolve("compiler/nested"))
            Files.writeString(root.resolve("compiler/nested/tool"), "compiler")
            Files.writeString(root.resolve("model"), "model")
            val case = BenchCase("fixture", inputs = listOf(
                BenchInput("model", sha256 = ExecutionStore.digest("model")),
                BenchInput("model", role = "dependency"), BenchInput("compiler", role = "compiler")))
            val expected = BenchRuns.pinned(case, root, root)
            val digests = BenchInputDigests()
            repeat(147) { assertEquals(expected, BenchRuns.pinned(case, root, root, digests = digests)) }
            assertEquals(2, digests.hashedFiles)
            assertEquals(13L, digests.hashedBytes)
            assertEquals(147 * 4 - 2, digests.reusedFiles)
        } finally { deleteTree(root) }
    }

    @Test fun `trusted declared checksums pin the same digest without reading or requiring files`() {
        val root = Files.createTempDirectory("bench-declared-digests")
        try {
            Files.writeString(root.resolve("model"), "model")
            val case = BenchCase("fixture", inputs = listOf(BenchInput("model", BenchInputRoot.CORPUS, "model", ExecutionStore.digest("model"))))
            val unread = BenchInputDigests(hash = { _, _ -> error("declared input bytes were read") })
            val verified = BenchRuns.pinned(case, root, root)
            assertEquals(verified, BenchRuns.pinned(case, root, root, digests = unread, trustDeclared = true))
            Files.delete(root.resolve("model"))
            assertEquals(verified, BenchRuns.pinned(case, root, root, digests = unread, trustDeclared = true))
            assertEquals(0, unread.hashedFiles)
            assertFails { BenchRuns.pinned(case, root, root) }
        } finally { deleteTree(root) }
    }

    @Test fun `restored mtimes replacements and nested changes invalidate reused checksums`() {
        val root = Files.createTempDirectory("bench-changed-digests")
        try {
            Files.createDirectories(root.resolve("include/nested"))
            val file = root.resolve("include/nested/file")
            Files.writeString(file, "first")
            val case = BenchCase("fixture", inputs = listOf(BenchInput("include", role = "include")))
            val digests = BenchInputDigests()
            val original = BenchRuns.pinned(case, root, root, digests = digests)
            val modified = Files.getLastModifiedTime(file)
            Files.writeString(file, "other"); Files.setLastModifiedTime(file, modified)
            val changed = BenchRuns.pinned(case, root, root, digests = digests)
            assertNotEquals(original, changed)
            assertEquals(BenchRuns.pinned(case, root, root), changed)
            val replacement = root.resolve("replacement")
            Files.writeString(replacement, "other"); Files.setLastModifiedTime(replacement, modified)
            Files.move(replacement, file, REPLACE_EXISTING)
            assertEquals(changed, BenchRuns.pinned(case, root, root, digests = digests))
            assertEquals(3, digests.hashedFiles, "replacement inode must be rehashed even with identical bytes and mtime")
            Files.writeString(file.resolveSibling("added"), "new")
            assertNotEquals(changed, BenchRuns.pinned(case, root, root, digests = digests))
            Files.delete(file.resolveSibling("added"))
            assertEquals(changed, BenchRuns.pinned(case, root, root, digests = digests))
            Files.delete(file); Files.createSymbolicLink(file, root.resolve("replacement"))
            assertFailsWith<IllegalArgumentException> { BenchRuns.pinned(case, root, root, digests = digests) }
        } finally { deleteTree(root) }
    }

    @Test fun `unreliable metadata falls back to hashing and cancellation still interrupts reuse`() {
        val root = Files.createTempDirectory("bench-portable-digests")
        try {
            val file = root.resolve("model"); Files.writeString(file, "model")
            val digests = BenchInputDigests(reliableRoot = { false })
            repeat(2) { assertEquals(ExecutionStore.digest("model"), digests.checksum(root, file)) }
            assertEquals(2, digests.hashedFiles); assertEquals(0, digests.reusedFiles)
            var cancelled = false
            val cancellable = BenchInputDigests(checkActive = { check(!cancelled) })
            cancellable.checksum(root, file); cancelled = true
            assertFailsWith<IllegalStateException> { cancellable.checksum(root, file) }
        } finally { deleteTree(root) }
    }

    @Test fun `declared checksums and concurrent mutation are checked even with digest reuse`() {
        val root = Files.createTempDirectory("bench-checksum-digests")
        try {
            val file = root.resolve("model"); Files.writeString(file, "model")
            val digests = BenchInputDigests()
            digests.checksum(root, file)
            val wrong = BenchCase("fixture", inputs = listOf(BenchInput("model", sha256 = "a".repeat(64))))
            assertFailsWith<IllegalArgumentException> { BenchRuns.pinned(wrong, root, root, digests = digests) }
            val changing = BenchInputDigests(hash = { path, _ ->
                val sha = hashFile(path); Files.writeString(path, "other"); sha
            })
            assertFailsWith<IllegalArgumentException> { changing.checksum(root, file) }
        } finally { deleteTree(root) }
    }
}
