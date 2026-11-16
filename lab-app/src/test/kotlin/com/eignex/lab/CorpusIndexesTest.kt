package com.eignex.lab

import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.store.*
import com.eignex.lab.web.*
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.*

class CorpusIndexesTest {
    private val root = Files.createTempDirectory("corpus-indexes")
    private val sha = "a".repeat(64)
    private fun index(id: String, path: String = "PB24/family/model.opb.xz") =
        """{"version":1,"collection":"$id","files":[{"path":"$path","sha256":"$sha","bytes":42}]}"""

    private fun archive(text: String, name: String = "index.json", extra: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream()
        TarArchiveOutputStream(output).use { tar ->
            val bytes = text.toByteArray()
            tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
            tar.write(bytes)
            tar.closeArchiveEntry()
            if (extra) {
                tar.putArchiveEntry(TarArchiveEntry("unexpected"))
                tar.closeArchiveEntry()
            }
        }
        return output.toByteArray()
    }

    @Test
    fun `all collections use only their signed index key and preserve packed checksums`() {
        val signed = mutableListOf<InputSignature>()
        val fetched = mutableListOf<String>()
        for (id in listOf("pb-comp-2024", "pb-comp-2025", "another-collection")) {
            val indexes = CorpusIndexes(root, mapOf("LAB_SHARED_INPUT_URL" to "https://store.invalid",
                "LAB_SHARED_INPUT_SIGNER" to "http://127.0.0.1:8093/"),
                sign = { _, request -> signed += request; SignedInput("https://signed.invalid/${request.key}") },
                download = { request -> fetched += request.url; archive(index(id)) })
            val metadata = assertNotNull(indexes.get(id))
            assertEquals(id, metadata.collection)
            assertEquals(CorpusIndex.Entry("PB24/family/model.opb.xz", sha, 42), metadata.files.single())
        }
        assertEquals(listOf("pb-comp-2024", "pb-comp-2025", "another-collection").map {
            InputSignature("GET", "corpus-packs/$it.index.tar")
        }, signed)
        assertTrue(fetched.all { it.endsWith(".index.tar") })
        assertEquals(0L, Files.list(root).use { it.count() })
    }

    @Test
    fun `unsigned mirrors need no signer and a confirmed missing index is distinct from transport failures`() {
        val environment = mapOf("LAB_CORPUS_MIRROR" to "https://mirror.invalid/packs")
        val indexes = CorpusIndexes(root, environment, sign = { _, _ -> error("unexpected signer") }, download = {
            assertEquals("https://mirror.invalid/packs/pb-comp-2024.index.tar", it.url)
            null
        })
        assertNull(indexes.get("pb-comp-2024"))
        val broken = CorpusIndexes(root, environment, download = { throw IOException("secret URL") })
        val failure = assertFailsWith<ApiError.Unavailable> { broken.get("pb-comp-2024") }
        assertFalse("secret" in failure.message.orEmpty())
        val signing = CorpusIndexes(root, environment + ("LAB_SHARED_INPUT_SIGNER" to "http://127.0.0.1:8093/"),
            sign = { _, _ -> throw IOException("secret signer") }, download = { error("must not download") })
        assertFailsWith<ApiError.Unavailable> { signing.get("pb-comp-2024") }
    }

    @Test
    fun `malformed wrong-collection duplicate and unsafe indexes fail as backend errors`() {
        for (bytes in listOf(
            archive("not json"), archive(index("wrong")), archive(index("sample", "../escape")),
            archive(index("sample"), "other.json"), archive(index("sample"), extra = true),
            archive(index("sample").replace(sha, "bad")), archive(index("sample").replace("42", "-1")),
            archive(index("sample").replace("\"version\":1", "\"version\":2")),
            archive("""{"collection":"sample","files":[{"path":"x","sha256":"$sha","bytes":1},{"path":"x","sha256":"$sha","bytes":1}]}"""),
            archive(index("sample")).copyOf(520),
        )) {
            val indexes = CorpusIndexes(root, mapOf("LAB_CORPUS_MIRROR" to "https://mirror.invalid"), download = { bytes })
            assertFailsWith<ApiError.Unavailable> { indexes.get("sample") }
        }
    }

    @Test
    fun `local sidecars require no corpus tree and missing storage is not missing metadata`() {
        val sidecar = root.resolve(".index").createDirectories().resolve("sample.json")
        sidecar.writeText(index("sample"))
        val indexes = CorpusIndexes(root, emptyMap(), download = { error("must not fetch") })
        assertEquals(sha, assertNotNull(indexes.get("sample")).files.single().sha256)
        assertNull(indexes.get("absent"))
        sidecar.writeText("broken")
        assertFailsWith<ApiError.Unavailable> { indexes.get("sample") }
        val outside = Files.createTempFile("outside-index", ".json").apply { writeText(index("linked")) }
        Files.createSymbolicLink(root.resolve(".index/linked.json"), outside)
        assertFailsWith<ApiError.Unavailable> { indexes.get("linked") }
        assertFailsWith<ApiError.Unavailable> { CorpusIndexes(root.resolve("missing"), emptyMap()).get("sample") }
    }

    @Test
    fun `HTTP distinguishes absent indexes invalid requests and failed backends`() = testApplication {
        val indexes = CorpusIndexes(root, emptyMap())
        root.resolve(".index").createDirectories().resolve("sample.json").writeText(index("sample"))
        application {
            install(ContentNegotiation) { json() }
            installApiErrors()
            routing { corpusRoutes(indexes) }
        }
        val success = client.get("/corpus/indexes/sample")
        assertEquals(HttpStatusCode.OK, success.status)
        assertEquals("no-store", success.headers["Cache-Control"])
        assertEquals("sample", Json.decodeFromString<CorpusIndex>(success.bodyAsText()).collection)
        assertTrue("\"version\":1" in success.bodyAsText())
        val missing = client.get("/corpus/indexes/absent")
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals("no published corpus index for absent", Json.decodeFromString<ErrorBody>(missing.bodyAsText()).error)
        root.resolve(".index/broken.json").writeText("broken")
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/corpus/indexes/broken").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/corpus/indexes/bad,id").status)
    }

    @Test
    fun `API wires metadata routing behind the existing password gate`() = testApplication {
        val config = Config(dataDir = Files.createTempDirectory("index-api"), corpusDir = root)
        val store = Store(config.database)
        application { api(config, store, HostReport("Test/arm64", 4, "java")) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/corpus/indexes/sample").status)
    }
}
