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
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostCheckTest {
    @Test fun `host check starts without benchmark JVM flags or loading a benchmark backend`() {
        val output = Files.createTempFile("host-check-", ".log")
        try {
            val process = ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xlog:class+load=info", "-cp", System.getProperty("java.class.path"),
                "com.eignex.lab.cli.MainKt", "check",
            ).apply {
                listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").forEach { environment().remove(it) }
            }.redirectErrorStream(true).redirectOutput(output.toFile()).start()
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS), "host check did not finish")
                val log = Files.readString(output)
                assertEquals(0, process.exitValue(), log.takeLast(4000))
                assertTrue(log.contains("com.eignex.lab.domain.HostCheckKt"), "host check must execute")
                assertFalse(log.contains("com.eignex.koblas."), "coordinator host reporting must not load a benchmark backend")
            } finally { process.destroyForcibly() }
        } finally { Files.deleteIfExists(output) }
    }
}
