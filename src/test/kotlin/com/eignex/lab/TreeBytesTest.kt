package com.eignex.lab

import com.eignex.lab.util.*
import java.nio.file.Files
import kotlin.test.*

class TreeBytesTest {
    @Test fun `fallback counts regular files once and never follows links`() {
        val root = Files.createTempDirectory("tree-byte-fallback")
        try {
            Files.write(root.resolve("one"), ByteArray(7))
            val child = Files.createDirectory(root.resolve("nested"))
            Files.write(child.resolve("two"), ByteArray(11))
            Files.createSymbolicLink(root.resolve("loop"), root)
            assertEquals(18L, treeBytes(root, entries = { _, _ -> null }))
            assertEquals(18L, treeBytes(root))
            assertFailsWith<IllegalArgumentException> { treeBytes(root, 17) }
            assertEquals(0L, treeBytes(root.resolve("missing")))
        } finally { deleteTree(root) }
    }

    @Test fun `bulk size accounting does not stat individual regular files`() {
        val root = Files.createTempDirectory("tree-byte-batch")
        try {
            val child = Files.createDirectory(root.resolve("nested"))
            val visited = mutableListOf<java.nio.file.Path>()
            fun entry(name: String, directory: Boolean, bytes: Long) = MacDirectoryEntries.Entry(name,
                directory, !directory, bytes, "unused", "unused", "unused", false)
            assertEquals(18L, treeBytes(root, entries = { path, check ->
                check(); visited.add(path)
                // These names do not exist: their authoritative batch attributes must be enough.
                if (path == root) listOf(entry("one", false, 7), entry("nested", true, 0))
                else listOf(entry("two", false, 11))
            }))
            assertEquals(listOf(root, child), visited)
        } finally { deleteTree(root) }
    }

    @Test fun `accounting propagates cancellation and metadata failures`() {
        val root = Files.createTempDirectory("tree-byte-failure")
        try {
            assertFailsWith<InterruptedException> { treeBytes(root, checkActive = { throw InterruptedException() }) }
            assertFailsWith<java.io.IOException> { treeBytes(root, entries = { _, _ -> throw java.io.IOException("disk") }) }
            assertFailsWith<IllegalArgumentException> { treeBytes(root, entries = { _, _ ->
                listOf(MacDirectoryEntries.Entry("oversize", false, true, -1, "", "", "", false))
            }) }
        } finally { deleteTree(root) }
    }
}
