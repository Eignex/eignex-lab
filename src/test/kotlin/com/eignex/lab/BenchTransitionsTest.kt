package com.eignex.lab

import com.eignex.lab.analysis.*
import com.eignex.lab.bench.*
import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.store.*
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class BenchTransitionsTest {
    private fun case(value: Double = 100.0, job: Long = 1, repeat: Int = 0,
                     status: BenchStatus = BenchStatus.SUCCESS, id: String = "dot") : CaseResult {
        val workload = BenchCase(id, parameters = mapOf("seed" to "1"), attributes = mapOf(
            "regressionPolicy" to "metric-v1", "regressionMetric" to "median_ns", "regressionThreshold" to "0.15",
            "labInputDigest" to "a".repeat(64)))
        val result = BenchResult(id, status, metrics = if (status == BenchStatus.SUCCESS)
            listOf(BenchMetric("median_ns", value, "ns/op", MetricDirection.LOWER)) else emptyList(),
            details = buildJsonObject { put("benchmarkControls", buildJsonObject { put("java", "25"); put("cpu", "fixture") }) },
            message = if (status == BenchStatus.SUCCESS) null else "wrong numerical result")
        return CaseResult(repeat, if (status == BenchStatus.FAILED) Status.FAILED else Status.DONE,
            BenchRuns.problem(workload), if (job == 1L) Confirm.BEFORE else Confirm.AFTER, repeat = repeat,
            host = RecordedHost.Local, origin = CaseOrigin(job, "attempt-$repeat"),
            record = BenchProtocol.json.encodeToJsonElement(BenchResult.serializer(), result))
    }

    @Test fun `metric regressions and correctness failures stay separate from solver claims`() {
        val before = listOf(case())
        val slow = Confirm.flips(before, listOf(case(120.0, 2)))
        assertEquals(1, slow.metricLost.size)
        assertTrue(slow.lost.isEmpty() && slow.proofLost.isEmpty() && slow.objectiveLost.isEmpty())
        assertEquals(1, Confirm.flips(listOf(case(120.0)), listOf(case(100.0, 2))).metricGained.size)
        assertTrue(Confirm.flips(before, listOf(case(110.0, 2))).confirmable.isEmpty())
        val failure = case(job = 2, status = BenchStatus.FAILED)
        assertEquals(1, Confirm.flips(before, listOf(failure)).benchmarkFailed.size)
        assertEquals(1, Confirm.flips(listOf(case(status = BenchStatus.FAILED)), listOf(case(job = 2))).benchmarkRecovered.size)
    }

    @Test fun `confirmation needs a majority of complete same-attempt blocks`() {
        val before = (0..2).map { case(repeat = it) }
        val after = (0..2).map { case(120.0, repeat = it).copy(arm = Confirm.AFTER) }
        assertEquals(1, Confirm.confirmed(before + after).metricLost.size)
        assertTrue(Confirm.confirmed(before + after.take(1)).metricLost.isEmpty())
        assertTrue(Confirm.confirmed(before + after + after).metricLost.isEmpty())
        assertTrue(Confirm.confirmed(before + after.map { it.copy(origin = CaseOrigin(1, "replacement")) }).metricLost.isEmpty())
        assertTrue(Confirm.confirmed(before + after.map { it.copy(host = null) }).metricLost.isEmpty())
    }

    @Test fun `changed controls input bytes skipped and instrumented records cannot vote`() {
        val before = listOf(case())
        val slow = case(120.0, 2)
        val changed = slow.problem.benchCase!!.copy(parameters = mapOf("seed" to "2"))
        val controls = slow.record!!.jsonObject.toMutableMap().also { it["details"] = buildJsonObject {
            put("benchmarkControls", buildJsonObject { put("java", "26") })
        } }
        for (candidate in listOf(slow.copy(instrumented = true), slow.copy(record = JsonObject(controls)),
            slow.copy(problem = slow.problem.copy(benchCase = changed)), case(job = 2, status = BenchStatus.SKIPPED),
            slow.copy(status = Status.FAILED))) {
            assertTrue(Confirm.flips(before, listOf(candidate)).confirmable.isEmpty())
        }
    }

    @Test fun `benchmark confirmation retains every selected workload and priorities 98 and 99`() {
        val before = listOf(case(id = "dot"), case(id = "copy"))
        val after = listOf(case(120.0, 2, id = "dot"), case(120.0, 2, id = "copy"))
        val spec = ExperimentSpec("sweep", priority = 98, host = HostPlacement.LOCAL,
            nodeTags = NodeSelector(require = setOf("local")), repository = "Eignex/koblas",
            bench = BenchSpec("bench.json", runTimeoutSec = 600))
        val confirmation = Confirm.spec("sweep", spec, "a".repeat(40), "b".repeat(40),
            Confirm.flips(before, after), 2, 1, sourceCases = after)
        assertEquals(99, confirmation.priority)
        assertEquals(spec.nodeTags, confirmation.nodeTags)
        assertEquals(HostPlacement.LOCAL, confirmation.host)
        assertEquals(2, confirmation.work.bench!!.frozenCases.size)
        assertEquals(600, confirmation.work.bench!!.runTimeoutSec)
        assertEquals(mapOf("before" to 1L, "after" to 2L), confirmation.work.bench!!.replayJobs)
    }

    @Test fun `failed scheduled benchmarks reach completion and queue one retained confirmation`() {
        val config = Config(dataDir = Files.createTempDirectory("benchmark-confirmation"))
        val store = Store(config.database)
        val original = ExperimentSpec("bench-sweep", priority = 98, repository = "Eignex/koblas",
            bench = BenchSpec("bench.json", runTimeoutSec = 600))
        store.schedules.create(original.name, "main", original, 120)
        fun run(sha: String, failed: Boolean): Long {
            val source = case(status = if (failed) BenchStatus.FAILED else BenchStatus.SUCCESS)
            val spec = original.copy(name = "${original.name}@$sha", base = mapOf("ref" to sha))
            val id = store.create(spec.name, sha, emptyList(), experiment = spec)
            store.plan(id, Experiments.arms(spec).map { PlannedArm(it, sha) }, listOf(source.problem),
                Experiments.cases(1, 1, emptyList()), listOf("true" to 60L))
            store.setup(id, sha)
            store.caseRecord(id, 0, source.record.toString())
            store.commandFinished(id, 0, if (failed) 1 else 0)
            store.finish(id, if (failed) Status.FAILED else Status.DONE)
            return id
        }
        val before = run("a".repeat(40), false)
        val after = run("b".repeat(40), true)
        assertTrue(after in store.execution.pendingDomainCompletion())
        repeat(2) { Confirmations(config.local, config.paths, store).queue(store.job(after)!!) }
        val confirmation = store.active().single()
        assertEquals(99, confirmation.priority)
        assertEquals(mapOf("before" to before, "after" to after), confirmation.experiment.work.bench!!.replayJobs)
        store.execution.domainCompleted(after)
        assertFalse(after in store.execution.pendingDomainCompletion())
    }
}
