package com.eignex.lab

import org.gradle.testkit.runner.GradleRunner
import java.nio.file.Files
import kotlin.test.*

class WorkerBuildPolicyTest {
    @Test fun `runtime builds omit standard and custom tests and their compilation dependencies`() {
        val root = Files.createTempDirectory("worker-build-policy")
        try {
            Files.createDirectory(root.resolve("subproject"))
            Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'fixture'; include 'subproject'")
            Files.writeString(root.resolve("build.gradle"), """
                allprojects {
                    apply plugin: 'java'
                    tasks.register('testFixture') { doLast { throw new GradleException('unnecessary fixture') } }
                    tasks.register('integrationTest', Test) { dependsOn 'testClasses', 'testFixture' }
                    tasks.register('installDist') {
                        dependsOn 'jar', 'test', 'integrationTest'
                        doLast { println('runtime artifacts prepared') }
                    }
                }
            """.trimIndent())
            for (project in listOf(root, root.resolve("subproject"))) {
                val source = project.resolve("src/test/java/Invalid.java")
                Files.createDirectories(source.parent)
                Files.writeString(source, "Test sources must not even be compiled")
            }
            val init = root.resolve("worker.init.gradle")
            Files.writeString(init, requireNotNull(javaClass.getResource("/worker-jvm.init.gradle")).readText())
            val result = GradleRunner.create().withProjectDir(root.toFile()).withArguments(
                "installDist", ":subproject:installDist", "--init-script", init.toString(), "--offline",
            ).build()
            assertNotNull(result.task(":installDist")); assertNotNull(result.task(":subproject:installDist"))
            assertTrue(result.tasks.none { it.path.contains("test", ignoreCase = true) }, result.tasks.toString())
        } finally { deleteTree(root) }
    }
}
