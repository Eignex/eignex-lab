package com.eignex.lab.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.util.concurrent.TimeUnit

/** Private copies prefer an atomic filesystem clone, with the same portable copy fallback. */
internal fun copyPrivateTree(source: Path, destination: Path, checkActive: () -> Unit) {
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
    val copied = withOwnedCopyProcess(process) {
        while (!process.waitFor(20, TimeUnit.MILLISECONDS)) checkActive()
        (process.exitValue() == 0).also { if (it) checkActive() }
    }
    if (copied) return
    // Cross-volume copies and filesystems without clone support retain the portable path.
    deleteTree(destination)
    Files.walk(source).use { paths -> paths.forEach { file ->
        checkActive()
        val target = destination.resolve(source.relativize(file))
        if (Files.isDirectory(file)) Files.createDirectories(target) else Files.copy(file, target, COPY_ATTRIBUTES)
    } }
}
