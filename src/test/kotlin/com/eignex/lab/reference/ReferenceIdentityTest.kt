package com.eignex.lab.reference

import com.eignex.lab.analysis.References
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ReferenceIdentityTest {
    private val directory = Files.createTempDirectory("reference-identity-test").toFile()

    @AfterTest
    fun cleanup() { directory.deleteRecursively() }

    @Test
    fun `native identities retain versions and distinguish builds with the same version`() {
        val binary = script("printf 'solver 1.0\\n'")
        val original = ReferenceIdentity.native(binary.path, 5_000)
        binary.appendText("# another build\n")
        val rebuilt = ReferenceIdentity.native(binary.path, 5_000)

        assertEquals("solver 1.0", original.stats["referenceSolverVersion"])
        assertEquals(binary.canonicalPath, original.executable)
        assertNotEquals(original.stats["referenceSolverSha256"], rebuilt.stats["referenceSolverSha256"])
        assertNotEquals(original.stats["referenceVersion"], rebuilt.stats["referenceVersion"])
    }

    @Test
    fun `missing failed or placeholder version output cannot qualify a native build`() {
        for (output in listOf("", "unknown", "Unknown", "null", "unavailable")) {
            assertFailsWith<IllegalStateException> {
                ReferenceIdentity.native(script("printf '$output'").path, 5_000)
            }
        }
        assertFailsWith<IllegalStateException> {
            ReferenceIdentity.native(script("printf 'solver 1.0'; exit 1").path, 5_000)
        }
    }

    @Test
    fun `a version query cannot outlive its allowance`() {
        assertFailsWith<IllegalStateException> {
            ReferenceIdentity.native(script("sleep 5; printf 'solver 1.0'").path, 50)
        }
    }

    @Test
    fun `changing the executable during version capture fails the reference attempt`() {
        assertFailsWith<IllegalStateException> {
            ReferenceIdentity.native(script("printf '# changed\\n' >> \"\$0\"; printf 'solver 1.0'").path, 5_000)
        }
    }

    @Test
    fun `image identities resolve the exact image used by the solve command`() {
        val id = "sha256:" + "a".repeat(64)
        val docker = script("test \"\$*\" = 'image inspect --format {{.Id}} test-image:latest' || exit 1; printf '$id'")

        val identity = ReferenceIdentity.image("test-image:latest", 5_000, docker.path)
        val command = DockerReference(identity.executable, "test-reference", "test solver")
            .command("test-case", listOf("--time-limit=1"))

        assertEquals("test-image:latest@$id", identity.stats["referenceVersion"])
        assertEquals(id, identity.stats["referenceImageId"])
        assertEquals(listOf(id, "--time-limit=1"), command.takeLast(2))
    }

    @Test
    fun `unknown malformed or failed image lookups cannot qualify a container build`() {
        for (output in listOf("", "unknown", "test-image:latest", "sha256:abc")) {
            assertFailsWith<IllegalStateException> {
                ReferenceIdentity.image("test-image:latest", 5_000, script("printf '$output'").path)
            }
        }
        assertFailsWith<IllegalStateException> {
            ReferenceIdentity.image("test-image:latest", 5_000, script("exit 1").path)
        }
    }

    @Test
    fun `native reference records qualify comparisons on the captured source`() {
        for (solver in listOf("kissat", "cvc5")) {
            val output = if (solver == "kissat") "s SATISFIABLE" else "sat"
            val binary = script("if [ \"\$1\" = --version ]; then printf '$solver 1.0'; else printf '$output\\n'; fi")
            val format = if (solver == "kissat") Format.DIMACS else Format.SMTLIB
            val model = File(directory, "model").apply { writeText("fixture") }
            val instance = Instance("fixture", format, model)
            val record = ReferenceCase(instance, Budget(5_000), solver, 1, 3, true, File(directory, solver)).run {
                val result = if (solver == "kissat") KissatReference.run(instance, Budget(5_000), binary.path)
                    else Cvc5Reference.run(instance, Budget(5_000), binary.path)
                ReferenceSolve.Run(solver, result, optimize = false, maximize = false)
            }
            val row = checkNotNull(References.of(record))

            assertTrue(row.proven)
            assertContains(row.version, "$solver 1.0|sha256=")
            assertContains(row.version, "$solver-reference-v1")
            assertEquals(listOf(row), References.applicable(listOf(row), References.identity(record)))
        }
    }

    @Test
    fun `native version lookup spends the solve allowance`() {
        val binary = script("if [ \"\$1\" = --version ]; then sleep 0.05; printf 'cvc5 1.0'; else printf '%s\\n' \"\$*\" > '${directory.path}/arguments'; printf 'sat\\n'; fi")
        val model = File(directory, "model.smt2").apply { writeText("(check-sat)") }

        Cvc5Reference.run(Instance("fixture", Format.SMTLIB, model), Budget(5_000), binary.path)

        val remaining = File(directory, "arguments").readText().substringAfter("--tlimit=").substringBefore(' ').toLong()
        assertTrue(remaining in 1..4_950)
    }

    private fun script(body: String): File = File(directory, "solver").apply {
        writeText("#!/bin/sh\n$body\n")
        check(setExecutable(true))
    }
}
