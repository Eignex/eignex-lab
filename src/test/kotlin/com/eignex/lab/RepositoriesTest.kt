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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RepositoriesTest {
    @Test
    fun `only Eignex slugs can select a source`() {
        for (repository in listOf("Eignex/koblas", "Eignex/skema", "Eignex/.github")) {
            assertEquals("https://github.com/$repository.git", Repositories.url(repository))
        }
        for (repository in listOf("other/koblas", "Eignex-fork/koblas", "Eignex/../other/repo", "Eignex/..", "Eignex/.",
            "Eignex/koblas.git", "Eignex/koblas/extra", "https://github.com/Eignex/koblas", "git@github.com:Eignex/koblas.git",
            "/tmp/repo", "file:///tmp/repo", "Eignex/koblas%2f..", "Eignex/koblas\n")) {
            assertFailsWith<IllegalArgumentException>(repository) { Repositories.url(repository) }
            assertFailsWith<IllegalArgumentException>(repository) {
                Experiments.validate(ExperimentSpec("bad", repository = repository, profile = ProfileMain(script = "profile.sh")), 4)
            }
        }
    }

    @Test
    fun `mirrors and fingerprints distinguish repositories`() {
        val config = Config(dataDir = Files.createTempDirectory("repos"))
        val profile = ProfileMain(script = "profile.sh")
        val koblas = ExperimentSpec("p", repository = "Eignex/koblas", profile = profile)
        val skema = koblas.copy(repository = "Eignex/skema")

        assertEquals(config.dataDir.resolve("repos/eignex-lab.git"), Repositories.mirror(config.paths, Repositories.DEFAULT))
        assertNotEquals(Repositories.mirror(config.paths, koblas.repository), Repositories.mirror(config.paths, skema.repository))
        assertNotEquals(Experiments.fingerprint(koblas), Experiments.fingerprint(skema))
        assertTrue(Repositories.mirror(config.paths, koblas.repository).startsWith(config.dataDir))
    }

    @Test
    fun `a configured URL cannot override the Eignex source restriction`() {
        // A repository's source takes no configuration at all: whatever LAB_REPO says, it is fetched from its GitHub URL.
        assertEquals(Repositories.url(Repositories.DEFAULT), Repositories.source(Repositories.DEFAULT))
    }

    @Test
    fun `a transferred repository is refused even when its commit is on main`() {
        var gitCalled = false
        val trust = Trust(setOf("rasros"), Repositories.url("Eignex/koblas"),
            git = { gitCalled = true; 0 to "" },
            github = { """{"full_name":"other/koblas"}""" })

        assertFailsWith<IllegalArgumentException> { trust.requireCommit("a".repeat(40), "main") }
        assertEquals(false, gitCalled)
    }

    @Test
    fun `a new job refreshes ownership even when the last job verified the same commit`() {
        var actual = "Eignex/koblas"
        val trust = Trust(setOf("rasros"), Repositories.url(actual), git = { 0 to "" },
            github = { """{"full_name":"$actual"}""" })
        trust.requireCommit("a".repeat(40), "main")
        actual = "other/koblas"

        assertFailsWith<UntrustedCommit> { trust.requireRepository(refresh = true) }
    }
}
