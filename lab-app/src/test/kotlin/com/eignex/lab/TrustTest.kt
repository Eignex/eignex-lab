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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrustTest {
    /** A mirror where [onMain] are on main and [branches] hold the commits listed, and GitHub's activity per branch. */
    private fun trust(
        onMain: Set<String> = emptySet(),
        branches: Map<String, Set<String>> = emptyMap(),
        pushers: Map<String, List<String>> = emptyMap(),
        calls: MutableList<String> = mutableListOf(),
    ) = Trust(
        setOf("rasros"),
        "https://github.com/Eignex/klause.git",
        git = { args ->
            when (args.first()) {
                "merge-base" -> (if (args[2] in onMain) 0 else 1) to ""
                "for-each-ref" -> 0 to branches.filterValues { args[2] in it }.keys.joinToString("\n")
                else -> error("unexpected git $args")
            }
        },
        github = { path ->
            if (path == "/repos/Eignex/klause") return@Trust "{\"full_name\":\"Eignex/klause\"}"
            calls += path
            val branch = java.net.URLDecoder.decode(Regex("ref=([^&]+)").find(path)!!.groupValues[1], Charsets.UTF_8).removePrefix("refs/heads/")
            val page = Regex("page=(\\d+)$").find(path)!!.groupValues[1].toInt()
            val who = if (page == 1) pushers[branch].orEmpty() else emptyList()
            who.joinToString(",", "[", "]") { """{"actor":{"login":"$it"},"activity_type":"push"}""" }
        },
    )

    @Test
    fun `a commit on main needs no branch activity`() {
        val calls = mutableListOf<String>()
        trust(onMain = setOf("abc"), calls = calls).requireCommit("abc", "main")
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `a commit on a branch only trusted users pushed to runs`() {
        trust(branches = mapOf("codex/x" to setOf("abc")), pushers = mapOf("codex/x" to listOf("rasros", "rasros")))
            .requireCommit("abc", "codex/x")
    }

    @Test
    fun `a commit whose only branch someone else pushed to is refused, naming who`() {
        val refused = assertFailsWith<UntrustedCommit> {
            trust(branches = mapOf("feature" to setOf("abc")), pushers = mapOf("feature" to listOf("rasros", "Johan-Ros")))
                .requireCommit("abc", "feature")
        }
        assertTrue("Johan-Ros" in refused.message!!)
    }

    @Test
    fun `a commit on no branch, or on one GitHub recorded nothing for, is refused`() {
        assertFailsWith<UntrustedCommit> { trust().requireCommit("abc", "abc") }
        assertFailsWith<UntrustedCommit> { trust(branches = mapOf("old" to setOf("abc"))).requireCommit("abc", "old") }
    }

    @Test
    fun `a commit passes when one of the branches holding it passes`() {
        trust(
            branches = mapOf("theirs" to setOf("abc"), "mine" to setOf("abc")),
            pushers = mapOf("theirs" to listOf("someone"), "mine" to listOf("rasros")),
        ).requireCommit("abc", "abc")
    }

    @Test
    fun `a submitted branch is checked by who pushed it, and main always passes`() {
        val trust = trust(pushers = mapOf("mine" to listOf("rasros"), "theirs" to listOf("someone")))
        trust.requireBranch("main")
        trust.requireBranch("mine")
        assertFailsWith<UntrustedCommit> { trust.requireBranch("theirs") }
    }

    @Test
    fun `a branch's pushers are read once for a while, not on every check`() {
        val calls = mutableListOf<String>()
        val trust = trust(pushers = mapOf("mine" to listOf("rasros")), calls = calls)
        repeat(3) { trust.requireBranch("mine") }
        // One short page for the first check, none after.
        assertEquals(1, calls.size)
    }

    @Test
    fun `a refused commit is not retried`() {
        var tries = 0
        assertFailsWith<UntrustedCommit> {
            retrying(Backoff(5, 1, 1), sleep = {}) {
                tries++
                throw UntrustedCommit("no")
            }
        }
        assertEquals(1, tries)
    }
}
