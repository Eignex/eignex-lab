package com.eignex.lab.store

import com.eignex.lab.util.hex
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A ZIP written to [output] whose bytes depend only on what is put in it: every entry has time 0, the directories go
 * first in sorted order, and already compressed files ([compressedArchivePath]) are stored in DEFLATED framing
 * without compressing again. The callers put files in their own deterministic order. Archive identities are these
 * bytes' SHA-256, so the layout must not change.
 */
internal class DeterministicZip(output: OutputStream) : AutoCloseable {
    private val archive = MessageDigest.getInstance("SHA-256")
    private val zip = ZipOutputStream(DigestOutputStream(output, archive))

    /** Put the empty directory entries [names], sorted; empty directories carry runtime meaning, such as a detached
     *  checkout's .git/refs. Call it before any [file]. */
    fun directories(names: Collection<String>, checkActive: () -> Unit = {}) {
        for (directory in names.sorted()) {
            checkActive(); zip.putNextEntry(ZipEntry(directory).apply { time = 0 }); zip.closeEntry()
        }
    }

    /** Put file [name], whose bytes [write] passes to the sink it is given; the SHA-256 of those bytes, in hex. */
    fun file(name: String, write: (sink: (ByteArray, Int, Int) -> Unit) -> Unit): String {
        // JAR/ZIP and Git pack contents are already compressed. Deflating those bytes again consumes CPU; retain
        // DEFLATED framing so the checksum and size stream in one pass.
        zip.setLevel(if (compressedArchivePath(name)) Deflater.NO_COMPRESSION else Deflater.BEST_SPEED)
        zip.putNextEntry(ZipEntry(name).apply { time = 0 })
        val digest = MessageDigest.getInstance("SHA-256")
        write { buffer, offset, length -> zip.write(buffer, offset, length); digest.update(buffer, offset, length) }
        zip.closeEntry()
        return digest.digest().hex()
    }

    /** Finish the archive and close [output]; the SHA-256 of all the archive's bytes, in hex. */
    fun finish(): String { zip.close(); return archive.digest().hex() }

    override fun close() = zip.close()
}
