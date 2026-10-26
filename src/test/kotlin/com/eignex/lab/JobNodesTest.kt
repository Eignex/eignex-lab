package com.eignex.lab

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.*

class JobNodesTest {
    private val host = HostReport("Mac/Arm64", 10, "java", "engine", true, emptyList())
    private val budget = ResourceBudget(2000, 2048, 2)
    private val pool = CapacityPool("avx2", "linux-avx2-spot", 16, 32768, 8, setOf("arch:x86_64", "simd:avx2"))
    private val config = Config(dataDir = Files.createTempDirectory("job-nodes"), passwordHash = null)
    private val store = Store(config.database)

    private fun job(): Long {
        val spec = ExperimentSpec("preparing", host = Experiments.AUTO_HOST)
        return store.create(spec.name, "main", emptyList(), experiment = spec)
    }

    private fun bind(job: Long, session: String, allocation: String, node: String, local: Boolean = false) {
        val token = ExecutionStore.token()
        val payload = """{"Constraints":[{"LTarget":"${'$'}{node.class}","RTarget":"${if (local) "lab-native" else pool.nodeClass}"},{"LTarget":"${'$'}{attr.kernel.name}","RTarget":"${if (local) "darwin" else "linux"}"}],"Env":{"secret":"private-worker-credential"}}"""
        store.execution.createSession(session, job, "prepare", "release", budget, token, payload)
        store.execution.bind(session, token, allocation, node, "release")
    }

    @Test fun `queue and job page show the preparation node and link to its hardware`() = testApplication {
        val id = job()
        bind(id, "prep", "allocation", "nomad-node-uuid")
        val report = CapacitySnapshot(now(), 0, CapacityDecision(mapOf(pool.id to 1), emptyList(), emptyList(), emptyMap()),
            listOf(CapacityNode("avx2:i-worker", pool.id, CapacityNodeState.READY, listOf(CapacityReservation("allocation", id, budget)))), emptyMap(), listOf(pool))
        Files.createDirectories(config.nomadCapacityReport.parent)
        Files.writeString(config.nomadCapacityReport, Json.encodeToString(report))
        application { api(config, store, host) }

        for (path in listOf("/", "/jobs/$id")) {
            val response = client.get(path) { accept(ContentType.Text.Html) }
            assertEquals(HttpStatusCode.OK, response.status)
            val page = response.bodyAsText()
            assertContains(page, "avx2:i-worker</a>")
            assertContains(page, "href=\"/nodes#node-avx2%3Ai-worker\"")
            assertContains(page, "title=\"Nomad node nomad-node-uuid\"")
            assertContains(page, "x86_64 / AVX2")
            assertFalse(page.contains("private-worker-credential"))
        }
        assertContains(client.get("/nodes").bodyAsText(), "id=\"node-avx2:i-worker\"")
    }

    @Test fun `active assignments deduplicate nodes omit stopped sessions and survive a missing capacity report`() = testApplication {
        val id = job()
        bind(id, "old", "old-allocation", "old-node")
        store.execution.loseSession("old", "infrastructure:spot")
        bind(id, "cloud", "cloud-allocation", "cloud-node-uuid")
        bind(id, "same-node", "second-allocation", "cloud-node-uuid")
        bind(id, "native", "native-allocation", "native-node-uuid", local = true)
        val waiting = job()
        application { api(config, store, host) }

        val nodes = jobNodes(config, store, listOf(id, waiting))
        assertEquals(listOf("native-node-uuid", "cloud-node-uuid"), nodes.getValue(id).map { it.id })
        assertNull(nodes[waiting])
        val page = client.get("/").bodyAsText()
        assertContains(page, "local + cloud</span>")
        assertContains(page, "native · native-n</a>")
        assertContains(page, "href=\"/nodes#local-node\"")
        assertContains(page, "linux-avx2-spot · cloud-no</a>")
        assertContains(page, "title=\"Nomad node cloud-node-uuid\"")
        assertContains(page, "waiting for a node")
        assertFalse(page.contains("old-node"))
    }
}
