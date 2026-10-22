package com.eignex.lab.checker

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolMemoryReceiptTest {
    @Test
    fun `kernel observations should bind their positive peak and timing bytes`() {
        val work = Files.createTempDirectory("tool-memory").toFile()
        try {
            val memory = DarwinToolMemory(work, 1_000_000)
            val text = "  32768  maximum resident set size\n"
            memory.timingFile.writeText(text)

            val receipt = memory.finish()

            assertTrue(receipt.qualified())
            assertEquals(32768L, receipt.kernelPeakBytes)
            assertEquals(text, receipt.kernelTiming)
            assertEquals(sha256(text.toByteArray()), receipt.timingSha256)
            assertFalse(receipt.copy(kernelTiming = text + " ").qualified())
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun `missing duplicate and nonpositive kernel peaks should decline`() {
        val work = Files.createTempDirectory("tool-memory").toFile()
        try {
            for (text in listOf("", "0 maximum resident set size\n", "1 maximum resident set size\n2 maximum resident set size\n")) {
                val memory = DarwinToolMemory(work, 1_000_000)
                memory.timingFile.writeText(text)

                assertFalse(memory.finish().qualified(), text)
            }
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun `resident samples should include every live descendant`() {
        val work = Files.createTempDirectory("tool-memory").toFile()
        try {
            val memory = DarwinToolMemory(work, 1_000_000)
            memory.timingFile.writeText("32768 maximum resident set size\n")

            assertTrue(memory.recordSample("1 10\n2 20\n", setOf(1L, 2L), { setOf(1L, 2L) }))
            val receipt = memory.finish()

            assertTrue(receipt.qualified())
            assertEquals(30720L, receipt.samplePeakBytes)
            assertEquals(1, receipt.samples)
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun `incomplete ambiguous and overflowing resident samples should decline`() {
        val work = Files.createTempDirectory("tool-memory").toFile()
        try {
            for (text in listOf("1 10\n", "1 10\n1 20\n", "1 10\n3 20\n", "1 -1\n2 2\n", "1 9223372036854775807\n2 1\n")) {
                val memory = DarwinToolMemory(work, 1_000_000)
                memory.timingFile.writeText("32768 maximum resident set size\n")

                assertFalse(memory.recordSample(text, setOf(1L, 2L), { setOf(1L, 2L) }), text)
                assertFalse(memory.finish().qualified(), text)
            }
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun `sample or kernel memory excess should reject the envelope`() {
        val work = Files.createTempDirectory("tool-memory").toFile()
        try {
            for (sampled in listOf(false, true)) {
                val memory = DarwinToolMemory(work, 1_000)
                memory.timingFile.writeText("${if (sampled) 512 else 2048} maximum resident set size\n")
                if (sampled) assertFalse(memory.recordSample("1 2\n", setOf(1L), { setOf(1L) }))

                val receipt = memory.finish()

                assertTrue(receipt.exceeded)
                assertFalse(receipt.qualified())
            }
        } finally {
            work.deleteRecursively()
        }
    }
}
