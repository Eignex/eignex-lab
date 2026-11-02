package com.eignex.lab.util

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Account regular-file bytes without following links. On macOS use one metadata batch per directory,
 * rather than a stat of each Gradle cache entry competing with compiler and verification reads.
 * An unsupported or incomplete batch falls back to the ordinary no-follow attributes. */
internal fun treeBytes(root: Path, maxBytes: Long = Long.MAX_VALUE, checkActive: () -> Unit = {},
                       entries: (Path, () -> Unit) -> List<MacDirectoryEntries.Entry>? = MacDirectoryEntries::read): Long {
    require(maxBytes >= 0)
    var total = 0L
    fun add(bytes: Long) {
        require(bytes >= 0 && bytes <= maxBytes - total) { "build staging exceeds disk budget" }
        total += bytes
    }
    fun visit(path: Path) {
        checkActive()
        try {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attributes.isDirectory) { if (attributes.isRegularFile) add(attributes.size()); return }
            val bulk = entries(path, checkActive)
            if (bulk != null) {
                for (entry in bulk) {
                    checkActive()
                    if (entry.directory) visit(path.resolve(entry.name)) else if (entry.regular) add(entry.bytes)
                }
            } else Files.newDirectoryStream(path).use { children -> for (child in children) visit(child) }
        } catch (_: NoSuchFileException) {
            // Compiler staging can remove temporary files while accounting runs. Final publication
            // seals its inventory separately, and pinned dependency homes are checked before/after.
        }
    }
    visit(root)
    return total
}
