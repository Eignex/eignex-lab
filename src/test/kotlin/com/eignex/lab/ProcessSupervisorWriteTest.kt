package com.eignex.lab

import com.eignex.lab.util.ProcessSupervisor
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ProcessSupervisorWriteTest {
    @Test fun `lease callback work is not mistaken for a blocked socket write`() {
        val root = Files.createTempDirectory("supervisor-write-budget")
        val socket = root.resolve("supervisor.sock")
        val script = root.resolve("supervisor.py")
        javaClass.getResourceAsStream("/nomad/process_supervisor.py")!!.use { Files.copy(it, script) }
        val service = ProcessBuilder("python3", script.toString(), "--socket", socket.toString())
            .redirectError(root.resolve("service.err").toFile()).start()
        try {
            val deadline = System.nanoTime() + 5_000_000_000
            while (!Files.exists(socket)) {
                check(service.isAlive && System.nanoTime() < deadline)
                Thread.sleep(20)
            }
            var calls = 0
            val exit = ProcessSupervisor(socket).run(listOf("sleep", "8"), root, System.getenv(),
                root.resolve("out"), root.resolve("err"), 20) {
                // Request construction and its initial write finish first. A slow first heartbeat
                // still fits the original 80-second lease and must not consume the socket write budget.
                calls++
                if (calls == 4) Thread.sleep(5500)
                80000
            }
            assertEquals(0, exit)
            assertTrue(calls >= 4)
        } finally {
            service.destroy()
            if (!service.waitFor(10, TimeUnit.SECONDS)) service.destroyForcibly()
        }
    }
}
