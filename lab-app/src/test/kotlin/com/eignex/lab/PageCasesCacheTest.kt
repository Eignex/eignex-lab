package com.eignex.lab

import com.eignex.lab.analysis.Outcome
import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import java.nio.file.Files
import kotlin.test.*

class PageCasesCacheTest {
    private fun planned(store: Store): Long {
        val id = store.create("page", "main", emptyList(), experiment = ExperimentSpec("page", emptyList()))
        store.plan(id, listOf(PlannedArm(Arm("a", emptyMap()), "sha")), listOf(Problem("s", "p")),
            listOf(Case(0, 0, null)), listOf("true" to 1L))
        return id
    }

    private fun record(time: Int) = """{"kind":"satisfy","feasible":true,"timeToBestMs":$time,"budgetMs":1000}"""

    @Test fun `active pages see new records and completed pages reuse them`() {
        val store = Store(Files.createTempDirectory("page-cases").resolve("lab.db"))
        val id = planned(store)
        assertNull(store.plans.pageCases(id).single().record)
        store.caseRecord(id, 0, record(10))
        assertEquals(10, Outcome.of(store.plans.pageCases(id).single().record)?.timeMs)
        store.caseRecord(id, 0, record(20))
        store.commandFinished(id, 0, 0)
        store.finish(id, Status.DONE)
        val done = store.plans.pageCases(id)
        assertEquals(20, Outcome.of(done.single().record)?.timeMs)
        assertSame(done, store.plans.pageCases(id))
    }

    @Test fun `another store rechecks retries and deletes without leaving a stale page`() {
        val file = Files.createTempDirectory("page-cases").resolve("lab.db")
        val writer = Store(file)
        val id = planned(writer)
        writer.caseRecord(id, 0, record(10))
        writer.finish(id, Status.FAILED)
        val reader = Store(file)
        val before = reader.plans.pageCases(id)
        assertSame(before, reader.plans.pageCases(id))
        writer.plans.caseCheck(id, 0, """{"policy":"test","state":"invalid","reason":"rejected"}""")
        val checked = reader.plans.pageCases(id)
        assertNotSame(before, checked)
        assertEquals(0, Outcome.of(checked.single().record)?.rank)
        assertSame(checked, reader.plans.pageCases(id))

        assertTrue(writer.retry(id))
        writer.caseRecord(id, 0, record(30))
        val retried = reader.plans.pageCases(id)
        assertEquals(30, Outcome.of(retried.single().record)?.timeMs)
        assertNotSame(retried, reader.plans.pageCases(id), "active jobs are never cached")
        writer.commandFinished(id, 0, 0)
        writer.finish(id, Status.DONE)
        val done = reader.plans.pageCases(id)
        assertSame(done, reader.plans.pageCases(id))
        assertTrue(writer.deleteJob(id))
        assertTrue(reader.plans.pageCases(id).isEmpty())
    }

    @Test fun `the finished job cache evicts the least recently used job`() {
        val store = Store(Files.createTempDirectory("page-cases").resolve("lab.db"))
        val ids = List(9) { planned(store).also { store.finish(it, Status.DONE) } }
        val first = store.plans.pageCases(ids[0])
        val second = store.plans.pageCases(ids[1])
        ids.subList(2, 8).forEach { store.plans.pageCases(it) }
        assertSame(first, store.plans.pageCases(ids[0]))
        store.plans.pageCases(ids[8])
        assertSame(first, store.plans.pageCases(ids[0]))
        assertNotSame(second, store.plans.pageCases(ids[1]))
    }

    @Test fun `oversized witnesses are read without retaining them in the cache`() {
        val store = Store(Files.createTempDirectory("page-cases").resolve("lab.db"))
        val id = planned(store)
        store.caseRecord(id, 0, """{"finalWitness":"${"x".repeat(16 * 1024 * 1024)}"}""")
        store.finish(id, Status.DONE)
        val first = store.plans.pageCases(id)
        val second = store.plans.pageCases(id)
        assertEquals(first, second)
        assertNotSame(first, second)
    }
    @Test fun `combined retained records stay within the cache size budget`() {
        val store = Store(Files.createTempDirectory("page-cases").resolve("lab.db"))
        val ids = List(2) { planned(store).also { id ->
            store.caseRecord(id, 0, """{"finalWitness":"${"x".repeat(8 * 1024 * 1024)}"}""")
            store.finish(id, Status.DONE)
        } }
        val first = store.plans.pageCases(ids[0])
        assertSame(first, store.plans.pageCases(ids[0]))
        val second = store.plans.pageCases(ids[1])
        assertSame(second, store.plans.pageCases(ids[1]))
        assertNotSame(first, store.plans.pageCases(ids[0]))
    }

}
