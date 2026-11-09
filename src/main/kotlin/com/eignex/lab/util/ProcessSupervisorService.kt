package com.eignex.lab.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import sun.misc.Signal
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/**
 * `eignex-lab process-supervisor --socket PATH [--local-lock PATH]`: the same-user native process service, outside
 * Nomad's raw_exec failure boundary, that [ProcessSupervisor] talks to.
 *
 * A private Unix connection owns a tree. EOF, lease expiry, or timeout ends it. A random inherited marker
 * ([MARKER]) finds children that detach and are reparented. PID/start identity is rechecked before every signal. This
 * assumes trusted lab executables preserve their inherited marker; it is not an isolation boundary for hostile code.
 *
 * Each connection sends one JSON line. `{"operation":"reserve","experiment":ID}` holds the host for an experiment
 * (answered `{"reserved":true}` once `--local-lock` is free, then kept by any bytes within 80 s, ended by EOF);
 * otherwise the line is a launch request (`argv`, `directory`, `environment`, `stdout`, `stderr`, `timeoutSec`,
 * `graceMs`, `leaseMs`, `experiment`, optional `runRemainingMs`), followed by newline-delimited remaining lease
 * milliseconds, and answered `{"exitCode":N,"runTimedOut":B,"signal":S}` or `{"error":...}`.
 *
 * Java can neither start a process in a new session nor tell a signal from an exit code of 128 + N, so every
 * process starts under a small perl launcher ([LAUNCHER]): it calls setsid, sets umask 077, starts the command,
 * waits for it and writes its wait status to a private file. Perl is part of macOS and of the worker image. The
 * socket's lock is a BSD flock held by a perl helper ([FLOCK]), the same lock the earlier Python supervisor took, so
 * the two exclude each other while one replaces the other.
 */
