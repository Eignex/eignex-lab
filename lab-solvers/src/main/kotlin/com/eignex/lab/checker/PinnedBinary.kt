package com.eignex.lab.checker

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A pinned external checker binary, downloaded once into `<dir>/<file>-<version>/<file>` and checked against its
 * SHA-256 every time it is used. A download that fails is not tried again for [RETRY_MS].
 */
class PinnedBinary(
    private val dir: File,
    private val tool: String,
    private val file: String,
    private val version: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var failure: Pair<Long, String>? = null

    @Synchronized
    fun get(url: String, pin: String): File {
        val binary = File(dir, "$file-$version/$file")
        if (binary.canExecute() && sha256(binary) == pin) return binary
        failure?.let { (at, why) -> if (clock() - at < RETRY_MS) error(why) }
        try {
            binary.parentFile.mkdirs()
            val partial = File.createTempFile("$file-", ".part", binary.parentFile)
            try {
                URI(url).toURL().openConnection().apply { connectTimeout = 30_000; readTimeout = 30_000 }
                    .getInputStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
                val sha = sha256(partial)
                check(sha == pin) { "SHA-256 $sha of ${url.substringAfterLast('/')} differs from the pin $pin" }
                check(partial.setExecutable(true, true)) { "the downloaded $tool cannot be made executable" }
                Files.move(partial.toPath(), binary.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                partial.delete()
            }
            failure = null
            return binary
        } catch (e: Exception) {
            val why = "$tool $version could not be downloaded: ${e.message}"
            failure = clock() to why
            error(why)
        }
    }

    companion object {
        const val RETRY_MS = 10 * 60_000L
    }
}
