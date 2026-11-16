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

import org.gradle.testkit.runner.GradleRunner
import java.nio.file.Files
import kotlin.test.*

class WorkerBuildPolicyTest {
    @Test fun `explicit native CLI builds retain executable configuration while JVM builds omit it`() {
        val root = Files.createTempDirectory("worker-native-build-policy")
        try {
            Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'fixture'")
            val plugin = root.resolve("buildSrc/src/main/groovy/com.eignex.cli.gradle")
            Files.createDirectories(plugin.parent)
            Files.writeString(root.resolve("buildSrc/build.gradle"), "plugins { id 'groovy-gradle-plugin' }")
            Files.writeString(plugin, """
                import org.gradle.api.provider.Property
                abstract class CliExtension { abstract Property<Boolean> getReleaseAssetsEnabled() }
                def cli = extensions.create('eignexCli', CliExtension)
                cli.releaseAssetsEnabled.convention(true)
                afterEvaluate {
                    if (cli.releaseAssetsEnabled.get()) {
                        tasks.register('linkReleaseExecutableLinuxX64') {
                            dependsOn 'test'
                            doLast { println('native executable prepared') }
                        }
                    }
                }
            """.trimIndent())
            Files.writeString(root.resolve("build.gradle"), """
                plugins { id 'java'; id 'com.eignex.cli' }
                tasks.register('installDist') {
                    doLast { assert tasks.findByName('linkReleaseExecutableLinuxX64') == null }
                }
            """.trimIndent())
            val invalid = root.resolve("src/test/java/Invalid.java")
            Files.createDirectories(invalid.parent)
            Files.writeString(invalid, "Test sources must not even be compiled")
            val init = root.resolve("worker.init.gradle")
            Files.writeString(init, requireNotNull(javaClass.getResource("/worker-jvm.init.gradle")).readText())
            fun build(task: String) = GradleRunner.create().withProjectDir(root.toFile()).withArguments(
                task, "--init-script", init.toString(), "--offline",
            ).build()
            assertNotNull(build("installDist").task(":installDist"))
            val native = build(":linkReleaseExecutableLinuxX64")
            assertContains(native.output, "native executable prepared")
            assertTrue(native.tasks.none { it.path.contains("test", ignoreCase = true) }, native.tasks.toString())
        } finally { deleteTree(root) }
    }

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
