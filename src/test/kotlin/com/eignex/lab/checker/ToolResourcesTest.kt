package com.eignex.lab.checker

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ToolResourcesTest {
    @Test
    fun `platform commands should preserve literal tool arguments and install their limits`() {
        val arguments = listOf("/tool with spaces", "a ' literal", "\$(a command)")
        val limits = ToolLimits(3, 1001)

        for (host in listOf("Linux", "Mac OS X")) {
            val command = BoundedToolProcess.command(arguments, limits, host, File("/timing with spaces"))

            assertEquals(arguments, command.takeLast(arguments.size))
            if (host == "Linux") {
                assertTrue("1.001s" in command)
                assertTrue("--as=4294967296" in command)
                assertTrue("--cpu=3" in command)
                assertTrue("--fsize=1048576" in command)
                assertTrue("--core=0" in command)
            } else {
                assertTrue(command.any { it.contains("ulimit -t 3") && it.contains("ulimit -f 1024") })
                assertTrue("/timing with spaces" in command)
            }
        }
    }

    @Test
    fun `native tools should retain complete output and the applied resource envelope`() {
        val work = Files.createTempDirectory("tool-resources").toFile()
        try {
            val input = File(work, "input.smt2").apply { writeText("checked") }
            val command = listOf("/bin/cat")

            val result = ToolProcess.run(command, work, 5000, stdin = input,
                limits = ToolLimits(2, 5000), inputFiles = listOf(input))

            assertEquals(0, result.exit)
            assertEquals("checked", result.stdout)
            val receipt = assertNotNull(result.resources)
            assertTrue(receipt.qualified(), receipt.toString())
            assertEquals(command, receipt.toolCommand)
            assertEquals(7, receipt.stdoutCapturedBytes)
            assertEquals(sha256("checked".toByteArray()), receipt.stdoutSha256)
            assertEquals(sha256("checked".toByteArray()), receipt.stdinSha256)
            assertEquals(mapOf(input.canonicalPath to sha256(input)), receipt.inputFiles)
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun `output at the file limit should not qualify`() {
        val work = Files.createTempDirectory("tool-resources").toFile()
        try {
            val result = ToolProcess.run(listOf("/bin/sh", "-c", "head -c 1048576 /dev/zero"), work, 5000,
                limits = ToolLimits(2, 5000))

            val receipt = assertNotNull(result.resources)
            assertFalse(receipt.qualified())
            assertTrue(receipt.stdoutBytes <= ToolProcess.OUTPUT_CAP)
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun `a hanging tool should lose verdict credit at its deadline`() {
        val work = Files.createTempDirectory("tool-resources").toFile()
        try {
            val result = ToolProcess.run(listOf("/bin/sh", "-c", "exec sleep 30"), work, 100,
                limits = ToolLimits(2, 100))

            assertTrue(result.timedOut)
            assertTrue(result.elapsedMs < 10_000)
            assertFalse(assertNotNull(result.resources).qualified())
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun `incomplete or exceeded resource receipts should not qualify`() {
        val hash = sha256(byteArrayOf())
        val command = BoundedToolProcess.command(listOf("tool"), ToolLimits(30, 35_000), "Linux", File("/unused"))
        val receipt = ToolResourceReceipt(command, listOf("tool"), 30, 35_000, 1_048_576, 0,
            1, false, true, ToolMemoryReceipt(ToolMemoryReceipt.LINUX, ToolMemoryReceipt.LIMIT, true, false),
            hash, 0, 0, 0, 0, hash, hash, true)

        assertTrue(receipt.qualified())
        for (invalid in listOf(
            receipt.copy(cleanupComplete = false), receipt.copy(timedOut = true), receipt.copy(exit = null),
            receipt.copy(utf8Valid = false), receipt.copy(elapsedMs = 35_001), receipt.copy(stdoutBytes = 1_048_576),
            receipt.copy(stderrBytes = 1), receipt.copy(stdoutSha256 = "missing"),
            receipt.copy(memory = receipt.memory.copy(exceeded = true)),
            receipt.copy(command = listOf("tool")),
        )) assertFalse(invalid.qualified(), invalid.toString())
    }

    @Test
    fun `lab check storage should retain typed resource receipts`() {
        val hash = sha256(byteArrayOf())
        val command = BoundedToolProcess.command(listOf("tool"), ToolLimits(30, 35_000), "Linux", File("/unused"))
        val receipt = ToolResourceReceipt(command, listOf("tool"), 30, 35_000, 1_048_576, 0,
            1, false, true, ToolMemoryReceipt(ToolMemoryReceipt.LINUX, ToolMemoryReceipt.LIMIT, true, false),
            hash, 0, 0, 0, 0, hash, hash, true)
        val check = LabCheck(Policies.SMTLIB, LabCheck.VALID, "accepted", "dolmen", resourceReceipts = listOf(receipt))

        val restored = assertNotNull(LabCheck.parse(check.json()))

        assertEquals(check, restored)
        assertTrue(restored.resourceReceipts.single().qualified())
    }

    @Test
    fun `historical lab checks should decode without resource receipts`() {
        val text = """{"policy":"old","state":"valid","reason":"accepted","checker":"dolmen"}"""

        val restored = assertNotNull(LabCheck.parse(text))

        assertEquals("old", restored.policy)
        assertEquals(LabCheck.VALID, restored.state)
        assertTrue(restored.resourceReceipts.isEmpty())
    }
}
