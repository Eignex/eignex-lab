package com.eignex.lab

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

/**
 * Package and verify campaign evidence without extracting archives: `eignex-lab campaign-evidence pack <root> <output>`
 * and `eignex-lab campaign-evidence verify <archive> <manifest>` (see deploy/SETUP.md). Such an archive, placed under
 * the lab's archive directory, is what an analysis job analyses saved captures from ([CaptureArchives]), verified by
 * [verify] first. An archive is a gzipped POSIX tar of every staged file in path order, with no times, owners or modes
 * beyond 0644, so packing the same tree twice gives the same bytes. Its manifest is JSON as Python's
 * `json.dumps(indent=2, sort_keys=True)` writes it, the form the first snapshots were made in, and verification accepts
 * those archives too.
 */
internal object CampaignEvidence {
    val ROLES = listOf("specifications", "raw", "profiles", "provenance", "summaries", "validation")
    private val CAMPAIGN = Regex("[a-z][a-z0-9-]{0,63}")

    /** The command line: what to print and the exit status, 1 on a rejected tree or archive, 2 on a usage error. */
    fun main(args: List<String>): Int {
        val usage = "usage: eignex-lab campaign-evidence pack <root> <output> | verify <archive> <manifest>"
        if (args.size != 3 || args[0] !in setOf("pack", "verify")) {
            System.err.println(usage)
            return 2
        }
        return try {
            if (args[0] == "pack") pack(Path.of(args[1]), Path.of(args[2])).forEach(::println)
            else println("verified ${verify(Path.of(args[1]), Path.of(args[2]))} files")
            0
        } catch (error: EvidenceException) {
            System.err.println(error.message)
            1
        } catch (error: IOException) {
            System.err.println(error.message ?: error.toString())
            1
        } catch (error: IllegalArgumentException) {
            System.err.println(error.message ?: error.toString())
            1
        }
    }

    /** [name] if it is a plain relative path inside the tree: no `.`, `..`, empty or absolute parts, no backslash. */
    fun safeName(name: String): String {
        if (name.isEmpty() || name.startsWith("/") || '\\' in name || name.split('/').any { it in setOf("", ".", "..") })
            throw EvidenceException("unsafe path: '$name'")
        return name
    }

