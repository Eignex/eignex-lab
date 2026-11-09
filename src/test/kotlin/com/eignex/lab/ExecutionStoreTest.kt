package com.eignex.lab

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

import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.*

class ExecutionStoreTest {
    @Test fun `journal pressure keeps a waiting worker's session without draining it`() {
        val id = job(problems = 3); bind(id)
        val before = store.job(id)!!
        assertFailsWith<SolveAdmissionHeld> {
            store.execution.claim("s", token, "alloc-s", 1001, allowNewSolve = { false }, waitWhenHeld = true)
        }
        assertEquals(Desired.RUN, store.execution.session("s")!!.desired)
        assertEquals(0, store.execution.events(id).count { it.detail == "held:journal-pressure" })
        assertTrue(store.job(id)!!.commands.all { it.status == Status.QUEUED && it.host == null })
        // Once the journal drains, the same session claims normally.
        val claim = assertNotNull(store.execution.claim("s", token, "alloc-s", 1002, waitWhenHeld = true))
        assertTrue(claim.indices.isNotEmpty())
        assertEquals(before.priority, store.job(id)!!.priority)
        assertEquals(0, store.execution.failureCount(id))
    }

    @Test fun `journal pressure replays owned paired rows before declining new claims`() {
        val id = job(problems = 3); bind(id)
        val before = store.job(id)!!
        val owned = store.execution.claim("s", token, "alloc-s", 1001)!!
        assertEquals(owned, Store(file).execution.claim("s", token, "alloc-s", 1002,
            allowNewSolve = { error("owned claim replay must precede the new-admission gate") }))
        assertEquals(Desired.RUN, store.execution.session("s")!!.desired)
        store.execution.renew(owned.owner, 1003)
        store.execution.phase(owned.owner, "executing", 1004)
        store.execution.phase(owned.owner, "uploading", 1005)
        assertTrue(store.execution.commit(owned.owner, result(owned), 1006))
        assertTrue(store.execution.commit(owned.owner, result(owned), 1007))
        assertNull(store.execution.claim("s", token, "alloc-s", 1008, allowNewSolve = { false }))
        assertEquals(Desired.DRAIN, store.execution.session("s")!!.desired)
        repeat(4) { assertNull(store.execution.claim("s", token, "alloc-s", 1009, allowNewSolve = { false })) }
        assertEquals(1, store.execution.events(id).count { it.detail == "held:journal-pressure" })
        assertEquals(1, store.execution.accepted(id).size)
        val after = store.job(id)!!
        assertEquals(before.experiment, after.experiment); assertEquals(before.priority, after.priority)
        assertEquals(before.paused, after.paused); assertEquals(before.runMs, after.runMs)
        assertTrue(after.commands.filter { it.index !in owned.indices }.all { it.status == Status.QUEUED && it.host == null })
        assertEquals(0, store.execution.failureCount(id))
    }

