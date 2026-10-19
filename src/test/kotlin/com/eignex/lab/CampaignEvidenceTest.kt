package com.eignex.lab

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

class CampaignEvidenceTest {
    private val temp: Path = Files.createTempDirectory("evidence")
    private val root: Path = Files.createDirectories(temp.resolve("input"))
    private val output: Path = temp.resolve("output")
    private var catalog = mutableMapOf<String, JsonElement>(
        "campaign" to JsonPrimitive("active-test"),
        "purpose" to JsonPrimitive("Verify retention"),
        "references" to JsonArray(listOf(JsonPrimitive("https://github.com/Eignex/klause/issues/2403"))),
        "limitations" to JsonArray(listOf(JsonPrimitive("Storage checks do not validate solver claims."))),
        "roles" to JsonObject(CampaignEvidence.ROLES.associateWith { JsonArray(listOf(JsonPrimitive("record.json"))) }),
    )

    init {
        root.resolve("record.json").writeText("{\"sourceSha256\":\"retained\"}\n")
        writeCatalog()
    }

    @AfterTest
    fun cleanUp() {
        temp.toFile().deleteRecursively()
    }

    private fun writeCatalog() = root.resolve("catalog.json").writeText(JsonObject(catalog).toString())

    private fun pack(into: Path = output): Pair<Path, Path> = CampaignEvidence.pack(root, into).let { it[0] to it[1] }

    private fun role(role: String, vararg paths: String) {
        val roles = catalog.getValue("roles").jsonObject.toMutableMap()
        roles[role] = JsonArray(paths.map(::JsonPrimitive))
        catalog["roles"] = JsonObject(roles)
        writeCatalog()
    }

    @Test
    fun `a snapshot preserves every file and is reproducible`() {
        val (archive, manifest) = pack()
        assertEquals(2, CampaignEvidence.verify(archive, manifest))
        val (other, _) = pack(temp.resolve("other"))
        assertContentEquals(archive.readBytes(), other.readBytes())
        val members = members(archive)
        assertEquals(listOf("catalog.json", "record.json"), members.map { it.first })
        assertContentEquals(root.resolve("record.json").readBytes(), members.last().second)
        assertTrue(manifest.readText().startsWith("{\n  \"archive\": {\n    \"name\": \"active-test-"), manifest.readText())
    }

    @Test
    fun `a changed download is rejected`() {
        val (archive, manifest) = pack()
        archive.writeBytes(archive.readBytes() + "changed".toByteArray())
        assertRejected("archive hash or size") { CampaignEvidence.verify(archive, manifest) }
    }

    @Test
    fun `missing required evidence is rejected`() {
        role("raw", "missing.json")
        assertRejected("missing raw file") { pack() }
    }

    @Test
    fun `absent profiles require an explanation`() {
        role("profiles")
        assertRejected("profiles requires") { pack() }
        catalog["omissions"] = JsonObject(mapOf("profiles" to JsonPrimitive("This uninstrumented experiment collected no profiles.")))
        writeCatalog()
        val (archive, manifest) = pack()
        assertEquals(2, CampaignEvidence.verify(archive, manifest))
    }

    @Test
    fun `a symlink cannot publish files outside staging`() {
        Files.createSymbolicLink(root.resolve("outside"), Path.of("/etc/passwd"))
        assertRejected("nonregular") { pack() }
    }

    @Test
    fun `an existing snapshot cannot be overwritten`() {
        val (archive, manifest) = pack()
        val original = manifest.readBytes()
        assertRejected("already exists") { pack() }
        assertContentEquals(original, manifest.readBytes())
        assertEquals(2, CampaignEvidence.verify(archive, manifest))
    }

    @Test
    fun `the output cannot be part of the input`() {
        assertRejected("outside") { pack(root.resolve("output")) }
    }

    @Test
    fun `unsafe, duplicate, extra and missing archive members are rejected`() {
        val (archive, manifest) = pack()
        val content = root.resolve("record.json").readBytes()
        for (names in listOf(listOf("record.json", "../outside"), listOf("record.json", "record.json"), listOf("record.json", "extra"),
            listOf("record.json"))) {
            rewrite(archive, manifest) { tar -> for (name in names) tar.file(name, content.size.toLong(), ByteArrayInputStream(content)) }
            assertFailsWith<EvidenceException>(names.toString()) { CampaignEvidence.verify(archive, manifest) }
        }
    }

    @Test
    fun `archive links and changed member bytes are rejected`() {
        val (archive, manifest) = pack()
        for (link in listOf(false, true)) {
            rewrite(archive, manifest) { tar ->
                if (link) tar.entry("record.json", 0, TarWriter.SYMLINK, "/etc/passwd")
                else tar.file("record.json", 7, ByteArrayInputStream("changed".toByteArray()))
            }
            assertFailsWith<EvidenceException>("link=$link") { CampaignEvidence.verify(archive, manifest) }
        }
    }

    @Test
    fun `long and non-ASCII names round trip through pax headers`() {
        val long = "n".repeat(120) + ".txt"
        root.resolve(long).writeText("long")
        root.resolve("évidence.txt").writeText("accent")
        role("summaries", long, "évidence.txt")
        val (archive, manifest) = pack()
        assertEquals(4, CampaignEvidence.verify(archive, manifest))
        assertEquals(listOf("catalog.json", long, "record.json", "évidence.txt"), members(archive).map { it.first })
    }

    @Test
    fun `the manifest is JSON as Python writes it, sorted, indented and ASCII`() {
        val value = Json.parseToJsonElement("""{"b":[1,{}],"a":"✓ \"q\"\n","c":[],"d":{"z":null,"y":true}}""")
        assertEquals("{\n  \"a\": \"\\u2713 \\\"q\\\"\\n\",\n  \"b\": [\n    1,\n    {}\n  ],\n  \"c\": [],\n  \"d\": {\n" +
            "    \"y\": true,\n    \"z\": null\n  }\n}", CampaignEvidence.pythonJson(value))
    }

    private fun assertRejected(message: String, block: () -> Unit) {
        val error = assertFailsWith<EvidenceException> { block() }
        assertTrue(message in error.message.orEmpty(), error.message)
    }

    /** Replace [archive] with the members [write] gives, and point [manifest] at its new hash and size. */
    private fun rewrite(archive: Path, manifest: Path, write: (TarWriter) -> Unit) {
        GZIPOutputStream(Files.newOutputStream(archive)).use { stream -> TarWriter(stream).also(write).finish() }
        val data = Json.parseToJsonElement(manifest.readText()).jsonObject.toMutableMap()
        val identity = data.getValue("archive").jsonObject.toMutableMap()
        identity["size"] = JsonPrimitive(Files.size(archive))
        identity["sha256"] = JsonPrimitive(MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) })
        data["archive"] = JsonObject(identity)
        manifest.writeText(JsonObject(data).toString())
    }

    private fun members(archive: Path): List<Pair<String, ByteArray>> = GZIPInputStream(Files.newInputStream(archive)).use { stream ->
        val reader = TarReader(stream)
        generateSequence { reader.next() }.map { member ->
            val bytes = java.io.ByteArrayOutputStream()
            reader.content { block, length -> bytes.write(block, 0, length) }
            member.name to bytes.toByteArray()
        }.toList()
    }
}
