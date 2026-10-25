package com.eignex.lab

import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.*

class ExecutionStoreTest {
    private val file = Files.createTempDirectory("execution-state").resolve("lab.db")
    private val store = Store(file)
    private val token = ExecutionStore.token()
    private val budget = ResourceBudget(2000, 2048, 2)

    private fun job(problems: Int = 2): Long {
        val spec = ExperimentSpec("ab", listOf(mapOf("suite" to "s")), grid = mapOf("x" to listOf("1", "2")), seeds = listOf(1), host = "auto")
        val id = store.create("ab", "main", emptyList(), parallel = 2, experiment = spec, backend = ExecutionBackendKind.NOMAD)
        val cases = Experiments.cases(problems, 2, spec.seeds)
        store.plan(id, Experiments.arms(spec).map { PlannedArm(it, "sha") }, List(problems) { Problem("s", "p$it") }, cases, cases.map { "true" to 10L })
        return id
    }
    private fun bind(id: Long, name: String = "s", s: Store = store): ExecutionSession {
        s.execution.createSession(name, id, "solve", "release-1", budget, token, "{}", 1000)
        return s.execution.bind(name, token, "alloc-$name", "node-$name", "release-1", 1000)
    }
    private fun result(claim: IterationClaim) = IterationResult(claim.indices.map { ExecutionCaseResult(it, 0, 1010, 1020, "{}") })
    private fun commit(claim: IterationClaim) {
        store.execution.phase(claim.owner, "uploading", 1010)
        store.execution.commit(claim.owner, result(claim), 1020)
    }

    @Test fun `nomad ownership excludes legacy claims and restart recovery`() {
        val id = job()
        assertNull(store.next()); assertTrue(store.nodeQueue().isEmpty()); assertNull(store.claimForNode(id))
        assertNull(store.claimIteration(id, "legacy"))
        bind(id)
        val claim = store.execution.claim("s", token, "alloc-s", 1001)!!
        store.recoverShared(id)
        assertEquals(claim, Store(file).execution.claim("s", token, "alloc-s", 1002))
        assertEquals(ExecutionBackendKind.NOMAD, Store(file).job(id)!!.backend)
    }

    @Test fun `ambiguous submission and claim reuse stable identities without storing bearer token`() {
        val id = job(); val session = bind(id)
        assertEquals(session, store.execution.createSession("s", id, "solve", "release-1", budget, token, "{}", 1002))
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
        assertEquals(1, store.execution.intents().count { it.operation == "stop" })
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
        assertEquals(setOf("submit", "stop"), store.execution.intents().map { it.operation }.toSet())
    }

    @Test fun `an incomplete manifest rolls back all projection and rollback requires stopped allocations`() {
        val id = job(); val session = bind(id)
        val claim = store.execution.claim("s", token, "alloc-s", 1001)!!
        store.execution.phase(claim.owner, "uploading", 1002)
        assertFailsWith<IllegalArgumentException> { store.execution.commit(claim.owner, result(claim).copy(cases = result(claim).cases.take(1)), 1003) }
        assertEquals(0, store.job(id)!!.done)
        assertFailsWith<IllegalArgumentException> { store.execution.releaseToLegacy(id, setOf(session.nomadJobId)) }
        store.execution.loseSession("s", "rollback", 1004)
        assertFailsWith<IllegalArgumentException> { store.execution.releaseToLegacy(id, emptySet()) }
        store.execution.releaseToLegacy(id, setOf(session.nomadJobId))
        assertEquals(id, store.next()?.id)
    }

    @Test fun `old jobs migrate to legacy and survive additional state migration`() {
        val id = store.create("old", "main", listOf("true" to 1L))
        assertEquals(ExecutionBackendKind.LEGACY, Store(file).job(id)!!.backend)
        assertEquals(id, Store(file).next()?.id)
    }
}
