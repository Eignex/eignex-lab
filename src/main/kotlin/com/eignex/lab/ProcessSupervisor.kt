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
class ProcessSupervisor(private val socket: Path) {
    @Serializable
    private data class Request(val argv: List<String>, val directory: String, val environment: Map<String, String>,
                               val stdout: String, val stderr: String, val timeoutSec: Long, val graceMs: Long, val leaseMs: Long)

    fun run(argv: List<String>, directory: Path, environment: Map<String, String>, stdout: Path, stderr: Path,
            timeoutSec: Long, graceMs: Long = 10_000, remainingLeaseMs: () -> Long): Int {
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
            write(Json.encodeToString(Request(argv, directory.toString(), environment, stdout.toString(), stderr.toString(), timeoutSec, graceMs, remaining())) + "\n")
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
                        return json["exitCode"]?.jsonPrimitive?.int ?: error("process supervision failed")
                    }
                }
                Thread.sleep(25)
            }
        }
    }
}
