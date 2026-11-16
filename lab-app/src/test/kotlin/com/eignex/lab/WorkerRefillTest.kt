package com.eignex.lab

import com.eignex.lab.util.*
import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.worker.*

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** A solve session's worker keeps its case slots full: it claims the next row as soon as one ends ([WorkerSession.refill]). */
class WorkerRefillTest {
    /** A coordinator handing out [rows] one-row claims of [arms] cases each, echoing claim names when [names] is set. */
    private class Coordinator(private val rows: Int, private val arms: Int, private val names: Boolean = true) : AutoCloseable {
        val claims = mutableListOf<String?>()
        private var next = 0
        private val byName = mutableMapOf<String, IterationClaim>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/worker/v1/sessions/session/claim") { exchange ->
                val request = Json.decodeFromString<ClaimRequest>(String(exchange.requestBody.readAllBytes()))
                val reply = synchronized(this@Coordinator) {
                    claims += request.claim
                    request.claim?.takeIf { names }?.let(byName::get) ?: if (next == rows) null else {
                        val row = next++
                        IterationClaim(AttemptOwner("session", "allocation", "row-$row", 1, "t"), List(arms) { row * arms + it }, 0, arms,
                            request.claim.takeIf { names }).also { claim -> request.claim?.let { byName[it] = claim } }
                    }
                }
                if (reply == null) exchange.sendResponseHeaders(204, -1)
                else Json.encodeToString(reply).toByteArray().let { exchange.sendResponseHeaders(200, it.size.toLong()); exchange.responseBody.write(it) }
                exchange.close()
            }
            start()
        }
        val api = URI("http://127.0.0.1:${server.address.port}")
        override fun close() = server.stop(0)
    }

    private val root = Files.createTempDirectory("worker-refill")
    private fun worker(coordinator: Coordinator) = WorkerSession(coordinator.api, "session", "token", "allocation",
        root.resolve("identity").also { Files.writeString(it, "fixture") }, "release", root, root.resolve("unused.sock"), "unused")

    @AfterTest fun clean() = deleteTree(root)

    @Test fun `a freed slot takes the next row while a slow row still runs`() {
        Coordinator(rows = 4, arms = 2).use { coordinator ->
            val slow = CountDownLatch(1)
            val started = ConcurrentHashMap<String, Long>()
            val finished = ConcurrentHashMap<String, Long>()
            val running = AtomicInteger(); val most = AtomicInteger()
            worker(coordinator).refill(4) { claim ->
                most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                started[claim.owner.iteration] = System.nanoTime()
                try {
                    if (claim.owner.iteration == "row-0") assertTrue(slow.await(10, TimeUnit.SECONDS)) else Thread.sleep(50)
                    if (claim.owner.iteration == "row-3") slow.countDown()
                    finished[claim.owner.iteration] = System.nanoTime()
                } finally { running.decrementAndGet() }
                true
            }
            assertEquals(setOf("row-0", "row-1", "row-2", "row-3"), finished.keys)
            assertEquals(2, most.get(), "four slots run two two-arm rows at once")
            // Rows 2 and 3 started in slots row 1 freed, while row 0 still held its own.
            assertTrue(started.getValue("row-3") < finished.getValue("row-0"))
            assertTrue(coordinator.claims.all { it != null && ExecutionStore.CLAIM_ID.matches(it) })
            assertEquals(4, coordinator.claims.take(4).toSet().size, "each claim that took a row had a fresh name")
        }
    }

    @Test fun `a coordinator that ignores claim names gets one attempt at a time`() {
        Coordinator(rows = 3, arms = 1, names = false).use { coordinator ->
            val running = AtomicInteger(); val most = AtomicInteger(); val ran = AtomicInteger()
            worker(coordinator).refill(4) { _ ->
                most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                Thread.sleep(30); running.decrementAndGet(); ran.incrementAndGet(); true
            }
            assertEquals(3, ran.get())
            assertEquals(1, most.get())
        }
    }

    @Test fun `a failed attempt stops new claims but lets running rows finish before the session ends`() {
        Coordinator(rows = 6, arms = 1).use { coordinator ->
            val failing = CountDownLatch(1)
            val completed = ConcurrentHashMap.newKeySet<String>()
            val error = assertFailsWith<IllegalStateException> {
                worker(coordinator).refill(2) { claim ->
                    when (claim.owner.iteration) {
                        "row-0" -> { failing.await(); error("disk full") }
                        "row-1" -> { failing.countDown(); Thread.sleep(200); completed += claim.owner.iteration; true }
                        else -> { completed += claim.owner.iteration; true }
                    }
                }
            }
            assertEquals("disk full", error.message)
            assertTrue("row-1" in completed, "the other running row still committed")
            assertTrue(coordinator.claims.size <= 3, "no new rows after the failure: ${coordinator.claims.size}")
        }
    }

    @Test fun `attempts running at once take distinct workspace slots and a lone attempt keeps slot zero`() {
        val slots = IterationExecutor.SlotNumbers()
        assertEquals(listOf(0, 1), slots.acquire("a", 2))
        assertEquals(listOf(2), slots.acquire("a", 1))
        assertEquals(listOf(0), slots.acquire("b", 1), "another commit's trees are numbered apart")
        slots.release("a", listOf(0, 1))
        assertEquals(listOf(0, 1), slots.acquire("a", 2), "freed trees are reused, warm")
        slots.release("a", listOf(0, 1, 2)); slots.release("b", listOf(0))
        assertEquals(listOf(0), slots.acquire("a", 1))
    }

    @Test fun `an attempt that ends the session stops claiming after the running rows`() {
        Coordinator(rows = 5, arms = 1).use { coordinator ->
            val ran = ConcurrentHashMap.newKeySet<String>()
            worker(coordinator).refill(1) { claim -> ran += claim.owner.iteration; claim.owner.iteration != "row-1" }
            assertEquals(setOf("row-0", "row-1"), ran)
        }
    }
}
