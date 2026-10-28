package com.eignex.lab

import java.nio.file.Files
import kotlin.test.*

class WorkerTreesTest {
    @Test fun `default runtime copies omit compiler intermediates but retain distributions sources and Git`() {
        val root = Files.createTempDirectory("runtime-copy")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            val kept = listOf("module/build/install/app/lib/app.jar", "module/build/libs/app.jar", "module/src/Main.kt", ".git/objects/pack/commit.pack", "build/provenance.json")
            val omitted = listOf("module/build/classes/Main.class", "module/build/kotlin/cache", "module/build/generated/source", "build/tmp/scratch")
            for (name in kept + omitted) {
                val file = source.resolve(name); Files.createDirectories(file.parent); Files.writeString(file, name)
            }
            val private = root.resolve("runtime")
            WorkerTrees.copyRuntime(source, private) {}
            for (name in kept) assertEquals(name, Files.readString(private.resolve(name)))
            for (name in omitted) assertFalse(Files.exists(private.resolve(name)), name)
            Files.writeString(private.resolve(kept.first()), "changed privately")
            assertEquals(kept.first(), Files.readString(source.resolve(kept.first())))
            val output = root.resolve("runtime.zip")
            val identity = WorkerBundle("a".repeat(64), listOf(InputFile("placeholder", "b".repeat(64), 0)), "Eignex/example", "a".repeat(40), "linux", "amd64", "fixture")
            val bundle = archive(source, output, identity, runtimeOnly = true) {}
            assertEquals(kept.toSet(), bundle.files.map { it.path }.toSet())
            assertEquals(hashFile(output), bundle.sha256)
            val repeated = root.resolve("repeated.zip")
            assertEquals(bundle, archive(source, repeated, identity, runtimeOnly = true) {})
            assertContentEquals(Files.readAllBytes(output), Files.readAllBytes(repeated))
            val restored = VerifiedCache(root.resolve("verified")).stage(bundle) { Files.newInputStream(output) }
            for (name in kept) assertEquals(name, Files.readString(restored.resolve(name)))
            val oldOutput = root.resolve("full.zip")
            val oldBundle = archive(source, oldOutput, identity) {}
            val inputCache = WorkerInputCache(root.resolve("inputs")); var downloads = 0
            inputCache.useRuntime(oldBundle, {}, { downloads++; Files.newInputStream(oldOutput) }) { runtime ->
                for (name in kept) assertTrue(Files.exists(runtime.resolve(name)))
                for (name in omitted) assertFalse(Files.exists(runtime.resolve(name)))
                assertEquals(runtime, inputCache.useRuntime(oldBundle, {}, { error("warm runtime") }) { it })
            }
            inputCache.use(oldBundle, {}, { downloads++; Files.newInputStream(oldOutput) }) { full ->
                for (name in omitted) assertTrue(Files.exists(full.resolve(name)))
            }
            assertEquals(2, downloads)
            val corruptSkipped = oldBundle.copy(files = oldBundle.files.map { if (it.path == omitted.first()) it.copy(sha256 = "0".repeat(64)) else it })
            assertFailsWith<IllegalArgumentException> {
                inputCache.useRuntime(corruptSkipped, {}, { Files.newInputStream(oldOutput) }) { error("corrupt discarded input accepted") }
            }
            val complete = root.resolve("complete")
            WorkerTrees.copy(source, complete) {}
            for (name in omitted) assertEquals(name, Files.readString(complete.resolve(name)))
        } finally { deleteTree(root) }
    }
    @Test fun `runtime archives do not walk excluded compiler trees`() {
        val root = Files.createTempDirectory("runtime-archive-inventory")
        try {
            val source = Files.createDirectory(root.resolve("source"))
            val scratch = Files.createDirectories(source.resolve("module/build/classes"))
            repeat(400) { Files.writeString(scratch.resolve("Class$it.class"), "compiled") }
            Files.writeString(source.resolve("runtime.jar"), "distribution")
            val identity = WorkerBundle("a".repeat(64), listOf(InputFile("placeholder", "b".repeat(64), 0)), "Eignex/klause", "a".repeat(40), "linux", "amd64", "fixture")
            var checks = 0
            val runtime = archive(source, root.resolve("runtime.zip"), identity, runtimeOnly = true) { checks++ }
            assertEquals(listOf("runtime.jar"), runtime.files.map { it.path })
            assertTrue(checks < 30, "runtime archive visited discarded compiler files: $checks")
            val full = archive(source, root.resolve("full.zip"), identity) {}
            assertEquals(401, full.files.size)
        } finally { deleteTree(root) }
    }

}
