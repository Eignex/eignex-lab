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
import kotlin.test.*

class ExecutionStoreTest {
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
        val id = store.create("ab", "main", emptyList(), parallel = 2, experiment = spec)
        val cases = Experiments.cases(problems, 2, spec.seeds)
        store.plan(id, Experiments.arms(spec).map { PlannedArm(it, "sha") }, List(problems) { Problem("s", "p$it") }, cases, cases.map { "true" to 10L })
        return id
    }
    private fun bind(id: Long, name: String = "s", s: Store = store): ExecutionSession {
        s.execution.createSession(name, id, ExecutionClass.SOLVE, "release-1", budget, token, "{}", 1000)
        return s.execution.bind(name, token, "alloc-$name", "node-$name", "release-1", 1000)
    }
    private fun result(claim: IterationClaim) = IterationResult(claim.indices.map { ExecutionCaseResult(it, 0, 1010, 1020, "{}") })
    private fun commit(claim: IterationClaim) {
        store.execution.phase(claim.owner, "uploading", 1010)
        store.execution.commit(claim.owner, result(claim), 1020)
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
        assertEquals(result, restarted.execution.accepted(id).single().second)
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
        assertEquals(result, restarted.execution.accepted(id).single().second)
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
        assertEquals(result, restarted.execution.accepted(id).single().second)
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
            1000, 2000, 2, PreparationBuildDiagnostic(3, "compiler output", "compile failure"))
        store.execution.preparationDiagnostics(claim.owner, diagnostic, 1600)
        store.execution.preparationDiagnostics(claim.owner, diagnostic.copy(elapsedMs = 400), 1601)
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
        assertTrue(store.retry(id))
        store.execution.expireRunDeadlines(1802)
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

    @Test fun `historical backend provenance stays readable while new jobs always use Nomad`() {
        val id = store.create("old", "main", listOf("recorded" to 1L), experiment = ExperimentSpec("old"))
        store.finish(id, Status.DONE)
        // What a database from before the backend column, and before numbered migrations, looks like.
        store.executionTransaction { db -> db.sqlUpdate("ALTER TABLE jobs DROP COLUMN backend"); db.sqlUpdate("PRAGMA user_version = 0") }
        val reopened = Store(file)
        assertEquals(Backend.LEGACY, reopened.job(id)!!.backend)
        assertEquals(Status.DONE, reopened.job(id)!!.status)
        assertEquals("recorded", reopened.job(id)!!.commands.single().cmd)
        assertFalse(reopened.retry(id))
        val current = reopened.create("new", "main", emptyList(), experiment = ExperimentSpec("new"))
        assertEquals(Backend.NOMAD, reopened.job(current)!!.backend)
    }
}
