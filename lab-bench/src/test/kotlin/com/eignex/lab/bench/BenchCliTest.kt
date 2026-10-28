package com.eignex.lab.bench

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BenchCliTest {
    @Test
    fun `describe and plan do not execute a workload`() = withDirectory { root ->
        val suite = object : ExampleSuite() {
            override fun plan(request: BenchRequest): List<BenchCase> = listOf(BenchCase("selected", request.parameters))
            override fun run(case: BenchCase, context: BenchContext): BenchResult = error("must not execute")
        }
        val descriptorOutput = StringBuilder()
        BenchCli.execute(suite, arrayOf("describe"), stdout = descriptorOutput)
        val request = root.resolve("request.json").apply {
            writeText(BenchProtocol.json.encodeToString(BenchRequest(mapOf("size" to "65"))))
        }
        val planOutput = StringBuilder()

        BenchCli.execute(suite, arrayOf("plan", request.toString()), stdout = planOutput)

        assertEquals("example", BenchProtocol.json.decodeFromString<BenchDescriptor>(descriptorOutput.toString()).id)
        assertEquals(mapOf("size" to "65"), BenchProtocol.json.decodeFromString<BenchPlan>(planOutput.toString()).cases.single().parameters)
    }

    @Test
    fun `run uses the lab output directory and retains measurements and captures`() = withDirectory { root ->
        val case = writeCase(root)
        val output = root.resolve("retained output")
        val suite = object : ExampleSuite() {
            override fun run(case: BenchCase, context: BenchContext): BenchResult {
                assertEquals("Eignex/koblas", context.repository)
                assertEquals(root, context.worktree)
                context.outputDirectory.resolve("raw.csv").writeText("case,ns\ndot,42\n")
                return BenchResult(case.id, metrics = listOf(BenchMetric("latency", 42.0, "ns/op")),
                    artifacts = listOf(context.artifact("raw.csv", "text/csv")))
            }
        }

        BenchCli.execute(suite, arrayOf("run", case.toString()), mapOf(
            "OUT" to output.toString(), "LAB_REPOSITORY" to "Eignex/koblas", "LAB_WORKTREE" to root.toString(),
        ))

        val result = readResult(output)
        assertEquals(BenchStatus.SUCCESS, result.status)
        assertEquals(42.0, result.metrics.single().value)
        assertEquals("raw.csv", result.artifacts.single().path)
        assertEquals("case,ns\ndot,42\n", output.resolve("raw.csv").readText())
    }

    @Test
    fun `a local explicit output directory takes precedence over OUT`() = withDirectory { root ->
        val output = root.resolve("explicit")

        BenchCli.execute(ExampleSuite(), arrayOf("run", writeCase(root).toString(), output.toString()),
            mapOf("OUT" to root.resolve("unused").toString()))

        assertEquals(BenchStatus.SUCCESS, readResult(output).status)
        assertFalse(root.resolve("unused").exists())
    }

    @Test
    fun `exceptions retain partial captures and a failed result while propagating`() = withDirectory { root ->
        val failure = IllegalStateException("backend unavailable")
        val output = root.resolve("out")
        val suite = object : ExampleSuite() {
            override fun run(case: BenchCase, context: BenchContext): BenchResult {
                context.outputDirectory.resolve("partial.log").writeText("initializing backend")
                throw failure
            }
        }

        val caught = assertFailsWith<IllegalStateException> {
            BenchCli.execute(suite, arrayOf("run", writeCase(root).toString(), output.toString()))
        }

        assertSame(failure, caught)
        assertEquals(BenchStatus.FAILED, readResult(output).status)
        assertEquals("backend unavailable", readResult(output).message)
        assertTrue(output.resolve("partial.log").exists())
    }

    @Test
    fun `an explicitly failed result keeps its measurements and fails the process`() = withDirectory { root ->
        val output = root.resolve("out")
        val suite = object : ExampleSuite() {
            override fun run(case: BenchCase, context: BenchContext): BenchResult = BenchResult(
                case.id, BenchStatus.FAILED, metrics = listOf(BenchMetric("relativeError", 1.0, "ratio")),
                message = "numerical check failed",
            )
        }

        assertFailsWith<IllegalStateException> {
            BenchCli.execute(suite, arrayOf("run", writeCase(root).toString(), output.toString()))
        }

        assertEquals(1.0, readResult(output).metrics.single().value)
        assertEquals("numerical check failed", readResult(output).message)
    }

    @Test
    fun `a deliberately skipped workload is retained without a process failure`() = withDirectory { root ->
        val output = root.resolve("out")
        val suite = object : ExampleSuite() {
            override fun run(case: BenchCase, context: BenchContext): BenchResult =
                BenchResult(case.id, BenchStatus.SKIPPED, message = "optional vendor absent")
        }

        BenchCli.execute(suite, arrayOf("run", writeCase(root).toString(), output.toString()))

        assertEquals(BenchStatus.SKIPPED, readResult(output).status)
    }

    @Test
    fun `retry removes an earlier successful receipt before executing`() = withDirectory { root ->
        val output = root.resolve("out").createDirectories()
        output.resolve("result.json").writeText(BenchProtocol.json.encodeToString(BenchResult("dot")))
        val suite = object : ExampleSuite() {
            override fun run(case: BenchCase, context: BenchContext): BenchResult {
                assertFalse(context.outputDirectory.resolve("result.json").exists())
                return BenchResult(case.id)
            }
        }

        BenchCli.execute(suite, arrayOf("run", writeCase(root).toString(), output.toString()))

        assertEquals(BenchStatus.SUCCESS, readResult(output).status)
    }

    @Test
    fun `incompatible case versions are rejected before the suite is called`() = withDirectory { root ->
        val case = root.resolve("case.json").apply { writeText("""{"id":"dot","protocolVersion":2}""") }
        val output = root.resolve("out")
        val suite = object : ExampleSuite() {
            override fun run(case: BenchCase, context: BenchContext): BenchResult = error("must not execute")
        }

        assertFailsWith<IllegalArgumentException> {
            BenchCli.execute(suite, arrayOf("run", case.toString(), output.toString()))
        }
        assertFalse(output.exists())
    }

    @Test
    fun `a result for a different workload cannot be recorded as successful`() = withDirectory { root ->
        val output = root.resolve("out")
        val suite = object : ExampleSuite() {
            override fun run(case: BenchCase, context: BenchContext): BenchResult = BenchResult("different")
        }

        assertFailsWith<IllegalArgumentException> {
            BenchCli.execute(suite, arrayOf("run", writeCase(root).toString(), output.toString()))
        }

        assertEquals("dot", readResult(output).caseId)
        assertEquals(BenchStatus.FAILED, readResult(output).status)
    }

    @Test
    fun `artifact references cannot escape the retained output or replace its receipt`() = withDirectory { root ->
        val output = root.resolve("out").createDirectories()
        val outside = root.resolve("outside.txt").apply { writeText("outside") }
        val context = BenchContext(output)
        for (path in listOf("../outside.txt", "/outside.txt", "nested/../outside.txt", "C:\\outside.txt", "result.json")) {
            assertFailsWith<IllegalArgumentException> { context.artifact(path) }
        }
        Files.createSymbolicLink(output.resolve("linked.txt"), outside)
        assertFailsWith<IllegalArgumentException> { context.artifact("linked.txt") }
    }

    @Test
    fun `unvalidated artifact declarations are checked before recording success`() = withDirectory { root ->
        val output = root.resolve("out")
        val suite = object : ExampleSuite() {
            override fun run(case: BenchCase, context: BenchContext): BenchResult =
                BenchResult(case.id, artifacts = listOf(BenchArtifact("missing.json")))
        }

        assertFailsWith<java.nio.file.NoSuchFileException> {
            BenchCli.execute(suite, arrayOf("run", writeCase(root).toString(), output.toString()))
        }

        assertEquals(BenchStatus.FAILED, readResult(output).status)
    }

    @Test
    fun `run requires an output directory instead of writing into the checkout`() = withDirectory { root ->
        assertFailsWith<IllegalArgumentException> {
            BenchCli.execute(ExampleSuite(), arrayOf("run", writeCase(root).toString()), environment = emptyMap())
        }
    }

    private open class ExampleSuite : BenchSuite {
        override fun describe(): BenchDescriptor = BenchDescriptor("example", "Fixture suite")
        override fun plan(request: BenchRequest): List<BenchCase> = emptyList()
        override fun run(case: BenchCase, context: BenchContext): BenchResult = BenchResult(case.id)
    }

    private fun writeCase(root: Path): Path = root.resolve("case.json").apply {
        writeText(BenchProtocol.json.encodeToString(BenchCase("dot")))
    }

    private fun readResult(output: Path): BenchResult = BenchProtocol.json.decodeFromString(output.resolve("result.json").readText())

    private inline fun withDirectory(action: (Path) -> Unit) {
        val root = Files.createTempDirectory("bench cli ")
        try { action(root) } finally { root.toFile().deleteRecursively() }
    }
}
