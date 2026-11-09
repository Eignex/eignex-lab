package com.eignex.lab

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import kotlin.system.exitProcess

/**
 * Another process taking the whole-file POSIX record lock (fcntl, as [com.eignex.lab.util.ProcessFilePin], Gradle and
 * lockf(3) take it) for tests that must see a lock from outside this JVM: a second FileChannel here would only
 * consult the JVM's own overlapping-lock table. It runs [main] on this test JDK and classpath.
 *
 * - `probe <file>`: try an exclusive lock on the existing file; exit [HELD] when another process holds it, else 0.
 * - `probe-shared <file>`: the same with a shared lock, the file opened for reading only.
 * - `hold <file> [<path> <text>]`: wait for an exclusive lock, creating the file, then write <text> to <path>, print
 *   `ready` and keep the lock until a line (or the end) arrives on standard input.
 */
object LockFixture {
    const val HELD = 73

    /** The command that runs the fixture with [args]. */
    fun command(vararg args: String): List<String> = listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("java.class.path"), LockFixture::class.java.name, *args)

    fun start(vararg args: String): Process = ProcessBuilder(command(*args)).start()

    /** [command] as one POSIX shell command line. */
    fun shell(vararg args: String): String = command(*args).joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }

    @JvmStatic fun main(args: Array<String>) {
        val file = Path.of(args[1])
        when (args[0]) {
            "probe" -> FileChannel.open(file, READ, WRITE).use { exitProcess(if (it.tryLock() == null) HELD else 0) }
            "probe-shared" -> FileChannel.open(file, READ).use {
                exitProcess(if (it.tryLock(0, Long.MAX_VALUE, true) == null) HELD else 0)
            }
            "hold" -> FileChannel.open(file, CREATE, WRITE).use { channel ->
                channel.lock().use {
                    if (args.size > 2) Files.writeString(Path.of(args[2]), args[3])
                    println("ready"); System.out.flush()
                    readlnOrNull()
                }
            }
            else -> error("unknown mode ${args[0]}")
        }
    }
}
