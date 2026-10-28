package com.eignex.lab.reference

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MiniZincReferenceTest {
    @Test
    fun `a versioned solver selector should reach the native command unchanged`() {
        val model = File(checkNotNull(javaClass.getResource("/reference/affine-objective.mzn")).toURI())

        val command = MiniZincReference.command(Instance("affine-objective", Format.MINIZINC, model),
            "gecode@6.4.0", Budget(1000), 1, free = true)

        assertEquals("gecode@6.4.0", command[command.indexOf("--solver") + 1])
    }

    @Test
    fun `a completed optimisation should report its last objective as proven`() {
        val output = "x = 3;\n_objective = 9;\n----------\nx = 2;\n_objective = 4;\n----------\n==========\n"

        val r = MiniZincReference.parse(output.lineSequence())

        assertEquals(true, r.feasible)
        assertEquals(true, r.proven)
        assertEquals("4", r.exactObjective)
    }

    @Test
    fun `an unsatisfiable model should be a proven infeasibility`() {
        val r = MiniZincReference.parse("=====UNSATISFIABLE=====\n".lineSequence())

        assertEquals(false, r.feasible)
        assertEquals(true, r.proven)
        assertNull(r.objective)
    }

    @Test
    fun `an incumbent without the completion marker should stay unproven`() {
        val r = MiniZincReference.parse("x = 1;\n----------\n%%%mzn-stat: nodes=12\n".lineSequence())

        assertEquals(true, r.feasible)
        assertEquals(false, r.proven)
        assertEquals("12", r.stats["nodes"])
    }
}
