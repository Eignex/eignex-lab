package com.eignex.lab

import com.eignex.lab.domain.*
import com.eignex.lab.execution.*
import com.eignex.lab.nomad.*
import com.eignex.lab.store.*
import com.eignex.lab.util.*
import com.eignex.lab.web.*
import com.eignex.lab.worker.*
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PreparedArchiveFilesTest {
    @Test fun `native snapshots export only on explicit request and use the ZIP checksum`() {
        val config = Config(dataDir = Files.createTempDirectory("native-export"))
        val store = Store(config.database)
        val job = store.create("native", "main", emptyList(), experiment = ExperimentSpec("native"))
        val snapshots = NativePreparedTrees(config.jobsDir)
        val source = Files.createDirectories(config.dataDir.resolve("source"))
        Files.writeString(source.resolve("model"), "source")
        val proof = config.dataDir.resolve("proof.json")
        val files = TreeVerification().capture(source, proof)
        val identity = WorkerBundle("0".repeat(64), files, Repositories.DEFAULT, "a".repeat(40), "mac", "arm64", "jdk25")
        val (metadata, bundle) = snapshots.publish(job, source, proof, identity, {})
        val token = ExecutionStore.token()
        store.execution.createSession("native", job, ExecutionClass.PREPARE, "release", ResourceBudget(1000, 1024), token, "{}", 1000)
        store.execution.bind("native", token, "allocation", "node", "release", 1001)
        val owner = store.execution.claim("native", token, "allocation", 1002)!!.owner
        store.execution.phase(owner, "uploading", 1003)
        val artifacts = mapOf(preparationArtifact(bundle) to bundle.sha256)
        store.execution.commit(owner, IterationResult(emptyList(), artifacts,
            preparation = PreparationResult(emptyList(), listOf(bundle), artifacts = artifacts)), 1004)
        val reader = PreparedArchiveFiles(config, store, LocalArtifactStore(config.dataDir.resolve("artifacts")), { snapshots })
        assertTrue(reader.entries(job).isEmpty())
        assertFalse(Files.exists(metadata.parent.resolve("export-data")))
        val exported = assertNotNull(reader.resolve(job, listOf("preparation", "${bundle.commit}.zip")))
        assertEquals(hashFile(exported.file), exported.sha256)
        assertNotEquals(bundle.sha256, exported.sha256)
        assertTrue(store.holds.active().isEmpty())
        Files.writeString(config.jobDir(job).resolve(JobFileNames.TRIMMED), "trimmed")
        assertNull(reader.resolve(job, listOf("preparation", "${bundle.commit}.zip")))
    }
    private class Archives : BuildArchives {
        val objects = mutableMapOf<String, ByteArray>()
        val gets = mutableListOf<String>()
        override fun size(sha: String) = objects[sha]?.size?.toLong()
        override fun put(source: Path, sha: String) { objects[sha] = Files.readAllBytes(source) }
        override fun get(sha: String, destination: Path) { gets += sha; Files.write(destination, objects.getValue(sha)) }
    }

    private class Lab(auth: Auth = Auth.Disabled) {
        val config = Config(dataDir = Files.createTempDirectory("job-archives"), auth = auth)
        val store = Store(config.database)
        val archives = Archives()
        val local = LocalArtifactStore(config.dataDir.resolve("nomad/artifacts"))
        val artifacts = RegionalArtifactStore(local, archives, config.dataDir.resolve("nomad/build-fetches"))
        val services = ApiServices(config, store, HostReport("Test/Arm64", 4, "java"), artifacts)
        val job = store.create("archive", "main", emptyList(), experiment = ExperimentSpec("archive"))
        val other = store.create("other", "main", emptyList(), experiment = ExperimentSpec("other"))
        val names = listOf("preparation/${"a".repeat(40)}.zip", "preparation/${"b".repeat(40)}.zip")
        val contents = listOf("old prepared bytes", "new prepared bytes")
        val hashes = contents.map(ExecutionStore::digest)

        init {
            Files.createDirectories(config.jobDir(job))
            contents.zip(hashes).forEach { (text, hash) -> archives.objects[hash] = text.toByteArray() }
            val accepted = names.zip(hashes).toMap()
            val token = ExecutionStore.token()
            store.execution.createSession("preparation", job, ExecutionClass.PREPARE, "release", ResourceBudget(1000, 1024), token, "{}", 1000)
            store.execution.bind("preparation", token, "allocation", "node", "release", 1001)
            val owner = store.execution.claim("preparation", token, "allocation", 1002)!!.owner
            store.execution.phase(owner, "uploading", 1003)
            store.execution.commit(owner, IterationResult(emptyList(), accepted,
                preparation = PreparationResult(emptyList(), emptyList(), artifacts = accepted)), 1004)
        }
    }

    @Test fun `accepted regional archives are listed with hashes without download and fetched by existing file routes`() = testApplication {
        val lab = Lab()
        application { api(lab.services) }
        val listed = Json.decodeFromString<List<FileEntry>>(client.get("/jobs/${lab.job}/files").bodyAsText())
        assertEquals(lab.names.zip(lab.contents).zip(lab.hashes).map { (entry, hash) -> FileEntry(entry.first, entry.second.length.toLong(), hash) }, listed)
        assertTrue(lab.archives.gets.isEmpty(), "listing must not download archive bytes")
        for (index in lab.names.indices) {
            repeat(2) {
                val response = client.get("/jobs/${lab.job}/files/${lab.names[index]}")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(lab.contents[index], response.bodyAsText())
                assertEquals("\"${lab.hashes[index]}\"", response.headers[HttpHeaders.ETag])
            }
        }
        assertEquals(lab.hashes, lab.archives.gets, "each immutable archive is fetched once")
        assertTrue(lab.names.none { Files.exists(lab.config.jobDir(lab.job).resolve(it)) }, "reads must not reproject bulk into job directories")
    }

    @Test fun `archive access requires authentication and an accepted mapping in the requested job`() = testApplication {
        val lab = Lab(Auth.Required(Password.hash("fixture-password")))
        application { api(lab.services) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/jobs/${lab.job}/files/${lab.names[0]}").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/jobs/${lab.job}/files").status)
        assertTrue(lab.archives.gets.isEmpty())
        assertNull(lab.services.preparedArchives.resolve(lab.other, lab.names[0].split('/')))
        assertNull(lab.services.preparedArchives.resolve(lab.job, listOf("preparation", "unaccepted.zip")))
        assertFailsWith<IllegalArgumentException> { lab.services.preparedArchives.resolve(lab.job, listOf("preparation", "..", "secret.zip")) }
        assertTrue(lab.archives.gets.isEmpty())
    }

    @Test fun `trimmed jobs do not resurrect regional archives`() = testApplication {
        val lab = Lab()
        application { api(lab.services) }
        assertEquals(HttpStatusCode.OK, client.get("/jobs/${lab.job}/files/${lab.names[0]}").status)
        Files.writeString(lab.config.jobDir(lab.job).resolve(JobFileNames.TRIMMED), "trim receipt\n")
        val listed = Json.decodeFromString<List<FileEntry>>(client.get("/jobs/${lab.job}/files").bodyAsText())
        assertTrue(listed.none { it.path in lab.names })
        for (name in lab.names) assertEquals(HttpStatusCode.NotFound, client.get("/jobs/${lab.job}/files/$name").status)
        assertEquals(listOf(lab.hashes[0]), lab.archives.gets)
    }

    @Test fun `corrupt regional bytes fail closed before publication`() = testApplication {
        val lab = Lab()
        lab.archives.objects[lab.hashes[0]] = "tampered".toByteArray()
        application { api(lab.services) }
        val response = client.get("/jobs/${lab.job}/files/${lab.names[0]}")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val error = Json.decodeFromString<ErrorBody>(response.bodyAsText())
        assertEquals("accepted preparation archive unavailable", error.error)
        assertNull(error.detail)
        assertFalse(Files.exists(lab.config.dataDir.resolve("nomad/artifacts/${lab.job}/${lab.hashes[0]}")))
        val projected = lab.config.jobDir(lab.job).resolve(lab.names[0])
        Files.createDirectories(projected.parent)
        Files.writeString(projected, "tampered projected archive")
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/jobs/${lab.job}/files/${lab.names[0]}").status)
    }

    @Test fun `unavailable accepted archives stay listed with unknown size without failing the listing`() = testApplication {
        val lab = Lab()
        lab.archives.objects.remove(lab.hashes[0])
        application { api(lab.services) }
        val listing = client.get("/jobs/${lab.job}/files")
        assertEquals(HttpStatusCode.OK, listing.status)
        val entries = Json.decodeFromString<List<FileEntry>>(listing.bodyAsText())
        val unavailable = entries.single { it.path == lab.names[0] }
        assertEquals(FileEntry.UNKNOWN_BYTES, unavailable.bytes)
        assertEquals(lab.hashes[0], unavailable.sha256)
        assertTrue(entries.any { it.path == lab.names[1] && it.bytes >= 0 })
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/jobs/${lab.job}/files/${lab.names[0]}").status)
        assertTrue(lab.archives.gets.isEmpty())
    }

    @Test fun `projected accepted archives remain readable when regional storage is unavailable`() = testApplication {
        val lab = Lab()
        lab.archives.objects.clear()
        for ((name, text) in lab.names.zip(lab.contents)) {
            val file = lab.config.jobDir(lab.job).resolve(name)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        application { api(lab.services) }
        val listed = Json.decodeFromString<List<FileEntry>>(client.get("/jobs/${lab.job}/files").bodyAsText())
        assertEquals(lab.hashes, listed.map { it.sha256 })
        for ((name, text) in lab.names.zip(lab.contents)) {
            assertEquals(text, client.get("/jobs/${lab.job}/files/$name").bodyAsText())
        }
        assertTrue(lab.archives.gets.isEmpty())
    }
}
