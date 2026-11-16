package com.eignex.lab

import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.worker.*
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkerConsoleTest {
    @Test
    fun `a worker's console is kept as a job file, appended at offsets and capped`() {
        val root = Files.createTempDirectory("worker-console-job")
        val logs = WorkerLogs(Store(root.resolve("lab.db"))) { root }
        val session = ExecutionSession("session-1", 7, 1, ExecutionClass.SOLVE, "release", ResourceBudget(1000, 512),
            "nomad-solve", Desired.STOP, null, null)

        assertEquals(6, logs.appendWorker(session, 0, "start\n".toByteArray()))
        assertEquals(6, logs.appendWorker(session, 0, "start\n".toByteArray()), "a retried chunk is accepted once")
        assertEquals(11, logs.appendWorker(session, 6, "done\n".toByteArray()))
        assertFailsWith<IllegalArgumentException> { logs.appendWorker(session, 20, "gap".toByteArray()) }

        assertEquals("start\ndone\n", Files.readString(WorkerLogs.workerPath(root, "session-1")))
        assertTrue(WorkerLogs.workerPath(root, "session-1").endsWith("nomad/workers/session-1.log"))
    }

    @Test
    fun `the console is copied to its file without changing what it prints, up to its cap`() {
        val file = Files.createTempDirectory("worker-console").resolve("worker-console.log")
        val printed = java.io.ByteArrayOutputStream()
        val original = System.out
        System.setOut(java.io.PrintStream(printed, true))
        try {
            WorkerConsole(file, maxBytes = 12).use {
                println("first")
                System.err.println("err")
                println("past the cap")
            }
        } finally {
            System.setOut(original)
        }
        assertEquals("first\npast the cap\n", printed.toString())
        val copied = Files.readString(file)
        assertTrue(copied.startsWith("first\nerr\n") && "past" !in copied, copied)
    }
}
