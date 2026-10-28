package com.eignex.lab.checker

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNull

class MiniZincFrameTest {
    @Test
    fun `changed compiler output cannot replace an accepted captured frame`() {
        val root = Files.createTempDirectory("minizinc-capture")
        try {
            val files = listOf("model.fzn", "model.ozn", "cli.stdout").map { name ->
                root.resolve(name).toFile().apply { writeText(name) }
            }
            val accepted = files.associate { it.name to sha256(it) }
            files[0].writeText("replacement FlatZinc")

            assertNull(MiniZincFrame.read(root, "", accepted))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