object ProcessSupervisorService {
    const val MARKER = "EIGNEX_PROCESS_OWNER"
    private const val PERL = "/usr/bin/perl"
    private val mac = System.getProperty("os.name").lowercase().startsWith("mac")
    private val privateFile = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    private val privateDirectory = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))

    /**
     * Runs ARGV[1..] in a new session with umask 077 and writes its wait status to ARGV[0]: `exit N`, `signal N`, or
     * `exec ERRNO` when it could not start. Terminating signals to the launcher itself do not end it; only the
     * supervisor's SIGKILL does, and then no status is written.
     */
    val LAUNCHER = """use strict; use POSIX ();
my ${'$'}status = shift @ARGV;
sub report { my ${'$'}f; open(${'$'}f, '>', "${'$'}status.part") and print(${'$'}f "${'$'}_[0]\n") and close(${'$'}f) and rename("${'$'}status.part", ${'$'}status); }
defined(POSIX::setsid()) or do { report("exec " . (${'$'}! + 0)); exit 0 };
umask 077;
pipe(my ${'$'}r, my ${'$'}w) or do { report("exec " . (${'$'}! + 0)); exit 0 };
my ${'$'}pid = fork();
defined ${'$'}pid or do { report("exec " . (${'$'}! + 0)); exit 0 };
if (${'$'}pid == 0) { close ${'$'}r; { exec { ${'$'}ARGV[0] } @ARGV; } syswrite(${'$'}w, ${'$'}! + 0); POSIX::_exit(127); }
close ${'$'}w;
${'$'}SIG{${'$'}_} = sub {} for qw(TERM INT HUP QUIT);
my ${'$'}failed = '';
while (1) { my ${'$'}n = sysread(${'$'}r, my ${'$'}b, 64); if (defined ${'$'}n) { last if ${'$'}n == 0; ${'$'}failed .= ${'$'}b; } elsif (!${'$'}!{EINTR}) { last } }
close ${'$'}r;
my ${'$'}done;
do { ${'$'}done = waitpid(${'$'}pid, 0) } while (${'$'}done == -1 && ${'$'}!{EINTR});
if (${'$'}failed ne '') { report("exec ${'$'}failed") }
elsif (${'$'}? & 127) { report("signal " . (${'$'}? & 127)) }
else { report("exit " . (${'$'}? >> 8)) }
exit 0;
"""

    /** Takes an exclusive, non-blocking flock on ARGV[0], prints `ok`, and holds it until its standard input ends. */
    val FLOCK = """use Fcntl qw(:flock); umask 077;
open(my ${'$'}f, '>>', ${'$'}ARGV[0]) or die "${'$'}!\n";
flock(${'$'}f, LOCK_EX | LOCK_NB) or exit 75;
${'$'}| = 1; print "ok\n";
1 while sysread(STDIN, my ${'$'}b, 64);
"""

    fun main(args: List<String>): Int {
        var socket: Path? = null
        var localLock: Path? = null
        var index = 0
        fun usage(message: String): Int {
            System.err.println("usage: eignex-lab process-supervisor --socket SOCKET [--local-lock LOCAL_LOCK]")
            System.err.println("eignex-lab process-supervisor: error: $message")
            return 2
        }
        while (index < args.size) {
            val argument = args[index]
            val (name, inline) = if (argument.startsWith("--") && '=' in argument) argument.substringBefore('=') to argument.substringAfter('=') else argument to null
            val value = inline ?: args.getOrNull(index + 1)
            when (name) {
                "--socket", "--local-lock" -> {
                    if (value == null) return usage("argument $name: expected one argument")
                    if (name == "--socket") socket = Path.of(value) else localLock = Path.of(value)
                    index += if (inline == null) 2 else 1
                }
                else -> return usage("unrecognized arguments: $argument")
            }
        }
        if (socket == null) return usage("the following arguments are required: --socket")
        return try {
            Service(socket.toAbsolutePath(), localLock?.toAbsolutePath()).serve()
        } catch (e: Exception) {
            System.err.println("process-supervisor: ${e.message ?: e.javaClass.simpleName}")
            1
        }
    }

    /** What ended a launched process, as the reply reports it. */
    private data class Ended(val exitCode: Int, val signal: Int)

    private class Blocked : Exception()

    /** One client connection, read and written without blocking the supervisor beyond the waits it asks for. */
    private class Connection(val channel: SocketChannel) : AutoCloseable {
        private val selector = Selector.open()
        private val key: SelectionKey

        init {
            channel.configureBlocking(false)
            key = channel.register(selector, SelectionKey.OP_READ)
        }

        /** Whether the connection has bytes or EOF to read within [timeoutMs] (0: only now). */
        fun readable(timeoutMs: Long): Boolean {
            key.interestOps(SelectionKey.OP_READ)
            val ready = if (timeoutMs <= 0) selector.selectNow() else selector.select(timeoutMs)
            selector.selectedKeys().clear()
            return ready > 0
        }

        /** At most [max] bytes, null at EOF; call when [readable]. */
        fun read(max: Int): ByteArray? {
            val buffer = ByteBuffer.allocate(max)
            val n = channel.read(buffer)
            if (n < 0) return null
            return buffer.array().copyOf(n)
        }

        /** [max] bytes within [timeoutMs], as a blocking recv with a socket timeout would; throws on timeout. */
        fun receive(max: Int, timeoutMs: Long): ByteArray? {
            if (!readable(timeoutMs)) throw java.net.SocketTimeoutException("timed out")
            return read(max)
        }

        fun send(text: String) {
            val buffer = ByteBuffer.wrap(text.toByteArray())
            while (buffer.hasRemaining()) {
                if (channel.write(buffer) == 0) {
                    key.interestOps(SelectionKey.OP_WRITE)
                    selector.select(100)
                    selector.selectedKeys().clear()
                }
            }
        }

        override fun close() {
            runCatching { key.cancel() }
            runCatching { selector.close() }
            runCatching { channel.close() }
        }
    }

    private class Service(val socket: Path, val localLock: Path?) {
        private val registry = socket.resolveSibling(socket.name + ".owners")
        private val statuses = socket.resolveSibling(socket.name + ".status")
        private val launchLock = Any()
        private val ownerLock = Any()
        @Volatile private var stopping = false
        private val owners = HashMap<String, Int>()
        private val experimentMarkers = HashMap<String, String?>()
        private var nativeChannel: FileChannel? = null
        private var nativeLock: FileLock? = null
        private val ownerFile = localLock?.let { it.resolveSibling(it.name + ".owner") }
        // Referenced for the service's lifetime: its pipe closing would end the helper and release the lock.
        private var flock: Process? = null

        fun serve(): Int {
            Files.createDirectories(socket.parent, privateDirectory)
            // Held for this process's lifetime by a helper whose standard input is this JVM's: it ends with us.
            flock = ProcessBuilder(PERL, "-e", FLOCK, socket.toString() + ".lock")
                .redirectError(ProcessBuilder.Redirect.INHERIT).start()
            val answer = flock!!.inputStream.bufferedReader().readLine()
            check(answer == "ok") { "${socket}.lock is held by another supervisor" }
            if (localLock != null) {
                Files.createDirectories(localLock.parent, privateDirectory)
                val channel = FileChannel.open(localLock, setOf(CREATE, WRITE, APPEND), privateFile)
                nativeChannel = channel
                nativeLock = channel.lock()
            }
            if (!registry.exists()) Files.createDirectory(registry, privateDirectory)
            if (!statuses.exists()) Files.createDirectory(statuses, privateDirectory)
            for (record in registry.listDirectoryEntries()) {
                if (record.name.length == 32 && record.name.all { it in "0123456789abcdef" }) {
                    cleanup(record.name)
                    record.deleteIfExists()
                }
            }
            statuses.listDirectoryEntries().forEach { it.deleteIfExists() }
            if (ownerFile != null) {
                ownerFile.deleteIfExists()
                nativeLock?.release(); nativeLock = null
            }
            socket.deleteIfExists()
            for (name in listOf("TERM", "INT")) Signal.handle(Signal(name)) { stopping = true }
            // The socket appears at its path only once it listens, privately: a client that sees it can connect.
            val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            val listening = socket.resolveSibling(".${UUID.randomUUID().toString().take(8)}")
            server.bind(UnixDomainSocketAddress.of(listening), 64)
            Files.setPosixFilePermissions(listening, PosixFilePermissions.fromString("rwx------"))
            Files.move(listening, socket, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            server.configureBlocking(false)
            val selector = Selector.open()
            server.register(selector, SelectionKey.OP_ACCEPT)
            while (!stopping) {
                selector.select(500)
                selector.selectedKeys().clear()
                val connection = server.accept() ?: continue
                Thread({ serveRequest(connection) }, "supervisor-request").start()
            }
            // A signal only sets the flag: the cleanup happens here, under the launch lock, before exiting at once.
            synchronized(launchLock) {
                for (record in registry.listDirectoryEntries()) {
                    cleanup(record.name)
                    record.deleteIfExists()
                }
            }
            ownerFile?.deleteIfExists()
            socket.deleteIfExists()
            // Release the socket lock before exiting, as a process's own flock is: a successor started right after
            // this exit must not find the helper still holding it.
            flock?.let { helper -> runCatching { helper.outputStream.close() }; helper.waitFor(5, TimeUnit.SECONDS) }
            Runtime.getRuntime().halt(0)
            return 0
        }

        private fun writeOwnerFile() {
            val file = ownerFile ?: return
            if (!file.exists()) Files.createFile(file, privateFile)
            Files.writeString(file, owners.keys.sorted().joinToString(","))
        }

        private fun reserve(connection: Connection, experiment: Any?) {
            require(experiment is String && experiment.isNotEmpty() && experiment.length <= 80) { "invalid experiment owner" }
            var acquired = false
            try {
                while (!acquired) {
                    synchronized(ownerLock) {
                        if (stopping) return
                        try {
                            if (owners.isEmpty() && nativeChannel != null) nativeLock = nativeChannel!!.tryLock() ?: throw Blocked()
                            owners[experiment] = (owners[experiment] ?: 0) + 1
                            writeOwnerFile()
                            acquired = true
                        } catch (_: Blocked) {
                        }
                    }
                    if (!acquired && connection.readable(100) && connection.read(64) == null) return
                }
                connection.send("{\"reserved\":true}\n")
                var expiry = System.nanoTime() + 80_000_000_000
                while (System.nanoTime() < expiry && !stopping) {
                    if (connection.readable(500)) {
                        if (connection.read(64) == null) break
                        expiry = System.nanoTime() + 80_000_000_000
                    }
                }
            } finally {
                if (acquired) synchronized(launchLock) {
                    synchronized(ownerLock) {
                        owners[experiment] = owners.getValue(experiment) - 1
                        if (owners[experiment] == 0) {
                            try {
                                for ((marker, owner) in experimentMarkers.toList()) if (owner == experiment) cleanup(marker)
                                owners.remove(experiment)
                                if (ownerFile != null) {
                                    if (owners.isNotEmpty()) writeOwnerFile() else ownerFile.deleteIfExists()
                                }
                                if (owners.isEmpty()) { nativeLock?.release(); nativeLock = null }
                            } catch (e: Exception) {
                                stopping = true
                                throw e
                            }
                        }
                    }
                }
            }
        }

        private fun serveRequest(channel: SocketChannel) {
            val marker = UUID.randomUUID().toString().replace("-", "")
            val status = statuses.resolve(marker)
            var process: Process? = null
            var cleaned = false
            val connection = runCatching { Connection(channel) }.getOrElse { runCatching { channel.close() }; return }
            try {
                val received = ByteArrayOutputStream()
                while (received.toByteArray().indexOf('\n'.code.toByte()) < 0) {
                    val data = connection.receive(65536, 5000) ?: throw java.net.ConnectException("worker disconnected before request")
                    received.write(data)
                    if (received.size() > 1024 * 1024) throw IllegalArgumentException("request too large")
                }
                val bytes = received.toByteArray()
                val newline = bytes.indexOf('\n'.code.toByte())
                val request = Json.parseToJsonElement(String(bytes, 0, newline)).jsonObject
                var pending = bytes.copyOfRange(newline + 1, bytes.size)
                if ((request["operation"] as? JsonPrimitive)?.content == "reserve") {
                    reserve(connection, (request["experiment"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
                    return
                }
                val experiment = (request["experiment"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (nativeChannel != null) synchronized(ownerLock) {
                    require(experiment in owners) { "native execution has no host reservation" }
                }
                val argv = (request["argv"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                require(argv != null && argv.isNotEmpty() && argv.all { it != null && '\u0000' !in it }) { "invalid argv" }
                var timeout = request.number("timeoutSec")
                require(timeout > 0 && timeout <= 604800) { "invalid timeout" }
                var graceMs = if ("graceMs" in request) request.number("graceMs") else 10000.0
                val runMs = request["runRemainingMs"]?.takeIf { it !is JsonNull }?.let { request.number("runRemainingMs") }
                var runLimited = false
                if (runMs != null) {
                    require(runMs > 0 && runMs <= 604800000) { "invalid profile deadline" }
                    // Reserve shutdown and cleanup/transfer time inside the total active-time allowance.
                    graceMs = minOf(graceMs, Math.floor(runMs / 10))
                    val workMs = maxOf(0.0, runMs - graceMs - minOf(30000.0, Math.floor(runMs / 10)))
                    runLimited = workMs < timeout * 1000
                    timeout = minOf(timeout, workMs / 1000)
                }
                val environment = (request["environment"] as? JsonObject ?: throw IllegalArgumentException("invalid environment"))
                    .mapValues { (_, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("invalid environment") }
                    .plus(MARKER to marker)
                val leaseMs = request.number("leaseMs")
                require(leaseMs > 0 && leaseMs <= 80000) { "invalid lease" }
                val stdout = privateOutput(request.text("stdout"))
                val stderr = privateOutput(request.text("stderr"))
                val directory = File(request.text("directory"))
                synchronized(launchLock) {
                    if (nativeChannel != null) synchronized(ownerLock) {
                        require(experiment in owners) { "native reservation ended before launch" }
                    }
                    check(!stopping) { "service stopping" }
                    Files.writeString(Files.createFile(registry.resolve(marker), privateFile), marker)
                    experimentMarkers[marker] = experiment
                    val builder = ProcessBuilder(listOf(PERL, "-e", LAUNCHER, status.toString()) + argv.map { it!! })
                        .directory(directory).redirectInput(ProcessBuilder.Redirect.INHERIT)
                        .redirectOutput(ProcessBuilder.Redirect.to(stdout)).redirectError(ProcessBuilder.Redirect.to(stderr))
                    builder.environment().clear()
                    builder.environment().putAll(environment)
                    process = builder.start()
                }
                val started = process!!
                val deadline = System.nanoTime() + (timeout * 1e9).toLong()
                var lease = System.nanoTime() + (leaseMs * 1e6).toLong()
                var code: Int? = null
                var runTimedOut = false
                while (started.isAlive) {
                    val current = System.nanoTime()
                    if (current >= lease) throw java.util.concurrent.TimeoutException("lease expired")
                    if (current >= deadline) {
                        cleanup(marker, minOf(graceMs.toLong(), maxOf(0L, (lease - current) / 1_000_000)))
                        code = 124
                        runTimedOut = runLimited
                        break
                    }
                    if (connection.readable(if (pending.isNotEmpty()) 0 else 100)) {
                        // Heartbeats are deliberately small, newline-delimited remaining monotonic lease durations.
                        val data = connection.read(64) ?: throw java.net.ConnectException("worker disconnected")
                        pending += data
                    }
                    while (pending.indexOf('\n'.code.toByte()) >= 0) {
                        val end = pending.indexOf('\n'.code.toByte())
                        val remaining = String(pending, 0, end).trim().toLong()
                        pending = pending.copyOfRange(end + 1, pending.size)
                        require(remaining > 0 && remaining <= 80000) { "invalid lease renewal" }
                        lease = System.nanoTime() + remaining * 1_000_000
                    }
                    require(pending.size <= 64) { "heartbeat too large" }
                }
                if (code == null) started.waitFor()
                cleanup(marker)
                cleaned = true
                if (!started.waitFor(10, TimeUnit.SECONDS)) throw java.util.concurrent.TimeoutException("process did not end")
                val ended = ended(started, status)
                connection.send("{\"exitCode\": ${code ?: ended.exitCode}, \"runTimedOut\": $runTimedOut, \"signal\": ${ended.signal}}\n")
                // Keep the read side open until the worker consumes the result. Closing with an unread
                // heartbeat can reset a Unix socket and hide an otherwise completed process's exit.
                channel.shutdownOutput()
                try {
                    while (true) { if ((connection.receive(64, 5000) ?: break).isEmpty()) break }
                } catch (_: java.io.IOException) {
                }
            } catch (error: Throwable) {
                System.err.println("supervisor request ended: ${error.javaClass.simpleName}")
                System.err.flush()
                // Do not log requests: they can contain paths and runtime credentials.
                try {
                    cleanup(marker)
                    cleaned = true
                } catch (_: Throwable) {
                    stopping = true // Retain the registry and refuse new launches until recovery succeeds.
                }
                runCatching { process?.waitFor(10, TimeUnit.SECONDS) }
                runCatching { connection.send("{\"error\":\"process supervision ended\"}\n") }
            } finally {
                if (cleaned) registry.resolve(marker).deleteIfExists()
                status.deleteIfExists()
                statuses.resolve("$marker.part").deleteIfExists()
                synchronized(launchLock) { experimentMarkers.remove(marker) }
                connection.close()
            }
        }

        /** The launched command's end, from the launcher's status, or the launcher's own when it was killed. */
        private fun ended(launcher: Process, status: Path): Ended {
            val text = if (status.isRegularFile()) status.readText().trim() else null
            return when {
                text == null -> launcher.exitValue().let { if (it > 128) Ended(it, it - 128) else Ended(it, 0) }
                text.startsWith("exit ") -> Ended(text.removePrefix("exit ").toInt(), 0)
                text.startsWith("signal ") -> text.removePrefix("signal ").toInt().let { Ended(128 + it, it) }
                else -> throw java.io.IOException("cannot start the command: $text")
            }
        }
    }

    private fun JsonObject.text(name: String): String =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("invalid $name")

    private fun JsonObject.number(name: String): Double {
        val value = this[name] as? JsonPrimitive ?: throw IllegalArgumentException("invalid $name")
        require(!value.isString) { "invalid $name" }
        return value.longOrNull?.toDouble() ?: value.doubleOrNull ?: throw IllegalArgumentException("invalid $name")
    }

    /** The output file, created private (0600) when new and truncated when not, as the earlier Python service opened it. */
    private fun privateOutput(path: String): File {
        val file = Path.of(path)
        if (!file.exists()) Files.createFile(file, privateFile)
        Files.newOutputStream(file).close()
        return file.toFile()
    }

    /** The processes carrying [marker], by PID with their start identity. */
    fun owned(marker: String): Map<Long, String> {
        val result = HashMap<Long, String>()
        val needle = "$MARKER=$marker"
        if (mac) {
            // -E prints launch environment on Darwin; never print or persist this output.
            val ps = ProcessBuilder("/bin/ps", "-E", "-ww", "-A", "-o", "pid=,command=").redirectErrorStream(false).start()
            val text = ps.inputStream.bufferedReader().readText()
            check(ps.waitFor(5, TimeUnit.SECONDS) && ps.exitValue() == 0) { "ps failed" }
            for (line in text.lines()) {
                val parts = line.trim().split(Regex("\\s+"), limit = 2)
                if (parts.size == 2 && needle in parts[1].split(Regex("\\s+"))) {
                    val pid = parts[0].toLongOrNull() ?: continue
                    identity(pid)?.let { result[pid] = it }
                }
            }
        } else {
            val bytes = needle.toByteArray()
            Files.newDirectoryStream(Path.of("/proc")) { it.name.all(Char::isDigit) }.use { entries ->
                for (entry in entries) {
                    val environ = try { entry.resolve("environ").readBytes() } catch (_: Exception) { continue }
                    if (entries(environ).any { it.contentEquals(bytes) }) {
                        val pid = entry.name.toLong()
                        identity(pid)?.let { result[pid] = it }
                    }
                }
            }
        }
        return result
    }

    private fun entries(environ: ByteArray): List<ByteArray> {
        val result = ArrayList<ByteArray>()
        var start = 0
        for (i in environ.indices) if (environ[i] == 0.toByte()) { result += environ.copyOfRange(start, i); start = i + 1 }
        if (start < environ.size) result += environ.copyOfRange(start, environ.size)
        return result
    }

    /** The process's start time, which a reused PID does not share; null once it is gone. */
    fun identity(pid: Long): String? = try {
        if (mac) ProcessHandle.of(pid).flatMap { it.info().startInstant() }.map { it.toString() }.orElse(null)
        else Path.of("/proc/$pid/stat").readText().substringAfterLast(")").trim().split(" ")[19]
    } catch (_: Exception) {
        null
    }

    private fun signal(processes: Map<Long, String>, name: String) {
        val current = processes.filter { (pid, start) -> identity(pid) == start }.keys
        if (current.isEmpty()) return
        when (name) {
            "TERM" -> current.forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroy() } }
            "KILL" -> current.forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
            // Java sends only TERM and KILL itself.
            else -> ProcessBuilder(listOf("/bin/sh", "-c", "kill -$name \"$@\" 2>/dev/null", "sh") + current.map { it.toString() })
                .redirectErrorStream(true).start().apply { inputStream.readAllBytes(); waitFor() }
        }
    }

    /** End every process carrying [marker]: TERM and up to [graceMs] to leave, then freeze them all and kill them. */
    fun cleanup(marker: String, graceMs: Long = 0) {
        if (graceMs > 0) {
            signal(owned(marker), "TERM")
            val until = System.nanoTime() + graceMs * 1_000_000
            while (System.nanoTime() < until && owned(marker).isNotEmpty()) Thread.sleep(50)
        }
        // Freeze before killing so a surviving parent cannot fork between scans.
        val found = HashMap<Long, String>()
        for (attempt in 1..20) {
            val current = owned(marker)
            signal(current, "STOP")
            val fresh = current.keys - found.keys
            found.putAll(current)
            if (fresh.isEmpty()) break
        }
        signal(found, "KILL")
    }

    private fun ByteArray.indexOf(value: Byte): Int { for (i in indices) if (this[i] == value) return i; return -1 }
}
