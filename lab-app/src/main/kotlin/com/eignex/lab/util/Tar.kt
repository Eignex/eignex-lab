package com.eignex.lab.util

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* Deterministic tar and gzip, as the campaign evidence bundles and capture archives are written and read. */

internal class EvidenceException(message: String) : RuntimeException(message)

/** gzip as Python's `GzipFile(filename='', mtime=0)` writes it: no name, no time, best compression. */
internal class PythonGzip(private val raw: OutputStream) : OutputStream() {
    private val crc = CRC32()
    private var length = 0L
    private val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
    private val body: DeflaterOutputStream

    init {
        // Magic, deflate, no flags, mtime 0, "best compression", unknown OS.
        raw.write(byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, 2, 0xff.toByte()))
        body = DeflaterOutputStream(raw, deflater, 64 * 1024)
    }

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        body.write(b, off, len)
        crc.update(b, off, len)
        length += len
    }

    override fun close() {
        body.finish()
        deflater.end()
        for (value in listOf(crc.value, length and 0xffffffffL)) for (shift in 0 until 32 step 8) raw.write((value shr shift).toInt() and 0xff)
        raw.close()
    }
}

/**
 * The POSIX tar Python's `tarfile` writes in `PAX_FORMAT`: ustar headers with no owner, time or device, and a pax header
 * before one whose name is not ASCII or longer than 100 bytes, or whose size does not fit.
 */
internal class TarWriter(private val out: OutputStream) {
    private var offset = 0L

    /** A regular file [name] of [size] bytes read from [content]. */
    fun file(name: String, size: Long, content: InputStream) {
        entry(name, size, REGULAR)
        val buffer = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val read = content.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) throw EOFException("$name is shorter than $size bytes")
            write(buffer, read)
            left -= read
        }
        pad()
    }

    /** A member's header: also how a test writes links and other members `pack` never does. */
    fun entry(name: String, size: Long, type: Char, link: String = "") {
        val pax = LinkedHashMap<String, String>()
        if (name.any { it.code > 0x7f } || name.length > NAME) pax["path"] = name
        if (link.any { it.code > 0x7f } || link.length > NAME) pax["linkpath"] = link
        if (size >= MAX_SIZE) pax["size"] = size.toString()
        if (pax.isNotEmpty()) {
            val records = ByteArrayOutputStream()
            for ((key, value) in pax) {
                val payload = value.toByteArray(Charsets.UTF_8)
                val fixed = key.length + payload.size + 3
                var digits = 0
                var total: Int
                while (true) {
                    total = fixed + digits.toString().length
                    if (total == digits) break
                    digits = total
                }
                records.write("$total $key=".toByteArray(Charsets.US_ASCII))
                records.write(payload)
                records.write('\n'.code)
            }
            val bytes = records.toByteArray()
            write(header("././@PaxHeader", 0, bytes.size.toLong(), PAX, ""), BLOCK)
            write(bytes, bytes.size)
            pad()
        }
        write(header(name, MODE, if (size >= MAX_SIZE) 0 else size, type, link), BLOCK)
    }

    /** Raw member bytes after an [entry]; [pad] ends them. */
    fun write(bytes: ByteArray, length: Int) {
        out.write(bytes, 0, length)
        offset += length
    }

    fun pad() {
        val rest = (BLOCK - offset % BLOCK) % BLOCK
        if (rest > 0) write(ByteArray(rest.toInt()), rest.toInt())
    }

    /** The two empty blocks that end an archive, and zeros to the end of its 10240-byte record. */
    fun finish() {
        write(ByteArray(2 * BLOCK), 2 * BLOCK)
        val rest = (RECORD - offset % RECORD) % RECORD
        if (rest > 0) write(ByteArray(rest.toInt()), rest.toInt())
        out.flush()
    }

    private fun header(name: String, mode: Int, size: Long, type: Char, link: String): ByteArray {
        val block = ByteArray(BLOCK)
        fun text(at: Int, length: Int, value: String) {
            // Python encodes ASCII with replacement: each other code point is one '?'.
            val bytes = value.codePoints().toArray().map { if (it < 0x80) it.toByte() else '?'.code.toByte() }
            for (i in 0 until minOf(length, bytes.size)) block[at + i] = bytes[i]
        }
        fun octal(at: Int, length: Int, value: Long) = text(at, length, "%0${length - 1}o".format(value) + "\u0000")
        text(0, 100, name)
        octal(100, 8, mode.toLong())
        octal(108, 8, 0)
        octal(116, 8, 0)
        octal(124, 12, size)
        octal(136, 12, 0)
        text(148, 8, " ".repeat(8))
        block[156] = type.code.toByte()
        text(157, 100, link)
        text(257, 8, "ustar\u000000")
        val sum = block.sumOf { it.toInt() and 0xff }
        text(148, 7, "%06o".format(sum) + "\u0000")
        return block
    }

    companion object {
        const val BLOCK = 512
        const val RECORD = 20 * BLOCK
        const val REGULAR = '0'
        const val SYMLINK = '2'
        private const val PAX = 'x'
        private const val MODE = 0b110_100_100
        private const val NAME = 100
        private const val MAX_SIZE = 1L shl 33
    }
}

