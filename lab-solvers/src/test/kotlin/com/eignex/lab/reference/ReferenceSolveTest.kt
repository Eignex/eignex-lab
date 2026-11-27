package com.eignex.lab.reference

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReferenceSolveTest {
    @Test
    fun `each format should default to its first reference solver`() {
        val defaults = Format.entries.associateWith { ReferenceSolve.solverIdFor(it) }

        assertEquals(
            mapOf(
                Format.MINIZINC to "cp-sat", Format.XCSP3 to "cp-sat", Format.DIMACS to "clasp", Format.OPB to "clasp",
                Format.WCNF to "clasp", Format.SMTLIB to "z3", Format.MPS to "scip",
            ),
            defaults,
        )
    }

    @Test
    fun `a named second reference should be accepted for its format`() {
        assertEquals("kissat", ReferenceSolve.solverIdFor(Format.DIMACS, "kissat"))
    }

    @Test
    fun `unsupported XCSP solvers should be rejected before reference invocation`() {
        val instance = Instance("absent", Format.XCSP3, File("absent.xml"))
        val errors = listOf("ace", "choco", "unknown").map { solver ->
            assertFailsWith<IllegalArgumentException> {
                ReferenceSolve.run(instance, Budget(1), solver)
            }.message
        }

        assertEquals(
            listOf("ace", "choco", "unknown").map {
                "reference solver '$it' does not solve XCSP3; it is solved by cp-sat"
            },
            errors,
        )
    }
}
