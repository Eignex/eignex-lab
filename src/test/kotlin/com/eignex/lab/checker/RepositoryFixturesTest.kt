package com.eignex.lab.checker

import com.eignex.lab.Problem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sources the klause repository carries itself, such as the bench's smoke corpus (job 7407's `degree-drop`), read at
 * the case's pinned commit from a worktree or the lab's mirror, and verified by hash as corpus files are.
 */
class RepositoryFixturesTest {
    private val path = "klause-bench/smoke-corpus/qfnra-source-canonical-v1/degeneracy/degree-drop.smt2"
    private val pinnedText = "(set-logic QF_NRA)\n(declare-fun x () Real)\n(declare-fun y () Real)\n(assert (= y 1))\n(check-sat)\n"
    private val headText = "(set-logic QF_NRA)\n(declare-fun x () Real)\n(declare-fun y () Real)\n(assert (= y 2))\n(check-sat)\n"
    private val pinnedHash = sha256(pinnedText.toByteArray())
    private val headHash = sha256(headText.toByteArray())

    private val origin = Files.createTempDirectory("klause").toFile()
    private val pinned: String
    private val head: String
    private val mirror = Files.createTempDirectory("mirror").toFile().resolve("repo.git")
    private val work = Files.createTempDirectory("work").toFile()
    private val scratch = Files.createTempDirectory("scratch").toFile()

    init {
        git(origin, "init", "-q")
        pinned = commit(pinnedText)
        head = commit(headText)
        git(origin.parentFile, "clone", "-q", "--bare", origin.path, mirror.path)
    }