    @Test fun `simultaneous ordinary and pressure claims either preserve one owner or atomically drain idle`() {
        val second = Store(file)
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(12) { round ->
                val id = job(); val session = "pressure-race-$round"; val allocation = "alloc-$session"
                bind(id, session)
                val start = java.util.concurrent.CountDownLatch(1)
                val ordinary = pool.submit(Callable { start.await(); store.execution.claim(session, token, allocation, 1001) })
                val pressured = pool.submit(Callable { start.await(); second.execution.claim(session, token, allocation, 1001, allowNewSolve = { false }) })
                start.countDown()
                val a = ordinary.get(5, java.util.concurrent.TimeUnit.SECONDS)
                val b = pressured.get(5, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(a, b, "a concurrent pressure request cannot drain between assigning and replaying ownership")
                if (a == null) {
                    assertEquals(Desired.DRAIN, store.execution.session(session)!!.desired)
                    assertFalse(store.execution.hasActiveAttempt(session))
                    assertTrue(store.job(id)!!.commands.all { it.host == null })
                } else {
                    assertEquals(Desired.RUN, store.execution.session(session)!!.desired)
                    assertEquals(a, store.execution.claim(session, token, allocation, 1002, allowNewSolve = { false }))
                    store.execution.phase(a.owner, "uploading", 1003)
                    assertTrue(store.execution.commit(a.owner, result(a), 1004))
                }
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun `pressure retirement and worker registration serialize without revoking owned rows`() {
        val second = Store(file); val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(12) { round ->
                val id = job(); val session = "registration-race-$round"; val allocation = "alloc-$session"
                store.execution.createSession(session, id, ExecutionClass.SOLVE, "release-1", budget, token, "{}", 1000)
                val start = java.util.concurrent.CountDownLatch(1)
                val retired = pool.submit(Callable { start.await(); store.execution.loseUnallocatedSession(session, "journal-pressure", 1001) })
                val bound = pool.submit(Callable {
                    start.await()
                    try { second.execution.bind(session, token, allocation, "node", "release-1", 1001) }
                    catch (_: ExecutionFenced) { null }
                })
                start.countDown()
                if (retired.get(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    assertNull(bound.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    assertEquals(Desired.STOP, store.execution.session(session)!!.desired)
                    assertFailsWith<ExecutionFenced> { store.execution.claim(session, token, allocation, 1002) }
                } else {
                    assertNotNull(bound.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    assertNull(store.execution.claim(session, token, allocation, 1002, allowNewSolve = { false }))
                    assertEquals(Desired.DRAIN, store.execution.session(session)!!.desired)
                }
                assertFalse(store.execution.hasActiveAttempt(session))
                assertTrue(store.job(id)!!.commands.all { it.host == null && it.status == Status.QUEUED })
                assertTrue(store.execution.accepted(id).isEmpty())
            }
        } finally { pool.shutdownNow() }
    }

    private fun checkpoint(elapsed: Long, cpu: Long, retirement: String? = null) = ResourceCheckpoint(elapsed,
        budget, "cgroup-v2-hard", "200000 100000", (budget.memoryMiB * 1024L * 1024).toString(),
        mapOf("cpu.stat" to "usage_usec $cpu"), "worker-cumulative-lower-bound", retirement)

    @Test fun `interruption retains accepted pairs checkpoints and spent allowance while replacing the unfinished row`() {
        val id = job(); bind(id)
        val finished = store.execution.claim("s", token, "alloc-s", 1001)!!; commit(finished)
        val lost = store.execution.claim("s", token, "alloc-s", 1030)!!
        store.execution.resourceCheckpoint(lost.owner, checkpoint(30, 100), 1031)
        store.execution.resourceCheckpoint(lost.owner, checkpoint(40, 200, "provider-interruption"), 1040)
        assertEquals(Desired.STOP, store.execution.session("s")!!.desired)
        assertEquals("interrupted:provider-interruption", store.execution.reports(id).single().observation)
        assertEquals(200L, Store(file).execution.reports(id).single().resources!!.cpuUsec)
        assertEquals(40L, store.job(id)!!.runMs)
        assertTrue(store.job(id)!!.commands.filter { it.index in finished.indices }.all { it.status == Status.DONE })
        assertTrue(store.job(id)!!.commands.filter { it.index in lost.indices }.all { it.status == Status.QUEUED })
        assertEquals(1, store.execution.accepted(id).size)
        assertFailsWith<ExecutionFenced> { store.execution.renew(lost.owner, 1041) }
        assertFailsWith<ExecutionFenced> { store.execution.commit(lost.owner, result(lost), 1041) }
        store.execution.resourceCheckpoint(lost.owner, checkpoint(50, 250), 1050)
        store.execution.resourceCheckpoint(lost.owner, checkpoint(35, 150), 1051)
        val resources = Store(file).execution.reports(id).single().resources!!
        assertEquals(250L, resources.cpuUsec); assertEquals("provider-interruption", resources.retirement)
        assertEquals(40L, store.job(id)!!.runMs, "late evidence must not restart the charged clock")
        bind(id, "replacement")
        val replacement = store.execution.claim("replacement", token, "alloc-replacement", 1060)!!
        assertEquals(lost.indices, replacement.indices)
    }

    @Test fun `resource evidence checks attempt token allocation budget expiry and superseded retirement`() {
        val id = job(); bind(id)
        val first = store.execution.claim("s", token, "alloc-s", 1001)!!; commit(first)
        val next = store.execution.claim("s", token, "alloc-s", 1030)!!
        assertFailsWith<ExecutionFenced> { store.execution.resourceCheckpoint(next.owner.copy(token = "wrong"), checkpoint(30, 100), 1031) }
        assertFailsWith<ExecutionFenced> { store.execution.resourceCheckpoint(next.owner.copy(allocation = "other"), checkpoint(30, 100), 1031) }
        val badBudget = checkpoint(30, 100).copy(budget = ResourceBudget(1000, 512))
        assertFailsWith<IllegalArgumentException> { store.execution.resourceCheckpoint(next.owner, badBudget, 1031) }
        assertFailsWith<IllegalArgumentException> { store.execution.resourceCheckpoint(first.owner, checkpoint(30, 100, "worker-shutdown"), 1031) }
        assertEquals(Desired.RUN, store.execution.session("s")!!.desired)
        store.execution.loseSession("s", "infrastructure:lost", 1040)
        assertFailsWith<ExecutionFenced> { store.execution.resourceCheckpoint(next.owner, checkpoint(40, 200), next.leaseUntil + 120001) }
        assertNull(store.execution.reports(id).single().resources)
    }

    @Test fun `pending preparation node hints reserve placement without granting claims or charging queue time`() {
        val spec = ExperimentSpec("pending-preparation")
        val id = store.create(spec.name, "main", emptyList(), experiment = spec)
        store.execution.createSession("pending", id, ExecutionClass.PREPARE, "release", budget, token, "{}", 1000)
        assertFalse(PreparationAdmission(store).allows(null, false, 1))
        store.execution.preparationNode("pending", "node")
        assertTrue(PreparationAdmission(store).allows(null, false, 1))
        assertNull(store.execution.session("pending")!!.allocation)
        assertNull(store.job(id)!!.runningSince)
        assertFailsWith<ExecutionFenced> { store.execution.claim("pending", token, "allocation", 1001) }
        store.execution.bind("pending", token, "allocation", "node", "release", 1002)
        store.execution.preparationNode("pending", "wrong-node")
        assertEquals("node", store.execution.session("pending")!!.node)
    }

    @Test fun `parallelism reports executing live claims separately from unallocated reservations`() {
        val id = job(); bind(id)
        store.execution.createSession("pending", id, ExecutionClass.SOLVE, "release-1", budget, token, "{}", 1000)
        val claim = store.execution.claim("s", token, "alloc-s", 1100)!!
        assertEquals(0 to 2, store.execution.parallelism(listOf(id), 1101)[id])
        store.execution.phase(claim.owner, "executing", 1102)
        assertEquals(claim.parallel to 2, store.execution.parallelism(listOf(id), 1103)[id])
        assertEquals(0 to 2, store.execution.parallelism(listOf(id), 1100 + ExecutionStore.LEASE_MS)[id])
        store.execution.phase(claim.owner, "uploading", 1104)
        assertEquals(0 to 2, store.execution.parallelism(listOf(id), 1105)[id])
        assertTrue(store.execution.loseUnallocatedSession("pending", "placement-native-capacity", 1106))
        assertEquals(0 to 0, store.execution.parallelism(listOf(id), 1107)[id])
    }

    @Test fun `a session claims whole rows until they fill its case slots and a loss returns them all`() {
        val id = job(problems = 4)
        store.execution.createSession("wide", id, ExecutionClass.SOLVE, "release-1", ResourceBudget(4000, 2048, 3), token, "{}", 1000)
        store.execution.bind("wide", token, "alloc-wide", "node", "release-1", 1000)
        val claim = store.execution.claim("wide", token, "alloc-wide", 1001)!!
        // Two arms a row: three slots take two whole rows, never one arm of a third.
        assertEquals(4, claim.indices.size)
        assertEquals(3, claim.parallel)
        val rows = store.plans.casePlan(id).filter { it.index in claim.indices }.map { it.problem }.distinct()
        assertEquals(2, rows.size)
        assertTrue(rows.all { problem -> store.plans.casePlan(id).count { it.problem == problem } == 2 })
        store.execution.loseSession("wide", "infrastructure:test", 1002)
        assertTrue(store.job(id)!!.commands.all { it.status == Status.QUEUED })
        bind(id, "next")
        val retry = store.execution.claim("next", token, "alloc-next", 1003)!!
        store.execution.phase(retry.owner, "uploading", 1004)
        assertTrue(store.execution.commit(retry.owner, result(retry), 1005))
    }

    @Test fun `compiler admission limit survives restart without changing strict placement JSON`() {
        val spec = ExperimentSpec("compiler-admission")
        val id = store.create(spec.name, "main", emptyList(), experiment = spec)
        store.execution.createSession("prep", id, ExecutionClass.PREPARE, "release", budget, token,
            """{"Meta":{"lab_preparation_slots":"3"}}""", 1000)
        assertEquals(3, Store(file).execution.placement("prep").preparationSlots)
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().executeQuery("SELECT placement FROM execution_sessions WHERE id = 'prep'").use { row ->
                assertTrue(row.next())
                assertFalse(row.getString(1).contains("preparationSlots"))
                assertEquals(SessionPlacement(), SessionPlacement.parse(row.getString(1)))
            }
        }
    }

    @ParameterizedTest
    @CsvSource("true,false", "false,false", "missing,false", "null,false", "malformed,false",
        "true,true", "false,true", "missing,true", "null,true", "malformed,true")
    fun `adapter resource projection follows the frozen session contract`(mode: String, legacy: Boolean) {
        val id = job()
        val manifest = when (mode) {
            "missing" -> """{"experiment":{"bench":{}}}"""
            "null" -> null
            "malformed" -> "incomplete historical manifest"
            else -> """{"experiment":{"bench":{"adapterResources":$mode}}}"""
        }
        store.execution.createSession("s", id, ExecutionClass.SOLVE, "release-1", budget, token, "{}", 1000, manifest)
        if (legacy) java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.prepareStatement("UPDATE execution_sessions SET manifest = ? WHERE id = 's'").use { statement ->
                statement.setString(1, manifest)
                statement.executeUpdate()
            }
            db.createStatement().use { it.executeUpdate("DELETE FROM execution_manifests WHERE session_id = 's'") }
        }

        assertEquals(mode == "true", store.execution.adapterResources("s"))
    }

    @Test fun `registration winning a placement replacement race preserves ownership`() {
        val id = job()
        bind(id)
        assertFalse(store.execution.loseUnallocatedSession("s", "placement-cpu-capacity", 1001))
        assertEquals(Desired.RUN, store.execution.session("s")!!.desired)
        assertNotNull(store.execution.claim("s", token, "alloc-s", 1002))
    }

    private val file = Files.createTempDirectory("execution-state").resolve("lab.db")
    private val store = Store(file)
    private val token = ExecutionStore.token()
    private val budget = ResourceBudget(2000, 2048, 2)

    private fun job(problems: Int = 2): Long {
        val spec = ExperimentSpec("ab", listOf(mapOf("suite" to "s")), grid = mapOf("x" to listOf("1", "2")), seeds = listOf(1), host = HostPlacement.AUTO)
        val id = store.create("ab", "main", emptyList(), vcpus = 2, experiment = spec)
        val cases = Experiments.cases(problems, 2, spec.seeds)
        store.plan(id, Experiments.arms(spec).map { PlannedArm(it, "sha") }, List(problems) { Problem("s", "p$it") }, cases, cases.map { "true" to 10L })
        return id
    }
    private fun bind(id: Long, name: String = "s", s: Store = store): ExecutionSession {
        s.execution.createSession(name, id, ExecutionClass.SOLVE, "release-1", budget, token, "{}", 1000)
        return s.execution.bind(name, token, "alloc-$name", "node-$name", "release-1", 1000)
    }
    private fun result(claim: IterationClaim, at: Long = 1010) = IterationResult(claim.indices.map { ExecutionCaseResult(it, 0, at, at + 10, "{}") })
    private fun commit(claim: IterationClaim, at: Long = 1010) {
        store.execution.phase(claim.owner, "uploading", at)
        store.execution.commit(claim.owner, result(claim, at), at + 10)
    }

    @Test fun `sessions of a job keep their repeated manifest parts once and read them back whole`() {
        val id = job()
        val spec = store.job(id)!!.experiment
        val arms = store.plans.arms(id).map { it.copy(sha = "d".repeat(40)) }
        val cases = store.plans.casePlan(id).map { WorkCase(it.index, arms.indexOfFirst { a -> a.arm.label == it.arm }, it.problem, it.seed, it.repeat, 60) }
        val bundle = WorkerBundle("b".repeat(64), listOf(InputFile("fixture", "c".repeat(64), 0)), spec.repository, "d".repeat(40), "linux", "amd64", "test")
        fun manifest(session: String) = WorkManifest(session, "release-1", spec, arms, cases, listOf(bundle), ResourceBudget(8000, 8192))
        store.execution.createSession("first", id, ExecutionClass.SOLVE, "release-1", budget, token, "{}", 1000,
            manifest = kotlinx.serialization.json.Json.encodeToString(WorkManifest.serializer(), manifest("first")))
        store.execution.createSession("second", id, ExecutionClass.SOLVE, "release-1", budget, token, "{}", 1001,
            manifest = kotlinx.serialization.json.Json.encodeToString(WorkManifest.serializer(), manifest("second")))

        assertEquals(manifest("first"), store.execution.manifest("first"))
        assertEquals(manifest("second"), Store(file).execution.manifest("second"))
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().executeQuery("SELECT count(*) FROM manifest_parts").use { r -> r.next(); assertEquals(2, r.getInt(1), "cases and bundles, once for both sessions") }
            // As an earlier release kept it: the whole manifest in the row.
            db.prepareStatement("UPDATE execution_manifests SET manifest = ? WHERE session_id = 'first'").use {
                it.setString(1, kotlinx.serialization.json.Json.encodeToString(WorkManifest.serializer(), manifest("first"))); it.executeUpdate()
            }
        }
        var cursor: Long? = 0L
        while (cursor != null) cursor = store.execution.splitManifests(cursor)
        assertEquals(manifest("first"), store.execution.manifest("first"))
        assertEquals(0, store.execution.dropUnusedManifestParts(), "every part is still named")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { it.createStatement().execute("DELETE FROM execution_manifests") }
        assertEquals(2, store.execution.dropUnusedManifestParts(), "parts go with the last manifest naming them")
    }

    @Test fun `done jobs lose their stopped sessions' manifests while failed jobs keep theirs for a retry`() {
        val done = job(); val failed = job()
        store.execution.createSession("done", done, ExecutionClass.SOLVE, "release-1", budget, token, "{}", 1000, manifest = "{}")
        store.execution.createSession("failed", failed, ExecutionClass.SOLVE, "release-1", budget, token, "{}", 1000, manifest = "{}")
        store.execution.loseSession("done", "test"); store.execution.loseSession("failed", "test")
        store.finish(done, Status.DONE); store.finish(failed, Status.FAILED, "boom")

        assertEquals(0, store.execution.dropDoneManifests(doneBefore = 0))
        assertEquals(1, store.execution.dropDoneManifests(doneBefore = Long.MAX_VALUE))
        assertEquals(0, store.execution.dropDoneManifests(doneBefore = Long.MAX_VALUE))
        assertFailsWith<IllegalArgumentException> { store.execution.manifest("done") }
    }

    @Test fun `a committed attempt keeps its metadata and digest, not its case records, and still fences a retry`() {
        val id = job(); bind(id)
        val claim = store.execution.claim("s", token, "alloc-s", 1001)!!
        store.execution.phase(claim.owner, "uploading", 1010)
        val result = IterationResult(claim.indices.map { ExecutionCaseResult(it, 0, 1010, 1020, """{"finalWitness":"${"x".repeat(1000)}"}""") },
            artifacts = mapOf("cases/0/out/record.json" to "a".repeat(64)))

        assertTrue(store.execution.commit(claim.owner, result, 1030))

        val kept = store.execution.accepted(id).single().second
        assertTrue(kept.cases.all { it.record == null }, "records live in cases, not in the attempt")
        assertEquals(result.artifacts, kept.artifacts)
        assertEquals(result.cases.map { it.index to it.exitCode }, kept.cases.map { it.index to it.exitCode })
        assertTrue(store.plans.cases(id).filter { it.index in claim.indices }.all { it.record.toString().contains("finalWitness") })
        val restarted = Store(file)
        assertTrue(restarted.execution.commit(claim.owner, result, 1031), "an identical retry is acknowledged")
        val different = result.copy(cases = result.cases.map { it.copy(record = "{}") })
        assertFailsWith<ExecutionFenced> { restarted.execution.commit(claim.owner, different, 1032) }
        assertFailsWith<ExecutionFenced> { restarted.execution.authorizeCommit(claim.owner, different, 1032) }
    }

    @Test fun `results committed before slimming are slimmed in batches and keep fencing retries`() {
        val id = job(); bind(id)
        val claim = store.execution.claim("s", token, "alloc-s", 1001)!!
        store.execution.phase(claim.owner, "uploading", 1010)
        val result = IterationResult(claim.indices.map { ExecutionCaseResult(it, 0, 1010, 1020, """{"stats":{"n":1}}""") })
        store.execution.commit(claim.owner, result, 1030)
        // As an earlier release kept it: the whole result and no digest.
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.prepareStatement("UPDATE iteration_attempts SET result = ?, result_sha256 = NULL WHERE job_id = ?").use {
                it.setString(1, kotlinx.serialization.json.Json.encodeToString(IterationResult.serializer(), result)); it.setLong(2, id); it.executeUpdate()
            }
        }
        assertEquals(result, store.execution.accepted(id).single().second)

        var cursor: Long? = 0L; var batches = 0
        while (cursor != null) { cursor = store.execution.slimAcceptedResults(cursor, batch = 1); if (cursor != null) batches++ }

        assertEquals(1, batches)
        assertEquals(ExecutionStore.slim(result), store.execution.accepted(id).single().second)
        assertTrue(store.execution.commit(claim.owner, result, 1031), "the original result still matches by digest")
        assertFailsWith<ExecutionFenced> { store.execution.commit(claim.owner, ExecutionStore.slim(result), 1032) }
        assertNull(store.execution.slimAcceptedResults(0L), "nothing is left to slim")
    }

    @Test fun `coordinator record metadata preserves the accepted manifest for acknowledgement retries`() {
        val id = job(); bind(id)
        val claim = store.execution.claim("s", token, "alloc-s", 1001)!!
        store.execution.phase(claim.owner, "uploading", 1010)
        val result = result(claim)
        val projected = """{"coordinator":true}"""

        store.execution.commit(claim.owner, result, 1020, projectRecord = { projected })
        val restarted = Store(file)
        restarted.execution.authorizeCommit(claim.owner, result, 1021)

        assertTrue(restarted.execution.commit(claim.owner, result, 1021))
        assertEquals(ExecutionStore.slim(result), restarted.execution.accepted(id).single().second)
        assertTrue(restarted.plans.cases(id).filter { it.index in claim.indices }.all {
            it.record.toString() == projected
        })
    }

    private fun profile(): Long {
        val spec = ExperimentSpec("profile", profile = ProfileMain(main = "com.eignex.Main", runTimeoutSec = 1, classpath = listOf("tools/lib/*")))
        val id = store.create("profile", "main", emptyList(), experiment = spec)
        val cases = Experiments.cases(1, 1, emptyList())
        store.plan(id, Experiments.arms(spec).map { PlannedArm(it, "sha") }, listOf(Experiments.profileProblem(spec)), cases, cases.map { "true" to 720L })
        return id
    }

    private fun bench(timeout: Long? = 1): Long {
        val spec = ExperimentSpec("bench", bench = BenchSpec("bench.json", runTimeoutSec = timeout))
        val id = store.create(spec.name, "main", emptyList(), experiment = spec)
        val cases = Experiments.cases(1, 1, emptyList())
        store.plan(id, Experiments.arms(spec).map { PlannedArm(it, "sha") }, listOf(Problem("bench", "p")), cases, cases.map { "true" to 720L })
        return id
    }

    @Test fun `bench deadline receipt retains partial output and survives acknowledgement retry`() {
        val id = bench(); bind(id)
        val claim = store.execution.claim("s", token, "alloc-s", 1100)!!
        assertEquals(900L, store.execution.remainingRunMs("s", 1100))
        store.execution.phase(claim.owner, "uploading", 1700)
        val result = IterationResult(claim.indices.map { ExecutionCaseResult(it, 124, 1100, 1700, "{}") },
            artifacts = mapOf("cases/0/out/solve.log" to "a".repeat(64)), runTimedOut = true)
        assertTrue(store.execution.commit(claim.owner, result, 1800))
        val restarted = Store(file)
        assertTrue(restarted.execution.commit(claim.owner, result, 1801))
        assertEquals(Status.FAILED, restarted.job(id)!!.status)
        assertEquals(ExecutionStore.slim(result), restarted.execution.accepted(id).single().second)
        assertTrue(restarted.execution.sessions(id).all { it.desired == Desired.STOP })
    }

    @Test fun `bench deadline survives replacement and cannot be reported without an allowance`() {
        val id = bench(); bind(id)
        store.execution.claim("s", token, "alloc-s", 1100)!!
        store.execution.loseSession("s", "infrastructure:lost", 1300)
        bind(id, "replacement")
        assertEquals(200L, Store(file).execution.remainingRunMs("replacement", 1800))
        store.execution.expireRunDeadlines(2000)
        assertEquals(Status.FAILED, store.job(id)!!.status)
        // A manual retry grants the whole-run limit afresh, while the job keeps its full run time as cost.
        val spent = store.job(id)!!.runMs
        assertTrue(spent > 0 && store.retry(id))
        assertEquals(spent, store.job(id)!!.runMs)
        assertEquals(0L, store.job(id)!!.deadlineElapsedMs(3000))
        store.execution.expireRunDeadlines(3000)
        assertEquals(Status.QUEUED, store.job(id)!!.status)
        val unlimited = bench(null); bind(unlimited, "unlimited")
        val claim = store.execution.claim("unlimited", token, "alloc-unlimited", 1100)!!
        store.execution.phase(claim.owner, "uploading", 1700)
        assertFailsWith<IllegalArgumentException> { store.execution.commit(claim.owner, result(claim).copy(runTimedOut = true), 1800) }
    }

    @Test fun `preparation has fenced ownership before a plan and survives coordinator restart`() {
        val spec = ExperimentSpec("prepare")
        val id = store.create(spec.name, "main", emptyList(), experiment = spec)
        store.execution.createSession("prep", id, ExecutionClass.PREPARE, "release", budget, token, "{}", 1000)
        store.execution.bind("prep", token, "allocation", "node", "release", 1000)
        val first = store.execution.claim("prep", token, "allocation", 1100)!!
        assertTrue(first.indices.isEmpty())
        assertEquals(first, Store(file).execution.claim("prep", token, "allocation", 1101))
        store.execution.loseSession("prep", "infrastructure:spot", 1200)
        store.execution.createSession("replacement", id, ExecutionClass.PREPARE, "release", budget, token, "{}", 1200)
        store.execution.bind("replacement", token, "allocation-2", "node-2", "release", 1200)
        val next = store.execution.claim("replacement", token, "allocation-2", 1300)!!
        assertEquals(2, next.owner.generation)
        assertFailsWith<ExecutionFenced> { store.execution.renew(first.owner, 1301) }
        store.execution.phase(next.owner, "uploading", 1301)
        val result = IterationResult(emptyList(), preparation = PreparationResult(emptyList(), emptyList()))
        assertTrue(store.execution.commit(next.owner, result, 1302))
        val restarted = Store(file)
        assertEquals(ExecutionStore.slim(result), restarted.execution.accepted(id).single().second)
        assertTrue(restarted.execution.commit(next.owner, result, 1303))
        assertNull(restarted.execution.claim("replacement", token, "allocation-2", 1304))
        assertFalse(restarted.job(id)!!.setupDone)
    }

    @Test fun `preparation progress survives restart fences stale owners and rejects updates after upload`() {
        val spec = ExperimentSpec("prepare-progress")
        val id = store.create(spec.name, "main", emptyList(), experiment = spec)
        store.execution.createSession("prep", id, ExecutionClass.PREPARE, "release", budget, token, "{}", 1000)
        store.execution.bind("prep", token, "allocation", "node", "release", 1000)
        val claim = store.execution.claim("prep", token, "allocation", 1100)!!
        store.execution.phase(claim.owner, "preparing", 1101)
        val progress = PreparationProgress("compilation", 500, 200, mapOf("checkout" to 300, "compilation" to 200),
            mapOf("build" to PreparationCacheEvent("hit", "verified-local", verificationMs = 120, waitingMs = 30)))
        store.execution.preparationProgress(claim.owner, progress, 1600)
        store.execution.preparationProgress(claim.owner, progress.copy(elapsedMs = 400, caches = emptyMap()), 1601)
        val report = Store(file).execution.reports(id).single()
        assertEquals(PreparationStatus(progress, 1600), report.preparation)
        assertTrue(executionPage(id, listOf(report)).contains("Compiling"))
        assertTrue(preparationDetails(report).contains("Build: latest hit (verified local entry), verification 0.12 s, waiting 0.03 s"))
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().executeQuery("SELECT preparation_progress, preparation_caches FROM iteration_attempts WHERE session_id = 'prep'").use { row ->
                assertTrue(row.next())
                // An older binary has no cache field and strict-decodes this column.
                assertFalse(row.getString(1).contains("caches"))
                assertTrue(row.getString(2).contains("verified-local"))
            }
        }
        assertTrue(preparationDetails(report, 1700).contains("0.30 s in phase"))
        assertTrue(preparationDetails(report, 8000).contains("last update"))
        store.execution.phase(claim.owner, "uploading", 1700)
        assertFailsWith<IllegalArgumentException> { store.execution.preparationProgress(claim.owner, progress, 1701) }
        store.execution.loseSession("prep", "infrastructure:lost", 1800)
        assertFailsWith<ExecutionFenced> { store.execution.preparationProgress(claim.owner, progress, 1801) }
    }

