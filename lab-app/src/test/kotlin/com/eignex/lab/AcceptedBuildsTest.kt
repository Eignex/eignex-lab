package com.eignex.lab

import com.eignex.lab.util.*
import com.eignex.lab.domain.*
import com.eignex.lab.worker.*
import com.eignex.lab.nomad.*

import java.nio.file.Files
import kotlin.test.*

class AcceptedBuildsTest {
    private val root = Files.createTempDirectory("accepted-builds")
    private val commit = "a".repeat(40)
    private val source = WorkerBundle("b".repeat(64), listOf(InputFile("fixture", "c".repeat(64), 1)), Repositories.DEFAULT, commit,
        "linux", "amd64", "jdk25", build = WorkerBuild(benchManifest = "bench.json"))
    private val spec = ExperimentSpec("accepted", bench = BenchSpec("bench.json"))
    private val recipe = WorkManifest("prepare", "release", spec, Experiments.arms(spec).map { PlannedArm(it, commit) }, emptyList(),
        listOf(source), ResourceBudget(1000, 512), reusableBuilds = emptyList(), preparationTimeoutSec = 60)

    private fun accepted(sha: Char, identity: Char): PreparationResult {
        val bundle = source.copy(sha256 = sha.toString().repeat(64), files = listOf(InputFile("build/out", "e".repeat(64), 2)), build = null)
        return PreparationResult(emptyList(), listOf(bundle), artifacts = mapOf(preparationArtifact(bundle) to bundle.sha256),
            builds = listOf(PreparedBuild(commit, "linux", "amd64", identity.toString().repeat(64), "commit:$commit")))
    }

    @AfterTest fun cleanup() = deleteTree(root)

    @Test fun `builds are found by recipe while young and replaced per worker identity`() {
        val index = AcceptedBuilds(root, maxAgeMs = 1000)
        val key = buildRecipeKey(source)
        assertNull(index.find(key, 0))
        index.remember(1, recipe, accepted('1', 'f'), { true }, at = 100)
        assertEquals(ReusableBuild("f".repeat(64), accepted('1', 'f').bundles.single(), "commit:$commit"), index.find(key, 200))
        // Another platform, toolchain or recipe is another key; budgets and timeouts are not part of it.
        assertNotEquals(key, buildRecipeKey(source.copy(arch = "arm64")))
        assertNotEquals(key, buildRecipeKey(source.copy(build = WorkerBuild(listOf("assemble"), benchManifest = "bench.json"))))
        assertEquals(key, buildRecipeKey(source.copy(build = source.build!!.copy(timeoutSec = 60))))

        // A newer worker identity is offered first; the same identity keeps only its newest archive.
        index.remember(2, recipe, accepted('2', 'd'), { true }, at = 300)
        assertEquals("d".repeat(64), index.find(key, 400)?.identity)
        index.remember(3, recipe, accepted('3', 'f'), { true }, at = 500)
        assertEquals("3".repeat(64), index.find(key, 600)?.bundle?.sha256)

        // Reuse never renews an archive's age: the regional store expires it from its first upload.
        index.remember(4, recipe, accepted('3', 'f'), { true }, at = 1400)
        assertNull(index.find(key, 1600), "an archive first accepted at 500 is too old at 1600")
        assertNull(AcceptedBuilds(root, maxAgeMs = 1000).find(key, 2000))
    }

    @Test fun `only regional archives that are the accepted bundle are recorded`() {
        val index = AcceptedBuilds(root)
        val key = buildRecipeKey(source)
        index.remember(1, recipe, accepted('1', 'f'), { false })
        assertNull(index.find(key))
        val result = accepted('1', 'f')
        index.remember(1, recipe, result.copy(artifacts = emptyMap()), { true })
        assertNull(index.find(key))
        index.remember(1, recipe, result, { error("store unreachable") })
        assertNull(index.find(key))
        index.remember(1, recipe, result, { true })
        assertNotNull(index.find(key))
    }
}
