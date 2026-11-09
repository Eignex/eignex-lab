package com.eignex.lab.analysis

import com.eignex.lab.checker.*
import com.eignex.lab.domain.*
import com.eignex.lab.reference.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*

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
