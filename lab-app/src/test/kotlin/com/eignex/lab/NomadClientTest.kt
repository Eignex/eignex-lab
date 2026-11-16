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

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

class NomadClientTest {
    @Test fun `executable driver errors are distinguished from image pulls and killed allocations`() {
        fun row(message: String, desired: String = "run", killed: Boolean = false) = NomadClient.allocationRow(buildJsonObject {
            put("ID", "allocation"); put("JobID", "job"); put("NodeID", "node"); put("ClientStatus", "failed"); put("DesiredStatus", desired)
            putJsonObject("TaskStates") { putJsonObject("worker") {
                put("State", "dead"); put("Failed", true)
                putJsonArray("Events") {
                    add(buildJsonObject { put("Type", "Driver Failure"); put("DriverError", message) })
                    if (killed) add(buildJsonObject { put("Type", "Killing"); put("KillReason", "evicted") })
                }
            } }
        })
        for (reason in listOf("permission denied", "exec format error", "no such file or directory", "executable file not found")) {
            val message = "unable to start container process: exec: /opt/lab/worker-entrypoint.sh: $reason"
            assertEquals(message, row(message).launchError)
            assertNull(row(message).workerExit, "no process started")
            assertNull(row(message, desired = "stop").launchError)
            assertNull(row(message, killed = true).launchError)
        }
        assertNull(row("failed to pull image: permission denied").launchError)
        assertNull(row("client disconnected").launchError)
    }

    @Test fun `startup stderr uses a finite tail read and caps even an oversized HTTP response`() {
        val root = Files.createTempDirectory("nomad-startup-logs")
        val token = root.resolve("token").also { Files.writeString(it, "fixture-token") }
        var status = 200
        var oversized = false
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { request ->
            assertEquals("/v1/client/fs/logs/fixture", request.requestURI.path)
            assertEquals("namespace=private&task=worker&type=stderr&origin=end&offset=8192&plain=true&follow=false", request.requestURI.rawQuery)
            assertEquals("fixture-token", request.requestHeaders.getFirst("X-Nomad-Token"))
            val body = if (oversized) "x".repeat(100_000) else "launcher: Permission denied"
            val bytes = body.toByteArray()
            request.sendResponseHeaders(status, bytes.size.toLong())
            try { request.responseBody.use { it.write(bytes) } } catch (_: java.io.IOException) { /* bounded reader cancels */ }
            request.close()
        }
        server.start()
        try {
            val client = NomadClient(URI("http://127.0.0.1:${server.address.port}"), token, HttpClient.newHttpClient(), "private")
            assertEquals("launcher: Permission denied", client.startupStderr("fixture"))
            oversized = true
            assertEquals(8192, client.startupStderr("fixture")!!.length)
            for (missing in listOf(403, 404, 500)) { status = missing; assertNull(client.startupStderr("fixture")) }
        } finally { server.stop(0) }
    }

    @Test fun `worker exit evidence excludes Spot loss kills OOM setup failures and missing events`() {
        fun allocation(client: String = "failed", desired: String = "run", signal: Int = 0, oom: Boolean = false,
                       extra: String? = null, started: Boolean = true, includeExit: Boolean = true, code: Int = 1) = buildJsonObject {
            put("ID", "allocation"); put("JobID", "job"); put("NodeID", "node"); put("ClientStatus", client); put("DesiredStatus", desired)
            putJsonObject("TaskStates") { putJsonObject("worker") {
                put("State", "dead"); put("Failed", true)
                putJsonArray("Events") {
                    add(buildJsonObject { put("Type", "Received") })
                    if (started) add(buildJsonObject { put("Type", "Started") })
                    if (includeExit) add(buildJsonObject {
                        put("Type", "Terminated"); put("ExitCode", code); put("Signal", signal)
                        put("DisplayMessage", "Exit Code: 1"); put("KillReason", ""); put("DriverError", ""); put("SetupError", "")
                        putJsonObject("Details") { put("oom_killed", oom.toString()); put("exit_code", code.toString()); put("signal", signal.toString()) }
                    })
                    if (extra != null) add(buildJsonObject { put("Type", extra) })
                    add(buildJsonObject { put("Type", "Not Restarting"); put("FailsTask", true); put("RestartReason", "Policy allows no restarts") })
                }
            } }
        }
        assertEquals(NomadWorkerExit(1), NomadClient.allocationRow(allocation()).workerExit)
        for (code in listOf(126, 127)) {
            assertEquals(NomadWorkerExit(code), NomadClient.allocationRow(allocation(started = false, code = code)).workerExit)
            assertNull(NomadClient.allocationRow(allocation(started = false, code = code, oom = true)).workerExit)
        }
        val oom = NomadClient.allocationRow(allocation(signal = 9, oom = true)).termination!!
        assertEquals(9, oom.signal); assertEquals(1, oom.exitCode); assertEquals(true, oom.oomKilled)
        assertNull(NomadClient.allocationRow(allocation(includeExit = false)).termination!!.exitCode)
        assertNull(NomadClient.allocationRow(allocation(includeExit = false)).termination!!.oomKilled)
        assertEquals("Killing", NomadClient.allocationRow(allocation(extra = "Killing")).termination!!.events.dropLast(1).last())
        assertFalse(kotlinx.serialization.json.Json.encodeToString(NomadTaskTermination.serializer(), oom).contains("DisplayMessage"))
        for (infra in listOf(allocation(client = "lost"), allocation(desired = "stop"), allocation(signal = 15), allocation(oom = true),
            allocation(extra = "Killing"), allocation(extra = "Driver Failure"), allocation(started = false), allocation(includeExit = false))) {
            assertNull(NomadClient.allocationRow(infra).workerExit)
        }
        assertNull(NomadClient.allocationRow(JsonObject(allocation() - "TaskStates")).workerExit)
        // A pending allocation's task has not started: Nomad sends null, not an absent key.
        val pending = NomadClient.allocationRow(JsonObject(allocation() + ("TaskStates" to JsonNull)))
        assertNull(pending.workerExit); assertNull(pending.termination)
    }

    @Test fun `client GC handles already removed allocations without hiding other server failures`() {
        val root = Files.createTempDirectory("nomad-client")
        val token = root.resolve("token").also { Files.writeString(it, "fixture-token") }
        var status = 500
        var body = "No such allocation on client, or allocation not eligible for GC"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { request ->
            assertEquals("GET", request.requestMethod)
            assertEquals("/v1/client/allocation/fixture/gc", request.requestURI.path)
            val bytes = body.toByteArray()
            request.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            request.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
            request.close()
        }
        server.start()
        try {
            val client = NomadClient(URI("http://127.0.0.1:${server.address.port}"), token, HttpClient.newHttpClient())
            assertFalse(client.collectAllocation("fixture"))
            body = "client RPC failed"
            assertFailsWith<IllegalStateException> { client.collectAllocation("fixture") }
            status = 200; body = ""
            assertTrue(client.collectAllocation("fixture"))
        } finally {
            server.stop(0)
        }
    }
}
