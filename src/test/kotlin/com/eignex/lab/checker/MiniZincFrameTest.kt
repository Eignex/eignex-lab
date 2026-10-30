package com.eignex.lab.checker

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

import java.nio.file.Files
import com.github.luben.zstd.Zstd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MiniZincFrameTest {
    @Test
    fun `packed MiniZinc sources retain their plain source identity`() {
        val root = Files.createTempDirectory("minizinc-source")
        try {
            val text = "var 1..5: x; solve satisfy;\n"
            val packed = root.resolve("source.mzn.zst").toFile().apply { writeBytes(Zstd.compress(text.toByteArray())) }

            assertEquals(text, MiniZincChecker.source(packed))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

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
