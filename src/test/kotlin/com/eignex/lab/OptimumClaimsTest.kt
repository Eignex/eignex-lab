package com.eignex.lab

import com.eignex.lab.analysis.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.reference.sha256
import com.eignex.lab.store.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OptimumClaimsTest {
    private val source = "a".repeat(64)
    private val witness = "v x=1"
    private val problem = Problem("fixture", "point", collection = "fixture", format = "XCSP3")

    private fun point(value: String, proven: Boolean = false, maximize: Boolean = false,
                      state: String = LabCheck.VALID, source: String = this.source): CaseResult {
        val record = buildJsonObject {
            put("solver", "klause"); put("kind", "optimize"); put("feasible", true)
            put("maximize", maximize); put("proven", proven); put("objective", value.toDoubleOrNull() ?: 0.0)
            put("exactObjective", value); put("budgetMs", 1000); put("timeToBestMs", 1)
            put("sourceHashes", buildJsonObject { put("model", source) })
            put("comparisonControls", buildJsonObject { put("exact", "true") })
            put("finalWitness", witness)
            put("labCheck", buildJsonObject {
                put("state", state); put("policy", Policies.XCSP3)
                put("checkerSha256", "b".repeat(64)); put("witnessSha256", sha256(witness.toByteArray()))
                put("sourceSha256", buildJsonObject { put("model", source) })
            })
        }
        return CaseResult(if (proven) 0 else 1, Status.DONE, problem, if (proven) "claim" else "point", seed = 1,
            record = record)
    }

    private fun edit(case: CaseResult, change: MutableMap<String, JsonElement>.() -> Unit): CaseResult =
        case.copy(record = JsonObject(case.record!!.jsonObject.toMutableMap().apply(change)))

    private fun assessed(claim: CaseResult, vararg points: CaseResult) =
        OptimumClaims.qualify(listOf(claim), points.toList()).single().let { requireNotNull(OptimumClaims.assessment(it.record)) }

    @Test
    fun `checked better points contradict either objective sense without withdrawing point validity`() {
        for (maximize in listOf(false, true)) {
            val claim = point("7", proven = true, maximize = maximize)
            val better = point(if (maximize) "8" else "6", maximize = maximize)
            val guarded = OptimumClaims.qualify(listOf(claim, better))
            val outcome = requireNotNull(Outcome.of(guarded.first().record))

            assertEquals(OptimumAssessment.CONTRADICTED, assessed(claim, better).state)
            assertEquals(true, outcome.feasible)
            assertEquals(SourceVerdict.VALID, outcome.sourceValidation)
            assertTrue(outcome.nativeProven)
            assertFalse(outcome.proven)
            assertEquals(true, claim.record!!.jsonObject["proven"]!!.jsonPrimitive.boolean)
        }
    }

    @Test
    fun `a cost equality or lack of a counterexample never supplies independent proof`() {
        val result = assessed(point("7", proven = true), point("7"), point("8"))
        assertEquals(OptimumAssessment.NO_COUNTEREXAMPLE, result.state)
        assertTrue("independently unverified" in result.reason)
    }

    @Test
    fun `a fresh assessment consumes newly supplied evidence and ignores a stale embedded assessment`() {
        val initial = OptimumClaims.qualify(listOf(point("7", proven = true))).single()
        val refreshed = OptimumClaims.qualify(listOf(initial), listOf(point("6"))).single()
        assertEquals(OptimumAssessment.CONTRADICTED, OptimumClaims.assessment(refreshed.record)?.state)
        val changedSource = edit(refreshed) { remove("sourceHashes") }
        assertEquals(OptimumAssessment.UNVERIFIED,
            OptimumClaims.assessment(OptimumClaims.qualify(listOf(changedSource)).single().record)?.state)
    }

    @Test
    fun `grid completion does not acquire original source optimum scope`() {
        val grid = edit(point("7", proven = true)) { put("stats", buildJsonObject { put("floatApproximation", "true") }) }
        assertEquals(OptimumAssessment.UNVERIFIED, assessed(grid, point("6")).state)
    }

    @Test
    fun `missing and indeterminate checks stay unverified when no checked scope is available`() {
        for (state in listOf(LabCheck.UNKNOWN, LabCheck.NOT_RUN, LabCheck.UNSUPPORTED, LabCheck.INVALID)) {
            val claim = point("7", proven = true, state = state)
            assertEquals(OptimumAssessment.UNVERIFIED, assessed(claim, point("6", state = state)).state)
        }
        val claim = edit(point("7", proven = true)) { remove("labCheck") }
        assertEquals(OptimumAssessment.UNVERIFIED, assessed(claim).state)
    }

    @Test
    fun `source and dependency mismatches cannot contradict a native optimum`() {
        val claim = point("7", proven = true)
        val other = point("6", source = "c".repeat(64))
        assertEquals(OptimumAssessment.NO_COUNTEREXAMPLE, assessed(claim, other).state)
        val dependency = edit(point("6")) {
            put("sourceHashes", buildJsonObject { put("model", source); put("data", "d".repeat(64)) })
        }
        assertEquals(OptimumAssessment.NO_COUNTEREXAMPLE, assessed(claim, dependency).state)
        assertEquals(OptimumAssessment.UNVERIFIED, assessed(edit(claim) { remove("sourceHashes") }, point("6")).state)
        assertTrue(Compare.compare(listOf("claim"), listOf(claim, other.copy(record = JsonObject(other.record!!.jsonObject +
            ("proven" to JsonPrimitive(true)))))).disagreements.isEmpty())
    }

    @Test
    fun `changed witnesses source receipts and checker identities cannot establish contradictions`() {
        val claim = point("7", proven = true)
        val better = point("6")
        val changedWitness = edit(better) { put("finalWitness", JsonPrimitive("v x=2")) }
        val changedSource = edit(better) {
            put("labCheck", JsonObject(getValue("labCheck").jsonObject + ("sourceSha256" to buildJsonObject { put("model", "c".repeat(64)) })))
        }
        val missingBinary = edit(better) { put("labCheck", JsonObject(getValue("labCheck").jsonObject - "checkerSha256")) }
        for (bad in listOf(changedWitness, changedSource, missingBinary, better.copy(status = Status.FAILED)))
            assertEquals(OptimumAssessment.NO_COUNTEREXAMPLE, assessed(claim, bad).state)
        val missingWitness = edit(better) { remove("finalWitness") }
        assertEquals(OptimumAssessment.NO_COUNTEREXAMPLE, assessed(claim, missingWitness).state)
    }

    private fun scoped(case: CaseResult, assumptions: List<String> = emptyList(), units: String = "original-source",
                       scale: String = "1", offset: String = "0", original: String? = null): CaseResult = edit(case) {
        val scope = buildJsonObject {
            put("schema", "original-source-optimum-v1"); put("semanticsSha256", "e".repeat(64))
            put("objective", buildJsonObject { put("sense", "min"); put("units", units); put("identity", "source-objective") })
            put("assumptions", JsonArray(assumptions.map(::JsonPrimitive)))
        }
        put("optimalityScope", scope)
        put("objectiveMapping", buildJsonObject { put("scale", scale); put("offset", offset) })
        val check = getValue("labCheck").jsonObject
        put("labCheck", JsonObject(check + ("optimumEvidence" to buildJsonObject {
            put("scope", scope); put("objective", original ?: getValue("exactObjective").jsonPrimitive.content)
        })))
    }

    @Test
    fun `affine objective maps compare original costs exactly across sign and unit changes`() {
        val claim = scoped(point("-9", proven = true, maximize = true), scale = "-2", offset = "1", original = "19")
        val better = scoped(point("18"))
        assertEquals(OptimumAssessment.CONTRADICTED, assessed(claim, better).state)
        assertEquals("19", assessed(claim, better).objective)
        assertEquals("18", assessed(claim, better).counterexampleObjective)
        assertEquals(OptimumAssessment.NO_COUNTEREXAMPLE, assessed(claim, scoped(point("18"), units = "cents")).state)
    }

    @Test
    fun `a witness must be checked under all claim assumptions`() {
        val claim = scoped(point("7", proven = true), assumptions = listOf("x=1"))
        val global = scoped(point("6"))
        val same = scoped(point("6"), assumptions = listOf("x=1"))
        assertEquals(OptimumAssessment.NO_COUNTEREXAMPLE, assessed(claim, global).state)
        assertEquals(OptimumAssessment.CONTRADICTED, assessed(claim, same).state)
        val uncheckedScope = edit(same) { put("labCheck", JsonObject(getValue("labCheck").jsonObject - "optimumEvidence")) }
        assertEquals(OptimumAssessment.NO_COUNTEREXAMPLE, assessed(claim, uncheckedScope).state)
    }

    @Test
    fun `effective semantics mismatches leave native claims uncontradicted`() {
        val claim = scoped(point("7", proven = true))
        val changed = edit(scoped(point("6"))) {
            put("optimalityScope", JsonObject(getValue("optimalityScope").jsonObject + ("semanticsSha256" to JsonPrimitive("f".repeat(64)))))
        }
        assertEquals(OptimumAssessment.NO_COUNTEREXAMPLE, assessed(claim, changed).state)
    }

    @Test
    fun `MPS counterexamples consume declared policy receipts rather than strict verdicts`() {
        val policy = buildJsonObject {
            put("numericAuthority", "DECIMAL_LITERALS")
            put("feasibilityTolerance", buildJsonObject { put("absolute", "1/10000000"); put("relative", "1/10000000") })
            put("undeclaredIntegers", buildJsonObject { put("lower", "0"); put("upper", "1") })
            put("undeclaredReals", buildJsonObject { put("lower", "0"); put("upper", JsonNull) })
            put("integrality", "exact"); put("objective", "exact")
        }
        val semantics = buildJsonObject {
            put("schema", "mps-decimal-semantics-v1"); put("declared", policy); put("effective", policy)
        }
        fun mps(value: String, proven: Boolean = false): CaseResult = edit(point(value, proven)) {
            put("numericalSemantics", semantics)
            val check = getValue("labCheck").jsonObject
            put("labCheck", JsonObject(check + ("policy" to JsonPrimitive(Policies.MPS_DECLARED)) + ("mpsChecks" to buildJsonObject {
                put("exact", buildJsonObject { put("state", LabCheck.INVALID) })
                put("configured", buildJsonObject { put("state", LabCheck.VALID); put("policy", Policies.MPS_DECLARED) })
                put("semanticsSha256", MpsNumericalSemantics.identity(semantics))
                put("semanticsQualified", true); put("declarationOrigin", "solver-record")
                put("checkerPolicySha256", MpsNumericalSemantics.checkerPolicyIdentity(semantics))
                put("comparisonIdentity", MpsNumericalSemantics.comparisonIdentity(semantics, source, "MIN"))
                put("sourceSha256", source); put("witnessSha256", sha256(witness.toByteArray()))
                put("claimedObjective", value); put("reconstructedObjective", value)
                put("objectiveSense", "MIN"); put("objectiveUnits", "original-mps"); put("scope", "original-model")
                put("assumptions", JsonArray(emptyList()))
            })))
        }.copy(problem = problem.copy(format = "MPS"))
        val claim = mps("7", proven = true)
        val better = mps("6")
        assertEquals(OptimumAssessment.CONTRADICTED, assessed(claim, better).state)
        val undeclared = edit(claim) { remove("numericalSemantics") }
        assertEquals(OptimumAssessment.UNVERIFIED, assessed(undeclared, better).state)
        val unsupported = edit(better) {
            val check = getValue("labCheck").jsonObject
            val receipt = check.getValue("mpsChecks").jsonObject
            put("labCheck", JsonObject(check + ("mpsChecks" to JsonObject(receipt + ("configured" to buildJsonObject {
                put("state", LabCheck.UNKNOWN); put("policy", Policies.MPS_DECLARED)
            })))))
        }
        assertEquals(OptimumAssessment.NO_COUNTEREXAMPLE, assessed(claim, unsupported).state)
    }

    @Test
    fun `malformed exact objectives cannot fall back to display doubles`() {
        val claim = point("7", proven = true)
        for (value in listOf("1/0", "NaN", "1e9999999")) {
            assertEquals(OptimumAssessment.NO_COUNTEREXAMPLE, assessed(claim, point(value)).state)
            assertEquals(OptimumAssessment.UNVERIFIED, assessed(point(value, proven = true), point("6")).state)
        }
    }

    @Test
    fun `rational objectives distinguish values beyond floating precision`() {
        val claim = scoped(point("9007199254740993", proven = true))
        assertEquals(OptimumAssessment.CONTRADICTED, assessed(claim, scoped(point("9007199254740992"))).state)
        assertEquals(OptimumAssessment.CONTRADICTED, assessed(scoped(point("2/5", proven = true)), scoped(point("1/3"))).state)
        assertEquals(OptimumAssessment.NO_COUNTEREXAMPLE, assessed(scoped(point("1/3", proven = true)), scoped(point("2/6"))).state)
    }

    @Test
    fun `summaries retain native claims checked points and contradicted claims separately`() {
        val claim = point("45650", proven = true)
        val better = point("43000").copy(seed = 3)
        val compared = Compare.compare(listOf("claim", "point"), listOf(claim, better))
        val summary = compared.arms.first()
        assertEquals(1, summary.nativeProofClaims)
        assertEquals(1, summary.contradictedOptima)
        assertEquals(1, summary.witnesses[WitnessCheck.ACCEPTED])
        assertEquals(0, summary.proven)
        assertTrue(compared.disagreements.any { "contradicted" in it.reason })
        val baseline = point("45650").copy(arm = Confirm.BEFORE)
        val guardedClaim = OptimumClaims.qualify(listOf(claim.copy(arm = Confirm.AFTER)), listOf(better)).single()
        assertTrue(Confirm.confirmed(listOf(baseline, guardedClaim)).proofGained.isEmpty())
    }

    @Test
    fun `unverified native claims cannot manufacture proof performance gains`() {
        fun fixture(case: CaseResult) = edit(scoped(case)) { put("solver", JsonPrimitive("fixture")); remove("comparisonControls") }
        val before = fixture(point("7", proven = true, state = LabCheck.UNKNOWN)).copy(arm = Confirm.BEFORE)
        val after = fixture(point("7", proven = true)).copy(arm = Confirm.AFTER)
        val guarded = OptimumClaims.qualify(listOf(before, after))
        val unavailable = OptimumClaims.qualify(listOf(before)).single()
        val known = guarded.last()
        assertEquals(OptimumAssessment.UNVERIFIED, OptimumClaims.assessment(unavailable.record)?.state)
        val x = requireNotNull(Outcome.of(unavailable.record)); val y = requireNotNull(Outcome.of(known.record))
        assertEquals(0.5, Compare.points(x, y))
        assertEquals(0.5, Compare.points(y, x))
        assertTrue(Confirm.flips(listOf(unavailable), listOf(known)).proofGained.isEmpty())
        val ordinary = fixture(point("7")).copy(arm = Confirm.BEFORE)
        assertEquals(1, Confirm.flips(listOf(ordinary), listOf(known)).proofGained.size)
    }

    @Test
    fun `retained evidence from another job automatically withdraws proof credit`() {
        val store = Store(Files.createTempDirectory("optimum-claims").resolve("lab.db"))
        fun retain(case: CaseResult): Long {
            val id = store.create("point", "fixture", emptyList(), experiment = ExperimentSpec("point", emptyList()))
            store.plan(id, listOf(PlannedArm(Arm(case.arm, emptyMap()), "fixture")), listOf(case.problem),
                Experiments.cases(1, 1, emptyList()), listOf("retained" to 1L))
            store.commandStarted(id, 0)
            val record = case.record!!.jsonObject
            store.caseRecord(id, 0, JsonObject(record - "labCheck").toString())
            store.commandFinished(id, 0, 0)
            store.plans.caseCheck(id, 0, record.getValue("labCheck").toString())
            return id
        }
        val claim = retain(point("45650", proven = true))
        val evidence = retain(point("43000").copy(seed = 3))
        val guarded = OptimumClaims.qualify(store, store.plans.scoringCases(claim)).single()
        val result = requireNotNull(OptimumClaims.assessment(guarded.record))
        assertEquals(OptimumAssessment.CONTRADICTED, result.state)
        assertEquals(evidence, result.evidenceJob)
        assertEquals(1, Compare.compare(listOf("claim"), listOf(guarded)).arms.single().contradictedOptima)
        assertEquals(true, store.plans.cases(claim).single().record!!.jsonObject["proven"]!!.jsonPrimitive.boolean)
    }
}
