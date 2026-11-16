package com.eignex.lab

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val GIT_TIMEOUT_SECONDS = 60L

/** Runs `git -C [directory] [args]` for a test fixture: its exit code and trimmed combined stdout and stderr. */
fun gitCommand(directory: Path, vararg args: String): Pair<Int, String> {
    val process = ProcessBuilder(listOf("git", "-C", directory.toString()) + args).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    assertTrue(process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS), "git ${args.joinToString(" ")} timed out")
    return process.exitValue() to output
}

/** Runs `git -C [directory] [args]`, failing the test unless it exits 0, and returns its trimmed output. */
fun git(directory: Path, vararg args: String): String {
    val (exit, output) = gitCommand(directory, *args)
    assertEquals(0, exit, "git ${args.joinToString(" ")}: $output")
    return output
}
