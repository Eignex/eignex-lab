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
    @Test fun `large compiler inventories keep pinned and selection identities with batched metadata`() {
        val source = Files.createTempDirectory("bench-bulk-compiler")
        try {
            val compiler = Files.createDirectories(source.resolve("compiler/nested"))
            Files.createDirectories(source.resolve("compiler/empty/deep"))
            repeat(800) { Files.writeString(compiler.resolve("tool-$it"), "compiler-$it") }
            Files.writeString(compiler.resolve("unicodé"), "unicode")
            val digests = BenchInputDigests()
            val legacy = digests.inventory(source, source.resolve("compiler"), bulkMetadata = false)
            assertEquals(legacy, digests.inventory(source, source.resolve("compiler")))
            val case = BenchCase("fixture", inputs = listOf(BenchInput("compiler", role = "compiler")))
            val pinned = ExecutionStore.digest(legacy.joinToString("\n") { "SOURCE/compiler/$it" })
            repeat(3) { assertEquals(pinned, BenchRuns.pinned(case, source, source, digests = digests).attributes["labInputDigest"]) }
            val plan = BenchPlan(listOf(case))
            assertEquals(ExecutionStore.digest(legacy.joinToString("\n") { "compiler/$it" }), BenchSelections.sourceDigest(plan, source, digests))
            assertEquals(801, digests.hashedFiles)
            if (MacDirectoryEntries.supported(source)) assertTrue(digests.bulkFiles >= 801 * 5)
            Files.writeString(compiler.resolve("added"), "added")
            assertNotEquals(pinned, BenchRuns.pinned(case, source, source, digests = digests).attributes["labInputDigest"])
            Files.delete(compiler.resolve("added"))
            assertEquals(pinned, BenchRuns.pinned(case, source, source, digests = digests).attributes["labInputDigest"])
        } finally { deleteTree(source) }
    }

    @Test fun `compiler pinning rejects changes while an earlier directory batch is being hashed`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").lowercase().contains("mac"))
        val source = Files.createTempDirectory("bench-bulk-mutation")
        try {
            val compiler = Files.createDirectory(source.resolve("compiler"))
            Files.writeString(compiler.resolve("a-source"), "first")
            val target = compiler.resolve("z-target"); Files.writeString(target, "other")
            val proof = source.resolveSibling("${source.fileName}.json")
            TreeVerification().capture(source, proof)
            Files.writeString(compiler.resolve("a-source"), "newer")
            val digests = BenchInputDigests(hash = { path, check ->
                hashFile(path, check).also {
                    if (path.fileName.toString() == "a-source") {
                        val modified = Files.getLastModifiedTime(target)
                        Files.writeString(target, "wrong"); Files.setLastModifiedTime(target, modified)
                    }
                }
            })
            digests.receipt(source, proof)
            val case = BenchCase("fixture", inputs = listOf(BenchInput("compiler", role = "compiler")))
            assertFailsWith<IllegalArgumentException> { BenchRuns.pinned(case, source, source, digests = digests) }
            Files.deleteIfExists(proof)
        } finally { deleteTree(source) }
    }

    @Test fun `verified private copy receipts avoid byte reads across fresh digest instances`() {
        val parent = Files.createTempDirectory("bench-proof")
        val source = Files.createDirectory(parent.resolve("source"))
        Files.writeString(source.resolve("model"), "original")
        val proof = parent.resolve("source.json")
        val verifier = TreeVerification(); verifier.capture(source, proof)
        val copied = parent.resolve("copy"); val copyProof = parent.resolve("copy.json")
        verifier.copy(source, proof, copied, copyProof, copy = { copyPrivateTree(source, copied, {}) })
        val unread = BenchInputDigests(hash = { _, _ -> error("verified bytes reread") })
        unread.receipt(copied, copyProof)
        assertEquals(ExecutionStore.digest("original"), unread.checksum(copied, copied.resolve("model")))
        assertEquals(0, unread.hashedFiles); assertEquals(1, unread.reusedFiles)

        val file = copied.resolve("model"); val mtime = Files.getLastModifiedTime(file)
        Files.writeString(file, "modified"); Files.setLastModifiedTime(file, mtime)
        val changed = BenchInputDigests(); changed.receipt(copied, copyProof)
        assertEquals(ExecutionStore.digest("modified"), changed.checksum(copied, file))
        assertEquals(1, changed.hashedFiles)
        val replacement = parent.resolve("replacement")
        Files.writeString(replacement, "original"); Files.setLastModifiedTime(replacement, mtime)
        Files.move(replacement, file, REPLACE_EXISTING)
        assertEquals(ExecutionStore.digest("original"), changed.checksum(copied, file))
        assertEquals(2, changed.hashedFiles, "a replacement inode must be checked")
    }

    @Test fun `missing corrupt wrong root and unreliable receipts fall back to byte verification`() {
        val parent = Files.createTempDirectory("bench-proof-fallback")
        val source = Files.createDirectory(parent.resolve("source"))
        val file = source.resolve("model"); Files.writeString(file, "model")
        val proof = parent.resolve("proof.json"); TreeVerification().capture(source, proof)
        val saved = Files.readString(proof)
        for (kind in listOf("missing", "corrupt", "wrong-root", "unreliable")) {
            var reads = 0
            val root = if (kind == "wrong-root") parent.resolve(kind).also { copyPrivateTree(source, it, {}) } else source
            val receipt = if (kind == "missing") parent.resolve("absent.json") else proof
            Files.writeString(proof, if (kind == "corrupt") "broken" else saved)
            val digests = BenchInputDigests(hash = { path, check -> reads++; hashFile(path, check) }, reliableRoot = { kind != "unreliable" })
            digests.receipt(root, receipt)
            assertEquals(ExecutionStore.digest("model"), digests.checksum(root, root.resolve("model")))
            assertEquals(1, reads, kind)
        }
    }
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

    @Test fun `a corpus directory declared by its files pins the digest of exactly those files without reading them`() {
        val root = Files.createTempDirectory("bench-declared-trees")
        try {
            val texts = mapOf("p/model.mzn" to "include \"lib/g.mzn\";", "p/lib/g.mzn" to "predicate g();", "p/d1.dzn" to "n = 1;")
            for ((path, text) in texts) { Files.createDirectories(root.resolve(path).parent); Files.writeString(root.resolve(path), text) }
            val files = texts.map { (path, text) -> BenchInput(path, BenchInputRoot.CORPUS, if (path.endsWith("model.mzn")) "model" else "include-file", ExecutionStore.digest(text)) }
            val tree = BenchInputTrees.declare("p", files)
            val case = BenchCase("fixture", inputs = listOf(tree) + files)
            assertEquals(mapOf(tree to texts.mapKeys { it.key.removePrefix("p/") }.mapValues { ExecutionStore.digest(it.value) }), BenchRuns.declaredTrees(case))
            // The directory as a view of the published files holds exactly them, so reading it pins the same digest.
            val read = BenchRuns.pinned(case, root, root)
            val unread = BenchInputDigests(hash = { _, _ -> error("declared input bytes were read") })
            deleteTree(root.resolve("p"))
            assertEquals(read, BenchRuns.pinned(case, root, root, digests = unread, trustDeclared = true))
            assertEquals(0, unread.hashedFiles)
            assertFails { BenchRuns.pinned(case, root, root) }
        } finally { deleteTree(root) }
    }

    @Test fun `a declared tree must match its files and a read directory must hold no others`() {
        val root = Files.createTempDirectory("bench-mismatched-trees")
        try {
            Files.createDirectories(root.resolve("p"))
            Files.writeString(root.resolve("p/a.mzn"), "a"); Files.writeString(root.resolve("p/b.mzn"), "b")
            val a = BenchInput("p/a.mzn", BenchInputRoot.CORPUS, "include-file", ExecutionStore.digest("a"))
            val b = BenchInput("p/b.mzn", BenchInputRoot.CORPUS, "include-file", ExecutionStore.digest("b"))
            val partial = BenchCase("fixture", inputs = listOf(BenchInputTrees.declare("p", listOf(a, b)), a))
            val mismatch = assertFailsWith<SharedInputs.DeclaredInputMismatch> { BenchRuns.declaredTrees(partial) }
            assertTrue(mismatch.detail.startsWith("declared input tree checksum mismatch: p"), mismatch.detail)
            // Declared by a alone, the tree pins when trusted, but a read directory also holding b is not that tree.
            val narrow = BenchCase("fixture", inputs = listOf(BenchInputTrees.declare("p", listOf(a)), a))
            BenchRuns.pinned(narrow, root, root, trustDeclared = true)
            assertFailsWith<SharedInputs.DeclaredInputMismatch> { BenchRuns.pinned(narrow, root, root) }
            // An undeclared directory and a single declared file keep their meaning.
            assertEquals(emptyMap(), BenchRuns.declaredTrees(BenchCase("fixture", inputs = listOf(BenchInput("p", BenchInputRoot.CORPUS, "include"), a))))
            BenchRuns.validateInputs(narrow, root, root)
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
