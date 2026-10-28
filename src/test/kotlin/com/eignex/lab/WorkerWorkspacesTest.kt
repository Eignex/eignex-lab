package com.eignex.lab

import java.nio.file.Files
import kotlin.test.*

class WorkerWorkspacesTest {
    @Test fun `sealed private workspaces retain setup reject changed files and remove generated outputs`() {
        val root = Files.createTempDirectory("private-workspaces")
        try {
            val cache = WorkerWorkspaces(root); val key = "a".repeat(64)
            assertNull(cache.reuse(key) {})
            val tree = cache.create(key, {}) { Files.createDirectories(it); Files.writeString(it.resolve("code"), "fixture") }
            Files.writeString(tree.resolve("provenance.json"), tree.toString())
            cache.seal(key, tree) {}
            assertEquals(tree, cache.reuse(key) {})
            val file = tree.resolve("code"); val modified = Files.getLastModifiedTime(file)
            Files.writeString(file, "corrupt"); Files.setLastModifiedTime(file, modified)
            assertNull(cache.reuse(key) {})
            val restored = cache.create(key, {}) { Files.createDirectories(it); Files.writeString(it.resolve("code"), "fixture") }
            assertFalse(Files.exists(restored.resolve("provenance.json")))
            cache.seal(key, restored) {}
            Files.writeString(restored.resolve("extra"), "output")
            val outside = root.resolve("outside").also { Files.writeString(it, "outside") }
            Files.createSymbolicLink(restored.resolve("extra-link"), outside)
            assertEquals(restored, cache.reuse(key) {})
            assertFalse(Files.exists(restored.resolve("extra")))
            assertFalse(Files.exists(restored.resolve("extra-link"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            assertEquals("outside", Files.readString(outside))
        } finally { deleteTree(root) }
    }

    @Test fun `unsealed setup cannot be reused after failure or a worker restart`() {
        val root = Files.createTempDirectory("unsealed-workspaces")
        try {
            val key = "b".repeat(64); val cache = WorkerWorkspaces(root)
            val tree = cache.create(key, {}) { Files.createDirectory(it); Files.writeString(it.resolve("code"), "fixture") }
            assertNull(cache.reuse(key) {})
            cache.seal(key, tree) {}
            assertNotNull(cache.reuse(key) {})
            assertNull(WorkerWorkspaces(root).reuse(key) {})
        } finally { deleteTree(root) }
    }
}
