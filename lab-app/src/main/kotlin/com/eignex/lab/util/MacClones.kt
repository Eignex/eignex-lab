package com.eignex.lab.util

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Prefer APFS copy-on-write clones. Directory clones still create separate inodes for their descendants,
 * so callers should select runtime inputs before copying large built trees. */
internal object MacClones {
    private val clone by lazy {
        runCatching {
            val linker = Linker.nativeLinker()
            linker.downcallHandle(linker.defaultLookup().find("clonefile").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))
        }.getOrNull()
    }
    fun copy(source: Path, destination: Path, checkActive: () -> Unit): Boolean {
        if (!System.getProperty("os.name").lowercase().contains("mac")) return false
        val function = clone ?: return false
        checkActive()
        if (Files.getFileStore(source) != Files.getFileStore(destination.parent)) return false
        require(!Files.exists(destination, NOFOLLOW_LINKS))
        val copied = Arena.ofConfined().use { arena ->
            function.invoke(arena.allocateFrom(source.toString()), arena.allocateFrom(destination.toString()), 0) as Int == 0
        }
        checkActive()
        return copied
    }
}