/**
 * A streaming tar reader for the archives [TarWriter] and Python's `tarfile` write: ustar, pax and GNU long names. It
 * stops at the first empty block, as `tarfile` does, and rejects a header whose checksum is wrong.
 */
internal class TarReader(private val input: InputStream) {
    class Member(val name: String, val size: Long, val type: Char) {
        /** A regular file, as Python's `TarInfo.isfile()` counts one. */
        val regular: Boolean get() = type in setOf('0', '\u0000', '7')
    }

    private var left = 0L
    private var pad = 0L
    private var global = mapOf<String, String>()

    fun next(): Member? {
        skip()
        var pax = mapOf<String, String>()
        var longName: String? = null
        while (true) {
            val block = ByteArray(TarWriter.BLOCK)
            val read = input.readNBytes(block, 0, block.size)
            if (read == 0) return null
            if (read < block.size) throw EvidenceException("truncated tar header")
            if (block.all { it.toInt() == 0 }) return null
            val stored = field(block, 148, 8).trim().toLongOrNull(8)
            val sum = block.withIndex().sumOf { (i, b) -> if (i in 148 until 156) ' '.code else b.toInt() and 0xff }
            if (stored != sum.toLong()) throw EvidenceException("invalid tar header checksum")
            val type = (block[156].toInt() and 0xff).toChar()
            var size = number(block, 124, 12)
            (pax["size"] ?: global["size"])?.let { size = it.toLongOrNull() ?: throw EvidenceException("invalid pax size") }
            when (type) {
                'x', 'g' -> {
                    val records = records(payload(size))
                    if (type == 'x') pax = records else global = global + records
                    continue
                }
                'L' -> {
                    longName = String(payload(size), Charsets.UTF_8).substringBefore('\u0000')
                    continue
                }
                'K' -> {
                    payload(size)
                    continue
                }
            }
            var name = field(block, 0, 100)
            val prefix = field(block, 345, 155)
            if (field(block, 257, 5) == "ustar" && prefix.isNotEmpty()) name = "$prefix/$name"
            name = pax["path"] ?: longName ?: global["path"] ?: name
            val kind = if (type == '\u0000' && name.endsWith("/")) '5' else type
            left = if (kind in setOf('0', '\u0000', '7')) size else 0
            pad = (TarWriter.BLOCK - left % TarWriter.BLOCK) % TarWriter.BLOCK
            return Member(name, size, kind)
        }
    }

    /** The current member's bytes, in blocks handed to [use] with how many of each are data. */
    fun content(use: (ByteArray, Int) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) throw EvidenceException("unexpected end of data")
            use(buffer, read)
            left -= read
        }
        input.skipNBytes(pad)
        pad = 0
    }

    private fun skip() {
        if (left > 0 || pad > 0) content { _, _ -> }
    }

    private fun payload(size: Long): ByteArray {
        if (size !in 0..MAX_HEADER_PAYLOAD) throw EvidenceException("tar header payload of $size bytes")
        val bytes = input.readNBytes(Math.toIntExact(size))
        if (bytes.size.toLong() != size) throw EvidenceException("unexpected end of data")
        val rest = (TarWriter.BLOCK - size % TarWriter.BLOCK) % TarWriter.BLOCK
        input.skipNBytes(rest)
        return bytes
    }

    private fun records(bytes: ByteArray): Map<String, String> {
        val records = LinkedHashMap<String, String>()
        var at = 0
        while (at < bytes.size) {
            val space = (at until bytes.size).firstOrNull { bytes[it] == ' '.code.toByte() } ?: throw EvidenceException("invalid pax header")
            val length = String(bytes, at, space - at, Charsets.US_ASCII).toIntOrNull() ?: throw EvidenceException("invalid pax header")
            if (length <= space - at || at + length > bytes.size) throw EvidenceException("invalid pax header")
            val record = String(bytes, space + 1, at + length - space - 2, Charsets.UTF_8)
            records[record.substringBefore('=')] = record.substringAfter('=')
            at += length
        }
        return records
    }

    private fun field(block: ByteArray, at: Int, length: Int): String {
        val end = (at until at + length).firstOrNull { block[it].toInt() == 0 } ?: (at + length)
        return String(block, at, end - at, Charsets.UTF_8)
    }

    private fun number(block: ByteArray, at: Int, length: Int): Long {
        if (block[at].toInt() and 0x80 != 0) {
            // GNU base-256 for sizes over 8 GiB.
            var value = 0L
            for (i in at + 1 until at + length) value = (value shl 8) or (block[i].toLong() and 0xff)
            return value
        }
        val text = field(block, at, length).trim()
        return if (text.isEmpty()) 0 else text.toLongOrNull(8) ?: throw EvidenceException("invalid tar number")
    }

    private companion object {
        /** The most a pax or GNU long-name header may hold: names and sizes, never file data. */
        const val MAX_HEADER_PAYLOAD = 1L shl 20
    }
}
