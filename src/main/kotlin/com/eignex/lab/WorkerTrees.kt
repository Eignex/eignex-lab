package com.eignex.lab

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.util.concurrent.TimeUnit

/** Private mutable worktrees keep the shared verified distribution immutable. Prefer filesystem clones where supported. */
internal object WorkerTrees {
    fun runtimePath(path: Path): Boolean {
        val parts = path.map { it.toString() }
        return !((parts.size >= 2 && parts[0] == "build" && parts[1] in compilerScratch) ||
            (parts.size >= 3 && parts[1] == "build" && parts[2] in compilerScratch))
    }
    private val compilerScratch = setOf("classes", "kotlin", "tmp", "generated")

    /** Default JVM distributions run their installed jars. Keep source/Git and distributions, omit compiler scratch. */
    fun copyRuntime(source: Path, destination: Path, checkActive: () -> Unit) {
        checkActive(); require(!Files.exists(destination)); Files.createDirectories(destination)
        try {
            val entries = Files.list(source).use { it.toList() }
            val projects = entries.filter { it.fileName.toString() != "build" && Files.isDirectory(it.resolve("build")) }
            val build = entries.singleOrNull { it.fileName.toString() == "build" && Files.isDirectory(it) }
            val copies = mutableListOf<Pair<List<Path>, Path>>()
            copies += (entries - projects.toSet() - listOfNotNull(build).toSet()) to destination
            fun buildOutputs(from: Path, to: Path) {
                Files.createDirectories(to)
                copies += Files.list(from).use { paths -> paths.filter { it.fileName.toString() !in compilerScratch }.toList() } to to
            }
            if (build != null) buildOutputs(build, destination.resolve("build"))
            for (project in projects) {
                checkActive(); val to = Files.createDirectory(destination.resolve(project.fileName))
                copies += Files.list(project).use { paths -> paths.filter { it.fileName.toString() != "build" }.toList() } to to
                buildOutputs(project.resolve("build"), to.resolve("build"))
            }
            for ((files, to) in copies) copyEntries(files, to, checkActive)
            checkActive(); return
        } catch (_: java.io.IOException) {
            deleteTree(destination); Files.createDirectory(destination)
        }
        // Portable fallback keeps the same inventory without relying on archive-tool glob semantics.
        Files.walkFileTree(source, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                checkActive()
                val relative = source.relativize(dir)
                if (!runtimePath(relative)) return java.nio.file.FileVisitResult.SKIP_SUBTREE
                Files.createDirectories(destination.resolve(relative)); return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                checkActive(); require(attrs.isRegularFile) { "runtime contains unsupported links" }
                if (runtimePath(source.relativize(file))) Files.copy(file, destination.resolve(source.relativize(file)), COPY_ATTRIBUTES)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    private fun copyEntries(entries: List<Path>, destination: Path, checkActive: () -> Unit) {
        val mac = System.getProperty("os.name").lowercase().contains("mac")
        for (batch in entries.chunked(64)) {
            checkActive()
            val clone = mac && Files.getFileStore(batch.first()) == Files.getFileStore(destination)
            val remaining = if (clone) batch.filterNot { MacClones.copy(it, destination.resolve(it.fileName), checkActive) } else batch
            if (remaining.isEmpty()) continue
            val command = (if (mac) listOf("/bin/cp", if (clone) "-cR" else "-Rp")
                else listOf("/bin/cp", "--reflink=auto", "-Rp")) + remaining.map(Path::toString) + destination.toString()
            val started = System.nanoTime()
            val process = ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try {
                while (!process.waitFor(20, TimeUnit.MILLISECONDS)) checkActive()
                if (process.exitValue() != 0) throw java.io.IOException("private runtime copy failed")
                val elapsed = (System.nanoTime()-started)/1_000_000
                if (elapsed > 250) println("worker tree copy timing: group=${destination.fileName} entries=${batch.size} clone=$clone elapsedMs=$elapsed")
            } finally {
                if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
            }
        }
    }

    fun copy(source: Path, destination: Path, checkActive: () -> Unit) {
        checkActive()
        require(!Files.exists(destination)) { "attempt worktree already exists" }
        Files.createDirectories(destination.parent)
        if (MacClones.copy(source, destination, checkActive)) return
        Files.createDirectory(destination)
        val mac = System.getProperty("os.name").lowercase().contains("mac")
        val clone = mac && Files.getFileStore(source) == Files.getFileStore(destination)
        val command = if (mac) listOf("/bin/cp", if (clone) "-cR" else "-Rp", "$source/.", destination.toString())
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
