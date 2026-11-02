package com.eignex.lab.analysis

import com.eignex.lab.bench.*
import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import kotlinx.serialization.json.*
import kotlin.io.path.writeText

/** Replays retained claim/check receipts through the production guard without running a solver or reference search. */
object OptimumReplay : BenchSuite {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun describe() = BenchDescriptor("optimum-claim-guard", "Scope-bound counterexamples to retained native optimum claims")

    override fun plan(request: BenchRequest): List<BenchCase> {
        require(request.parameters.values.sumOf { it.length.toLong() } <= 120 * 1024)
        val parameters = MiniZincCheckBench.parameters(request.parameters)
        require(parameters.keys == setOf("captures")) { "Supply captures only" }
        val text = parameters.getValue("captures")
        require(text.length <= 12 * 1024 * 1024)
        val captures = Json.parseToJsonElement(text).jsonArray
        require(captures.size in 1..12)
        for (capture in captures) {
            val item = capture.jsonObject
            require(item.keys == setOf("origin", "case", "expected"))
            require(item.getValue("origin").jsonObject.getValue("job").jsonPrimitive.long > 0)
            require(item.getValue("expected").jsonPrimitive.content in setOf("point", OptimumAssessment.CONTRADICTED,
                OptimumAssessment.UNVERIFIED, OptimumAssessment.NO_COUNTEREXAMPLE))
            json.decodeFromJsonElement<CaseResult>(item.getValue("case"))
        }
        return listOf(BenchCase("retained-optimum-claims", request.parameters,
            BenchResources(cpus = 1, memoryMiB = 512, timeoutSeconds = 60)))
    }

    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        val parameters = MiniZincCheckBench.parameters(case.parameters)
        val captures = Json.parseToJsonElement(parameters.getValue("captures")).jsonArray
        val report = context.outputDirectory.resolve("report").toFile().apply { mkdirs() }.toPath()
        report.resolve("captures.json").writeText(captures.toString())
        val store = Store(context.outputDirectory.resolve("receipts.db"))
        val ids = captures.map { capture ->
            val item = capture.jsonObject
            val original = json.decodeFromJsonElement<CaseResult>(item.getValue("case"))
            val id = store.create("retained-claim", "retained", emptyList(), experiment = ExperimentSpec("retained-claim", emptyList()))
            store.plan(id, listOf(PlannedArm(Arm(original.arm, emptyMap()), "retained")), listOf(original.problem),
                Experiments.cases(1, 1, emptyList()), listOf("receipt replay; no solving" to 1L))
            store.commandStarted(id, 0)
            val record = requireNotNull(original.record as? JsonObject)
            store.caseRecord(id, 0, JsonObject(record - "labCheck" - "labOptimumClaim").toString())
            store.commandFinished(id, 0, 0)
            record["labCheck"]?.let { store.plans.caseCheck(id, 0, it.toString()) }
            id
        }
        val guarded = ids.map { OptimumClaims.qualify(store, store.plans.scoringCases(it)).single() }
        val assessments = guarded.mapIndexed { i, result -> buildJsonObject {
            put("origin", captures[i].jsonObject.getValue("origin"))
            val assessment = OptimumClaims.assessment(result.record)
            put("assessment", assessment?.let { json.encodeToJsonElement(it) } ?: JsonNull)
            put("expected", captures[i].jsonObject.getValue("expected"))
        } }
        val success = assessments.all {
            val expected = it.getValue("expected").jsonPrimitive.content
            if (expected == "point") it["assessment"] == JsonNull
            else it["assessment"]?.jsonObject?.get("state")?.jsonPrimitive?.content == expected
        }
        val labels = guarded.map { it.arm }.distinct()
        val summary = Compare.compare(labels, guarded)
        report.resolve("assessments.json").writeText(JsonArray(assessments).toString())
        report.resolve("qualification.csv").writeText(Results.casesCsv(guarded))
        report.resolve("summary.json").writeText(buildJsonObject {
            put("scope", "retained independently checked receipts; no new assignment check, reference search or independent proof")
            put("checkedPoints", summary.arms.sumOf { it.witnesses[WitnessCheck.ACCEPTED] ?: 0 })
            put("nativeProofClaims", summary.arms.sumOf { it.nativeProofClaims })
            put("contradictedOptima", summary.arms.sumOf { it.contradictedOptima })
            put("eligibleNativeProofs", summary.arms.sumOf { it.proven })
        }.toString())
        return BenchResult(case.id, status = if (success) BenchStatus.SUCCESS else BenchStatus.FAILED,
            message = "retained claim assessments ${if (success) "match" else "differ from"} the supplied expectations",
            artifacts = context.artifacts("report"), details = buildJsonObject { put("assessments", JsonArray(assessments)) })
    }
}

fun main(args: Array<String>) = BenchCli.execute(OptimumReplay, args)
