package com.eignex.lab.reference

import com.eignex.lab.References
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ReferenceCaseTest {
    private val directory = Files.createTempDirectory("reference-case").toFile()
    private val model = File(directory, "p.smt2").apply { writeText("(assert false)\n(check-sat)\n") }
    private val instance = Instance("family/p", Format.SMTLIB, model)

    private fun case() = ReferenceCase(instance, Budget(10_000), null, 1, 3, true, File(directory, "out"))

    @Test
    fun `a reference verdict should become the lab's reference row`() {
        val record = case().run {
            ReferenceSolve.Run("z3", Z3Reference.parse("unsat\n", 120, listOf("z3")), optimize = false, maximize = false)
        }

        val row = References.of(record)!!

        assertEquals("z3", row.solver)
        assertEquals(false, row.feasible)
        assertEquals(true, row.proven)
        assertEquals(10_000, row.budgetMs)
        assertEquals(CorpusFiles.sha256(model), record.getValue("sourceHashes").jsonObject.getValue("model").jsonPrimitive.content)
    }

    @Test
    fun `a failing solve should leave an error record the lab keeps out of the references`() {
        val record = case().run { error("docker run exited 125") }

        assertNull(References.of(record))
        assertEquals("ERROR", record.getValue("command").jsonPrimitive.content)
        assertEquals(true, File(directory, "out/family_p.json").isFile)
    }
}
