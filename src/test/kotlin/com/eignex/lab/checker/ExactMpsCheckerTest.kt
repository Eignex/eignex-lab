package com.eignex.lab.checker

import com.eignex.lab.Problem
import com.github.luben.zstd.ZstdOutputStream
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class ExactMpsCheckerTest {
    @TempDir lateinit var dir: File
    private fun source(name: String) = checkNotNull(javaClass.getResource("/checker/mps/$name.mps")).readText()
    private fun check(text: String, witness: String = "v X=1/3", objective: String = "1/3",
                      adapter: FormatChecker = ExactMpsChecker(), hash: String? = null, compressed: Boolean = false): LabCheck {
        val model = File(dir, if (compressed) "model.mps.zst" else "model.mps")
        if (compressed) ZstdOutputStream(model.outputStream()).use { it.write(text.toByteArray()) } else model.writeText(text)
        val record = buildJsonObject {
            put("solver", "klause"); put("feasible", true); put("kind", "optimize")
            put("finalWitness", witness); put("exactObjective", objective)
            put("sourceHashes", buildJsonObject { put("model", hash ?: sha256(text.toByteArray())) })
        }
        return SourceChecker(listOf(adapter), SourceLocator(dir), File(dir, "scratch"))
            .check(Problem("test", "model", format = "MPS", model = model.path), record)!!
    }
    private fun expect(state: String, result: LabCheck): LabCheck = result.also { assertEquals(state, it.state, it.reason) }

    @Test fun `captured original layouts accept exact points and reject altered points`() {
        val pure = source("pure-real-min")
        val open = source("open-real-min")
        assertEquals("3b2461c612113c6d10a6345d15459f9ac32afa1a8f396a2e0dec3f9b519e0eac", sha256(pure.toByteArray()))
        assertEquals("084ccb613364c5bf849e85cdefb0dfe9dabae3e00b88e1b70852eccf162b4f29", sha256(open.toByteArray()))
        val valid = expect(LabCheck.VALID, check(pure))
        assertEquals(Policies.MPS_EXACT, valid.policy)
        assertContains(valid.checkerVersion!!, "source sha256 ")
        assertFalse(valid.checkerVersion!!.endsWith("null)"))
        assertContains(valid.diagnostic!!, "zero tolerance")
        expect(LabCheck.INVALID, check(pure, "v X=0.3333333333333333333333333333333333"))
        expect(LabCheck.INVALID, check(pure, objective = "3333333333333333333333333333333334/10000000000000000000000000000000000"))
        expect(LabCheck.VALID, check(open, "v I=3 X=3", "3/2"))
        expect(LabCheck.INVALID, check(open, "v I=3 X=2", "1"))
        expect(LabCheck.INVALID, check(open, "v I=2 X=3", "3/2"))
        expect(LabCheck.INVALID, check(open, "v I=7/2 X=7/2", "7/4"))
        expect(LabCheck.VALID, check(pure, compressed = true))
        assertEquals(emptyList(), File(dir, "scratch").listFiles()!!.toList())
    }

    @Test fun `objective constant and sense retain original units including fixed-column layout`() {
        val text = """NAME          OFFSET
OBJSENSE
 MAX
ROWS
 N  COST
 E  EQ
COLUMNS
    X         COST      2             EQ        3
RHS
    RHS       COST      -1            EQ        1
BOUNDS
 FR B X
ENDATA
"""
        val valid = expect(LabCheck.VALID, check(text, objective = "5/3"))
        assertContains(valid.reason, "MAX objective 5/3")
        expect(LabCheck.INVALID, check(text, objective = "2/3"))
        expect(LabCheck.INVALID, check(text, objective = "-1/3"))
        expect(LabCheck.INVALID, check(text, objective = "-5/3"))
        expect(LabCheck.INVALID, check(text.replace("COST      -1", "COST      1"), objective = "5/3"))
        expect(LabCheck.VALID, check(text.replace(" MAX", " MIN"), objective = "5/3"))
    }

    @Test fun `row senses ranges duplicate terms decimals and integer defaults are checked`() {
        val base = """NAME T
ROWS
 N OBJ
 E EQ
 G GE
 L LE
COLUMNS
 X OBJ 1D-1 EQ .5
 X EQ 0.5 GE 1
 X LE 1
RHS
 R EQ 1 GE 0
 R LE 2
RANGES
 R EQ 1 GE -2
 R LE 2
BOUNDS
 LO B X 0
 UP B X 2
ENDATA
"""
        expect(LabCheck.VALID, check(base, "v X=3/2", "0.15"))
        expect(LabCheck.INVALID, check(base, "v X=1/2", "0.05"))
        expect(LabCheck.INVALID, check(base, "v X=5/2", "0.25"))
        val negativeRange = base.replace("EQ 1 GE -2", "EQ -1 GE -2")
        expect(LabCheck.VALID, check(negativeRange, "v X=1/2", "0.05"))
        expect(LabCheck.INVALID, check(negativeRange, "v X=3/2", "0.15"))
        val integer = """NAME T
ROWS
 N OBJ
COLUMNS
 M0 'MARKER' 'INTORG'
 X OBJ 1
 M1 'MARKER' 'INTEND'
ENDATA
"""
        expect(LabCheck.VALID, check(integer, "v X=1", "1"))
        expect(LabCheck.INVALID, check(integer, "v X=2", "2"))
        expect(LabCheck.INVALID, check(integer, "v X=1/2", "1/2"))
        for ((bound, valid, bad) in listOf(Triple("FX B X 2", "2", "1"), Triple("BV B X", "1", "2"),
            Triple("MI B X", "-2", "1/2"), Triple("UI B X 3", "3", "4"), Triple("LI B X 3", "4", "2"))) {
            val m = integer.replace("ENDATA", "BOUNDS\n $bound\nENDATA")
            expect(LabCheck.VALID, check(m, "v X=$valid", valid))
            expect(LabCheck.INVALID, check(m, "v X=$bad", bad))
        }
    }

    @Test fun `parser unsupported timeout incomplete and invalid stay distinct`() {
        val pure = source("pure-real-min")
        assertContains(expect(LabCheck.UNKNOWN, check(pure.replace("X UPPER 3", "X MISSING 3"))).reason, "parser failure")
        assertContains(expect(LabCheck.UNSUPPORTED, check(pure.replace("FR B X", "SC B X 2"))).reason, "bound type SC")
        assertContains(expect(LabCheck.UNKNOWN, check(pure, "v")).reason, "incomplete witness")
        expect(LabCheck.UNKNOWN, check(pure, "v X=1/3 X=1/3"))
        expect(LabCheck.INVALID, check(pure, "v Y=1/3"))
        expect(LabCheck.UNKNOWN, check(pure, "v X=1/0"))
        expect(LabCheck.UNKNOWN, check(pure.replace("ROWS", "OBJSENSE\nROWS")))
        expect(LabCheck.UNKNOWN, check(pure.replace("ROWS", "OBJSENSE\n MIN\n MAX\nROWS")))
        expect(LabCheck.UNSUPPORTED, check(pure.replace("X COST 1", "X COST 1e4095"), "v X=1e4095", "1/3"))
        expect(LabCheck.UNKNOWN, check(pure.removeSuffix("ENDATA\n")))
        expect(LabCheck.UNSUPPORTED, check(pure, "v X=1e999999999"))
        expect(LabCheck.UNSUPPORTED, check(pure, "v X=${"1".repeat(4096)}"))
        expect(LabCheck.UNSUPPORTED, check(pure.replace("NAME T", "NAME ${"x".repeat(8191)}")))
        val multiple = pure.replace("RHS LOWER 1 UPPER 2", "RHS LOWER 1\n OTHER UPPER 2")
        expect(LabCheck.UNSUPPORTED, check(multiple))
        var clock = 0L
        assertContains(expect(LabCheck.NOT_RUN, check(pure, adapter = ExactMpsChecker(1) { clock += 1_000_001; clock })).reason, "timeout")
        expect(LabCheck.NOT_RUN, check(pure, hash = "0".repeat(64)))
        Thread.currentThread().interrupt()
        try { assertFailsWith<InterruptedException> { check(pure) } }
        finally { Thread.interrupted() }
    }

    @Test fun `router preserves legacy identity and sends fractions and rejected layouts to exact route`() {
        var legacyCalls = 0
        val legacy = object : FormatChecker {
            override val format = "MPS"
            override val policy = "test-legacy"
            override val checker = "legacy checker"
            override fun check(case: CheckCase, work: File): Verdict {
                legacyCalls++
                return Verdict(if ("RHS" in case.sources.model.readText()) LabCheck.VALID else LabCheck.UNSUPPORTED, "test layout")
            }
        }
        val router = RationalMpsChecker(legacy)
        expect(LabCheck.VALID, check(source("pure-real-min"), adapter = router))
        assertEquals(0, legacyCalls)
        expect(LabCheck.VALID, check(source("open-real-min"), "v I=3 X=3", "1.5", router))
        assertEquals(1, legacyCalls)
        val retained = expect(LabCheck.VALID, check(source("pure-real-min"), "v X=0.5", "0.5", router))
        assertEquals("test-legacy", retained.policy)
        assertEquals("legacy checker", retained.checker)
    }
}
