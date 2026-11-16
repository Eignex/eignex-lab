package com.eignex.lab.util

import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Path

/** Atomically exchanges two existing paths on one volume, e.g. a directory and a symlink, so no reader sees a gap.
 * https://github.com/apple-oss-distributions/xnu/blob/main/bsd/man/man2/rename.2 (renamex_np, RENAME_SWAP). */
internal object MacRenameSwap {
    private const val RENAME_SWAP = 0x2
    private val function by lazy {
        if (!System.getProperty("os.name").lowercase().contains("mac")) null else runCatching {
            Linker.nativeLinker().let { linker -> linker.downcallHandle(linker.defaultLookup().find("renamex_np").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT), Linker.Option.captureCallState("errno")) }
        }.getOrNull()
    }
    val supported get() = function != null

    fun swap(first: Path, second: Path) {
        val swap = checkNotNull(function) { "atomic rename swap unsupported" }
        Arena.ofConfined().use { arena ->
            val state = arena.allocate(Linker.Option.captureStateLayout())
            val result = swap.invoke(state, arena.allocateFrom(first.toString()), arena.allocateFrom(second.toString()), RENAME_SWAP) as Int
            if (result != 0) {
                val errno = Linker.Option.captureStateLayout().varHandle(java.lang.foreign.MemoryLayout.PathElement.groupElement("errno")).get(state, 0L) as Int
                throw IOException("rename swap failed (errno $errno): $first <-> $second")
            }
        }
    }
}
