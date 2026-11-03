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

    @Test fun `memory copy receipts retain full verification without writing a target receipt`() {
        verifier().capture(tree, receipt)
        val sourceProof = Files.readString(receipt)
        val memory = TreeVerification.MemoryReceipt()
        val destination = root.resolve("memory-copy"); val targetReceipt = root.resolve("memory-copy.json")
        val copied = TreeVerification(memoryReceipt = memory).copy(tree, receipt, destination, targetReceipt) {
            WorkerTrees.copy(tree, destination) {}
        }
        assertEquals(expected, copied); assertFalse(Files.exists(targetReceipt))
        assertEquals(sourceProof, Files.readString(receipt), "the shared source proof remains persistent")
        fun warm() = TreeVerification(memoryReceipt = memory, hash = { _, _ -> error("unchanged bytes reread") })
        val verification = warm()
        assertTrue(verification.valid(destination, expected, targetReceipt, exactExecutables = true))
        assertEquals(0, verification.stats.hashedFiles)
        Files.writeString(destination.resolve("generated"), "output")
        assertTrue(warm().valid(destination, expected, targetReceipt, removeUndeclared = true))
        assertFalse(Files.exists(destination.resolve("generated"))); assertFalse(Files.exists(targetReceipt))
        assertTrue(destination.resolve("tool").toFile().setExecutable(true, true))
        assertFalse(warm().valid(destination, expected, targetReceipt, exactExecutables = true))
        assertTrue(destination.resolve("tool").toFile().setExecutable(false, false))
        val modified = Files.getLastModifiedTime(destination.resolve("tool"))
        Files.writeString(destination.resolve("tool"), "wrong"); Files.setLastModifiedTime(destination.resolve("tool"), modified)
        val changed = TreeVerification(memoryReceipt = memory)
        assertFalse(changed.valid(destination, expected, targetReceipt)); assertEquals(1, changed.stats.hashedFiles)
        assertFalse(Files.exists(targetReceipt))
    }

    @Test fun `memory receipts reject replaced roots and cannot replace persistent source proof`() {
        val memory = TreeVerification.MemoryReceipt()
        val captured = TreeVerification(memoryReceipt = memory).capture(tree, receipt)
        assertFalse(Files.exists(receipt))
        val withoutSourceProof = root.resolve("no-persistent-source")
        assertNull(TreeVerification(memoryReceipt = memory).copy(tree, receipt, withoutSourceProof, root.resolve("ignored.json")) {
            WorkerTrees.copy(tree, withoutSourceProof) {}
        }, "a target memory receipt must never stand in for a persistent source proof")
        Files.move(tree, root.resolve("old-root"))
        Files.createDirectory(tree); Files.writeString(tree.resolve("tool"), "hello")
        val replacement = TreeVerification(memoryReceipt = memory)
        assertTrue(replacement.valid(tree, captured, receipt)); assertEquals(1, replacement.stats.hashedFiles)
        val changed = TreeVerification(memoryReceipt = memory)
        Files.writeString(file, "wrong")
        assertFalse(changed.valid(tree, captured, receipt)); assertEquals(1, changed.stats.hashedFiles)
        assertFalse(Files.exists(receipt))
    }

    @Test fun `memory receipt fallback hashes without a trusted source and unsupported metadata stays conservative`() {
        val memory = TreeVerification.MemoryReceipt()
        val sealing = TreeVerification(memoryReceipt = memory)
        val captured = sealing.capture(tree, receipt)
        assertEquals(1, sealing.stats.hashedFiles); assertFalse(Files.exists(receipt))
        assertTrue(TreeVerification(memoryReceipt = memory).valid(tree, captured, receipt))
        FileSystems.newFileSystem(root.resolve("memory-cache.zip"), mapOf("create" to "true")).use { fs ->
            val directory = Files.createDirectory(fs.getPath("/tree")); Files.writeString(directory.resolve("tool"), "hello")
            val proof = fs.getPath("/receipt.json"); val unsupported = TreeVerification.MemoryReceipt()
            repeat(2) {
                val verification = TreeVerification(memoryReceipt = unsupported)
                assertTrue(verification.valid(directory, expected, proof)); assertEquals(1, verification.stats.hashedFiles)
            }
            assertFalse(Files.exists(proof))
        }
    }

    @Test fun `rejection diagnostics distinguish changed bytes missing files and generated outputs`() {
        val verification = verifier()
        assertTrue(verification.valid(tree, expected, receipt))
        Files.writeString(tree.resolve("generated"), "output")
        assertFalse(verification.valid(tree, expected, receipt))
        assertEquals(TreeVerification.Rejection.UNDECLARED_FILE, verification.stats.rejection)
        assertTrue(verification.valid(tree, expected, receipt, removeUndeclared = true))
        assertEquals(TreeVerification.Rejection.NONE, verification.stats.rejection)
        assertEquals(1, verification.stats.removedFiles)
        Files.writeString(file, "wrong")
        assertFalse(verification.valid(tree, expected, receipt))
        assertEquals(TreeVerification.Rejection.CHECKSUM, verification.stats.rejection)
        Files.writeString(file, "longer")
        assertFalse(verification.valid(tree, expected, receipt))
        assertEquals(TreeVerification.Rejection.SIZE, verification.stats.rejection)
        Files.delete(file)
        assertFalse(verification.valid(tree, expected, receipt))
        assertEquals(TreeVerification.Rejection.MISSING_FILE, verification.stats.rejection)
    }

    @Test fun `fresh sealing hashes once and creates a reusable receipt without weakening tamper checks`() {
        val captured = verifier().capture(tree, receipt)
        assertEquals(1, reads)
        assertTrue(verifier().valid(tree, captured, receipt, exactExecutables = true)); assertEquals(1, reads)
        Files.writeString(tree.resolve("cache"), "generated")
        assertTrue(verifier().valid(tree, captured, receipt, removeUndeclared = true)); assertEquals(1, reads)
        assertFalse(Files.exists(tree.resolve("cache")))
        val mtime = Files.getLastModifiedTime(file); Files.writeString(file, "wrong"); Files.setLastModifiedTime(file, mtime)
        assertFalse(verifier().valid(tree, captured, receipt)); assertEquals(2, reads)
    }

    @Test fun `sealing rejects files changed while their checksum is captured`() {
        val changing = TreeVerification { path, check -> hashFile(path, check).also { Files.writeString(path, "wrong") } }
        assertFailsWith<IllegalArgumentException> { changing.capture(tree, receipt) }
        assertFalse(Files.exists(receipt))
    }

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
    @Test fun `warm traversal records real checksum work and includes empty directories in its existing walk`() {
        Files.createDirectories(tree.resolve("empty/nested"))
        val cold = verifier(); val directories = mutableListOf<String>()
        val captured = cold.capture(tree, receipt, directories = directories)
        assertEquals(setOf("empty", "empty/nested"), directories.toSet())
        assertEquals(1, cold.stats.hashedFiles); assertEquals(5L, cold.stats.hashedBytes)
        val warm = verifier()
        assertTrue(warm.valid(tree, captured, receipt))
        assertEquals(1, warm.stats.checkedFiles); assertEquals(0, warm.stats.hashedFiles)
        assertEquals(0L, warm.stats.hashedBytes)
    }

    @Test fun `bulk copy guards validate selected receipt files without requiring unrelated compiler scratch`() {
        val source = Files.createDirectories(root.resolve("selected-source"))
        val selected = (0 until 64).map { index ->
            val path = source.resolve("runtime/file$index"); Files.createDirectories(path.parent)
            Files.writeString(path, "value-$index")
            InputFile("runtime/file$index", ExecutionStore.digest("value-$index"), "value-$index".length.toLong())
        }
        val scratch = source.resolve("compiler/scratch"); Files.createDirectories(scratch.parent); Files.writeString(scratch, "scratch")
        val sourceReceipt = root.resolve("selected-source.json")
        TreeVerification().capture(source, sourceReceipt)
        assertTrue(TreeVerification().valid(source, selected, sourceReceipt, allowUndeclared = true))
        val destination = root.resolve("selected-copy"); val receipt = root.resolve("selected-copy.json")
        val wanted = selected.map { it.path }.toSet()
        val verifier = TreeVerification()
        val copied = verifier.copy(source, sourceReceipt, destination, receipt, include = { it.toString().replace('\\', '/') in wanted }) {
            for (file in selected) {
                val target = destination.resolve(file.path); Files.createDirectories(target.parent)
                Files.copy(source.resolve(file.path), target, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES)
            }
            Files.writeString(scratch, "unrelated change")
        }
        assertEquals(selected.toSet(), assertNotNull(copied).toSet())
        val unread = TreeVerification(hash = { _, _ -> error("selected copied bytes reread") })
        assertTrue(unread.valid(destination, selected, receipt))
        assertFalse(Files.exists(destination.resolve("compiler")))
        assertEquals(0, verifier.stats.hashedFiles)
    }

    @Test fun `trusted copy rejects concurrent source changes and seals private inode stamps without rehashing`() {
        verifier().capture(tree, receipt)
        val destination = root.resolve("private"); val targetReceipt = root.resolve("private.json")
        val copy = verifier()
        val copied = copy.copy(tree, receipt, destination, targetReceipt) { WorkerTrees.copy(tree, destination) {} }
        assertEquals(expected, copied)
        assertEquals(0, copy.stats.hashedFiles)
        val fresh = verifier(); assertTrue(fresh.valid(destination, expected, targetReceipt, exactExecutables = true))
        assertEquals(0, fresh.stats.hashedFiles)
        Files.writeString(destination.resolve("tool"), "other")
        assertFalse(verifier().valid(destination, expected, targetReceipt))
        val changing = root.resolve("changing")
        assertFailsWith<IllegalArgumentException> {
            verifier().copy(tree, receipt, changing, root.resolve("changing.json")) {
                WorkerTrees.copy(tree, changing) {}; Files.writeString(file, "wrong")
            }
        }
        assertFalse(Files.exists(root.resolve("changing.json")))
    }

    @Test fun `copy handoff requires complete matching destination inventory and executable bits`() {
        verifier().capture(tree, receipt)
        for (kind in listOf("missing", "extra", "executable")) {
            val destination = root.resolve(kind); val targetReceipt = root.resolve("$kind.json")
            assertFailsWith<IllegalArgumentException> {
                verifier().copy(tree, receipt, destination, targetReceipt) {
                    WorkerTrees.copy(tree, destination) {}
                    when (kind) {
                        "missing" -> Files.delete(destination.resolve("tool"))
                        "extra" -> Files.writeString(destination.resolve("extra"), "unexpected")
                        else -> assertTrue(destination.resolve("tool").toFile().setExecutable(true, true))
                    }
                }
            }
            assertFalse(Files.exists(targetReceipt), "an incomplete copy must not publish a reusable receipt")
        }
    }

    @Test fun `missing and unsupported source proofs cannot produce a trusted copy handoff`() {
        val missing = root.resolve("missing-proof")
        assertNull(verifier().copy(tree, missing, root.resolve("without-proof"), root.resolve("without-proof.json")) {
            WorkerTrees.copy(tree, root.resolve("without-proof")) {}
        })
        assertFalse(Files.exists(root.resolve("without-proof.json")))
        FileSystems.newFileSystem(root.resolve("copy.zip"), mapOf("create" to "true")).use { fs ->
            val source = Files.createDirectory(fs.getPath("/source")); Files.writeString(source.resolve("tool"), "hello")
            val sourceReceipt = fs.getPath("/receipt.json")
            verifier().capture(source, sourceReceipt)
            val destination = root.resolve("unsupported-source"); val targetReceipt = root.resolve("unsupported-source.json")
            assertNull(verifier().copy(source, sourceReceipt, destination, targetReceipt) {
                Files.createDirectory(destination); Files.copy(source.resolve("tool"), destination.resolve("tool"))
            })
            assertFalse(Files.exists(targetReceipt))
            val unsupportedDestination = fs.getPath("/destination"); val unsupportedReceipt = fs.getPath("/destination.json")
            verifier().capture(tree, receipt)
            assertNull(verifier().copy(tree, receipt, unsupportedDestination, unsupportedReceipt) {
                Files.createDirectory(unsupportedDestination); Files.copy(file, unsupportedDestination.resolve("tool"))
            })
            assertFalse(Files.exists(unsupportedReceipt))
        }
    }

    @Test fun `failed copy receipt publication cannot return a trusted handoff`() {
        verifier().capture(tree, receipt)
        val destination = root.resolve("receipt-failed"); val targetReceipt = Files.createDirectory(root.resolve("receipt-directory"))
        Files.writeString(targetReceipt.resolve("keep"), "existing")
        assertFailsWith<java.io.IOException> {
            verifier().copy(tree, receipt, destination, targetReceipt) { WorkerTrees.copy(tree, destination) {} }
        }
        assertEquals("existing", Files.readString(targetReceipt.resolve("keep")))
    }

    @Test fun `copy verification timing excludes time inside the trusted filesystem copy`() {
        TreeVerification().capture(tree, receipt)
        val copy = TreeVerification(); var copyingNanos = 0L
        val started = System.nanoTime()
        copy.copy(tree, receipt, root.resolve("timed"), root.resolve("timed.json")) {
            val copyingAt = System.nanoTime()
            Thread.sleep(25)
            WorkerTrees.copy(tree, root.resolve("timed")) {}
            copyingNanos = System.nanoTime() - copyingAt
        }
        val elapsed = System.nanoTime() - started
        assertTrue(copy.stats.verificationNanos > 0)
        assertTrue(copy.stats.verificationNanos <= elapsed - copyingNanos,
            "copy execution was incorrectly counted as verification")
    }

    @Test fun `metadata read-ahead keeps warm hits checksum free and leaves every rejection to the walk`() {
        val many = (0 until 64).map { index ->
            val name = "dir${index % 4}/file$index"
            Files.createDirectories(tree.resolve(name).parent); Files.writeString(tree.resolve(name), "bytes $index")
            InputFile(name, ExecutionStore.digest("bytes $index"), "bytes $index".length.toLong())
        } + expected
        fun ahead() = TreeVerification(readAheadFiles = 1) { path, check -> reads++; hashFile(path, check) }
        val cold = ahead(); assertTrue(cold.valid(tree, many, receipt)); assertEquals(many.size, reads)
        val warm = ahead(); assertTrue(warm.valid(tree, many, receipt))
        assertEquals(0, warm.stats.hashedFiles)
        if (MacDirectoryEntries.supported(tree)) {
            assertEquals(many.size, warm.stats.bulkFiles); assertEquals(0, warm.stats.readAheadFiles)
        } else assertEquals(many.size, warm.stats.readAheadFiles)
        val changed = tree.resolve("dir1/file1"); val modified = Files.getLastModifiedTime(changed)
        Files.writeString(changed, "BYTES 1"); Files.setLastModifiedTime(changed, modified)
        assertFalse(ahead().valid(tree, many, receipt))
        Files.writeString(changed, "bytes 1"); assertTrue(ahead().valid(tree, many, receipt))
        Files.writeString(tree.resolve("dir2/extra"), "undeclared"); assertFalse(ahead().valid(tree, many, receipt))
        Files.delete(tree.resolve("dir2/extra")); Files.delete(tree.resolve("dir3/file3"))
        assertFalse(ahead().valid(tree, many, receipt))
        assertTrue(ahead().valid(tree, many - many.single { it.path == "dir3/file3" }, receipt))
        assertFailsWith<CancellationException> { ahead().valid(tree, many, receipt, { throw CancellationException("lease ended") }) }
        assertTrue(Thread.getAllStackTraces().keys.none { it.name == "tree-read-ahead" && it.isAlive })
    }
}
