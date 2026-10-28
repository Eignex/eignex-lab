package com.eignex.lab

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class ColdStagingTest {
    @Test fun `trusted copy seals unchanged bytes without reading them and hashes setup changes`() {
        val root = Files.createTempDirectory("copy-proof")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            Files.writeString(source.resolve("code"), "hello")
            val receipt = root.resolve("source.verified.json"); var reads = 0
            val verifier = TreeVerification { path, check -> reads++; hashFile(path, check) }
            verifier.capture(source, receipt); assertEquals(1, reads)
            val target = root.resolve("target"); val proof = root.resolve("target.verified.json")
            verifier.copy(source, receipt, target, proof) { WorkerTrees.copy(source, target) {} }
            assertEquals(1, reads)
            assertNotEquals(Files.getAttribute(source.resolve("code"), "unix:ino"), Files.getAttribute(target.resolve("code"), "unix:ino"))
            verifier.capture(target, proof); assertEquals(1, reads)
            val modified = Files.getLastModifiedTime(target.resolve("code"))
            Files.writeString(target.resolve("code"), "setup"); Files.setLastModifiedTime(target.resolve("code"), modified)
            Files.writeString(target.resolve("provenance"), target.toString())
            val files = verifier.capture(target, proof); assertEquals(3, reads)
            assertEquals(ExecutionStore.digest("setup"), files.single { it.path == "code" }.sha256)
            assertTrue(verifier.valid(target, files, proof)); assertEquals(3, reads)
            assertEquals("hello", Files.readString(source.resolve("code")))
        } finally { deleteTree(root) }
    }

    @Test fun `copy proof rejects source changes before and during copy including restored mtime`() {
        for (during in listOf(false, true)) {
            val root = Files.createTempDirectory("copy-race")
            try {
                val source = Files.createDirectory(root.resolve("source")); val file = source.resolve("code")
                Files.writeString(file, "hello"); val receipt = root.resolve("source.json")
                TreeVerification().capture(source, receipt)
                fun corrupt() { val mtime = Files.getLastModifiedTime(file); Files.writeString(file, "wrong"); Files.setLastModifiedTime(file, mtime) }
                if (!during) corrupt()
                val destination = root.resolve("target"); val proof = root.resolve("target.json")
                assertFailsWith<IllegalArgumentException> {
                    TreeVerification().copy(source, receipt, destination, proof) {
                        WorkerTrees.copy(source, destination) {}; if (during) corrupt()
                    }
                }
                assertFalse(Files.exists(proof))
            } finally { deleteTree(root) }
        }
    }

    @Test fun `missing copy receipts fall back to hashing and written receipts detect late corruption`() {
        val root = Files.createTempDirectory("copy-fallback")
        try {
            val source = Files.createDirectory(root.resolve("source")); Files.writeString(source.resolve("code"), "hello")
            val target = root.resolve("target"); val receipt = root.resolve("target.json"); var reads = 0
            val verifier = TreeVerification { path, check -> reads++; hashFile(path, check) }
            verifier.copy(source, root.resolve("missing"), target, receipt) { WorkerTrees.copy(source, target) {} }
            assertFalse(Files.exists(receipt)); verifier.capture(target, receipt); assertEquals(1, reads)
            val file = target.resolve("code")
            val proof = verifier.written(file, InputFile("code", ExecutionStore.digest("hello"), 5))
            val mtime = Files.getLastModifiedTime(file); Files.writeString(file, "wrong"); Files.setLastModifiedTime(file, mtime)
            assertFailsWith<IllegalArgumentException> { verifier.record(target, listOf(proof), receipt) }
        } finally { deleteTree(root) }
    }

    @Test fun `stream extraction checks trailer and per-file hashes without retaining an archive`() {
        val root = Files.createTempDirectory("stream-extract")
        try {
            val payload = "hello"
            val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("code")); zip.write(payload.toByteArray()); zip.closeEntry()
            } }.toByteArray()
            val manifest = WorkerBundle("b".repeat(64), listOf(InputFile("code", ExecutionStore.digest(payload), payload.length.toLong())), "Eignex/klause", "a".repeat(40), "linux", "amd64", "fixture")
                .copy(sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).hex())
            val cacheRoot = root.resolve("cache"); val cache = VerifiedCache(cacheRoot)
            val tree = cache.stage(manifest) { ByteArrayInputStream(bytes) }
            assertEquals(payload, Files.readString(tree.resolve("code")))
            var reads = 0
            val verifier = TreeVerification { path, check -> reads++; hashFile(path, check) }
            assertTrue(verifier.valid(tree, manifest.files, tree.resolveSibling("${tree.fileName}.verified.json"))); assertEquals(0, reads)
            assertFalse(Files.walk(cacheRoot).use { paths -> paths.anyMatch { it.fileName.toString().endsWith(".zip") } })
            val trailer = bytes.copyOf().also { it[it.lastIndex - 2] = (it[it.lastIndex - 2].toInt() xor 1).toByte() }
            val fresh = VerifiedCache(root.resolve("bad-trailer"))
            assertFailsWith<IllegalArgumentException> { fresh.stage(manifest) { ByteArrayInputStream(trailer) } }
            assertFailsWith<IllegalArgumentException> { cache.stage(manifest.copy(files = listOf(manifest.files.single().copy(sha256 = "0".repeat(64))))) { ByteArrayInputStream(bytes) } }
        } finally { deleteTree(root) }
    }

    @Test fun `cold input cache clones only identical verified local builds and falls back after corruption`() {
        val root = Files.createTempDirectory("local-build-inputs")
        try {
            val buildRoot = Files.createDirectory(root.resolve("builds")); val key = "a".repeat(64)
            val candidate = Files.createDirectory(buildRoot.resolve(key)); val tree = Files.createDirectory(candidate.resolve("tree"))
            val files = mapOf(".git/HEAD" to "${"b".repeat(40)}\n", "module/build/install/tool/lib/code.jar" to "hello", "module/build/classes/Code.class" to "compiler")
            for ((name, bytes) in files) { val path = tree.resolve(name); Files.createDirectories(path.parent); Files.writeString(path, bytes) }
            val inventory = TreeVerification().capture(tree, candidate.resolve("verified.json"))
            val entry = kotlinx.serialization.json.buildJsonObject {
                put("identity", kotlinx.serialization.json.JsonPrimitive(key))
                put("files", kotlinx.serialization.json.Json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(InputFile.serializer()), inventory))
            }
            Files.writeString(candidate.resolve("manifest.json"), entry.toString())
            val identity = WorkerBundle("c".repeat(64), inventory, "Eignex/klause", "b".repeat(40), "linux", "amd64", "fixture")
            val output = root.resolve("input.zip"); val bundle = archive(tree, output, identity) {}
            val builds = WorkerBuildCache(buildRoot, ProcessSupervisor(root.resolve("unused.sock")))
            val inputs = WorkerInputCache(root.resolve("inputs"), builds = builds)
            inputs.useRuntime(bundle, {}, { error("identical local build must avoid download and extraction") }) { staged ->
                assertEquals("hello", Files.readString(staged.resolve("module/build/install/tool/lib/code.jar")))
                assertFalse(Files.exists(staged.resolve("module/build/classes")))
                Files.writeString(staged.resolve("module/build/install/tool/lib/code.jar"), "private")
                assertEquals("hello", Files.readString(tree.resolve("module/build/install/tool/lib/code.jar")))
            }
            val source = tree.resolve("module/build/install/tool/lib/code.jar"); val mtime = Files.getLastModifiedTime(source)
            Files.writeString(source, "wrong"); Files.setLastModifiedTime(source, mtime)
            var downloads = 0
            WorkerInputCache(root.resolve("other-inputs"), builds = builds).useRuntime(bundle, {}, { downloads++; Files.newInputStream(output) }) { staged ->
                assertEquals("hello", Files.readString(staged.resolve("module/build/install/tool/lib/code.jar")))
            }
            assertEquals(1, downloads)
            val different = bundle.copy(files = bundle.files.map { if (it.path.endsWith("code.jar")) it.copy(sha256 = "0".repeat(64)) else it })
            assertFailsWith<IllegalArgumentException> {
                WorkerInputCache(root.resolve("different-inputs"), builds = builds).useRuntime(different, {}, { Files.newInputStream(output) }) { error("changed approval accepted") }
            }
        } finally { deleteTree(root) }
    }

    @Test fun `external workspace lifetime protects active sessions reaps crashes and removes normal exits`() {
        val root = Files.createTempDirectory("workspace-storage")
        try {
            val stale = Files.createDirectory(root.resolve("session-stale")); Files.writeString(stale.resolve("owner.lock"), "")
            Files.writeString(stale.resolve("abandoned"), "old state")
            WorkerWorkspaceStorage(root).use { first ->
                val key = "a".repeat(64)
                val active = first.workspaces.create(key, {}) { Files.createDirectories(it); Files.writeString(it.resolve("code"), "hello") }
                WorkerWorkspaceStorage(root).use { second ->
                    second.reap(); assertFalse(Files.exists(stale)); assertTrue(Files.exists(active))
                }
                assertEquals("hello", Files.readString(active.resolve("code")))
            }
            assertTrue(Files.list(root).use { paths -> paths.noneMatch { Files.isDirectory(it) } })
        } finally { deleteTree(root) }
    }
}
