package com.eignex.lab.checker

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

import com.eignex.lab.domain.Problem
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A MiniZinc model and its data are found as one pair, each verified by its own hash, in the corpus and in the klause
 * repository alike. Sweep 7671's cases 63 (`2013/ghoulomb/3-9-16`) and 78 (`2020/p1f-pjs/10`) were `not-run` because
 * the same data bytes sit in an earlier year's directory (`2010/ghoulomb/3-9-16.dzn`, `2009/p1f/10.dzn`) whose model is
 * another, and the first data file of the name was the only one whose directories were searched for the model.
 */
class SourcePairTest {
    private val corpus = Files.createTempDirectory("corpus").toFile()
    private val scratch = Files.createTempDirectory("scratch").toFile()

    private fun file(path: String, text: String) = File(corpus, path).apply { parentFile.mkdirs(); writeText(text) }

    private val data = "n = 3;\n"
    private val earlier = "int: n;\nvar 1..n: x;\nconstraint x = 1;\nsolve satisfy;\n"
    private val later = "int: n;\nvar 1..n: x;\nconstraint x = n;\nsolve satisfy;\n"
    private val other = "int: n;\nvar 0..n: y;\nsolve satisfy;\n"

    private fun hashes(model: String, data: String = this.data) = mapOf("model" to sha256(model.toByteArray()), "data" to sha256(data.toByteArray()))

    /** The 7671 layout: the solved data twice, the earlier copy beside another model, and a third under another family. */
    private fun ghoulomb() {
        file("mzn-challenge/2010/ghoulomb/ghoulomb.mzn", earlier)
        file("mzn-challenge/2010/ghoulomb/3-9-16.dzn", data)
        file("mzn-challenge/2013/ghoulomb/ghoulomb.mzn", later)
        file("mzn-challenge/2013/ghoulomb/3-9-16.dzn", data)
        file("mzn-challenge/2012/still-life-wastage/still-life.mzn", other)
        file("mzn-challenge/2012/still-life-wastage/3-9-16.dzn", data)
    }

    private val problem = Problem("mzn-bench", "2013/ghoulomb/3-9-16", collection = "mzn-challenge", family = "2013/ghoulomb", format = "MINIZINC")

    private fun locate(problem: Problem, hashes: Map<String, String>) = SourceLocator(corpus, hash = ::sha256).locate(problem, hashes)

    @Test
    fun `the matching model and data pair is found whichever directory with the same data comes first`() {
        ghoulomb()
        val found = locate(problem, hashes(later)).getOrThrow()
        assertEquals(File(corpus, "mzn-challenge/2013/ghoulomb/ghoulomb.mzn"), found.model)
        assertEquals(File(corpus, "mzn-challenge/2013/ghoulomb/3-9-16.dzn"), found.data)

        // The same name with the earlier model's hash is that pair, so neither path order nor the name decides.
        val first = locate(problem, hashes(earlier)).getOrThrow()
        assertEquals(File(corpus, "mzn-challenge/2010/ghoulomb/ghoulomb.mzn"), first.model)
        assertEquals(File(corpus, "mzn-challenge/2010/ghoulomb/3-9-16.dzn"), first.data)
        val third = locate(problem, hashes(other)).getOrThrow()
        assertEquals(File(corpus, "mzn-challenge/2012/still-life-wastage/still-life.mzn"), third.model)
    }

    @Test
    fun `a model in a data file's parent directory pairs with it and the collection's libraries stay the includes`() {
        file("mzn-challenge/2009/p1f/p1f.mzn", earlier)
        file("mzn-challenge/2009/p1f/10.dzn", data)
        file("mzn-challenge/2020/p1f-pjs/p1f-pjs.mzn", later)
        file("mzn-challenge/2020/p1f-pjs/data/10.dzn", data)
        val lib = File(corpus, "mzn-challenge/lib").apply { mkdirs() }

        val found = locate(problem.copy(problem = "2020/p1f-pjs/10", family = "2020/p1f-pjs"), hashes(later)).getOrThrow()
        assertEquals(File(corpus, "mzn-challenge/2020/p1f-pjs/p1f-pjs.mzn"), found.model)
        assertEquals(File(corpus, "mzn-challenge/2020/p1f-pjs/data/10.dzn"), found.data)
        assertEquals(listOf(lib), found.includeDirs)
    }

