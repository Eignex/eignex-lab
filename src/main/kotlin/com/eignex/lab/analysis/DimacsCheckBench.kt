package com.eignex.lab.analysis

import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*


import com.eignex.lab.bench.*
import java.io.File
import kotlinx.serialization.json.*
import com.eignex.lab.execution.BenchSpec
import com.eignex.lab.execution.CaseChecks
import com.eignex.lab.execution.Checkers
import com.eignex.lab.execution.RecheckResult

/** Frozen original records through the ordinary runner's CaseChecks queue and its ended-case recheck path. */
object DimacsCheckBench : BenchSuite {
    override fun describe() = BenchDescriptor("dimacs-source-point", "Independent original DIMACS SAT point checking and recheck")

    override fun plan(request: BenchRequest): List<BenchCase> {
        require(request.parameters.values.sumOf { it.length.toLong() } <= 120 * 1024) { "Use bounded capture-zstd-base64 transport for larger captures" }
        val parameters = MiniZincCheckBench.parameters(request.parameters)
        require(parameters.keys == setOf("captures")) { "Supply captures only" }
        val text = parameters.getValue("captures")
        require(text.length <= 12 * 1024 * 1024) { "Capture list exceeds 12 MiB" }
        val captures = Json.parseToJsonElement(text).jsonArray
        require(captures.size in 1..12) { "Supply 1..12 explicit captures" }
        return captures.mapIndexed { index, capture ->
            val item = capture.jsonObject
            require(item.keys == setOf("model", "record", "control", "expected", "origin"))
            require(item.getValue("model") is JsonPrimitive && item.getValue("record") is JsonObject)
            require(item.getValue("control").jsonObject.keys.all { it == "finalWitness" })
            require(item.getValue("expected").jsonPrimitive.content in LabCheck.STATES)
            BenchCase("dimacs-point-$index", mapOf("capture" to item.toString()),
                BenchResources(cpus = 1, memoryMiB = 1024, timeoutSeconds = 60,
                    requiredTags = setOf("platform:linux", "arch:x86_64")))
        }
    }

    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        val item = Json.parseToJsonElement(case.parameters.getValue("capture")).jsonObject
        val report = context.outputDirectory.resolve("report").toFile().apply { mkdirs() }
        val model = File(report, "original.cnf").apply { writeText(item.getValue("model").jsonPrimitive.content) }
        val original = item.getValue("record").jsonObject
        val effective = JsonObject(original + item.getValue("control").jsonObject)
        File(report, "original-record.json").writeText(original.toString())
        File(report, "effective-record.json").writeText(effective.toString())
        File(report, "origin.json").writeText(item.getValue("origin").toString())
        val state = context.outputDirectory.resolve("state").toFile().apply { mkdirs() }
        val config = Config(dataDir = state.toPath())
        val store = Store(File(state, "flow.db").toPath())
        val spec = ExperimentSpec("dimacs-capture", bench = BenchSpec("checker/dimacs.json", sourceValidation = true))
        val id = store.create(spec.name, "capture", emptyList(), experiment = spec)
        val problem = Problem("capture", "original.cnf", format = "DIMACS", model = model.absolutePath)
        store.plan(id, listOf(PlannedArm(Arm("capture", emptyMap()), "capture")), listOf(problem),
            Experiments.cases(1, 1, emptyList()), listOf("retained record; no solver" to 60L))
        store.commandStarted(id, 0)
        store.caseRecord(id, 0, effective.toString())
        store.commandFinished(id, 0, 0)
        val before = store.plans.caseRecordText(id, 0)
        val checks = CaseChecks(store, { Checkers.of(config.paths) }) { _, line -> System.err.println(line) }
        fun receipt(): LabCheck? = store.plans.cases(id).single().record?.jsonObject?.get("labCheck")?.let { LabCheck.parse(it.toString()) }
        checks.afterCase(id, 0)
        checks.await(id)
        val ordinary = requireNotNull(receipt()) { "ordinary runner produced no SAT point check" }
        File(report, "ordinary-check.json").writeText(ordinary.json())
        val recheck = checks.recheck(id) { false }
        File(report, "recheck.json").writeText(Json.encodeToString(RecheckResult.serializer(), recheck))
        val repeated = requireNotNull(receipt())
        File(report, "recheck-check.json").writeText(repeated.json())
        val expected = item.getValue("expected").jsonPrimitive.content
        val accepted = listOf(ordinary, repeated).all { it.state == expected && it.policy == DimacsChecker().policy } &&
            ordinary.sourceSha256 == repeated.sourceSha256 && ordinary.witnessSha256 == repeated.witnessSha256 &&
            recheck.checked == 1 && store.plans.caseRecordText(id, 0) == before
        return BenchResult(case.id, status = if (accepted) BenchStatus.SUCCESS else BenchStatus.FAILED,
            artifacts = context.artifacts("report"), message = "ordinary=${ordinary.state}; recheck=${repeated.state}; expected=$expected",
            details = buildJsonObject {
                put("scope", "SAT point only; no UNSAT, optimum or performance claim")
                put("expected", expected); put("origin", item.getValue("origin"))
                put("ordinary", Json.parseToJsonElement(ordinary.json())); put("recheck", Json.parseToJsonElement(repeated.json()))
                put("javaVersion", System.getProperty("java.runtime.version")); put("os", System.getProperty("os.name"))
                put("arch", System.getProperty("os.arch")); put("cpus", case.resources.cpus); put("memoryMiB", case.resources.memoryMiB)
            })
    }
}

fun main(args: Array<String>) = BenchCli.execute(DimacsCheckBench, args)
