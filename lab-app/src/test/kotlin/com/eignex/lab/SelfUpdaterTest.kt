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

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SelfUpdaterTest {
    private class FakeGit(private val head: String, private val upstream: String) {
        val calls = mutableListOf<List<String>>()

        fun exec(@Suppress("UNUSED_PARAMETER") dir: File, cmd: List<String>, log: File?): Pair<Int, String> {
            calls += cmd
            return when (cmd.last()) {
                "HEAD" -> 0 to head
                "@{u}" -> 0 to upstream
                else -> 0 to ""
            }
        }
    }

    private var restarts = 0

    private fun updater(git: FakeGit, now: () -> Long = { 0L }) = SelfUpdater(
        Files.createTempDirectory("src"), 300_000, File.createTempFile("update", ".log"), now, git::exec, { restarts++ },
    )

    @Test
    fun `a checkout behind its upstream runs the update script`() {
        val git = FakeGit(head = "aaa", upstream = "bbb")

        updater(git).maybeUpdate()

        assertEquals(listOf("bash", "deploy/update.sh"), git.calls.last())
    }

    @Test
    fun `a checkout at its upstream does not update`() {
        val git = FakeGit(head = "aaa", upstream = "aaa")

        updater(git).maybeUpdate()

        assertEquals(0, git.calls.count { it.first() == "bash" })
    }

    @Test
    fun `checks closer together than the interval fetch once`() {
        val git = FakeGit(head = "aaa", upstream = "aaa")
        var now = 0L
        val updater = updater(git) { now }

        updater.maybeUpdate()
        now = 1_000L
        updater.maybeUpdate()

        assertEquals(1, git.calls.count { it.contains("fetch") })
    }

    @Test
    fun `with periodic checks off only a request fetches`() {
        val git = FakeGit(head = "aaa", upstream = "bbb")
        val request = File.createTempFile("update", ".requested").apply { delete() }
        val updater = SelfUpdater(
            Files.createTempDirectory("src"), 0, File.createTempFile("update", ".log"), { 0L }, git::exec, { restarts++ },
            requestFile = request,
        )

        val unrequested = updater.due()
        request.writeText("now")
        updater.maybeUpdate()

        assertEquals(listOf(false, 1, 1), listOf(unrequested, git.calls.count { it.contains("fetch") }, restarts))
    }

    @Test
    fun `a successful update restarts the runner`() {
        val git = FakeGit(head = "aaa", upstream = "bbb")

        updater(git).maybeUpdate()

        assertEquals(1, restarts)
    }

    @Test
    fun `a requested check ignores the interval and an update stays due until applied`() {
        var upstream = "aaa"
        var fetches = 0
        val exec = { _: File, cmd: List<String>, _: File? ->
            if ("fetch" in cmd) fetches++
            0 to when (cmd.last()) {
                "HEAD" -> "aaa"
                "@{u}" -> upstream
                else -> ""
            }
        }
        val request = File.createTempFile("update", ".requested").apply { delete() }
        var now = 0L
        val updater = SelfUpdater(
            Files.createTempDirectory("src"), 300_000, File.createTempFile("update", ".log"), { now }, exec, { restarts++ },
            requestFile = request,
        )
        val before = updater.due()
        upstream = "bbb"
        now = 1_000L
        val withinInterval = updater.due()
        request.writeText("now")

        val requested = updater.due()
        val stillDue = updater.due()
        updater.maybeUpdate()

        assertEquals(listOf(false, false, true, true, 2, false), listOf(before, withinInterval, requested, stillDue, fetches, request.exists()))
        assertEquals(1, restarts)
    }
}
