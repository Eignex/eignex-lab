package com.eignex.lab.checker

import com.eignex.lab.reference.sha256
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class MpsPolicyChecksTest {
    @TempDir lateinit var dir: File
    private fun declaration(absolute: String = "0.0000001", relative: String = "1/10000000",
                            realLower: String? = "0", realUpper: String? = null) = buildJsonObject {
        val policy = buildJsonObject {
            put("numericAuthority", "DECIMAL_LITERALS")
            put("feasibilityTolerance", buildJsonObject { put("absolute", absolute); put("relative", relative) })
            put("undeclaredIntegers", buildJsonObject { put("lower", "0"); put("upper", "1") })
            put("undeclaredReals", buildJsonObject {
                put("lower", realLower?.let(::JsonPrimitive) ?: JsonNull)
                put("upper", realUpper?.let(::JsonPrimitive) ?: JsonNull)
            })
            put("integrality", "exact"); put("objective", "exact")
        }
        put("schema", MpsNumericalSemantics.SCHEMA); put("declared", policy); put("effective", policy)
    }
    private fun check(source: String, witness: String, objective: String, semantics: JsonObject? = declaration()): Verdict {
        val model = File(dir, "source.mps").apply { writeText(source) }
        val record = buildJsonObject {
            put("exactObjective", objective)
            put("sourceHashes", buildJsonObject { put("model", sha256(source.toByteArray())) })
            if (semantics != null) put("numericalSemantics", semantics)
        }
        return RationalMpsChecker().check(CheckCase(record, SourceFiles(model), witness), dir)
    }
    private val equality = "NAME T\nROWS\n N COST\n E R\nCOLUMNS\n X COST 1 R 1\nRHS\n RHS R 1\nENDATA\n"

    @Test fun `equivalent witness spellings receive identical exact and configured verdicts`() {
        val results = listOf("100000005/100000000", "1.00000005", "100000005e-8").map {
            check(equality, "v X=$it", it).mpsChecks!!
        }
        for (result in results) {
            assertEquals(LabCheck.INVALID, result.exact.state)
            assertEquals(LabCheck.VALID, result.configured.state)
            assertEquals("20000001/20000000", result.reconstructedObjective)
            assertEquals("MIN", result.objectiveSense)
            assertEquals(emptyList(), result.assumptions)
        }
        assertEquals(1, results.map { it.comparisonIdentity }.distinct().size)
        assertNotNull(results.first().comparisonIdentity)
        assertEquals(3, results.map { it.witnessSha256 }.distinct().size)
    }

    @Test fun `row tolerance uses sum of absolute term activities before cancellation`() {
        val source = "NAME T\nROWS\n N COST\n E R\nCOLUMNS\n X COST 0 R 1\n Y R -1\nRHS\n RHS R 0\nENDATA\n"
        assertEquals(LabCheck.VALID, check(source, "v X=100000000 Y=99999999", "0").state)
        assertEquals(LabCheck.INVALID, check(source, "v X=100000000 Y=99999900", "0").state)
        for (bound in listOf("100000000", "-100000000")) {
            val signed = equality.replace("RHS R 1", "RHS R $bound").replace("ENDATA", "BOUNDS\n FR B X\nENDATA")
            val value = if (bound.startsWith('-')) "-99999999" else "100000001"
            assertEquals(LabCheck.VALID, check(signed, "v X=$value", value).state)
        }
    }

    @Test fun `bounds integrality and objective are independently enforced without rounding`() {
        val bound = "NAME T\nROWS\n N COST\nCOLUMNS\n X COST 1\nBOUNDS\n UP B X 1\nENDATA\n"
        assertEquals(LabCheck.VALID, check(bound, "v X=1.00000005", "1.00000005").state)
        assertEquals(LabCheck.INVALID, check(bound, "v X=1.0001", "1.0001").state)
        assertEquals(LabCheck.INVALID, check(bound, "v X=1", "100000001/100000000").state)
        val integer = bound.replace(" UP B X 1", " BV B X")
        assertEquals(LabCheck.INVALID, check(integer, "v X=100000001/100000000", "100000001/100000000").state)
        val decimal = equality.replace("R 1\nRHS", "R 3.0000000000000001\nRHS")
        assertEquals(LabCheck.VALID, check(decimal, "v X=10000000000000000/30000000000000001",
            "10000000000000000/30000000000000001", declaration("0", "0")).state)
        assertEquals(LabCheck.INVALID, check(decimal, "v X=1/3", "1/3", declaration("0", "0")).state)
    }

    @Test fun `effective defaults apply only to declarations without explicit bounds`() {
        val source = "NAME T\nROWS\n N COST\nCOLUMNS\n X COST 1\nENDATA\n"
        val policy = declaration("0", "0", "2", "3")
        assertEquals(LabCheck.VALID, check(source, "v X=2", "2", policy).state)
        assertEquals(LabCheck.INVALID, check(source, "v X=1", "1", policy).state)
        val explicit = source.replace("ENDATA", "BOUNDS\n LO B X 0\nENDATA")
        assertEquals(LabCheck.VALID, check(explicit, "v X=1", "1", policy).state)
    }

    @Test fun `undeclared malformed and unsupported policies cannot qualify comparisons`() {
        val policies = listOf(null, buildJsonObject {}, declaration("-1"), declaration().let {
            JsonObject(it + ("effective" to JsonObject(it.getValue("effective").jsonObject +
                ("integrality" to JsonPrimitive("approximate")))))
        })
        for (policy in policies) {
            val result = check(equality, "v X=1", "1", policy)
            assertEquals(LabCheck.UNKNOWN, result.state)
            assertEquals(LabCheck.VALID, result.mpsChecks!!.exact.state)
            assertNull(result.mpsChecks!!.comparisonIdentity)
            assertNull(MpsNumericalSemantics.identity(policy))
        }
        assertEquals(MpsNumericalSemantics.identity(declaration()),
            MpsNumericalSemantics.identity(declaration("1/10000000", "0.0000001")))
        assertNotEquals(MpsNumericalSemantics.identity(declaration()), MpsNumericalSemantics.identity(declaration("0", "0")))
    }
}
