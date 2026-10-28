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
            val identity = WorkerBundle("a".repeat(64), listOf(InputFile("placeholder", "b".repeat(64), 0)), "Eignex/klause", "a".repeat(40), "linux", "amd64", "fixture")
            val bundle = archive(source, output, identity, runtimeOnly = true) {}
            assertEquals(kept.toSet(), bundle.files.map { it.path }.toSet())
            assertEquals(hashFile(output), bundle.sha256)
            val restored = VerifiedCache(root.resolve("verified")).stage(bundle) { Files.newInputStream(output) }
            for (name in kept) assertEquals(name, Files.readString(restored.resolve(name)))
            val complete = root.resolve("complete")
            WorkerTrees.copy(source, complete) {}
            for (name in omitted) assertEquals(name, Files.readString(complete.resolve(name)))
        } finally { deleteTree(root) }
    }
}
