package com.eignex.lab

import com.eignex.lab.util.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeleteTreeTest {
    @Test
    fun `a tree is removed without following its links, tolerating a root already gone`() {
        val base = Files.createTempDirectory("delete-tree")
        try {
            val outside = Files.createDirectories(base.resolve("outside")).also { Files.writeString(it.resolve("kept"), "kept") }
            for (ignoreMissing in listOf(false, true)) {
                val root = Files.createDirectories(base.resolve("root-$ignoreMissing/a/b")).parent.parent
                Files.writeString(root.resolve("a/b/file"), "bytes")
                Files.createSymbolicLink(root.resolve("a/link"), outside)

                deleteTree(root, ignoreMissing)

                assertFalse(Files.exists(root))
                assertTrue(Files.exists(outside.resolve("kept")))
            }
            deleteTree(base.resolve("never-there"), ignoreMissing = true)
        } finally { deleteTree(base) }
    }
}