    @Test
    fun `no pair whose model and data both match leaves the case unchecked, saying why`() {
        ghoulomb()
        // The data matches in three places but no model beside or above any of them has the solved model's bytes.
        val noModel = locate(problem, hashes("solve satisfy;\n")).exceptionOrNull()?.message.orEmpty()
        assertContains(noModel, "no model with the solved model of 2013/ghoulomb/3-9-16's SHA-256")
        assertContains(noModel, "3 data file(s)")

        // The model is there but no data file of the name has the solved data's bytes: the model is not taken alone.
        val noData = locate(problem, hashes(later, data = "n = 4;\n")).exceptionOrNull()?.message.orEmpty()
        assertContains(noData, "no file in ${File(corpus, "mzn-challenge")} has the solved data of 2013/ghoulomb/3-9-16's SHA-256")

        // A record that names only one of the two hashes is never paired with an unverified file.
        val modelOnly = locate(problem.copy(data = "/home/ubuntu/corpus/mzn-challenge/2013/ghoulomb/3-9-16.dzn"),
            mapOf("model" to sha256(later.toByteArray()))).exceptionOrNull()?.message.orEmpty()
        assertContains(modelOnly, "names no data hash")
        val dataOnly = locate(problem, mapOf("data" to sha256(data.toByteArray()))).exceptionOrNull()?.message.orEmpty()
        assertContains(dataOnly, "names no model hash")

        val unnamed = locate(problem.copy(problem = "2013/ghoulomb/absent"), hashes(later)).exceptionOrNull()?.message.orEmpty()
        assertContains(unnamed, "no file for the data of 2013/ghoulomb/absent")
    }

    @Test
    fun `a cloud case's planned pair is moved to the lab's corpus and taken only when both files match`() {
        ghoulomb()
        val node = SourceLocator.NODE_CORPUS
        val planned = problem.copy(model = "$node/mzn-challenge/2013/ghoulomb/ghoulomb.mzn", data = "$node/mzn-challenge/2013/ghoulomb/3-9-16.dzn")
        val found = locate(planned, hashes(later)).getOrThrow()
        assertEquals(File(corpus, "mzn-challenge/2013/ghoulomb/ghoulomb.mzn"), found.model)
        assertEquals(File(corpus, "mzn-challenge/2013/ghoulomb/3-9-16.dzn"), found.data)

        // A planned model whose data does not match is not taken with any other data: the search finds the verified pair.
        File(corpus, "mzn-challenge/2013/ghoulomb/3-9-16.dzn").writeText("n = 5;\n")
        val searched = locate(planned, hashes(later)).exceptionOrNull()?.message.orEmpty()
        assertContains(searched, "no model with the solved model")
        // A planned model alone is no pair when the record solved data too.
        File(corpus, "mzn-challenge/2013/ghoulomb/3-9-16.dzn").writeText(data)
        val modelOnly = locate(planned.copy(data = null), hashes(later)).getOrThrow()
        assertEquals(File(corpus, "mzn-challenge/2013/ghoulomb/3-9-16.dzn"), modelOnly.data, "the data is still found and verified")
    }

    @Test
    fun `a check without a verified pair is not-run and a check with one sees both files`() {
        ghoulomb()
        val seen = mutableListOf<Pair<String, String?>>()
        val adapter = object : FormatChecker {
            override val format = "MINIZINC"
            override val policy = Policies.MINIZINC
            override val checker = "fake"
            override fun check(case: CheckCase, work: File): Verdict {
                seen += case.sources.model.readText() to case.sources.data?.readText()
                return Verdict(LabCheck.VALID, "accepted")
            }
        }
        val checker = SourceChecker(listOf(adapter), SourceLocator(corpus, hash = ::sha256), Files.createTempDirectory("checks").toFile())
        fun record(hashes: Map<String, String>): JsonObject = buildJsonObject {
            put("solver", "klause")
            put("kind", "satisfy")
            put("feasible", true)
            put("finalWitness", "x = 3;")
            put("sourceHashes", buildJsonObject { hashes.forEach { (k, v) -> put(k, v) } })
        }

        assertEquals(LabCheck.VALID, checker.check(problem, record(hashes(later)))!!.state)
        assertEquals(listOf<Pair<String, String?>>(later to data), seen)
        val partial = checker.check(problem, record(hashes(later, data = "n = 4;\n")))!!
        assertEquals(LabCheck.NOT_RUN, partial.state)
        assertContains(partial.reason, "original source not found on the lab")
        assertEquals(1, seen.size, "no check runs on half a pair")
    }

