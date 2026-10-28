package com.eignex.lab.checker

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MiniZincPointTest {
    private val fzn = """
        var 1..20: x :: output_var;
        var 2..40: hidden = x;
        constraint int_lin_eq([2,-1], [x,hidden], 0);
        solve :: int_search([x], input_order, indomain_min, complete) minimize hidden;
    """.trimIndent()
    private val stdout = """
        x = 7;
        _objective = 14;
        % klause-assignment-begin: flatzinc-integer-v1
        % klause-assignment: x = 7;
        % klause-assignment: hidden = 14;
        % klause-assignment: _objective = 14;
        % klause-assignment-end
        ----------
    """.trimIndent()

    @Test
    fun `point binding preserves original aliases and domains while fixing hidden coordinates`() {
        val bound = MiniZincPoint.bind(MiniZincFrame(fzn, "", stdout), "14")
        assertContains(bound.model, "var 2..40: hidden = x;")
        assertContains(bound.model, "constraint hidden = 14;")
        assertContains(bound.model, "constraint int_lin_eq([2,-1], [x,hidden], 0);")
        assertFalse("int_search" in bound.model)
        assertFalse("klause-assignment" in bound.native)
    }

    @Test
    fun `parser constants do not replace or add original variable obligations`() {
        val captured = stdout.replace("% klause-assignment-end",
            "% klause-assignment: __const_int_-10039_1826 = -10039;\n% klause-assignment-end")

        val bound = MiniZincPoint.bind(MiniZincFrame(fzn, "", captured), "14")

        assertFalse("__const_int_" in bound.model)
        kotlin.test.assertEquals(1, bound.diagnosticCoordinates)
    }

    @Test
    fun `missing hidden coordinates cannot be completed by the compiler`() {
        assertFailsWith<IllegalArgumentException> {
            MiniZincPoint.bind(MiniZincFrame(fzn, "", stdout.replace("% klause-assignment: hidden = 14;\n", "")), "14")
        }
    }

    @Test
    fun `record objective must agree exactly with the captured original objective`() {
        assertFailsWith<IllegalArgumentException> {
            MiniZincPoint.bind(MiniZincFrame(fzn, "", stdout), "15")
        }
    }

    @Test
    fun `only a discharged compiler output is accepted without backend completion`() {
        assertTrue(MiniZincPoint.discharged("% compiler\nsolve  satisfy;\n"))
        assertFalse(MiniZincPoint.discharged("var 0..1: x; solve satisfy;"))
        assertFalse(MiniZincPoint.discharged("constraint int_eq(1,2); solve satisfy;"))
        assertTrue(MiniZincPoint.inconsistent("constraint bool_eq(false, true); solve satisfy;"))
        assertFalse(MiniZincPoint.inconsistent("solve satisfy;"))
    }
}
