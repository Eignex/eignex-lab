package com.eignex.lab.reference

import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@Timeout(30)
class DockerReferenceTest {
    private val docker = DockerReference("test-image", "lab-reference-test", "test solver")

    @Test
    fun `container commands retain resource limits and literal solver arguments`() {
        val name = docker.containerName()
        val arguments = listOf("-c", "read /dev/stdin mps", "-c", "set limits time 10")
        val command = docker.command(name, arguments, interactive = true)

        assertNotEquals(name, docker.containerName())
        assertTrue(name.startsWith("lab-reference-test-${ProcessHandle.current().pid()}-"))
        assertEquals(listOf("docker", "run", "--rm", "--log-driver", "none", "-i"), command.take(6))
        assertEquals(name, command[command.indexOf("--name") + 1])
        assertEquals("6g", command[command.indexOf("--memory") + 1])
        assertEquals("6g", command[command.indexOf("--memory-swap") + 1])
        assertEquals("1", command[command.indexOf("--cpus") + 1])
        assertEquals("lab-reference-test", command[command.indexOf("--label") + 1])
        assertEquals(listOf("test-image") + arguments, command.takeLast(arguments.size + 1))
    }

    @Test
    fun `file based solvers retain mounts and worker count without interactive input`() {
        val command = docker.command(docker.containerName(), listOf("/in/model.xml", "10", "4", "7"),
            cpus = 4, mounts = listOf("/path with spaces:/in:ro"))

        assertFalse("-i" in command)
        assertEquals("4", command[command.indexOf("--cpus") + 1])
        assertEquals("/path with spaces:/in:ro", command[command.indexOf("-v") + 1])
        assertEquals(listOf("test-image", "/in/model.xml", "10", "4", "7"), command.takeLast(5))
    }

    @Test
    fun `large stdout stderr and input can flow concurrently without mixing verdict output`() {
        val input = "x".repeat(256 * 1024)
        val run = exec("head -c 262144 /dev/zero >&2; printf warning >&2; head -c 262144 /dev/zero; cat") {
            it.write(input)
        }

        assertEquals("\u0000".repeat(256 * 1024) + input, run.stdout)
        assertEquals("\u0000".repeat(200 - "warning".length) + "warning", run.stderr)
        assertEquals(0, run.exit)
        assertFalse(run.timedOut)
        assertTrue(run.elapsedMs >= 0)
    }

    @Test
    fun `stdin closes even when the adapter has no input`() {
        assertEquals("finished", exec("cat; printf finished").stdout)
    }

    @Test
    fun `docker launch failures are retriable but solver failures remain results`() {
        for (exit in 125..127) {
            val failure = assertFailsWith<IllegalStateException> { exec("printf 'launch failed' >&2; exit $exit") }
            assertEquals("test solver: docker run exited $exit: launch failed", failure.message)
        }
        val run = exec("printf 'solver failed' >&2; exit 137")
        assertEquals(137, run.exit)
        assertEquals("solver failed", run.stderr)
        assertFalse(run.timedOut)
    }

    // Exercise the process lifecycle without requiring a Docker daemon or installed solver images.
    private fun exec(script: String, input: ((java.io.Writer) -> Unit)? = null): DockerReference.Run =
        docker.exec(listOf("/bin/sh", "-c", script), docker.containerName(), 1_000, input)
}