    @Test fun `bounded preparation evidence survives failure without authorizing stale or mismatched writers`() {
        val spec = ExperimentSpec("prepare-diagnostics")
        val id = store.create(spec.name, "main", emptyList(), experiment = spec)
        store.execution.createSession("prep", id, ExecutionClass.PREPARE, "release", budget, token, "{}", 1000)
        store.execution.bind("prep", token, "allocation", "node", "release", 1000)
        val claim = store.execution.claim("prep", token, "allocation", 1100)!!
        store.execution.phase(claim.owner, "preparing", 1101)
        val diagnostic = PreparationDiagnostics(500, "compilation", budget, "advisory-native", null, null, emptyMap(),
            1000, 2000, 2, PreparationBuildDiagnostic(3, "compiler output", "compile failure"), sequence = 2)
        store.execution.preparationDiagnostics(claim.owner, diagnostic, 1600)
        store.execution.preparationDiagnostics(claim.owner, diagnostic.copy(elapsedMs = 400), 1601)
        store.execution.preparationDiagnostics(claim.owner, diagnostic.copy(build = null, sequence = 1), 1602)
        assertEquals(diagnostic, Store(file).execution.reports(id).single().preparation!!.diagnostics)
        assertFailsWith<ExecutionFenced> { store.execution.preparationDiagnostics(claim.owner.copy(generation = 2), diagnostic, 1602) }
        assertFailsWith<ExecutionFenced> { store.execution.preparationDiagnostics(claim.owner.copy(token = "wrong"), diagnostic, 1602) }
        assertFailsWith<ExecutionFenced> { store.execution.preparationDiagnostics(claim.owner.copy(allocation = "wrong"), diagnostic, 1602) }
        assertFailsWith<IllegalArgumentException> { store.execution.preparationDiagnostics(claim.owner, diagnostic.copy(budget = budget.copy(memoryMiB = 4096)), 1602) }
        assertFailsWith<ExecutionFenced> { store.execution.preparationDiagnostics(claim.owner, diagnostic, 1100 + ExecutionStore.LEASE_MS) }
        store.execution.phase(claim.owner, "uploading", 1650)
        assertFailsWith<IllegalArgumentException> { store.execution.preparationDiagnostics(claim.owner, diagnostic, 1651) }
        store.execution.loseSession("prep", "infrastructure:lost", 1700)
        assertFailsWith<ExecutionFenced> { store.execution.preparationDiagnostics(claim.owner, diagnostic.copy(elapsedMs = 600), 1701) }
        assertEquals(diagnostic, Store(file).execution.reports(id).single().preparation!!.diagnostics)
        assertEquals(emptyList(), store.execution.accepted(id))
        assertFailsWith<IllegalArgumentException> { diagnostic.copy(uploads = List(17) { PreparationUploadDiagnostic("a".repeat(64), "GET", 503, null, 0, 0, 10) }) }
        assertFailsWith<IllegalArgumentException> { PreparationBuildDiagnostic(stdout = "x".repeat(4097)) }
    }

