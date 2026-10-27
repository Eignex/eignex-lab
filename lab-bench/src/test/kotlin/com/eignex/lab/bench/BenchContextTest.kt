package com.eignex.lab.bench

import java.nio.file.Files
import kotlin.test.*

class BenchContextTest {
    @Test fun `report destination stays absent until the harness creates it`() {
        val root = Files.createTempDirectory("bench-context")
        try {
            val context = BenchContext(root.resolve("out"))
            val report = context.reportPath()
            assertFalse(Files.exists(report))
            Files.createDirectories(report.resolve("nested"))
            Files.writeString(report.resolve("nested/raw.csv"), "sample,value\n0,12\n")
            assertEquals(listOf("report/nested/raw.csv"), context.artifacts().map { it.path })
            assertFailsWith<IllegalArgumentException> { context.reportPath() }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `report trees reject files reached through escaping links`() {
        val root = Files.createTempDirectory("bench-context")
        try {
            val context = BenchContext(root.resolve("out"))
            val report = Files.createDirectories(context.reportPath())
            Files.writeString(root.resolve("private"), "private")
            Files.createSymbolicLink(report.resolve("escape"), root.resolve("private"))
            assertFailsWith<IllegalArgumentException> { context.artifacts() }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `subprocess failures retain original argv output and partial reports`() {
        val root = Files.createTempDirectory("bench-context")
        try {
            val context = BenchContext(root.resolve("out"), worktree = root)
            val script = root.resolve("harness.sh")
            Files.writeString(script, "mkdir \"\$1\"; printf '%s' \"\$2\" > \"\$1/partial.txt\"; echo failed; exit 7\n")
            val exit = context.process(listOf("bash", script.toString(), context.reportPath().toString(), "spaces ' literal $ value"), "capture.log")
            assertEquals(7, exit)
            assertEquals("spaces ' literal $ value", Files.readString(context.outputDirectory.resolve("report/partial.txt")))
            assertEquals("failed\n", Files.readString(context.outputDirectory.resolve("capture.log")))
        } finally { root.toFile().deleteRecursively() }
    }
}
