package com.eignex.lab

import com.eignex.lab.util.*
import com.eignex.lab.domain.*
import com.eignex.lab.store.*
import com.eignex.lab.execution.*

import com.eignex.lab.bench.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class BenchSelectionsTest {
    private val root = Files.createTempDirectory("bench-selections").toAbsolutePath()
    private val commit = "a".repeat(40)
    private fun tree(name: String, manifest: String, files: Map<String, String> = emptyMap()): Path =
        Files.createDirectories(root.resolve(name)).also { tree ->
            (files + ("bench/lab/bench.json" to manifest)).forEach { (path, text) ->
                Files.createDirectories(tree.resolve(path).parent); Files.writeString(tree.resolve(path), text)
            }
        }
    private val spec = BenchSpec("bench/lab/bench.json")
    private fun key(tree: Path, request: BenchRequest = BenchRequest(mapOf("set" to "sweep")), sha: String = commit,
                    release: String = "release", corpus: String = "local", bench: BenchSpec = spec) =
        BenchSelections.key(release, Repositories.DEFAULT, bench, request, sha, tree, corpus)

    @Test fun `an undeclared manifest keys on the commit and the request, not on file contents`() {
        val base = tree("commit", """{"main":"a.B"}""", mapOf("bench/sets/sweep.txt" to "p1"))
        val first = key(base)
        assertEquals(first, key(base))
        Files.writeString(base.resolve("bench/sets/sweep.txt"), "p2")
        assertEquals(first, key(base), "the commit already identifies every file of the tree")
        assertNotEquals(first, key(base, sha = "b".repeat(40)))
        assertNotEquals(first, key(base, BenchRequest(mapOf("set" to "other"))))
        assertNotEquals(first, key(base, release = "next"))
        assertNotEquals(first, key(base, corpus = "shared:https://inputs.invalid/"))
        assertNotEquals(first, key(base, bench = BenchSpec("bench/lab/other.json").also {
            Files.copy(base.resolve("bench/lab/bench.json"), base.resolve("bench/lab/other.json"))
        }))
        val ordered = BenchRequest(linkedMapOf("a" to "1", "b" to "2"))
        assertEquals(key(base, ordered), key(base, BenchRequest(linkedMapOf("b" to "2", "a" to "1"))))
        // Frozen cases and coordinator filters apply after the cached plan.
        assertEquals(first, key(base, bench = spec.copy(frozenCases = listOf(BenchCase("p")), referenceFilter = ReferenceFilterMode.PROVEN)))
    }

    @Test fun `declared plan inputs key on manifest and declared file contents across commits`() {
        val manifest = """{"main":"a.B","planInputs":["bench/sets","bench/src"]}"""
        val base = tree("declared", manifest, mapOf("bench/sets/sweep.txt" to "p1", "bench/src/Plan.kt" to "plan", "core/Solver.kt" to "v1"))
        val first = key(base)
        assertEquals(first, key(base, sha = "b".repeat(40)), "declared inputs make the plan independent of the commit")
        Files.writeString(base.resolve("core/Solver.kt"), "v2")
        assertEquals(first, key(base), "undeclared files do not determine the plan")
        Files.writeString(base.resolve("bench/sets/sweep.txt"), "p2")
        val changedSet = key(base)
        assertNotEquals(first, changedSet)
        Files.writeString(base.resolve("bench/src/Added.kt"), "new")
        assertNotEquals(changedSet, key(base))
        Files.delete(base.resolve("bench/src/Added.kt"))
        assertEquals(changedSet, key(base))
        Files.writeString(base.resolve("bench/lab/bench.json"), manifest.replace("\"a.B\"", "\"a.C\""))
        assertNotEquals(changedSet, key(base), "the manifest is always a plan input")
        assertNotEquals(changedSet, key(base, BenchRequest(mapOf("set" to "other"))))
        Files.createSymbolicLink(base.resolve("bench/sets/link.txt"), base.resolve("core/Solver.kt"))
        assertFailsWith<IllegalArgumentException> { key(base) }
    }

    @Test fun `source digests bind source inputs and shared inputs must cover corpus inputs`() {
        val source = tree("source", "{}", mapOf("data/a.txt" to "a", "data/dir/b.txt" to "b"))
        val plan = BenchPlan(listOf(BenchCase("p", inputs = listOf(BenchInput("data/a.txt"), BenchInput("data/dir"),
            BenchInput("set/x.cnf", BenchInputRoot.CORPUS), BenchInput("set/y", BenchInputRoot.CORPUS)))))
        val digest = BenchSelections.sourceDigest(plan, source, BenchInputDigests())
        assertEquals(digest, BenchSelections.sourceDigest(plan, source, BenchInputDigests()))
        Files.writeString(source.resolve("data/dir/b.txt"), "changed")
        assertNotEquals(digest, BenchSelections.sourceDigest(plan, source, BenchInputDigests()))
        assertEquals(listOf("set/x.cnf", "set/y"), BenchSelections.corpusPaths(plan))
        fun input(path: String) = SharedInput(InputFile(path, ExecutionStore.digest(path), 1), "corpus/$path")
        val inputs = listOf(input("set/x.cnf"), input("set/y/1.cnf"), input("set/y/2.cnf"), input("set/z.cnf"), input("set/yy.cnf"))
        assertEquals(inputs.take(3), BenchSelections.covering(inputs, BenchSelections.corpusPaths(plan)))
        assertNull(BenchSelections.covering(inputs.drop(1), BenchSelections.corpusPaths(plan)))
        assertNull(BenchSelections.covering(listOf(input("set/x.cnf"), input("set/yy.cnf")), BenchSelections.corpusPaths(plan)))
    }
}
