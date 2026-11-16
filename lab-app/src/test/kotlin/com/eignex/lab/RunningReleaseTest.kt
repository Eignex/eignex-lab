package com.eignex.lab

import com.eignex.lab.web.RunningRelease
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunningReleaseTest {
    @Test fun `a release reports its directory and recorded commit`() {
        val root = Files.createTempDirectory("releases-test")
        val release = Files.createDirectories(root.resolve("releases/fdcb3a79-20261109103330"))
        val jar = Files.createDirectories(release.resolve("lib")).resolve("eignex-lab.jar")
        assertEquals(RunningRelease("fdcb3a79-20261109103330", null, 5), RunningRelease.of(jar, 5))
        Files.writeString(release.resolve("COMMIT"), "fdcb3a79e34130ca0841789e455f86179ea76582\n")
        assertEquals(RunningRelease("fdcb3a79-20261109103330", "fdcb3a79e34130ca0841789e455f86179ea76582", 5), RunningRelease.of(jar, 5))
        Files.writeString(release.resolve("COMMIT"), "not a commit")
        assertNull(RunningRelease.of(jar, 5)!!.commit)
    }

    @Test fun `a build tree is not a release`() {
        val root = Files.createTempDirectory("build-test")
        assertNull(RunningRelease.of(root.resolve("build/install/eignex-lab/lib/eignex-lab.jar"), null))
        assertNull(RunningRelease.of(root.resolve("build/classes/kotlin/main"), null))
    }
}
