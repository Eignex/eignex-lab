package com.eignex.lab.checker

import com.eignex.lab.util.*
import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.analysis.*
import com.eignex.lab.capacity.*
import com.eignex.lab.execution.*
import com.eignex.lab.worker.*
import com.eignex.lab.nomad.*
import com.eignex.lab.web.*
import com.eignex.lab.cli.*

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class DimacsSourceEvaluationTest {
    private fun check(source: String, witness: String) = DimacsSourceEvaluation.check(source, witness)

    @Test fun `every original clause and declared coordinate is checked with no propagation`() {
        val source = "c original\np cnf 3 4\n1 -2\n3 0 2 0\nc between clauses\n-1 3 0\n1 -1 0\n"
        val result = check(source, "v 3 1\nv 2 0")
        assertEquals(LabCheck.VALID, result.state, result.reason)
        assertEquals(3, result.variables)
        assertEquals(4, result.constraints)
        assertEquals(LabCheck.INVALID, check(source, "v 1 -2 3 0").state)
        assertEquals(LabCheck.VALID, check("p cnf 0 0\n", "v 0").state)
        assertEquals(LabCheck.INVALID, check("p cnf 0 1\n0\n", "v 0").state)
        assertEquals(LabCheck.VALID, check("p cnf 2 0\n", "v -2 1").state)
    }

    @Test fun `unused original variables require explicit complete assignment`() {
        val source = "p cnf 3 1\n1 0\n"
        assertEquals(LabCheck.INVALID, check(source, "v 1 0").state)
        assertEquals(LabCheck.VALID, check(source, "v 1 -2 3").state)
        assertEquals(LabCheck.INVALID, check(source, "v 1 -2 3 4").state)
        assertEquals(LabCheck.INVALID, check("p cnf 2 1\n1 0", "v 10").state) // no bit-string interpretation
    }

    @Test fun `malformed contradictory duplicate and incomplete assignments are invalid`() {
        val source = "p cnf 2 1\n1 2 0\n"
        for (witness in listOf("s SATISFIABLE", "v", "v 1", "v 1 1 -2", "v 1 -1 -2", "v 1 -2 0 0", "v 1 0 -2",
            "v 1 -2 x3", "v 1 -2.0", "v +1 -2", "v 01 -2", "v 1 -0", "v 1 -2147483648", "v 1 v -2", "v 1\ns SATISFIABLE", "v 1 -2\nv", "v\nv 1 -2")) {
            assertEquals(LabCheck.INVALID, check(source, witness).state, witness)
        }
        assertContains(check(source, "v 1 -1 -2").reason, "contradictory")
        assertContains(check(source, "v 1 1 -2").reason, "duplicate")
    }

    @Test fun `a false clause rejects and a malformed tail cannot become a partial check`() {
        assertContains(check("p cnf 2 2\n1 0\n2 0", "v 1 -2 0").reason, "clause 2 is false")
        for (source in listOf("1 0", "p wcnf 1 1\n1 1 0", "p cnf 1\n1 0", "p cnf 1 1 extra\n1 0", "p cnf -1 0",
            "p cnf 1 1\n1", "p cnf 1 2\n1 0", "p cnf 1 1\n1 0 1 0", "p cnf 1 1\n1 2 0", "p cnf 1 1\n1 junk 0",
            "p cnf 1 1\n1 -2147483648 0", "p cnf 1 2\n-1 0\np cnf 1 1\n1 0", "p cnf 1 1\n1 0\n%\n0")) {
            assertEquals(LabCheck.UNSUPPORTED, check(source, "v 1").state, source)
        }
    }

    @Test fun `resource refusals never become invalid or valid point evidence`() {
        val normal = DimacsSourceEvaluation.Limits()
        for (limits in listOf(normal.copy(bytes = 4), normal.copy(witness = 2), normal.copy(variables = 1),
            normal.copy(clauses = 0), normal.copy(literals = 1), normal.copy(token = 2))) {
            val source = "p cnf 2 1\n1 2 0"
            val result = DimacsSourceEvaluation.check(source, "v 1 -2", limits)
            assertEquals(LabCheck.UNSUPPORTED, result.state, result.reason)
            assertContains(result.reason, "resource limit")
        }
        assertEquals(LabCheck.UNSUPPORTED, check("p cnf 1000001 0", "v 1").state)
        assertEquals(LabCheck.UNSUPPORTED, check("p cnf 1 2000001", "v 1").state)
        assertEquals(LabCheck.UNSUPPORTED, check("p cnf 1 1\n1 0", "v ${"1".repeat(33)}").state)
    }
}
