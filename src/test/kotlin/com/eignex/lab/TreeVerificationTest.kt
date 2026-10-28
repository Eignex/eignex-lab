package com.eignex.lab

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.CancellationException
import kotlin.test.*

class TreeVerificationTest {
    private val root = Files.createTempDirectory("tree-verification")
    private val tree = Files.createDirectory(root.resolve("tree"))
    private val file = tree.resolve("tool").also { Files.writeString(it, "hello") }
    private val expected = listOf(InputFile("tool", ExecutionStore.digest("hello"), 5))
    private val receipt = root.resolve("verified.json")
    private var reads = 0
    private fun verifier() = TreeVerification { path, check -> reads++; hashFile(path, check) }

    @Test fun `warm verification reuses checksums across instances and refreshes changed metadata`() {
        assertTrue(verifier().valid(tree, expected, receipt)); assertEquals(1, reads)
        assertTrue(verifier().valid(tree, expected, receipt)); assertEquals(1, reads)
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(123456789))
        assertTrue(verifier().valid(tree, expected, receipt)); assertEquals(2, reads)
        assertTrue(verifier().valid(tree, expected, receipt)); assertEquals(2, reads)
        Files.writeString(receipt, "damaged receipt")
        assertTrue(verifier().valid(tree, expected, receipt)); assertEquals(3, reads)
    }

    @Test fun `same length corruption with restored mtime is never a warm hit`() {
        assertTrue(verifier().valid(tree, expected, receipt))
        val modified = Files.getLastModifiedTime(file)
        Files.writeString(file, "wrong"); Files.setLastModifiedTime(file, modified)
        assertFalse(verifier().valid(tree, expected, receipt)); assertEquals(2, reads)
    }

    @Test fun `replacement inode and changed expected checksum invalidate receipts`() {
        assertTrue(verifier().valid(tree, expected, receipt))
        val modified = Files.getLastModifiedTime(file)
        val replacement = root.resolve("replacement"); Files.writeString(replacement, "hello")
        Files.setLastModifiedTime(replacement, modified); Files.move(replacement, file, REPLACE_EXISTING)
        assertTrue(verifier().valid(tree, expected, receipt)); assertEquals(2, reads)
        assertFalse(verifier().valid(tree, listOf(expected.single().copy(sha256 = ExecutionStore.digest("wrong"))), receipt))
        assertEquals(3, reads)
    }

    @Test fun `warm trees reject extra missing and linked files and executable changes`() {
        assertTrue(verifier().valid(tree, expected, receipt, exactExecutables = true))
        val extra = tree.resolve("extra"); Files.writeString(extra, "undeclared")
        assertFalse(verifier().valid(tree, expected, receipt)); Files.delete(extra)
        assertTrue(file.toFile().setExecutable(true, true))
        assertFalse(verifier().valid(tree, expected, receipt, exactExecutables = true))
        Files.delete(file); assertFalse(verifier().valid(tree, expected, receipt))
        val outside = root.resolve("outside"); Files.writeString(outside, "hello"); Files.createSymbolicLink(file, outside)
        assertFalse(verifier().valid(tree, expected, receipt))
        Files.delete(file); Files.createSymbolicLink(tree.resolve("nested"), root)
        assertFalse(verifier().valid(tree, emptyList(), receipt))
    }

    @Test fun `providers without inode and ctime hash every reuse`() {
        FileSystems.newFileSystem(root.resolve("cache.zip"), mapOf("create" to "true")).use { fs ->
            val directory = Files.createDirectory(fs.getPath("/tree")); Files.writeString(directory.resolve("tool"), "hello")
            val proof = fs.getPath("/receipt.json")
            repeat(2) { assertTrue(verifier().valid(directory, expected, proof)) }
            assertEquals(2, reads)
        }
    }

    @Test fun `validation propagates cancellation instead of rebuilding a valid tree`() {
        assertTrue(verifier().valid(tree, expected, receipt))
        assertFailsWith<CancellationException> {
            verifier().valid(tree, expected, receipt, { throw CancellationException("lease ended") })
        }
        assertEquals(1, reads)
    }
}
