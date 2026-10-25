package com.eignex.lab

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeTagsTest {
    private fun aws() = AwsConfig("eu-north-1", "p", "c7i.2xlarge", 4, 32, "k", "/k.pem", "sg", null, 4, 24, "2.9.7", null)
    private val x86 = CpuCapabilities("x86_64", setOf("avx2", "avx512f"), "test x86", true)
    private val arm = CpuCapabilities("arm64", setOf("neon", "sme", "sme2"), "test arm", true)

    @Test
    fun `ordinary jobs omit selectors and accept the existing host policy`() {
        val spec = ExperimentSpec("ordinary", problems = listOf(mapOf("suite" to "s")))
        assertFalse("nodeTags" in Json.encodeToString(ExperimentSpec.serializer(), spec))
        assertTrue(NodeTags.matches(null, emptySet()))
        assertEquals(Experiments.fingerprint(spec), Experiments.fingerprint(spec.copy(nodeTags = NodeSelector())))
        assertTrue(NodeTags.onDemand(profile = true, null))
        assertFalse(NodeTags.onDemand(profile = false, null))
    }

    @Test
    fun `required and excluded tags match capabilities and arbitrary operator labels`() {
        val tags = NodeTags.observed(x86, setOf("fast-ssd"), cloud = true, spot = true)
        assertTrue(NodeTags.matches(NodeSelector(require = setOf("fast-ssd", "simd:avx512", "spot")), tags))
        assertFalse(NodeTags.matches(NodeSelector(require = setOf("simd:avx2"), exclude = setOf("simd:avx512")), tags))
        assertFalse(NodeTags.matches(NodeSelector(exclude = setOf("spot")), tags))
        assertFailsWith<IllegalArgumentException> { NodeTags.validate(NodeSelector(setOf("spot"), setOf("spot"))) }
        assertFailsWith<IllegalArgumentException> { NodeTags.validate(NodeSelector(setOf("arch:arm64", "arch:x86_64"))) }
    }

    @Test
    fun `operator labels cannot fabricate CPU features or market facts`() {
        val tags = NodeTags.observed(arm, setOf("simd:avx512", "spot", "arch:x86_64", "apple-m4"))
        assertTrue(NodeTags.matches(NodeSelector(require = setOf("apple-m4", "simd:sme2")), tags))
        assertFalse("spot" in tags || "arch:x86_64" in tags || "simd:avx512" in tags)
        assertFalse("simd:sve" in tags)
        assertFalse(NodeTags.matches(NodeSelector(exclude = setOf("simd:avx512")), CpuCapabilities().tags))
    }

    @Test
    fun `tag order does not change experiment identity`() {
        val spec = ExperimentSpec("p", profile = ProfileMain(script = "run.sh"), nodeTags = NodeSelector(linkedSetOf("a", "b")))
        assertEquals(Experiments.fingerprint(spec), Experiments.fingerprint(spec.copy(nodeTags = NodeSelector(linkedSetOf("b", "a")))))
    }

    @Test
    fun `a local queue skips incompatible jobs without blocking compatible work or causing a yield`() {
        val config = Config(dataDir = Files.createTempDirectory("tag queue"), cpuCapabilities = x86)
        val store = Store(config.database)
        fun queue(name: String, priority: Int, selector: NodeSelector?) = store.create(name, "main", emptyList(), priority = priority,
            experiment = ExperimentSpec(name, problems = listOf(mapOf("suite" to "s")), host = Experiments.AUTO_HOST, nodeTags = selector))
        queue("arm", 100, NodeSelector(require = setOf("arch:arm64")))
        val local = queue("local", 1, null)

        assertEquals(local, store.localNext(nodeTags = config.localNodeTags))
        assertEquals(local, store.next(nodeTags = config.localNodeTags)?.id)
        assertFalse(store.shouldYield(local, config.localNodeTags))
        queue("local higher", 2, NodeSelector(require = setOf("arch:x86_64")))
        assertTrue(store.shouldYield(local, config.localNodeTags))
    }

    @Test
    fun `placement chooses cloud only when the local node does not match and refuses unsatisfied selectors`() {
        val root = Files.createTempDirectory("tag placement")
        val config = Config(dataDir = root, cpuCapabilities = arm)
        root.resolve("aws").toFile().mkdirs()
        root.resolve("aws/aws.properties").writeText("region=eu-north-1\nkeyName=k\nkeyFile=/k\nsecurityGroup=sg\ntags.c7i=arch:x86_64,simd:avx2,simd:avx512\n")
        val spec = ExperimentSpec("profile", profile = ProfileMain(script = "run.sh"), nodeTags = NodeSelector(require = setOf("simd:avx512")))

        assertEquals(Experiments.AWS_HOST, Placement.host(spec, spec.name, config))
        assertFailsWith<IllegalArgumentException> { Placement.host(spec.copy(nodeTags = NodeSelector(require = setOf("missing-host"))), spec.name, config) }
        assertEquals(Experiments.LAB_HOST, Placement.host(spec.copy(nodeTags = NodeSelector(require = setOf("simd:sme"))), spec.name, config))
    }

    @Test
    fun `cloud sizes support multiple architectures and preserve Graviton core counts`() {
        val aws = aws().copy(instanceTypes = listOf("c7i.2xlarge", "c7g.2xlarge"),
            instanceTags = mapOf("c7i" to setOf("arch:x86_64", "simd:avx512"), "c7g" to setOf("arch:arm64", "simd:sve")))
        val size = checkNotNull(aws.sizeFor(8, NodeSelector(require = setOf("arch:arm64"))))
        assertEquals("c7g.2xlarge", size.type)
        assertEquals(8, size.cores)
        assertEquals(4, aws.sizes.first { it.type == "c7i.2xlarge" }.cores)
        assertFalse(AwsBuildCache.archives("bucket", "sha", "arm64").any { it.endsWith("plain/sha.tar.zst") })
    }

    @Test
    fun `provider architecture metadata selects ARM without operator hardware labels`() {
        val root = Files.createTempDirectory("provider metadata")
        val properties = root.resolve("aws.properties")
        properties.writeText("region=eu-north-1\nkeyName=k\nkeyFile=/k\nsecurityGroup=sg\ninstanceTypes=c7g.2xlarge\narchitecture.c7g=arm64\nthreadsPerCore.c7g=1\n")
        val config = checkNotNull(AwsConfig.load(properties))
        val size = checkNotNull(config.sizeFor(8, NodeSelector(require = setOf("arch:arm64"))))
        assertEquals("c7g.2xlarge", size.type)
        assertEquals(8, size.cores)
        assertTrue("arch:arm64" in size.tags(false))
    }

    @Test
    fun `submission allows cloud only core capacity and refuses unavailable tags`() = testApplication {
        val root = Files.createTempDirectory("tag api")
        val config = Config(dataDir = root, cpuCapabilities = arm, cores = 2, maxParallel = 2, passwordHash = null)
        root.resolve("aws").toFile().mkdirs()
        root.resolve("aws/aws.properties").writeText("region=eu-north-1\nkeyName=k\nkeyFile=/k\nsecurityGroup=sg\ntags.c7i=simd:avx512\n")
        val store = Store(config.database)
        application { api(config, store, HostReport("test", 2, "java", "vector", true, emptyList())) }
        suspend fun submit(tag: String) = client.post("/experiments") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"cloud","description":"hardware run","problems":{"suite":"s"},"base":{"ref":"0123456789abcdef","processors":"4"},"nodeTags":{"require":["$tag"]}}""")
        }
        assertEquals(HttpStatusCode.Created, submit("simd:avx512").status)
        val job = store.active().single()
        assertEquals(Experiments.AWS_HOST, job.experiment!!.host)
        assertEquals("4", job.experiment!!.base["processors"])
        assertEquals(HttpStatusCode.BadRequest, submit("missing-node").status)
        assertEquals(1, store.active().size)
    }

    @Test
    fun `node picking and provisioning respect market and architecture selectors`() {
        val selector = NodeSelector(require = setOf("arch:arm64"), exclude = setOf("spot"))
        val demand = Demand(1, 1.0, 1, nodeTags = selector)
        val types = listOf(InstanceSize("c7i.2xlarge", 8), InstanceSize("c7g.2xlarge", 8, "arm64", emptySet(), 1))
        val plan = checkNotNull(NodePool.launchPlan(types, listOf(demand), 0, 32, 16))
        assertEquals("c7g.2xlarge", plan.size.type)
        assertTrue(plan.onDemand)
        assertEquals(null, NodePool.pick(NodeShape(8, false, setOf("arch:arm64", "spot")), emptyList(), listOf(demand)))
        assertEquals(NodeWork.Claim(1), NodePool.pick(NodeShape(8, true, setOf("arch:arm64", "on-demand")), emptyList(), listOf(demand)))
        val spotProfile = Demand(2, 1.0, 1, profile = true, nodeTags = NodeSelector(require = setOf("spot")))
        assertEquals(NodeWork.Claim(2), NodePool.pick(NodeShape(8, false, setOf("spot")), emptyList(), listOf(spotProfile)))
        assertTrue(checkNotNull(NodePool.launchPlan(types, listOf(spotProfile), 0, 32, 16)).spotOnly)
    }

    @Test
    fun `explicit requirements provision a missing shape while ordinary jobs keep the overflow gate`() {
        val demand = Demand(1, 0.01, 1, profile = true, nodeTags = NodeSelector(require = setOf("arch:arm64")))
        val local = NodeTags.observed(x86, emptySet())
        assertEquals(demand, NodePool.dedicated(listOf(demand), local, emptyList()))
        assertEquals(null, NodePool.dedicated(listOf(demand.copy(nodeTags = null)), local, emptyList()))
        assertEquals(null, NodePool.dedicated(listOf(demand), local, listOf(NodeShape(8, true, setOf("arch:arm64", "on-demand")))))
    }

    @Test
    fun `node specific operator labels on an active node can satisfy submission placement`() {
        val root = Files.createTempDirectory("node labels")
        val config = Config(dataDir = root, cpuCapabilities = arm)
        root.resolve("aws").toFile().mkdirs()
        root.resolve("aws/aws.properties").writeText("region=eu-north-1\nkeyName=k\nkeyFile=/k\nsecurityGroup=sg\n")
        val spec = ExperimentSpec("reserved", profile = ProfileMain(script = "run.sh"), nodeTags = NodeSelector(require = setOf("reserved-node")))
        val report = NodePoolReport(now(), 0.0, 0, listOf(NodeReport("i-one", "c7i.2xlarge", "on-demand", "idle", tags = setOf("reserved-node", "cloud", "on-demand"))))
        root.resolve("aws/${AwsWorker.NODES_FILE}").writeText(Json.encodeToString(NodePoolReport.serializer(), report))
        assertEquals(Experiments.AWS_HOST, Placement.host(spec, spec.name, config))
        assertEquals(4, Placement.cloudSizes(spec, config).single().cores)
        root.resolve("aws/aws.properties").toFile().appendText("tags.c7i=other-new-label\n")
        assertTrue("other-new-label" in config.aws!!.sizes.first().labels)
    }

    @Test
    fun `a runner refuses a mismatched node even if a job was queued without validation`() {
        val root = Files.createTempDirectory("wrong node")
        val config = Config(dataDir = root, cpuCapabilities = arm)
        val store = Store(config.database)
        val spec = ExperimentSpec("x86", profile = ProfileMain(script = "run.sh"), nodeTags = NodeSelector(require = setOf("simd:avx512")))
        val id = store.create(spec.name, "main", emptyList(), experiment = spec)
        var fetched = false
        Runner(config, store, sourceUrl = { fetched = true; error("must not fetch") }).runJob(checkNotNull(store.next()))
        assertEquals(Status.FAILED, store.job(id)?.status)
        assertFalse(fetched)
    }

    @Test
    fun `ARM launches request an ARM image and bootstrap uses architecture specific installers`() {
        val cli = object : AwsCli(aws()) {
            override fun call(vararg args: String): String { assertTrue(args.any { "/arm64/" in it }); return "ami-arm" }
        }
        assertEquals("ami-arm", cli.image("arm64"))
        val script = AwsWorker.bootstrap(aws())
        assertContains(script, "jdk_arch=aarch64")
        assertContains(script, "aws_arch=aarch64")
        val process = ProcessBuilder("bash", "-n").start()
        process.outputStream.bufferedWriter().use { it.write(script) }
        assertEquals(0, process.waitFor())
    }

    @Test
    fun `macOS probing detects SME independently from ordinary SVE`() {
        val root = Files.createTempDirectory("mac probe")
        try {
            root.resolve("uname").apply { writeText("#!/bin/sh\nif [ \"\$1\" = -m ]; then echo arm64; else echo Darwin; fi\n"); toFile().setExecutable(true) }
            root.resolve("sysctl").apply {
                writeText("#!/bin/sh\ncase \"\$2\" in hw.optional.neon|hw.optional.arm.FEAT_SME|hw.optional.arm.FEAT_SME2) echo 1;; *) exit 1;; esac\n")
                toFile().setExecutable(true)
            }
            val process = ProcessBuilder("bash", "-c", NodeTags.probeScript).apply { environment()["PATH"] = "$root:${System.getenv("PATH")}" }.start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor())
            val tags = NodeTags.parse(output).tags
            assertTrue("simd:sme" in tags && "simd:sme2" in tags && "simd:neon" in tags)
            assertFalse("simd:sve" in tags || "hardware:unknown" in tags)
        } finally { root.toFile().deleteRecursively() }
    }
}
