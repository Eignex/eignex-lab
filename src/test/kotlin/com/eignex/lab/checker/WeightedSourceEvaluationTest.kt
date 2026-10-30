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
import kotlin.test.assertEquals
import kotlin.test.assertContains

class WeightedSourceEvaluationTest {
    private val huge = "12345678901234567890123456789012345678901234567890"
    private fun check(format: String, source: String, witness: String, cost: String? = "0") =
        WeightedSourceEvaluation.check(format, source.trimIndent(), witness, cost)

    @Test fun `WBO exact soft costs and hard constraints use original coordinates`() {
        val model = """
            * #variable= 3 #constraint= 3
            soft: ;
            1 x1 +1 x2 >= 1;
            [$huge] +1 x2 = 1;
            [7] +1 x3 >= 1;
        """
        val valid = check("OPB", model, "v x1 -x2 x3", huge)
        assertEquals(LabCheck.VALID, valid.state, valid.reason)
        assertEquals(huge, valid.exactObjective)
        assertEquals(3, valid.variables)
        assertEquals(LabCheck.INVALID, check("WBO", model, "v -x1 -x2 x3", huge).state)
        assertEquals(LabCheck.INVALID, check("OPB", model, "v x1 -x2 x3", "7").state)
    }

    @Test fun `OPB objectives preserve signs products complements and repeated terms`() {
        val model = """
            * #variable= 3 #constraint= 2 #product= 2
            min: -$huge x1 x2 +7 ~x2 x3 +2 x1 -2 x1;
            2 x1 x2 +1 ~x2 x3 >= 1;
            1 x3 = 1;
        """
        assertEquals(LabCheck.VALID, check("OPB", model, "v x1 x2 x3", "-$huge").state)
        assertEquals(LabCheck.VALID, check("OPB", model, "v x1 -x2 x3", "7").state)
        assertEquals(LabCheck.INVALID, check("OPB", model, "v x1 -x2 -x3", "0").state)
    }

    @Test fun `WBO top is an aggregate strict upper bound and high weights are hard`() {
        val model = "soft: 6; [3] 1 x1 >= 1; [3] 1 x2 >= 1;"
        assertEquals(LabCheck.INVALID, check("WBO", model, "v -x1 -x2", "6").state)
        assertEquals(LabCheck.VALID, check("WBO", model, "v x1 -x2", "3").state)
        assertEquals(LabCheck.INVALID, check("WBO", "soft: 6; [6] 1 x1 >= 1;", "v -x1").state)
        assertEquals(LabCheck.VALID, check("WBO", "soft: ; [6] 1 x1 >= 1;", "v -x1", "6").state)
    }

    @Test fun `legacy WCNF top separates hard clauses and arbitrary precision soft totals`() {
        val model = """
            c original source
            p wcnf 2 3 999999999999999999999999999999999999999999999999999
            999999999999999999999999999999999999999999999999999 1 0
            $huge -1 2 0
            $huge -1 0
        """
        val twice = "24691357802469135780246913578024691357802469135780"
        assertEquals(LabCheck.VALID, check("WCNF", model, "v 1 -2 0", twice).state)
        assertEquals(LabCheck.INVALID, check("WCNF", model, "v -1 -2 0", "0").state)
        assertEquals(LabCheck.INVALID, check("WCNF", model, "v 1 -2 0", huge).state)
        assertEquals(LabCheck.VALID, check("WCNF", model, "v 10", twice).state)
    }

    @Test fun `headerless WCNF checks all literals even after satisfied literal`() {
        val model = "h 1 2 0\n$huge -1 0\n2 -2 0\n"
        assertEquals(LabCheck.VALID, check("WCNF", model, "v 10", huge).state)
        assertEquals(LabCheck.INVALID, check("WCNF", model, "v 1", huge).state)
        assertEquals(LabCheck.INVALID, check("WCNF", model, "v 00", "0").state)
        assertEquals(LabCheck.INVALID, check("WCNF", model, "v 10", "0").state)
    }

    @Test fun `old nonpartial WCNF without top has only soft clauses`() {
        assertEquals(LabCheck.VALID, check("WCNF", "p wcnf 1 1\n3 1 0\n", "v 0", "3").state)
        assertEquals(LabCheck.VALID, check("WCNF", "p wcnf 1 1\n3 1 0\n", "v 1", "0").state)
    }

    @Test fun `complete declared coordinates include unused variables`() {
        val pb = "* #variable= 2 #constraint= 1\n1 x1 >= 1;"
        val cnf = "p wcnf 2 1 2\n2 1 0\n"
        assertEquals(LabCheck.INVALID, check("OPB", pb, "v x1", null).state)
        assertEquals(LabCheck.INVALID, check("WCNF", cnf, "v 1").state)
        assertEquals(LabCheck.VALID, check("OPB", pb, "v x1 -x2", null).state)
        assertEquals(LabCheck.VALID, check("WCNF", cnf, "v 10").state)
    }

    @Test fun `malformed duplicate conflicting extra and incomplete assignments reject`() {
        val pb = "1 x1 +1 x2 >= 1;"
        for (witness in listOf("v x1", "v x1 x1 -x2", "v x1 -x1 -x2", "v x1 -x2 x3", "v x1=1 x2=0", "s SATISFIABLE")) {
            assertEquals(LabCheck.INVALID, check("OPB", pb, witness).state, witness)
        }
        val cnf = "h 1 2 0\n"
        for (witness in listOf("v 1 1 -2", "v 1 -1 -2", "v 1 -2 3", "v 1 0 -2", "v true false")) {
            assertEquals(LabCheck.INVALID, check("WCNF", cnf, witness).state, witness)
        }
    }

    @Test fun `unsupported or truncated source cannot become an accepted partial check`() {
        for (model in listOf("min: 1 x1; 1 x1 >= 1", "max: 1 x1;", "1 x1 != 0;", "[1] 1 x1 >= 1;", "soft: ; min: 1 x1;"))
            assertEquals(LabCheck.UNSUPPORTED, check("OPB", model, "v x1").state, model)
        for (model in listOf("h 1", "p wcnf 1 2 9\n1 1 0", "0 1 0", "-2 1 0", "p wcnf 1 1 9\n1 1 0\nunknown"))
            assertEquals(LabCheck.UNSUPPORTED, check("WCNF", model, "v 1").state, model)
        assertEquals(LabCheck.UNSUPPORTED, check("OPB", "* #variable= 1 #constraint= 2\n1 x1 >= 1;", "v x1").state)
    }

    @Test fun `missing costs remain not run and nonintegral claimed costs reject`() {
        val source = "soft: ; [3] 1 x1 >= 1;"
        assertEquals(LabCheck.NOT_RUN, check("OPB", source, "v -x1", null).state)
        for (cost in listOf("3.5", "NaN", "3/1")) assertEquals(LabCheck.INVALID, check("OPB", source, "v -x1", cost).state)
    }

    @Test fun `resource limits are unsupported rather than mathematical rejection`() {
        assertEquals(LabCheck.UNSUPPORTED, check("OPB", "min: ${"9".repeat(4097)} x1;", "v x1").state)
        assertEquals(LabCheck.UNSUPPORTED, check("WCNF", "p wcnf 2147483647 0 1\n", "v 1").state)
        assertEquals(LabCheck.UNSUPPORTED, check("WBO", "soft: ; [1] 1 x1 >= 1;", "v -x1", "9".repeat(4097)).state)
        assertContains(check("OPB", "1 x1000001 >= 1;", "v x1").reason, "variable index")
    }
}
