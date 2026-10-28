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

    @Test fun `report trees include directories linked inside the output directory`() {
        val root = Files.createTempDirectory("bench-context")
        try {
            val context = BenchContext(root.resolve("out"))
            val captures = Files.createDirectories(context.outputDirectory.resolve("captures"))
            Files.writeString(captures.resolve("raw.csv"), "sample,value\n0,12\n")
            Files.createSymbolicLink(context.outputDirectory.resolve("report"), captures)

            assertEquals(listOf("report/raw.csv"), context.artifacts().map { it.path })

            Files.delete(context.outputDirectory.resolve("report"))
            val report = Files.createDirectories(context.reportPath())
            Files.createSymbolicLink(report.resolve("linked"), captures)
            assertEquals(listOf("report/linked/raw.csv"), context.artifacts().map { it.path })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `report trees reject escaping directory links even when empty`() {
        val root = Files.createTempDirectory("bench-context")
        try {
            val context = BenchContext(root.resolve("out"))
            val report = Files.createDirectories(context.reportPath())
            val outside = Files.createDirectories(root.resolve("outside"))
            Files.createSymbolicLink(report.resolve("escape"), outside)

            assertFailsWith<IllegalArgumentException> { context.artifacts() }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `report directory link cycles fail instead of hanging`() {
        val root = Files.createTempDirectory("bench-context")
        try {
            val context = BenchContext(root.resolve("out"))
            val report = Files.createDirectories(context.reportPath())
            Files.createSymbolicLink(report.resolve("loop"), report)

            val failure = assertFailsWith<java.io.UncheckedIOException> { context.artifacts() }
            assertIs<java.nio.file.FileSystemLoopException>(failure.cause)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `a dangling link is not an absent report destination`() {
        val root = Files.createTempDirectory("bench-context")
        try {
            val context = BenchContext(root.resolve("out"))
            Files.createSymbolicLink(context.outputDirectory.resolve("report"), root.resolve("missing"))

            assertFailsWith<IllegalArgumentException> { context.reportPath() }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `a dangling report link cannot silently return an empty artifact list`() {
        val root = Files.createTempDirectory("bench-context")
        try {
            val context = BenchContext(root.resolve("out"))
            Files.createSymbolicLink(context.outputDirectory.resolve("report"), root.resolve("missing"))

            assertFailsWith<java.nio.file.NoSuchFileException> { context.artifacts() }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `an escaping log parent is rejected before creating outside directories`() {
        val root = Files.createTempDirectory("bench-context")
        try {
            val context = BenchContext(root.resolve("out"))
            val outside = Files.createDirectories(root.resolve("outside"))
            Files.createSymbolicLink(context.outputDirectory.resolve("linked"), outside)

            assertFailsWith<IllegalArgumentException> {
                context.process(listOf("bash", "-c", "exit 0"), "linked/new/capture.log")
            }
            assertFalse(Files.exists(outside.resolve("new")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `subprocesses receive EOF on stdin without waiting for a timeout`() {
        val root = Files.createTempDirectory("bench-context")
        try {
            val context = BenchContext(root.resolve("out"))

            val exit = context.process(listOf("bash", "-c", "read -r -t 2 value; exit \$?"), "capture.log")

            assertEquals(1, exit, "read must receive EOF rather than time out on an open stdin pipe")
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