    @Test fun `profile allowance survives replacement and expires without another infrastructure retry`() {
        val id = profile(); bind(id, "first")
        val first = store.execution.claim("first", token, "alloc-first", 1100)!!
        assertEquals(800, store.execution.remainingRunMs("first", 1200))
        store.execution.loseSession("first", "infrastructure:lost", 1300)
        bind(id, "second")
        assertEquals(200, Store(file).execution.remainingRunMs("second", 1800))
        store.execution.claim("second", token, "alloc-second", 1800)!!
        assertFailsWith<ExecutionFenced> { store.execution.runTimedOut(first.owner, 1801) }
        store.execution.expireRunDeadlines(1999)
        assertEquals(Status.RUNNING, store.job(id)!!.status)
        store.execution.expireRunDeadlines(2000)
        assertEquals(Status.FAILED, Store(file).job(id)!!.status)
        assertEquals(RunDeadlineExceeded.ERROR, store.job(id)!!.error)
        assertTrue(store.execution.sessions(id).all { it.desired == Desired.STOP })
        assertEquals(1, store.execution.failureCount(id))
    }

    @Test fun `gracefully timed out profile retains its accepted result and failure across acknowledgement retry`() {
        val id = profile(); bind(id)
        val claim = store.execution.claim("s", token, "alloc-s", 1100)!!
        store.execution.phase(claim.owner, "uploading", 1700)
        val result = IterationResult(claim.indices.map { ExecutionCaseResult(it, 124, 1100, 1700) }, runTimedOut = true)
        assertTrue(store.execution.commit(claim.owner, result, 1800))
        assertTrue(Store(file).execution.commit(claim.owner, result, 1801))
        assertEquals(Status.FAILED, store.job(id)!!.status)
        assertEquals(1, store.execution.accepted(id).size)
        assertEquals(Status.FAILED, store.job(id)!!.commands.single().status)
        assertEquals(listOf(id), store.execution.pendingProjectionJobs())
        // Routine scans skip jobs that ended before their window; the full scan still finds them.
        assertEquals(listOf(id), store.execution.pendingProjectionJobs(endedSince = 1800))
        assertEquals(emptyList(), store.execution.pendingProjectionJobs(endedSince = 1801))
        assertTrue(store.retry(id))
        store.execution.expireRunDeadlines(1802)
        assertEquals(Status.QUEUED, store.job(id)!!.status)
        assertEquals(ExecutionStore.slim(result), store.execution.accepted(id).single().second)
        val spent = store.job(id)!!.runMs
        store.execution.createSession("retried", id, ExecutionClass.SOLVE, "release", budget, token, "{}", 3000)
        store.execution.bind("retried", token, "retried-allocation", "node", "release", 3000)
        assertEquals(800L, Store(file).execution.remainingRunMs("retried", 3200))
        assertEquals(spent, store.job(id)!!.runMs)
        store.execution.expireRunDeadlines(4000)
        assertEquals(Status.FAILED, store.job(id)!!.status)
    }