    // Repository fixtures: the pair at the case's pinned commit.

    private val origin = Files.createTempDirectory("klause").toFile()
    private val mirror = Files.createTempDirectory("mirror").toFile().resolve("repo.git")
    private val work = Files.createTempDirectory("work").toFile()
    private val collection = "klause-bench/smoke-corpus/exact-globals"

    private fun git(dir: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false") + args)
            .directory(dir).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")}: $out" }
        return out.trim()
    }

    /** A pinned commit with two models and two data files in the collection, and a later one that changes the data. */
    private fun repository(): Pair<String, String> {
        git(origin, "init", "-q")
        fun write(name: String, text: String) = File(origin, "$collection/$name").apply { parentFile.mkdirs() }.writeText(text)
        write("driver.mzn", later)
        write("other.mzn", earlier)
        write("0-0.dzn", data)
        write("0-1.dzn", "n = 4;\n")
        git(origin, "add", "-A")
        git(origin, "commit", "-q", "-m", "pinned")
        val pinned = git(origin, "rev-parse", "HEAD")
        write("0-0.dzn", "n = 6;\n")
        git(origin, "commit", "-q", "-am", "head")
        val head = git(origin, "rev-parse", "HEAD")
        git(origin.parentFile, "clone", "-q", "--bare", origin.path, mirror.path)
        return pinned to head
    }

    private fun fixtureLocator() = SourceLocator(corpus, hash = ::sha256, fixtures = RepositoryFixtures(
        mirror = mirror,
        worktrees = { (job, sha) -> listOf(File(work, "$job"), File(work, "$job@${sha.take(12)}")) },
        worktreeRoots = listOf(work.path, "/home/ubuntu/work"),
    ))

    private val vendored = Problem("exact-globals", "bin-packing-0-0", collection = collection, format = "MINIZINC")

    @Test
    fun `a repository model and data are read at the pinned commit as a verified pair, on the lab or a cloud node`() {
        val (pinned, head) = repository()
        val found = fixtureLocator().locate(vendored, hashes(later), Revision(7700, pinned), scratch).getOrThrow()
        assertEquals(later, found.model.readText())
        assertEquals(data, found.data!!.readText())
        assertTrue(found.model.startsWith(scratch) && found.data!!.startsWith(scratch))

        // A cloud node's planned worktree paths map to the repository paths of the same pair.
        val node = "/home/ubuntu/work/${pinned.take(12)}/$collection"
        val planned = vendored.copy(model = "$node/driver.mzn", data = "$node/0-0.dzn")
        val cloud = fixtureLocator().locate(planned, hashes(later), Revision(7700, pinned), scratch).getOrThrow()
        assertEquals(later to data, cloud.model.readText() to cloud.data!!.readText())

        // At the head commit 0-0.dzn changed: the pinned data's hash is no file there, and nothing stands in for it.
        val moved = fixtureLocator().locate(planned, hashes(later), Revision(7700, head), scratch).exceptionOrNull()?.message.orEmpty()
        assertContains(moved, "has the solved data's SHA-256")
    }

    @Test
    fun `a repository pair with a mismatched or missing half leaves the case unchecked`() {
        val (pinned, _) = repository()
        val revision = Revision(7700, pinned)
        val noData = fixtureLocator().locate(vendored, hashes(later, data = "n = 9;\n"), revision, scratch).exceptionOrNull()?.message.orEmpty()
        assertContains(noData, "has the solved data's SHA-256")
        val noModel = fixtureLocator().locate(vendored, hashes("solve satisfy;\n"), revision, scratch).exceptionOrNull()?.message.orEmpty()
        assertContains(noModel, "has the solved model's SHA-256")
        val half = fixtureLocator().locate(vendored.copy(data = "${work.path}/7700/$collection/0-0.dzn"),
            mapOf("model" to sha256(later.toByteArray())), revision, scratch).exceptionOrNull()?.message.orEmpty()
        assertContains(half, "names no data hash")
        assertTrue(scratch.walkTopDown().none { it.isFile && it.name.endsWith(".dzn") }, "a data copy that failed its hash is deleted")
    }
}