    private fun git(dir: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + args)
            .directory(dir).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")}: $out" }
        return out.trim()
    }

    private fun commit(text: String): String {
        File(origin, path).apply { parentFile.mkdirs() }.writeText(text)
        git(origin, "add", "-A")
        git(origin, "commit", "-q", "-m", "fixture")
        return git(origin, "rev-parse", "HEAD")
    }

    private fun fixtures() = RepositoryFixtures(
        mirror = mirror,
        worktrees = { (job, sha) -> listOf(File(work, "$job"), File(work, "$job@${sha.take(12)}")) },
        worktreeRoots = listOf(work.path, "/home/ubuntu/work"),
    )

    private val problem = Problem("smtlib-qfnra-source-canonical-v1", "degeneracy/degree-drop",
        collection = "klause-bench/smoke-corpus/qfnra-source-canonical-v1/degeneracy", format = "SMTLIB")

    private fun locate(problem: Problem, hash: String, sha: String = pinned, corpus: File = Files.createTempDirectory("corpus").toFile()) =
        SourceLocator(corpus, fixtures = fixtures()).locate(problem, mapOf("model" to hash), Revision(7407, sha), scratch)

    @Test
    fun `a repository fixture is read from the mirror at its pinned commit and verified by hash`() {
        val found = locate(problem, pinnedHash).getOrThrow()

        assertEquals(pinnedText, found.model.readText())
        assertTrue(found.model.startsWith(scratch), "the mirror's copy goes in the check's scratch directory")
        assertTrue(fixtures().owns(problem))
    }

    @Test
    fun `the pinned bytes are used even when the file has changed since, and a hash mismatch is never checked against`() {
        assertEquals(pinnedText, locate(problem, pinnedHash).getOrThrow().model.readText())
        assertEquals(headText, locate(problem, headHash, sha = head).getOrThrow().model.readText())

        // The record solved the pinned file; the file as it is now does not stand in for it, nor the other way round.
        val mismatch = locate(problem, headHash).exceptionOrNull()
        assertContains(mismatch?.message.orEmpty(), "SHA-256 $headHash")
        assertTrue(scratch.walkTopDown().none { it.isFile }, "a copy that failed its hash is deleted")
    }

    @Test
    fun `a path the pinned commit lacks leaves the case unchecked`() {
        val missing = problem.copy(problem = "degeneracy/absent")
        assertContains(locate(missing, pinnedHash).exceptionOrNull()?.message.orEmpty(), "no file for degeneracy/absent")

        val planned = problem.copy(model = "${work.path}/7407/klause-bench/smoke-corpus/gone.smt2")
        assertContains(locate(planned, pinnedHash).exceptionOrNull()?.message.orEmpty(), "klause-bench/smoke-corpus/gone.smt2 is not in klause")

        val unknown = locate(problem, pinnedHash, sha = "0".repeat(40)).exceptionOrNull()?.message.orEmpty()
        assertContains(unknown, "is not in the lab's mirror")

        val unpinned = SourceLocator(work, fixtures = fixtures()).locate(problem, mapOf("model" to pinnedHash), null, scratch)
        assertContains(unpinned.exceptionOrNull()?.message.orEmpty(), "no pinned klause commit")
        val unhashed = SourceLocator(work, fixtures = fixtures()).locate(problem, emptyMap(), Revision(7407, pinned), scratch)
        assertContains(unhashed.exceptionOrNull()?.message.orEmpty(), "no source hash")
    }

    @Test
    fun `a cloud node's planned worktree path maps to the repository path`() {
        val fixtures = fixtures()
        val node = "/home/ubuntu/work/${pinned.take(12)}/$path"
        assertEquals(path, fixtures.relative(node))
        assertEquals(path, fixtures.relative("${work.path}/7407@${pinned.take(12)}/$path"))
        assertNull(fixtures.relative("/home/ubuntu/corpus/xcsp3/a.xml"))

        val found = locate(problem.copy(model = node), pinnedHash).getOrThrow()
        assertEquals(pinnedText, found.model.readText())
        // A worktree on a host the lab does not know is cut at the collection.
        val elsewhere = "/Users/someone/eignex-lab-data/work/7407@${pinned.take(12)}/$path"
        assertEquals(pinnedText, locate(problem.copy(model = elsewhere), pinnedHash).getOrThrow().model.readText())
    }

    @Test
    fun `the job's worktree of the commit is read in place while it exists`() {
        val tree = File(work, "7407@${pinned.take(12)}/$path").apply { parentFile.mkdirs(); writeText(pinnedText) }

        assertEquals(tree, locate(problem, pinnedHash).getOrThrow().model)
        assertTrue(scratch.walkTopDown().none { it.isFile })

        // A worktree file that does not match is passed over for the pinned commit's bytes.
        tree.writeText(headText)
        assertEquals(pinnedText, locate(problem, pinnedHash).getOrThrow().model.readText())
    }

    @Test
    fun `corpus sources are found in the corpus as before`() {
        val corpus = Files.createTempDirectory("corpus").toFile()
        val source = File(corpus, "smt-test/QF_NRA/circle.smt2").apply { parentFile.mkdirs(); writeText(pinnedText) }
        val corpusProblem = Problem("smt-test", "QF_NRA/circle", collection = "smt-test", format = "SMTLIB")

        assertFalse(fixtures().owns(corpusProblem))
        assertEquals(source, locate(corpusProblem, pinnedHash, corpus = corpus).getOrThrow().model)
        assertContains(locate(corpusProblem.copy(collection = "absent"), pinnedHash, corpus = corpus).exceptionOrNull()?.message.orEmpty(),
            "is not in the lab's corpus")
    }

    @Test
    fun `a check reads the fixture into its scratch directory and deletes it afterwards`() {
        val seen = mutableListOf<String>()
        val adapter = object : FormatChecker {
            override val format = "SMTLIB"
            override val policy = Policies.SMTLIB
            override val checker = "fake"
            override fun check(case: CheckCase, work: File): Verdict {
                seen += case.sources.model.readText()
                return Verdict(LabCheck.VALID, "accepted")
            }
        }
        val workRoot = Files.createTempDirectory("checks").toFile()
        val checker = SourceChecker(listOf(adapter), SourceLocator(work, fixtures = fixtures()), workRoot)
        fun record(hash: String): JsonObject = buildJsonObject {
            put("solver", "klause")
            put("kind", "satisfy")
            put("feasible", true)
            put("finalWitness", "(\n  (define-fun x () Real 0.0)\n  (define-fun y () Real 1.0)\n)")
            put("sourceHashes", buildJsonObject { put("model", hash) })
        }

        val valid = checker.check(problem, record(pinnedHash), Revision(7407, pinned))!!
        assertEquals(LabCheck.VALID, valid.state)
        assertEquals(listOf(pinnedText), seen)
        assertTrue(workRoot.walkTopDown().none { it.isFile }, "the copy is deleted with the check's scratch directory")

        val mismatch = checker.check(problem, record(headHash), Revision(7407, pinned))!!
        assertEquals(LabCheck.NOT_RUN, mismatch.state)
        assertContains(mismatch.reason, "original source not found on the lab")
        assertEquals(1, seen.size)
    }
}