    @Test fun `expired profile rejects acceptance before the next coordinator reconciliation`() {
        val id = profile(); bind(id)
        val claim = store.execution.claim("s", token, "alloc-s", 1100)!!
        store.execution.phase(claim.owner, "uploading", 1700)
        assertFailsWith<RunDeadlineExceeded> { store.execution.commit(claim.owner, result(claim), 2000) }
        assertTrue(store.execution.accepted(id).isEmpty())
        assertNull(store.execution.claim("s", token, "alloc-s", 2000))
        assertEquals(Status.FAILED, store.job(id)!!.status)
    }

    @Test fun `source build failure stops siblings once and a fenced generation cannot fail replacement work`() {
        val id = job(); bind(id, "a"); bind(id, "b")
        val lost = store.execution.claim("a", token, "alloc-a", 1001)!!
        store.execution.loseSession("a", "revoked", 1002)
        val replacement = store.execution.claim("b", token, "alloc-b", 1003)!!
        assertFailsWith<ExecutionFenced> { store.execution.rejectBuild(lost.owner, "compile failed", 1004) }
        assertEquals(Status.RUNNING, store.job(id)!!.status)
        store.execution.rejectBuild(replacement.owner, "compile failed", 1005)
        store.execution.rejectBuild(replacement.owner, "compile failed", 1006) // Lost failure acknowledgement.
        assertEquals(Status.FAILED, Store(file).job(id)!!.status)
        assertEquals("source-build: compile failed", store.job(id)!!.error)
        assertTrue(store.execution.sessions(id).all { it.desired == Desired.STOP })
        assertEquals(0, store.execution.accepted(id).size)
    }

