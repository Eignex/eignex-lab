package com.eignex.lab.reference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpsReferenceTest {
    private fun result(claim: MpsReference.Claim) = MpsReference.result(claim, false, 10, "output", "command", "test", "", emptyMap())

    @Test fun `native assignments are retained without repair or source evaluation`() {
        val result = result(MpsReference.Claim(MpsReference.Status.OPTIMAL, 7.0, 7.0, 0.0, mapOf("x" to 0.999999)))

        assertEquals(true, result.feasible)
        assertEquals(7.0, result.objective)
        assertTrue(result.proven)
        assertEquals("x 0.999999\n", result.assignment)
        assertEquals("unchecked", result.stats["validation"])
    }

    @Test fun `an optimal status with an open reported gap remains an incumbent`() {
        val result = result(MpsReference.Claim(MpsReference.Status.OPTIMAL, 10000.0, 9999.5, 0.00005, mapOf("x" to 1.0)))

        assertEquals(true, result.feasible)
        assertFalse(result.proven)
    }

    @Test fun `native infeasibility needs no assignment`() {
        val result = result(MpsReference.Claim(MpsReference.Status.INFEASIBLE, null, null, null, null))

        assertEquals(false, result.feasible)
        assertTrue(result.proven)
    }

    @Test fun `a native optimum counts without retaining an assignment`() {
        val result = result(MpsReference.Claim(MpsReference.Status.OPTIMAL, 7.0, 7.0, 0.0, null))

        assertEquals(true, result.feasible)
        assertTrue(result.proven)
        assertEquals(null, result.assignment)
    }

    @Test fun `MPS objective orientation comes from the header only`() {
        assertTrue(MpsReference.maximize("NAME test\n* note\nOBJSENSE\n MAX\nROWS\n N obj\n"))
        assertTrue(MpsReference.maximize("NAME test\nOBJSENSE MAXIMIZE\nROWS\n"))
        assertFalse(MpsReference.maximize("NAME test\nROWS\n N OBJSENSE\n"))
    }
}
