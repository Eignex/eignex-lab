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
    @Test
    fun `hardware profile shows one SIMD tier without changing compatibility tags`() {
        val profiles = mapOf(
            setOf("arch:x86_64", "simd:avx2") to "x86_64 / AVX2",
            setOf("arch:x86_64", "simd:avx2", "simd:avx512f", "simd:avx512") to "x86_64 / AVX-512",
            setOf("arch:arm64", "simd:neon") to "ARM64 / NEON",
            setOf("arch:arm64", "simd:neon", "simd:sve") to "ARM64 / SVE",
            setOf("arch:arm64", "simd:neon", "simd:sve", "simd:sve2") to "ARM64 / SVE2",
            setOf("arch:arm64", "simd:neon", "simd:sme", "simd:sme2") to "ARM64 / SME2",
        )
        for ((tags, expected) in profiles) assertEquals(expected, NodeTags.hardwareProfile(tags))
        assertTrue(NodeTags.matches(NodeSelector(require = setOf("simd:avx2")), profiles.keys.elementAt(1)))
    }
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