    fun catalogCheck(catalog: JsonElement, files: Set<String>) {
        val fields = catalog as? JsonObject ?: throw EvidenceException("catalog must be an object")
        val campaign = (fields["campaign"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (campaign == null || !CAMPAIGN.matches(campaign)) throw EvidenceException("campaign must be a lowercase slug")
        if (!truthy(fields["purpose"]) || !truthy(fields["references"]) || !truthy(fields["limitations"]))
            throw EvidenceException("catalog requires purpose, references and limitations")
        val roles = fields["roles"] as? JsonObject ?: throw EvidenceException("catalog requires roles")
        val omissions = fields["omissions"] as? JsonObject
        for (role in ROLES) {
            val paths = roles[role] as? JsonArray ?: throw EvidenceException("$role requires a list of paths")
            if (paths.isEmpty() && !truthy(omissions?.get(role)))
                throw EvidenceException("$role requires paths or an explicit omission reason")
            for (path in paths) {
                val name = (path as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw EvidenceException("$role paths must be strings")
                if (safeName(name) !in files) throw EvidenceException("missing $role file: $name")
            }
        }
    }

    /** How many files the [archive] holds, once each is the one [manifest] lists, byte for byte. */
    fun verify(archive: Path, manifest: Path): Int {
        val data = parse(manifest.readText()) as? JsonObject ?: throw EvidenceException("manifest must be an object")
        if ((data["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toDoubleOrNull() != 1.0)
            throw EvidenceException("unsupported manifest version")
        val identity = data["archive"] as? JsonObject ?: throw EvidenceException("manifest has no archive")
        if (archive.name != safeName(string(identity["name"]))) throw EvidenceException("archive name differs from manifest")
        if (Files.size(archive) != number(identity["size"]) || fileDigest(archive) != string(identity["sha256"]))
            throw EvidenceException("archive hash or size differs from manifest")
        val expected = (data["files"] as? JsonObject ?: throw EvidenceException("manifest has no files"))
            .mapValues { (_, file) -> file as? JsonObject ?: throw EvidenceException("manifest file entries must be objects") }
        expected.keys.forEach(::safeName)
        val seen = mutableSetOf<String>()
        var catalogBytes: ByteArray? = null
        Files.newInputStream(archive).buffered().use { raw ->
            val bundle = TarReader(GZIPInputStream(raw))
            while (true) {
                val member = bundle.next() ?: break
                val name = safeName(member.name)
                if (!member.regular || name in seen || name !in expected)
                    throw EvidenceException("unexpected, duplicate or nonregular member: $name")
                seen += name
                val file = expected.getValue(name)
                // The size first, so a header claiming more than the manifest is refused before its bytes are read.
                if (member.size != number(file["size"])) throw EvidenceException("file hash or size differs: $name")
                val digest = MessageDigest.getInstance("SHA-256")
                val keep = if (name == "catalog.json") ByteArrayOutputStream() else null
                bundle.content { block, length -> digest.update(block, 0, length); keep?.write(block, 0, length) }
                keep?.let { catalogBytes = it.toByteArray() }
                if (hex(digest.digest()) != string(file["sha256"]))
                    throw EvidenceException("file hash or size differs: $name")
            }
        }
        if (seen != expected.keys)
            throw EvidenceException("missing members: ${(expected.keys - seen).sortedWith(::compareCodePoints).joinToString(", ", "[", "]") { "'$it'" }}")
        val catalog = parse(String(catalogBytes ?: throw EvidenceException("missing catalog.json"), Charsets.UTF_8))
        catalogCheck(catalog, expected.keys)
        if (catalog != data["catalog"]) throw EvidenceException("catalog differs from manifest")
        return seen.size
    }

    /**
     * Pack every regular file under [root] into `<campaign>-<sha256>.tar.gz` and its `.manifest.json` in [output], which
     * must lie outside [root]: the two paths written. Neither may exist yet, so a published snapshot is never replaced.
     */
    fun pack(root: Path, output: Path): List<Path> {
        val tree = root.toRealPath()
        val out = resolve(output)
        if (out.startsWith(tree)) throw EvidenceException("output must be outside the staged evidence tree")
        val staged = Files.walk(tree).use { walk -> walk.filter { it != tree }.toList() }
            .map { path -> tree.relativize(path).joinToString("/") { it.toString() } to path }
            .sortedWith { a, b -> compareParts(a.first, b.first) }
        val paths = LinkedHashMap<String, Path>()
        for ((name, path) in staged) {
            safeName(name)
            if (path.isSymbolicLink() || !(path.isRegularFile() || path.isDirectory()))
                throw EvidenceException("nonregular staged path: $name")
            if (path.isRegularFile()) paths[name] = path
        }
        val catalog = parse((paths["catalog.json"] ?: throw EvidenceException("missing catalog.json")).readText())
        catalogCheck(catalog, paths.keys)
        val files = paths.mapValues { (_, path) -> path.fileSize() to fileDigest(path) }
        Files.createDirectories(out)
        val staging = Files.createTempDirectory(out, "tmp")
        try {
            val temp = staging.resolve("bundle.tar.gz")
            Files.newOutputStream(temp).use { stream ->
                PythonGzip(stream).use { compressed ->
                    val bundle = TarWriter(compressed)
                    for ((name, path) in paths) Files.newInputStream(path).use { bundle.file(name, files.getValue(name).first, it) }
                    bundle.finish()
                }
            }
            val sha = fileDigest(temp)
            val stem = "${string((catalog as JsonObject)["campaign"])}-$sha"
            val archive = Files.move(temp, staging.resolve("$stem.tar.gz"))
            val manifest = staging.resolve("$stem.manifest.json")
            val document = JsonObject(mapOf(
                "version" to JsonPrimitive(1),
                "catalog" to catalog,
                "files" to JsonObject(files.mapValues { (_, file) ->
                    JsonObject(mapOf("size" to JsonPrimitive(file.first), "sha256" to JsonPrimitive(file.second)))
                }),
                "archive" to JsonObject(mapOf(
                    "name" to JsonPrimitive(archive.name), "size" to JsonPrimitive(Files.size(archive)), "sha256" to JsonPrimitive(sha),
                )),
            ))
            manifest.writeText(pythonJson(document) + "\n")
            verify(archive, manifest)
            val destinations = listOf(out.resolve(archive.name), out.resolve(manifest.name))
            if (destinations.any { it.exists(LinkOption.NOFOLLOW_LINKS) }) throw EvidenceException("snapshot already exists")
            val created = mutableListOf<Path>()
            try {
                for ((source, target) in listOf(archive, manifest).zip(destinations)) {
                    Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { destination ->
                        created.add(target)
                        Files.newInputStream(source).use { it.copyTo(destination, BUFFER) }
                    }
                }
            } catch (error: Throwable) {
                created.forEach { Files.deleteIfExists(it) }
                throw error
            }
            return destinations
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    /** [value] as Python's `json.dumps(value, indent=2, sort_keys=True)` writes it, ASCII only. */
    fun pythonJson(value: JsonElement, level: Int = 0): String {
        val inner = "  ".repeat(level + 1)
        val outer = "  ".repeat(level)
        return when (value) {
            is JsonObject -> if (value.isEmpty()) "{}" else value.entries.sortedWith { a, b -> compareCodePoints(a.key, b.key) }
                .joinToString(",\n", "{\n", "\n$outer}") { (key, item) -> "$inner${pythonString(key)}: ${pythonJson(item, level + 1)}" }
            is JsonArray -> if (value.isEmpty()) "[]" else value.joinToString(",\n", "[\n", "\n$outer]") { "$inner${pythonJson(it, level + 1)}" }
            is JsonNull -> "null"
            is JsonPrimitive -> if (value.isString) pythonString(value.content) else value.content
        }
    }

    private fun pythonString(text: String): String = buildString {
        append('"')
        for (c in text) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            in ' '..'~' -> append(c)
            else -> append("\\u%04x".format(c.code))
        }
        append('"')
    }

    /** Python's truth of a JSON value: false for null, false, zero, and empty strings, lists and objects. */
    private fun truthy(value: JsonElement?): Boolean = when (value) {
        null, is JsonNull -> false
        is JsonObject -> value.isNotEmpty()
        is JsonArray -> value.isNotEmpty()
        is JsonPrimitive -> when {
            value.isString -> value.content.isNotEmpty()
            value.content == "false" -> false
            else -> value.content.toDoubleOrNull() != 0.0
        }
    }

    private fun parse(text: String): JsonElement =
        try { Json.parseToJsonElement(text) } catch (error: kotlinx.serialization.SerializationException) {
            throw EvidenceException("invalid JSON: ${error.message}")
        }

    private fun string(value: JsonElement?): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw EvidenceException("expected a string, got $value")

    private fun number(value: JsonElement?): Long =
        (value as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull() ?: throw EvidenceException("expected a number, got $value")

    private fun fileDigest(path: Path): String = Files.newInputStream(path).use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** [path] absolute, with the symbolic links of the part that exists resolved, as Python's `Path.resolve()`. */
    private fun resolve(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        var existing = absolute
        while (!existing.exists() && existing.parent != null) existing = existing.parent
        return existing.toRealPath().resolve(existing.relativize(absolute)).normalize()
    }

    /** Paths in Python's order: part by part, each part by code point. */
    private fun compareParts(a: String, b: String): Int {
        val left = a.split('/')
        val right = b.split('/')
        for (i in 0 until minOf(left.size, right.size)) compareCodePoints(left[i], right[i]).let { if (it != 0) return it }
        return left.size.compareTo(right.size)
    }

    private fun compareCodePoints(a: String, b: String): Int {
        val left = a.codePoints().toArray()
        val right = b.codePoints().toArray()
        for (i in 0 until minOf(left.size, right.size)) left[i].compareTo(right[i]).let { if (it != 0) return it }
        return left.size.compareTo(right.size)
    }

    private const val BUFFER = 1024 * 1024
}

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
