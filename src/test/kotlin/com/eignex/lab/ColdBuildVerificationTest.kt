package com.eignex.lab

import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import com.eignex.lab.worker.*

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class ColdBuildVerificationTest {
    @Test fun `checkout verification retains nested source and git files unchanged`() {
        val root = Files.createTempDirectory("checkout-metadata")
        try {
            for (name in listOf("source/main.kt", ".git/objects/pack/approved.pack")) {
                val path = root.resolve(name); Files.createDirectories(path.parent); Files.writeString(path, name)
            }
            verifyCheckoutTree(root) {}
            assertEquals("source/main.kt", Files.readString(root.resolve("source/main.kt")))
            assertEquals(".git/objects/pack/approved.pack", Files.readString(root.resolve(".git/objects/pack/approved.pack")))
        } finally { deleteTree(root) }
    }

    @Test fun `checkout verification rejects source git directory and broken links without following them`() {
        val root = Files.createTempDirectory("checkout-links")
        val outside = Files.createTempDirectory("checkout-outside")
        try {
            Files.writeString(outside.resolve("keep"), "safe")
            for ((name, target) in listOf("source-link" to outside.resolve("keep"), ".git/objects/linked" to outside.resolve("keep"),
                    "directory-link" to outside, "broken-link" to outside.resolve("missing"))) {
                val link = root.resolve(name); Files.createDirectories(link.parent); Files.createSymbolicLink(link, target)
                assertFailsWith<IllegalArgumentException> { verifyCheckoutTree(root) {} }
                assertTrue(Files.isSymbolicLink(link)); Files.delete(link)
                assertEquals("safe", Files.readString(outside.resolve("keep")))
            }
        } finally { deleteTree(root); deleteTree(outside) }
    }

    @Test fun `checkout verification checks cancellation at directories and files`() {
        for (directories in listOf(false, true)) {
            val root = Files.createTempDirectory("checkout-cancel")
            try {
                if (directories) Files.createDirectories(root.resolve("one/two/three"))
                else repeat(5) { Files.writeString(root.resolve("file-$it"), "source") }
                var checks = 0
                assertFailsWith<java.util.concurrent.CancellationException> {
                    verifyCheckoutTree(root) { if (++checks == 3) throw java.util.concurrent.CancellationException() }
                }
                assertEquals(3, checks)
            } finally { deleteTree(root) }
        }
    }

    @Test fun `checkout verification propagates missing root instead of accepting partial input`() {
        val root = Files.createTempDirectory("checkout-missing")
        try {
            assertFailsWith<java.nio.file.NoSuchFileException> { verifyCheckoutTree(root.resolve("missing")) {} }
        } finally { deleteTree(root) }
    }

    private val manifest = """{"main":"example.Main","classpath":["runtime/*"],"buildTasks":[],"workspaceInputs":["runtime","runtime/code.jar",".git"],"planInputs":["planner","missing"]}"""
    private fun source(root: Path): Path {
        val source = Files.createDirectory(root.resolve("source"))
        for ((name, content) in mapOf("bench.json" to manifest, "runtime/code.jar" to "runtime", "planner/code.kt" to "planner",
                ".git/HEAD" to "approved", "unrelated/source.kt" to "x".repeat(1024), "other/build/classes/code.class" to "scratch")) {
            val file = source.resolve(name); Files.createDirectories(file.parent); Files.writeString(file, content)
        }
        return source
    }
    private fun bundle() = WorkerBundle("a".repeat(64), listOf(InputFile("placeholder", "b".repeat(64), 0)),
        "Eignex/example", "c".repeat(40), "linux", "amd64", "fixture", build = WorkerBuild(benchManifest = "bench.json"))

    @Test fun `cold declared build seals only runtime and planner inputs and drains discarded files`() {
        val root = Files.createTempDirectory("cold-build-scope")
        val cache = WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(root.resolve("unused")), maxBytes = 512)
        try {
            val source = source(root)
            val built = cache.withPreparedSource(bundle(), ResourceBudget(1000, 512), emptyMap(), { null }, { 10_000 },
                source = { it(source) }, declaredWorkspace = true) { it }
            val entry = Json.parseToJsonElement(Files.readString(built.parent.resolve("manifest.json"))).jsonObject
            assertEquals("bench.json", entry.getValue("workspaceManifest").jsonPrimitive.content)
            assertEquals(setOf("bench.json", "runtime/code.jar", "planner/code.kt", ".git/HEAD"),
                entry.getValue("files").jsonArray.map { it.jsonObject.getValue("path").jsonPrimitive.content }.toSet())
            assertFalse(Files.exists(built.resolve("unrelated")))
            assertFalse(Files.exists(built.resolve("other")))
            assertEquals("planner", Files.readString(built.resolve("planner/code.kt")))
            assertEquals("approved", Files.readString(built.resolve(".git/HEAD")))
            val trace = PreparationTrace()
            cache.withPreparedSource(bundle(), ResourceBudget(1000, 512), emptyMap(), { null }, { 10_000 }, trace,
                source = { error("warm scoped tree must not reacquire source") }, declaredWorkspace = true) { assertEquals(built, it) }
            assertEquals("hit", trace.snapshot().caches.getValue("build").outcome)
            assertEquals(0, trace.snapshot().caches.getValue("build").hashedFiles)
            assertTrue(cache.awaitMaintenanceIdle())
            assertTrue(Files.list(root.resolve("builds")).use { paths -> paths.noneMatch { it.fileName.toString().startsWith(".evict-") } })
        } finally { assertTrue(cache.awaitMaintenanceIdle()); deleteTree(root) }
    }

    @Test fun `changed retained planner runtime and manifest bytes rebuild instead of reusing stale proof`() {
        for (name in listOf("planner/code.kt", "runtime/code.jar", "bench.json")) {
            val root = Files.createTempDirectory("cold-build-corruption")
            val cache = WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(root.resolve("unused")))
            try {
                val source = source(root); var acquisitions = 0
                fun prepared() = cache.withPreparedSource(bundle(), ResourceBudget(1000, 512), emptyMap(), { null }, { 10_000 },
                    source = { acquisitions++; it(source) }, declaredWorkspace = true) { it }
                val built = prepared(); val file = built.resolve(name); val modified = Files.getLastModifiedTime(file)
                Files.writeString(file, "corrupt"); Files.setLastModifiedTime(file, modified)
                assertEquals(Files.readString(source.resolve(name)), Files.readString(prepared().resolve(name)))
                assertEquals(2, acquisitions)
            } finally { assertTrue(cache.awaitMaintenanceIdle()); deleteTree(root) }
        }
    }

    @Test fun `full checkout consumers rebuild scoped entries and undeclared benches keep all files`() {
        val root = Files.createTempDirectory("cold-build-full")
        val cache = WorkerBuildCache(root.resolve("builds"), ProcessSupervisor(root.resolve("unused")))
        try {
            val source = source(root); var acquisitions = 0
            fun prepared(declared: Boolean) = cache.withPreparedSource(bundle(), ResourceBudget(1000, 512), emptyMap(), { null }, { 10_000 },
                source = { acquisitions++; it(source) }, declaredWorkspace = declared) { it }
            assertFalse(Files.exists(prepared(true).resolve("unrelated/source.kt")))
            assertEquals("x".repeat(1024), Files.readString(prepared(false).resolve("unrelated/source.kt")))
            assertEquals(2, acquisitions)
            prepared(true); assertEquals(2, acquisitions, "a full verified tree remains reusable for declared preparation")
            Files.writeString(source.resolve("bench.json"), """{"main":"example.Main","classpath":["runtime/*"],"buildTasks":[]}""")
            val different = bundle().copy(commit = "d".repeat(40))
            cache.withPreparedSource(different, ResourceBudget(1000, 512), emptyMap(), { null }, { 10_000 },
                source = { it(source) }, declaredWorkspace = true) { assertTrue(Files.exists(it.resolve("unrelated/source.kt"))) }
        } finally { assertTrue(cache.awaitMaintenanceIdle()); deleteTree(root) }
    }

    @Test fun `retention moves declared roots without copying and a crashed cleanup remains discoverable`() {
        val root = Files.createTempDirectory("cold-build-move")
        val storage = WorkerBuildStaging(root); val lease = storage.create {}
        try {
            val tree = Files.createDirectory(lease.directory.resolve("tree"))
            val kept = Files.createDirectories(tree.resolve("runtime")); Files.writeString(kept.resolve("code"), "kept")
            Files.writeString(tree.resolve("discarded"), "discarded")
            val key = Files.readAttributes(kept.resolve("code"), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
            val trash = storage.retain(lease, listOf("runtime/code", "runtime", "missing")) {}
            assertEquals(key, Files.readAttributes(tree.resolve("runtime/code"), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey())
            assertEquals("discarded", Files.readString(trash.resolve("discarded")))
            assertFalse(Files.exists(trash.resolve("runtime")))
            WorkerBuildCache(root, ProcessSupervisor(root.resolve("unused"))).maintain()
            assertFalse(Files.exists(trash)); assertEquals("kept", Files.readString(tree.resolve("runtime/code")))
        } finally { storage.close(lease); deleteTree(root) }
    }
}
