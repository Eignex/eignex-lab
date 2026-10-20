package com.eignex.lab

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManualLaunchTest {
    private val host = HostReport("Test/Arm64", 4, "java", "engine", true, emptyList())
    private val now = 10_000_000L

    /** A lab with cloud settings (extra `aws.properties` lines in [aws]), or none when [aws] is null. */
    private fun lab(aws: String? = ""): Config {
        val config = Config(dataDir = Files.createTempDirectory("lab"), passwordHash = null, cores = 8)
        if (aws != null) {
            config.dataDir.resolve("aws").toFile().mkdirs()
            config.dataDir.resolve("aws/aws.properties").toFile()
                .writeText("region=eu-north-1\nkeyName=k\nkeyFile=/k.pem\nsecurityGroup=sg-1\n$aws")
        }
        return config
    }

    private fun report(
        nodes: Int = 0, busy: Int = 0, update: Boolean = false, size: String? = "c7i.2xlarge", at: Long = now, manual: Boolean = false,
    ) = NodePoolReport(at, 0.0, 0, List(nodes) { NodeReport("i-$it", "c7i.2xlarge", "spot", "idle", manual = manual) },
        updateWaiting = update, busyVcpus = busy, manualSize = size)

    private fun write(config: Config, report: NodePoolReport) =
        config.dataDir.resolve("aws/nodes.json").toFile().writeText(Json.encodeToString(NodePoolReport.serializer(), report))

    @Test
    fun `a manual start is requested with the size the queue calls for and spot first, unless it names them`() {
        val config = lab()
        val aws = config.aws!!

        val (status, message) = ManualLaunch.request(config, aws, report(), NodeStart(), now)
        val waiting = ManualLaunch.take(ManualLaunch.file(config))
        val named = ManualLaunch.request(config, aws, report(), NodeStart("c7i.4xlarge", "on-demand"), now)
        val other = ManualLaunch.take(ManualLaunch.file(config))

        assertEquals(202, status)
        assertContains(message, "a c7i.2xlarge spot-first node, the size the queue calls for")
        assertEquals(ManualLaunchRequest(null, false, now), waiting)
        assertFalse(ManualLaunch.file(config).exists())
        assertEquals(202, named.first)
        assertEquals(ManualLaunchRequest("c7i.4xlarge", true, now), other)
    }

    @Test
    fun `a manual start is refused at maxInstances, with the quota full, while an update waits or one is already asked`() {
        val config = lab("maxInstances=2\n")
        val aws = config.aws!!
        fun ask(report: NodePoolReport?, start: NodeStart = NodeStart()) = ManualLaunch.request(config, aws, report, start, now)

        val atMax = ask(report(nodes = 2, busy = 16))
        val quota = ask(report(nodes = 1, busy = 24), NodeStart("c7i.4xlarge"))
        val none = ask(report(nodes = 1, busy = 30, size = null))
        val update = ask(report(update = true))
        val stale = ask(report(at = now - 5 * 60_000))
        val missing = ask(null)
        val unknown = ask(report(), NodeStart("m7i.large"))
        val market = ask(report(), NodeStart(market = "reserved"))
        assertEquals(202, ask(report()).first)
        val twice = ask(report())

        assertEquals(409, atMax.first)
        assertContains(atMax.second, "at maxInstances (2 nodes up or launching)")
        assertContains(quota.second, "vCPU quota full: a c7i.4xlarge needs 16 vCPUs, 8 of 32 are left")
        assertContains(none.second, "vCPU quota full: no size fits the 2 vCPUs left of 32")
        assertContains(update.second, "lab update waiting")
        assertEquals(listOf(409, 409, 409), listOf(update.first, stale.first, missing.first))
        assertContains(stale.second, "is the runner up?")
        assertEquals(listOf(400, 400), listOf(unknown.first, market.first))
        assertContains(unknown.second, "size must be one of c7i.2xlarge, c7i.4xlarge, c7i.8xlarge")
        assertEquals(409, twice.first)
        assertContains(twice.second, "already requested")
        // Right after any launch, a second press is refused as a repeat of the first.
        val repeat = ManualLaunch.request(lab("maxInstances=2\n"), aws, report().copy(lastLaunch = now - 30_000), NodeStart(), now)
        assertEquals(409, repeat.first)
        assertContains(repeat.second, "a node was launched 30 s ago")
        assertEquals(202, ManualLaunch.request(lab("maxInstances=2\n"), aws, report().copy(lastLaunch = now - ManualLaunch.REPEAT_MS), NodeStart(), now).first)
    }

    @Test
    fun `the worker checks a manual request again against the pool as it stands, and drops an old one`() {
        val aws = lab("maxInstances=2\n").aws!!
        val small = aws.sizes.first()
        val request = ManualLaunchRequest(null, false, now)

        assertEquals(ManualLaunchPlan(small, false), ManualLaunch.decide(request, now, aws, small, 0, 32, false).getOrNull())
        assertEquals(ManualLaunchPlan(aws.sizes[1], true),
            ManualLaunch.decide(ManualLaunchRequest("c7i.4xlarge", true, now), now, aws, small, 1, 24, false).getOrNull())
        assertContains(ManualLaunch.decide(request, now, aws, small, 2, 32, false).exceptionOrNull()!!.message!!, LaunchGate.AT_MAX)
        assertContains(ManualLaunch.decide(request, now, aws, small, 0, 32, true).exceptionOrNull()!!.message!!, LaunchGate.UPDATE_WAITING)
        assertContains(ManualLaunch.decide(request, now, aws, null, 1, 4, false).exceptionOrNull()!!.message!!, LaunchGate.QUOTA_FULL)
        assertContains(ManualLaunch.decide(request, now + ManualLaunch.EXPIRY_MS + 1, aws, small, 0, 32, false).exceptionOrNull()!!.message!!,
            "expired")
    }

    @Test
    fun `a manual launch is the gate's last launch, so the cooldown holds the next automatic one`() {
        val gate = LaunchGate()
        val minute = 60_000L
        gate.shouldLaunch(0, 50.0, 0, 10.0, room = true, sustainMs = 10 * minute, cooldownMs = 5 * minute)

        gate.manualLaunch(minute)

        assertEquals(listOf<Long?>(minute, null), listOf(gate.lastLaunch, gate.since))
        assertFalse(gate.shouldLaunch(minute, 1000.0, 1, 10.0, room = true, sustainMs = 0, cooldownMs = 5 * minute))
        assertEquals("cooldown 4 min", gate.blocker(2 * minute, 1000.0, 1, 10.0, null, 0, 5 * minute))
        assertTrue(gate.shouldLaunch(7 * minute, 1000.0, 1, 10.0, room = true, sustainMs = 0, cooldownMs = 5 * minute))
    }

    @Test
    fun `POST cloud nodes requests a node, refuses without cloud settings, and refuses a cross-site form`() {
        val config = lab()
        val bare = lab(aws = null)
        write(config, report(at = System.currentTimeMillis()))
        testApplication {
            application { api(config, Store(config.database), host) }
            val accepted = client.post("/cloud/nodes") {
                header(HttpHeaders.ContentType, "application/json")
                setBody("""{"market": "spot"}""")
            }
            val crossSite = client.post("/cloud/nodes") { header("Sec-Fetch-Site", "cross-site") }

            assertEquals(HttpStatusCode.Accepted, accepted.status)
            assertContains(accepted.bodyAsText(), "manual launch requested")
            val waiting = ManualLaunch.take(ManualLaunch.file(config))!!
            assertEquals(listOf<Any?>(null, false), listOf(waiting.size, waiting.onDemand))
            assertEquals(HttpStatusCode.Forbidden, crossSite.status)
            assertFalse(ManualLaunch.file(config).exists())
        }
        testApplication {
            application { api(bare, Store(bare.database), host) }
            val refused = client.post("/cloud/nodes")

            assertEquals(HttpStatusCode.Conflict, refused.status)
            assertContains(refused.bodyAsText(), "the lab has no cloud settings")
        }
    }

    @Test
    fun `the queue page has the start button only with cloud settings, and marks a manual node`() {
        val config = lab()
        write(config, report(nodes = 1, manual = true, size = "c7i.4xlarge"))

        val summary = workSummary(config, emptyList(), emptyMap())
        val none = workSummary(lab(aws = null), emptyList(), emptyMap())

        assertContains(summary, "Start a node</button>")
        assertContains(summary, "act('/cloud/nodes'")
        assertContains(summary, "2xlarge spot</span> <span title=\"started by hand\">manual</span> idle")
        // The size the queue calls for is offered first.
        assertTrue(summary.indexOf("value=\"c7i.4xlarge\"") < summary.indexOf("value=\"c7i.2xlarge\""))
        assertFalse("Start a node" in none)
        assertContains(workSummary(lab(), emptyList(), emptyMap()), "no pool report yet <span class=\"start-node\">")
        assertNull(ManualLaunch.take(ManualLaunch.file(config)))
    }
}
