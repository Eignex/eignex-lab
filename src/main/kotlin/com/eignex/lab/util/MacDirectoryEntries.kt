package com.eignex.lab.util

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant

/** Fetch the same no-follow inode/ctime/size/access metadata as individual Unix stats in directory batches.
 * This is a traversal, not a filesystem snapshot: callers still recheck individual files after hashing/copying.
 * Layout and effective-access semantics:
 * https://github.com/apple-oss-distributions/xnu/blob/main/bsd/man/man2/getattrlistbulk.2
 * https://github.com/apple-oss-distributions/xnu/blob/main/bsd/man/man2/getattrlist.2 */
internal object MacDirectoryEntries {
    data class Entry(val name: String, val directory: Boolean, val regular: Boolean, val bytes: Long,
                     val modified: String, val changed: String, val key: String, val executable: Boolean)
    private const val BUFFER_BYTES = 64 * 1024
    private val common = 0xa2200c0b.toInt() // returned attrs, error, name, device, type, mtime, ctime, access, inode
    private const val FILE_LENGTH = 0x00000200
    private val requiredCommon = common and 0x1fffffff // error and returned-mask fields are handled separately
    private class Native {
        private val linker = Linker.nativeLinker()
        private fun function(name: String, descriptor: FunctionDescriptor) =
            linker.downcallHandle(linker.defaultLookup().find(name).orElseThrow(), descriptor)
        val open = function("open", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
        val close = function("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT))
        val read = function("getattrlistbulk", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG))
    }
    private val native by lazy { runCatching { Native() }.getOrNull() }
    fun supported(path: Path) = System.getProperty("os.name").lowercase().contains("mac") &&
        path.fileSystem == FileSystems.getDefault() && native != null

    /** Unsupported calls or incomplete attributes return null, so the ordinary no-follow walker decides validity. */
    fun read(directory: Path, checkActive: () -> Unit): List<Entry>? {
        if (!supported(directory)) return null
        val functions = requireNotNull(native)
        checkActive()
        return Arena.ofConfined().use { arena ->
            val fd = functions.open.invoke(arena.allocateFrom(directory.toString()), 0x01100100) as Int // directory, no-follow, close-on-exec
            if (fd < 0) return@use null
            try {
                val attributes = arena.allocate(24, 4)
                attributes.fill(0)
                attributes.set(JAVA_SHORT, 0, 5.toShort())
                attributes.set(JAVA_INT, 4, common)
                attributes.set(JAVA_INT, 16, FILE_LENGTH)
                val buffer = arena.allocate(BUFFER_BYTES.toLong(), 8)
                val entries = mutableListOf<Entry>()
                while (true) {
                    checkActive()
                    val count = functions.read.invoke(fd, attributes, buffer, BUFFER_BYTES.toLong(), 8L) as Int // pack invalid attrs
                    if (count < 0) return@use null
                    if (count == 0) return@use entries
                    val batch = decode(buffer.toArray(JAVA_BYTE), count) ?: return@use null
                    entries += batch
                }
                @Suppress("UNREACHABLE_CODE") null
            } finally { functions.close.invoke(fd) }
        }
    }

    /** Packed records use four-byte field alignment even for 64-bit values. Validate every range and mask. */
    internal fun decode(bytes: ByteArray, count: Int): List<Entry>? {
        if (count !in 0..bytes.size / 96) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        val result = mutableListOf<Entry>()
        var offset = 0
        repeat(count) {
            if (offset > bytes.size - 96) return null
            val length = buffer.getInt(offset)
            if (length < 96 || length % 8 != 0 || length > bytes.size - offset) return null
            val mask = buffer.getInt(offset + 4)
            if (mask and requiredCommon != requiredCommon || buffer.getInt(offset + 24) != 0) return null
            val type = buffer.getInt(offset + 40)
            val regular = type == 1
            val nameOffset = buffer.getInt(offset + 28).toLong() + 28
            val nameBytes = buffer.getInt(offset + 32)
            // Type-specific file attributes are omitted for directories even with PACK_INVAL_ATTRS.
            if (nameOffset < (if (regular) 96 else 88) || nameBytes !in 1..4096 || nameOffset + nameBytes > length) return null
            val start = offset + nameOffset.toInt()
            if (bytes[start + nameBytes - 1] != 0.toByte()) return null
            val name = String(bytes, start, nameBytes - 1, Charsets.UTF_8)
            if (name.isEmpty() || '/' in name || '\u0000' in name) return null
            if (regular && buffer.getInt(offset + 16) and FILE_LENGTH == 0) return null
            fun time(at: Int): String? {
                val seconds = buffer.getLong(offset + at); val nanos = buffer.getLong(offset + at + 8)
                if (nanos !in 0..999999999 || seconds !in -31557014167219200L..31556889864403199L) return null
                return FileTime.from(Instant.ofEpochSecond(seconds, nanos)).toString()
            }
            val modified = time(44) ?: return null
            val changed = time(60) ?: return null
            val device = buffer.getInt(offset + 36).toLong()
            val inode = buffer.getLong(offset + 80)
            val size = if (regular) buffer.getLong(offset + 88) else 0
            if (regular && size < 0) return null
            if (name !in setOf(".", "..")) result += Entry(name, type == 2, regular, size, modified, changed,
                "(dev=${java.lang.Long.toHexString(device)},ino=$inode)", buffer.getInt(offset + 76) and 1 != 0)
            offset += length
        }
        return result
    }
}
