package com.eignex.lab

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Process ownership lives in an independent, same-user service. Closing the connection relinquishes the tree. */
class ProcessSupervisor(private val socket: Path, private val experiment: String? = System.getenv("LAB_WORKER_EXPERIMENT")) {
    @Serializable
    private data class Request(val argv: List<String>, val directory: String, val environment: Map<String, String>,
                               val stdout: String, val stderr: String, val timeoutSec: Long, val graceMs: Long, val leaseMs: Long, val experiment: String?,
                               val runRemainingMs: Long? = null)


    /** Kept for the whole native session, including artifact transfer and iteration boundaries. */
    fun reserve(experiment: String): AutoCloseable {
        require(experiment.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            channel.connect(UnixDomainSocketAddress.of(socket)); channel.configureBlocking(false)
            val request = ByteBuffer.wrap(("{\"operation\":\"reserve\",\"experiment\":\"$experiment\"}\n").toByteArray())
            while (request.hasRemaining()) { if (channel.write(request) == 0) Thread.sleep(10) }
            val deadline = System.nanoTime() + 80_000_000_000
            val response = StringBuilder(); val buffer = ByteBuffer.allocate(4096)
            while ('\n' !in response) {
                check(System.nanoTime() < deadline) { "native ownership wait expired" }
                val n = channel.read(buffer); check(n >= 0) { "supervisor disconnected" }
                if (n > 0) { response.append(String(buffer.array(), 0, n)); buffer.clear(); require(response.length <= 4096) }
                else Thread.sleep(25)
            }
            require(Json.parseToJsonElement(response.toString().trim()).jsonObject["reserved"]?.jsonPrimitive?.content == "true") { "native ownership unavailable" }
            val heartbeat = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it, "native-ownership").apply { isDaemon = true } }
            heartbeat.scheduleWithFixedDelay({ runCatching { channel.write(ByteBuffer.wrap("1\n".toByteArray())) }.onFailure { channel.close() } }, 10, 10, java.util.concurrent.TimeUnit.SECONDS)
            return AutoCloseable { heartbeat.shutdownNow(); channel.close() }
        } catch (error: Throwable) { channel.close(); throw error }
    }

    fun run(argv: List<String>, directory: Path, environment: Map<String, String>, stdout: Path, stderr: Path,
            timeoutSec: Long, graceMs: Long = 10_000, runRemainingMs: Long? = null, remainingLeaseMs: () -> Long): Int {
        if (runRemainingMs != null && runRemainingMs <= 0) throw ProfileRunTimeout()
        fun remaining() = remainingLeaseMs().coerceAtMost(80_000).also { if (it <= 0 || Thread.currentThread().isInterrupted) throw CancellationException("execution lease ended") }
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(socket))
            channel.configureBlocking(false)
            fun write(value: String) {
                val buffer = ByteBuffer.wrap(value.toByteArray())
                val deadline = System.nanoTime() + 5_000_000_000
                while (buffer.hasRemaining()) {
                    remaining()
                    if (System.nanoTime() >= deadline) error("supervisor write timeout")
                    if (channel.write(buffer) == 0) Thread.sleep(10)
                }
            }
            write(Json.encodeToString(Request(argv, directory.toString(), environment, stdout.toString(), stderr.toString(), timeoutSec, graceMs, remaining(), experiment, runRemainingMs)) + "\n")
            var heartbeat = System.nanoTime()
            val response = StringBuilder(); val buffer = ByteBuffer.allocate(4096)
            while (true) {
                val lease = remaining()
                if (System.nanoTime() >= heartbeat) { write("$lease\n"); heartbeat = System.nanoTime() + 1_000_000_000 }
                buffer.clear()
                val n = channel.read(buffer)
                if (n < 0) error("supervisor disconnected")
                if (n > 0) {
                    response.append(String(buffer.array(), 0, n)); require(response.length <= 4096)
                    if ('\n' in response) {
                        val json = Json.parseToJsonElement(response.toString().substringBefore('\n')).jsonObject
                        if (json["runTimedOut"]?.jsonPrimitive?.content == "true") throw ProfileRunTimeout()
                        return json["exitCode"]?.jsonPrimitive?.int ?: error("process supervision failed")
                    }
                }
                Thread.sleep(25)
            }
        }
    }
}
