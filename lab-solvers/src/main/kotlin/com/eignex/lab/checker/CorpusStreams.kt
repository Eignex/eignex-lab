package com.eignex.lab.checker

import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import java.io.File
import java.io.InputStream

/**
 * Opens corpus files that may be zstd-compressed (`.zst`), decoding in process with a bounded window: a corrupt or
 * hostile frame fails the read instead of a child process exiting unseen, and no frame can ask for more memory than
 * [windowLog] allows. zstd decodes deterministically, so a compressed and a plain copy still read and hash alike.
 */
object CorpusStreams {
    /** The `zstd` command's own default decoding limit (128 MiB), so whatever it decoded still decodes. */
    const val WINDOW_LOG = 27

    fun isCompressed(file: File): Boolean = file.name.endsWith(".zst")

    fun open(file: File, windowLog: Int = WINDOW_LOG): InputStream {
        val raw = file.inputStream()
        if (!isCompressed(file)) return raw
        return try {
            ZstdInputStreamNoFinalizer(raw).setLongMax(windowLog)
        } catch (failure: Throwable) {
            raw.close()
            throw failure
        }
    }
}
