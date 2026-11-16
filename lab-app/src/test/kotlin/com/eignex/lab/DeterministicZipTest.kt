package com.eignex.lab

import com.eignex.lab.store.*
import com.eignex.lab.util.*
import java.io.ByteArrayOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class DeterministicZipTest {
    private val directories = listOf(".git/refs/", "a/", "a/b/")
    private val files = listOf("a/b/lib.jar" to ByteArray(70_000) { (it % 251).toByte() },
        "a/text.txt" to "hello hello hello\n".repeat(5000).toByteArray(), "z.pack" to ByteArray(10) { it.toByte() })

    /** The bytes the archive writers produced before they shared [DeterministicZip]; archive identities depend on them. */
    private fun legacy(presetLevel: Boolean): Pair<ByteArray, List<String>> {
        val bytes = ByteArrayOutputStream(); val archive = MessageDigest.getInstance("SHA-256"); val shas = mutableListOf<String>()
        ZipOutputStream(DigestOutputStream(bytes, archive)).use { zip ->
            if (presetLevel) zip.setLevel(Deflater.BEST_SPEED)
            for (directory in directories.sorted()) { zip.putNextEntry(ZipEntry(directory).apply { time = 0 }); zip.closeEntry() }
            for ((name, content) in files) {
                val digest = MessageDigest.getInstance("SHA-256")
                zip.setLevel(if (compressedArchivePath(name)) Deflater.NO_COMPRESSION else Deflater.BEST_SPEED)
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                content.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val n = input.read(buffer); if (n < 0) break; zip.write(buffer, 0, n); digest.update(buffer, 0, n) }
                }
                zip.closeEntry(); shas += digest.digest().hex()
            }
        }
        return bytes.toByteArray() to shas + archive.digest().hex()
    }

    @Test fun `archives are byte-identical to the former writers`() {
        val bytes = ByteArrayOutputStream(); val shas = mutableListOf<String>()
        DeterministicZip(bytes).use { zip ->
            zip.directories(directories.reversed())
            for ((name, content) in files) shas += zip.file(name) { sink ->
                content.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val n = input.read(buffer); if (n < 0) break; sink(buffer, 0, n) }
                }
            }
            shas += zip.finish()
        }
        for (preset in listOf(true, false)) {
            val (expected, expectedShas) = legacy(preset)
            assertEquals(expected.hex(), bytes.toByteArray().hex())
            assertEquals(expectedShas, shas)
        }
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).hex(), shas.last())
    }
}
