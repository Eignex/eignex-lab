package com.eignex.lab.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.file.Path
import java.util.concurrent.CancellationException
import com.eignex.lab.worker.LeaseLost
import com.eignex.lab.worker.RunDeadlineExceeded

/**
 * Waits for a non-blocking [channel] to become readable or writable, rather than trying it on a timer: the waiting
 * thread wakes when the supervisor answers, or at the deadline it gives.
 */
private class ChannelWait(channel: SocketChannel) : AutoCloseable {
    private val selector = Selector.open()
    private val key = channel.register(selector, 0)

    /** Wait at most [timeoutMs] for [ops] ([SelectionKey.OP_READ] or [SelectionKey.OP_WRITE]). */
    fun await(ops: Int, timeoutMs: Long) {
        key.interestOps(ops)
        selector.select(timeoutMs.coerceAtLeast(1))
        selector.selectedKeys().clear()
    }

    override fun close() {
        key.cancel()
        selector.close()
    }
}

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
            val response = StringBuilder()
            ChannelWait(channel).use { wait ->
                val request = ByteBuffer.wrap(("{\"operation\":\"reserve\",\"experiment\":\"$experiment\"}\n").toByteArray())
                while (request.hasRemaining()) { if (channel.write(request) == 0) wait.await(SelectionKey.OP_WRITE, 10) }
                val deadline = System.nanoTime() + 80_000_000_000
                val buffer = ByteBuffer.allocate(4096)
                while ('\n' !in response) {
                    check(System.nanoTime() < deadline) { "native ownership wait expired" }
                    val n = channel.read(buffer); check(n >= 0) { "supervisor disconnected" }
                    if (n > 0) { response.append(String(buffer.array(), 0, n)); buffer.clear(); require(response.length <= 4096) }
                    else wait.await(SelectionKey.OP_READ, (deadline - System.nanoTime()) / 1_000_000)
                }
            }
            require(Json.parseToJsonElement(response.toString().trim()).jsonObject["reserved"]?.jsonPrimitive?.content == "true") { "native ownership unavailable" }
            val heartbeat = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it, "native-ownership").apply { isDaemon = true } }
            heartbeat.scheduleWithFixedDelay({ runCatching { channel.write(ByteBuffer.wrap("1\n".toByteArray())) }.onFailure { channel.close() } }, 10, 10, java.util.concurrent.TimeUnit.SECONDS)
            return AutoCloseable { heartbeat.shutdownNow(); channel.close() }
        } catch (error: Throwable) { channel.close(); throw error }
    }

    fun run(argv: List<String>, directory: Path, environment: Map<String, String>, stdout: Path, stderr: Path,
            timeoutSec: Long, graceMs: Long = 10_000, runRemainingMs: Long? = null, remainingLeaseMs: () -> Long): Int {
        if (runRemainingMs != null && runRemainingMs <= 0) throw RunDeadlineExceeded()
        fun remaining() = remainingLeaseMs().coerceAtMost(80_000).also {
            if (it <= 0) throw LeaseLost("execution lease ended")
            if (Thread.currentThread().isInterrupted) throw CancellationException("execution interrupted")
        }
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(socket))
            channel.configureBlocking(false)
            ChannelWait(channel).use { wait ->
                fun write(value: String) {
                    val buffer = ByteBuffer.wrap(value.toByteArray())
                    val deadline = System.nanoTime() + 5_000_000_000
                    while (buffer.hasRemaining()) {
                        remaining()
                        if (System.nanoTime() >= deadline) error("supervisor write timeout")
                        if (channel.write(buffer) == 0) wait.await(SelectionKey.OP_WRITE, 10)
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
                            if (json["runTimedOut"]?.jsonPrimitive?.content == "true") throw RunDeadlineExceeded()
                            return json["exitCode"]?.jsonPrimitive?.int ?: error("process supervision failed")
                        }
                    } else {
                        // Until the supervisor answers, or the next heartbeat is due.
                        wait.await(SelectionKey.OP_READ, ((heartbeat - System.nanoTime()) / 1_000_000).coerceIn(1, 1000))
                    }
                }
            }
        }
    }
}