    @Test fun `ambiguous submission and claim reuse stable identities without storing bearer token`() {
        val id = job(); val session = bind(id)
        assertEquals(session, store.execution.createSession("s", id, ExecutionClass.SOLVE, "release-1", budget, token, "{}", 1002))
        assertEquals(1, store.execution.intents().size)
        val first = store.execution.claim("s", token, "alloc-s", 1001)
        assertEquals(first, Store(file).execution.claim("s", token, "alloc-s", 1002))
        assertFailsWith<ExecutionFenced> { store.execution.bind("s", token, "other", "node-s", "release-1") }
        assertFailsWith<ExecutionFenced> { store.execution.claim("s", "x".repeat(64), "alloc-s", 1002) }
    }

    @Test fun `separate processes claim distinct complete paired iterations`() {
        val id = job(); bind(id, "a"); val second = Store(file); bind(id, "b", second)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val claims = pool.invokeAll(listOf(Callable { store.execution.claim("a", token, "alloc-a", 1001)!! }, Callable { second.execution.claim("b", token, "alloc-b", 1001)!! })).map { it.get() }
            assertEquals(listOf(2, 2), claims.map { it.indices.size })
            assertEquals((0..3).toSet(), claims.flatMap { it.indices }.toSet())
            assertEquals(2, claims.map { it.owner.iteration }.distinct().size)
        } finally { pool.shutdownNow() }
    }

    @Test fun `lost session fences old generation and retains finished iterations`() {
        val id = job(); bind(id)
        val finished = store.execution.claim("s", token, "alloc-s", 1001)!!; commit(finished)
        val lost = store.execution.claim("s", token, "alloc-s", 1030)!!
        store.execution.phase(lost.owner, "executing", 1031)
        store.execution.loseSession("s", "spot", 1040)
        bind(id, "replacement")
        val replacement = store.execution.claim("replacement", token, "alloc-replacement", 1050)!!
        assertEquals(lost.indices, replacement.indices); assertEquals(lost.owner.generation + 1, replacement.owner.generation)
        assertFailsWith<ExecutionFenced> { store.execution.renew(lost.owner, 1051) }
        assertFailsWith<ExecutionFenced> { store.execution.commit(lost.owner, result(lost), 1051) }
        assertTrue(store.job(id)!!.commands.filter { it.index in finished.indices }.all { it.status == Status.DONE })
        assertEquals(1, store.execution.intents().count { it.operation == IntentOp.STOP })
    }

    @Test fun `case comparison origins follow complete accepted rows across worker replacement`() {
        val id = job(); bind(id)
        assertTrue(store.plans.cases(id).all { it.origin == CaseOrigin(id, null) })
        val finished = store.execution.claim("s", token, "alloc-s", 1001)!!; commit(finished)
        val lost = store.execution.claim("s", token, "alloc-s", 1030)!!
        store.execution.loseSession("s", "spot", 1040)
        assertTrue(store.plans.cases(id).filter { it.index in lost.indices }.all { it.origin?.attempt == null })
        bind(id, "replacement")
        val replacement = store.execution.claim("replacement", token, "alloc-replacement", 1050)!!
        commit(replacement, 1060)

        val cases = store.plans.cases(id)
        val first = assertNotNull(cases.filter { it.index in finished.indices }.map { it.origin }.distinct().single())
        val second = assertNotNull(cases.filter { it.index in replacement.indices }.map { it.origin }.distinct().single())

        assertEquals(id, first.job)
        assertEquals(id, second.job)
        assertNotNull(first.attempt)
        assertNotNull(second.attempt)
        assertNotEquals(first, second)
        assertEquals(cases.map { it.origin }, Store(file).plans.scoringCases(id).map { it.origin })
    }

    @Test fun `lease expiry retires credential before fresh claims and repeated commit is accepted`() {
        val id = job(); bind(id)
        val done = store.execution.claim("s", token, "alloc-s", 1001)!!; commit(done)
        assertTrue(Store(file).execution.commit(done.owner, result(done), 999999))
        val lost = store.execution.claim("s", token, "alloc-s", 1030, 10)!!
        assertFailsWith<ExecutionFenced> { store.execution.renew(lost.owner, 1040) }
        assertFailsWith<ExecutionFenced> { store.execution.claim("s", token, "alloc-s", 1040) }
        store.execution.expire(1040)
        bind(id, "fresh")
        assertEquals(2, store.execution.claim("fresh", token, "alloc-fresh", 1041)!!.owner.generation)
    }

    @Test fun `cancel and upload serialize and cancellation cannot become success`() {
        val id = job(); bind(id)
        val claim = store.execution.claim("s", token, "alloc-s", 1001)!!
        store.execution.phase(claim.owner, "uploading", 1002)
        assertEquals(CancelOutcome.CANCELLED, Store(file).requestCancel(id))
        assertFailsWith<ExecutionFenced> { store.execution.commit(claim.owner, result(claim), 1003) }
        assertEquals(Status.CANCELLED, store.job(id)!!.status)
        assertTrue(store.job(id)!!.commands.all { it.status == Status.CANCELLED })
        assertEquals(setOf(IntentOp.SUBMIT, IntentOp.STOP), store.execution.intents().map { it.operation }.toSet())
    }

    @Test fun `several sessions preserve the per experiment per node case cap`() {
        val sharedId = job();
        for (name in listOf("same-a", "same-b")) {
            store.execution.createSession(name, sharedId, ExecutionClass.SOLVE, "release-1", budget, token, """{"Meta":{"lab_per_host_parallel":"2"}}""", 1000)
            store.execution.bind(name, token, "alloc-$name", "shared-node", "release-1", 1000)
        }
        val first = store.execution.claim("same-a", token, "alloc-same-a", 1001)!!
        assertEquals(2, first.parallel)
        assertNull(store.execution.claim("same-b", token, "alloc-same-b", 1002))
        store.execution.phase(first.owner, "uploading", 1003)
        store.execution.commit(first.owner, result(first), 1004)
        assertEquals(2, store.execution.claim("same-b", token, "alloc-same-b", 1005)!!.parallel)
    }

    @Test fun `same job can claim two CPU bounded workers on one node by default`() {
        val id = job()
        for (name in listOf("a", "b")) {
            store.execution.createSession(name, id, ExecutionClass.SOLVE, "release-1", budget, token, "{}", 1000)
            store.execution.bind(name, token, "alloc-$name", "shared", "release-1", 1000)
        }
        assertEquals(2, store.execution.claim("a", token, "alloc-a", 1001)!!.parallel)
        assertEquals(2, store.execution.claim("b", token, "alloc-b", 1002)!!.parallel)
    }
}
