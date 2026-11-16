package com.eignex.lab.bench

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class WorkspaceChecksumsTest {
    @Test
    fun `worker cache storage is optional`() {
        assertEquals(null, WorkspaceChecksums.directory(emptyMap()))
        assertEquals(
            Path.of("worker-cache", "checksums-v1"),
            WorkspaceChecksums.directory(mapOf("LAB_WORKSPACE_CACHE" to "worker-cache")),
        )
    }

    @Test
    fun `fresh readers reuse unchanged file bytes`() = fixture { root ->
        val file = Files.writeString(root.resolve("input"), "original")
        val cache = root.resolve("cache")
        var reads = 0
        repeat(2) {
            WorkspaceChecksums(root, cache, hash = { source ->
                reads++
                digest(source)
            }).use { checksums ->
                assertEquals(digest(file), checksums.checksum(file))
            }
        }
        assertEquals(1, reads)
    }

    @Test
    fun `preserved modification time cannot hide changed bytes or inodes`() {
        for (change in listOf("write", "replace")) fixture { root ->
            val file = Files.writeString(root.resolve("input"), "original")
            val cache = root.resolve("cache")
            val modified = Files.getLastModifiedTime(file)
            WorkspaceChecksums(root, cache).use { it.checksum(file) }
            if (change == "write") {
                Files.writeString(file, "modified")
                Files.setLastModifiedTime(file, modified)
            } else {
                val replacement = Files.writeString(root.resolve("replacement"), "original")
                Files.setLastModifiedTime(replacement, modified)
                Files.move(replacement, file, REPLACE_EXISTING)
            }
            var reads = 0
            WorkspaceChecksums(root, cache, hash = { source ->
                reads++
                digest(source)
            }).use {
                assertEquals(digest(file), it.checksum(file))
            }
            assertEquals(1, reads)
        }
    }

    @Test
    fun `invalid cache records require byte verification`() {
        for (failure in listOf("corrupt", "oversize")) fixture { root ->
            val file = Files.writeString(root.resolve("input"), "original")
            val cache = root.resolve("cache")
            WorkspaceChecksums(root, cache).use { it.checksum(file) }
            val receipt = Files.list(cache).use { files ->
                files.filter { it.toString().endsWith(".bin") }.findFirst().orElseThrow()
            }
            Files.write(receipt, if (failure == "corrupt") byteArrayOf(1) else ByteArray(1024 * 1024 + 1))
            var reads = 0
            WorkspaceChecksums(root, cache, hash = { source ->
                reads++
                digest(source)
            }).use {
                assertEquals(digest(file), it.checksum(file))
            }
            assertEquals(1, reads)
        }
    }

    @Test
    fun `valid hex corruption of a cached digest requires byte verification`() = fixture { root ->
        val file = Files.writeString(root.resolve("input"), "original")
        val cache = root.resolve("cache")
        WorkspaceChecksums(root, cache).use { it.checksum(file) }
        val receipt = Files.list(cache).use { files ->
            files.filter { it.toString().endsWith(".bin") }.findFirst().orElseThrow()
        }
        val bytes = Files.readAllBytes(receipt)
        val savedDigest = digest(file).toByteArray()
        val offset = bytes.indices.first { start ->
            start + savedDigest.size <= bytes.size &&
                savedDigest.indices.all { index -> bytes[start + index] == savedDigest[index] }
        }
        bytes[offset] = if (bytes[offset] == '0'.code.toByte()) '1'.code.toByte() else '0'.code.toByte()
        Files.write(receipt, bytes)
        var reads = 0

        WorkspaceChecksums(root, cache, hash = { source ->
            reads++
            digest(source)
        }).use { checksums ->
            assertEquals(digest(file), checksums.checksum(file))
        }

        assertEquals(1, reads)
    }

    @Test
    fun `unsupported filesystems read the bytes without storing receipts`() = fixture(unix = false) { root ->
        val file = Files.writeString(root.resolve("input"), "original")
        val cache = root.resolve("cache")
        var reads = 0
        repeat(2) {
            WorkspaceChecksums(root, cache, hash = { source ->
                reads++
                digest(source)
            }, reliable = { _ -> false }).use { checksums ->
                assertEquals(digest(file), checksums.checksum(file))
            }
        }
        assertEquals(2, reads)
        assertFalse(Files.exists(cache))
    }

    @Test
    fun `unavailable cache storage preserves fresh byte verification`() = fixture(unix = false) { root ->
        val file = Files.writeString(root.resolve("input"), "original")
        val cache = Files.writeString(root.resolve("cache"), "not a directory")
        WorkspaceChecksums(root, cache).use { assertEquals(digest(file), it.checksum(file)) }
    }

    @Test
    fun `changes during hashing cannot publish or return a verified digest`() = fixture { root ->
        val file = Files.writeString(root.resolve("input"), "original")
        val cache = root.resolve("cache")
        assertFailsWith<IllegalArgumentException> {
            WorkspaceChecksums(root, cache, hash = { source ->
                digest(source).also { Files.writeString(source, "modified") }
            }).use { it.checksum(file) }
        }
        assertFalse(Files.exists(cache))
    }

    @Test
    fun `checksum failures retain their error rather than supplying a cache claim`() = fixture(unix = false) { root ->
        val file = Files.writeString(root.resolve("input"), "original")
        assertFailsWith<IOException> {
            WorkspaceChecksums(root, root.resolve("cache"), hash = { throw IOException("read failed") }).use {
                it.checksum(file)
            }
        }
    }

    @Test
    fun `same process contention preserves the active writer descriptor`() = fixture { root ->
        val file = Files.writeString(root.resolve("input"), "original")
        val cache = root.resolve("cache")
        var opened = 0
        var written = 0
        val open: (Path) -> FileChannel = { path ->
            opened++
            FileChannel.open(path, CREATE, WRITE)
        }
        val second = WorkspaceChecksums(root, cache, open = open)
        second.checksum(file)
        val first = WorkspaceChecksums(root, cache, open = open, write = { path, bytes ->
            second.close()
            written++
            Files.write(path, bytes)
        })
        first.checksum(file)

        first.close()

        assertEquals(1, opened)
        assertEquals(1, written)
        assertEquals(0L, Files.list(cache).use { files -> files.filter { it.toString().endsWith(".pending") }.count() })
        WorkspaceChecksums(root, cache, hash = { error("published checksum must remain reusable") }).use {
            assertEquals(digest(file), it.checksum(file))
        }
    }

    private fun fixture(unix: Boolean = true, block: (Path) -> Unit) {
        val root = Files.createTempDirectory("lab-checksums")
        try {
            if (!unix || Files.getFileStore(root).supportsFileAttributeView("unix")) block(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun digest(file: Path): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))
}
