package com.eignex.lab.util

import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Path

/** One waited volume barrier, instead of a drive-cache flush for every file of a native snapshot.
 * Apple's API flushes both data and metadata: https://github.com/apple-oss-distributions/Libc/blob/main/gen/sync_volume_np.3 */
internal object MacVolumeSync {
    private val function by lazy {
        if (!System.getProperty("os.name").lowercase().contains("mac")) null else runCatching {
            Linker.nativeLinker().let { linker -> linker.downcallHandle(linker.defaultLookup().find("sync_volume_np").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)) }
        }.getOrNull()
    }
    val supported get() = function != null
    fun flush(path: Path, check: () -> Unit) {
        val sync = checkNotNull(function)
        check()
        val error = Arena.ofConfined().use { arena -> sync.invoke(arena.allocateFrom(path.toString()), 0x01 or 0x02) as Int }
        if (error != 0) throw IOException("native snapshot volume sync failed ($error)")
        check()
    }
}
