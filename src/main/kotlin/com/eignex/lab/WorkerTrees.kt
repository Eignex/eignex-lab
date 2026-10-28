package com.eignex.lab

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.util.concurrent.TimeUnit

/** Private mutable worktrees keep the shared verified distribution immutable. Prefer filesystem clones where supported. */
internal object WorkerTrees {
    /** Default JVM distributions run their installed jars. Keep source/Git and distributions, omit compiler scratch. */
    fun copyRuntime(source: Path, destination: Path, checkActive: () -> Unit) {
        checkActive(); require(!Files.exists(destination)); Files.createDirectories(destination)
        val exclude = listOf("classes", "kotlin", "tmp", "generated").flatMap {
            listOf("--exclude=build/$it", "--exclude=*/build/$it")
        }
        val pack = ProcessBuilder(listOf("/usr/bin/tar") + exclude + listOf("-cf", "-", "-C", source.toString(), "."))
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        val unpack = ProcessBuilder("/usr/bin/tar", "-xf", "-", "-C", destination.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
        val processes = ProcessBuilder.startPipeline(listOf(pack, unpack))
        try {
            while (processes.any { it.isAlive }) { checkActive(); Thread.sleep(20) }
            if (processes.all { it.exitValue() == 0 }) { checkActive(); return }
        } finally {
            for (process in processes) if (process.isAlive) process.destroyForcibly()
            for (process in processes) process.waitFor(5, TimeUnit.SECONDS)
        }
        deleteTree(destination); copy(source, destination, checkActive)
    }

    fun copy(source: Path, destination: Path, checkActive: () -> Unit) {
        checkActive()
        require(!Files.exists(destination)) { "attempt worktree already exists" }
        Files.createDirectories(destination)
        val mac = System.getProperty("os.name").lowercase().contains("mac")
        val command = if (mac) listOf("/bin/cp", "-cRp", "$source/.", destination.toString())
            else listOf("/bin/cp", "--reflink=auto", "-Rp", "$source/.", destination.toString())
        val process = ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            while (!process.waitFor(20, TimeUnit.MILLISECONDS)) checkActive()
            if (process.exitValue() == 0) { checkActive(); return }
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
        }
        // Cross-volume copies and filesystems without clone support retain the portable path.
        deleteTree(destination)
        Files.walk(source).use { paths -> paths.forEach { file ->
            checkActive()
            val target = destination.resolve(source.relativize(file))
            if (Files.isDirectory(file)) Files.createDirectories(target) else Files.copy(file, target, COPY_ATTRIBUTES)
        } }
    }
}
