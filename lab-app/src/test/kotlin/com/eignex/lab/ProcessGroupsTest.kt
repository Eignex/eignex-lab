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
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessGroupsTest {
    @Test
    fun `a command past its time limit is killed with its children and reported as timed out`() {
        val pidFile = Files.createTempFile("child", ".pid")
        val started = System.nanoTime()
        val (exit, _) = runProcess(listOf("bash", "-c", "sleep 60 & echo \$! > $pidFile; wait"), timeout = Duration.ofMillis(500))

        assertEquals(TIMED_OUT_EXIT, exit)
        val child = ProcessHandle.of(Files.readString(pidFile).trim().toLong())
        // Child exit and reaping can finish after the parent process has been collected.
        child.ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
        assertTrue(child.map { !it.isAlive }.orElse(true), "the background child is killed with its group")
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(10))
    }

    @Test
    fun `a command that finishes returns its exit and output`() {
        assertEquals(3 to "out\nerr\n", runProcess(listOf("bash", "-c", "echo out; echo err >&2; exit 3"), timeout = Duration.ofSeconds(10)))
    }

    @Test
    fun `mirror fetches take turns`() {
        val mirror = Files.createTempDirectory("mirrors").resolve("repo.git")
        var inside = 0
        var most = 0
        val threads = List(4) {
            thread {
                withMirrorLock(mirror) {
                    synchronized(this) { inside++; most = maxOf(most, inside) }
                    Thread.sleep(50)
                    synchronized(this) { inside-- }
                }
            }
        }
        threads.forEach { it.join() }
        assertEquals(1, most)
    }
}
