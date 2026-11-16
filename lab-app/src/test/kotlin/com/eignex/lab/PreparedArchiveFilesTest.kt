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
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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

        fun project(index: Int = 0): Path = config.jobDir(job).resolve(names[index]).also {
            Files.createDirectories(it.parent)
            Files.writeString(it, contents[index])
        }.toRealPath()

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
        lab.project()
        assertNotNull(lab.services.preparedArchives.resolve(lab.job, lab.names[0].split('/')))
        application { api(lab.services) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/jobs/${lab.job}/files/${lab.names[0]}").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/jobs/${lab.job}/files").status)
        assertTrue(lab.archives.gets.isEmpty())
        assertNull(lab.services.preparedArchives.resolve(lab.other, lab.names[0].split('/')))
        assertNull(lab.services.preparedArchives.resolve(lab.job, listOf("preparation", "unaccepted.zip")))
        assertFailsWith<IllegalArgumentException> { lab.services.preparedArchives.resolve(lab.job, listOf("preparation", "..", "secret.zip")) }
        assertTrue(lab.archives.gets.isEmpty())
    }

    @Test fun `repeated projected archive reads reuse verified metadata but a fresh reader hashes cold bytes`() {
        val lab = Lab(); val file = lab.project(); lab.archives.objects.clear()
        var hashes = 0
        fun freshReader() = PreparedArchiveFiles(lab.config, lab.store, lab.artifacts,
            verification = ArtifactFileVerification(hash = { hashes++; hashFile(it) }))
        val reader = freshReader()
        repeat(3) {
            val archive = assertNotNull(reader.resolve(lab.job, lab.names[0].split('/')))
            assertEquals(file, archive.file); assertEquals(lab.hashes[0], archive.sha256)
            assertEquals(lab.contents[0], Files.readString(archive.file))
        }
        assertEquals(1, hashes)
        assertNotNull(freshReader().resolve(lab.job, lab.names[0].split('/')))
        assertEquals(2, hashes, "disposable RAM proofs do not make a fresh reader trust historical bytes")
        assertTrue(lab.archives.gets.isEmpty())
    }

    @Test fun `concurrent projected archive reads share verification without blocking another accepted archive`() {
        val lab = Lab(); val file = lab.project(); lab.project(1); lab.archives.objects.clear()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val followerObserved = CountDownLatch(1)
        val followerThread = AtomicReference<Thread>(); val hashes = AtomicInteger()
        val proof = ArtifactFileVerification(hash = {
            hashes.incrementAndGet()
            if (it == file) { entered.countDown(); check(release.await(5, SECONDS)) }
            hashFile(it)
        }, observe = {
            if (Thread.currentThread() === followerThread.get()) followerObserved.countDown()
            if (TreeVerification.reliableMetadata(it)) TreeVerification.fileMetadata(it) else null
        })
        val reader = PreparedArchiveFiles(lab.config, lab.store, lab.artifacts, verification = proof)
        val pool = Executors.newFixedThreadPool(3)
        try {
            val first = pool.submit<PreparedArchiveFiles.Archive?> { reader.resolve(lab.job, lab.names[0].split('/')) }
            assertTrue(entered.await(5, SECONDS))
            val follower = pool.submit<PreparedArchiveFiles.Archive?> {
                followerThread.set(Thread.currentThread()); reader.resolve(lab.job, lab.names[0].split('/'))
            }
            assertTrue(followerObserved.await(5, SECONDS))
            val other = pool.submit<PreparedArchiveFiles.Archive?> { reader.resolve(lab.job, lab.names[1].split('/')) }
            assertEquals(lab.hashes[1], assertNotNull(other.get(5, SECONDS)).sha256)
            assertFalse(follower.isDone)
            release.countDown()
            assertEquals(file, assertNotNull(first.get(5, SECONDS)).file)
            assertEquals(file, assertNotNull(follower.get(5, SECONDS)).file)
            assertNotNull(reader.resolve(lab.job, lab.names[0].split('/')))
            assertEquals(2, hashes.get(), "each accepted immutable archive is checksummed once")
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, SECONDS)) }
        assertTrue(lab.archives.gets.isEmpty())
    }

    @Test fun `projected archive edits and same size corruption with restored mtime fail before a response`() {
        val lab = Lab(); val file = lab.project(); lab.archives.objects.clear()
        var hashes = 0
        val reader = PreparedArchiveFiles(lab.config, lab.store, lab.artifacts,
            verification = ArtifactFileVerification(hash = { hashes++; hashFile(it) }))
        fun resolve() = reader.resolve(lab.job, lab.names[0].split('/'))
        assertNotNull(resolve())
        Files.writeString(file, "different length corrupt archive")
        assertFailsWith<ApiError.Unavailable> { resolve() }
        Files.writeString(file, lab.contents[0]); assertNotNull(resolve())
        val modified = Files.getLastModifiedTime(file)
        Files.writeString(file, "x".repeat(lab.contents[0].length)); Files.setLastModifiedTime(file, modified)
        assertFailsWith<ApiError.Unavailable> { resolve() }
        Files.writeString(file, lab.contents[0]); Files.setLastModifiedTime(file, modified)
        assertNotNull(resolve()); assertNotNull(resolve())
        assertEquals(5, hashes, "only restored valid bytes may establish a fresh proof")
        assertTrue(lab.archives.gets.isEmpty(), "corrupt projected bytes must not silently switch to another source")
    }

    @Test fun `replacement projected inodes reverify even with matching size and mtime`() {
        val lab = Lab(); val file = lab.project(); lab.archives.objects.clear()
        var hashes = 0
        val reader = PreparedArchiveFiles(lab.config, lab.store, lab.artifacts,
            verification = ArtifactFileVerification(hash = { hashes++; hashFile(it) }))
        assertNotNull(reader.resolve(lab.job, lab.names[0].split('/')))
        val modified = Files.getLastModifiedTime(file)
        val replacement = file.resolveSibling("replacement.zip")
        Files.writeString(replacement, lab.contents[0]); Files.setLastModifiedTime(replacement, modified)
        Files.move(replacement, file, REPLACE_EXISTING)
        assertNotNull(reader.resolve(lab.job, lab.names[0].split('/')))
        assertEquals(2, hashes)
        Files.writeString(replacement, "x".repeat(lab.contents[0].length)); Files.setLastModifiedTime(replacement, modified)
        Files.move(replacement, file, REPLACE_EXISTING)
        assertFailsWith<ApiError.Unavailable> { reader.resolve(lab.job, lab.names[0].split('/')) }
        assertEquals(3, hashes)
        assertTrue(lab.archives.gets.isEmpty())
    }

    @Test fun `unprovable projected archive metadata rehashes every read and still rejects corruption`() {
        val lab = Lab(); val file = lab.project(); lab.archives.objects.clear()
        var hashes = 0
        val reader = PreparedArchiveFiles(lab.config, lab.store, lab.artifacts,
            verification = ArtifactFileVerification(hash = { hashes++; hashFile(it) }, observe = { null }))
        repeat(2) { assertNotNull(reader.resolve(lab.job, lab.names[0].split('/'))) }
        assertEquals(2, hashes)
        Files.writeString(file, "x".repeat(lab.contents[0].length))
        assertFailsWith<ApiError.Unavailable> { reader.resolve(lab.job, lab.names[0].split('/')) }
        assertEquals(3, hashes)
    }

    @Test fun `a warm projected proof cannot bypass accepted job names paths or trimming`() {
        val lab = Lab(); val file = lab.project(); lab.archives.objects.clear()
        var hashes = 0
        val reader = PreparedArchiveFiles(lab.config, lab.store, lab.artifacts,
            verification = ArtifactFileVerification(hash = { hashes++; hashFile(it) }))
        assertNotNull(reader.resolve(lab.job, lab.names[0].split('/')))
        val other = lab.config.jobDir(lab.other).resolve(lab.names[0])
        Files.createDirectories(other.parent); Files.copy(file, other)
        assertNull(reader.resolve(lab.other, lab.names[0].split('/')))
        Files.copy(file, file.resolveSibling("unaccepted.zip"))
        assertNull(reader.resolve(lab.job, listOf("preparation", "unaccepted.zip")))
        assertFailsWith<IllegalArgumentException> { reader.resolve(lab.job, listOf("preparation", "..", "secret.zip")) }
        Files.writeString(lab.config.jobDir(lab.job).resolve(JobFileNames.TRIMMED), "trimmed")
        assertNull(reader.resolve(lab.job, lab.names[0].split('/')))
        assertTrue(reader.entries(lab.job).isEmpty())
        assertEquals(1, hashes, "acceptance and retention are checked before consulting a byte proof")
        assertTrue(lab.archives.gets.isEmpty())
    }

    @Test fun `warm projected archives preserve inside root links and reject links escaping the job`() {
        val lab = Lab(); val file = lab.project(); lab.archives.objects.clear()
        var hashes = 0
        val reader = PreparedArchiveFiles(lab.config, lab.store, lab.artifacts,
            verification = ArtifactFileVerification(hash = { hashes++; hashFile(it) }))
        assertNotNull(reader.resolve(lab.job, lab.names[0].split('/')))
        val outside = Files.writeString(lab.config.dataDir.resolve("outside.zip"), lab.contents[0]).toRealPath()
        Files.delete(file); Files.createSymbolicLink(file, outside)
        assertFailsWith<ApiError.Unavailable> { reader.resolve(lab.job, lab.names[0].split('/')) }
        assertEquals(1, hashes, "a cached proof must not read bytes outside the accepted job")
        val inside = Files.writeString(file.resolveSibling("inside.zip"), lab.contents[0])
        Files.delete(file); Files.createSymbolicLink(file, inside)
        assertEquals(inside, assertNotNull(reader.resolve(lab.job, lab.names[0].split('/'))).file)
        assertEquals(2, hashes)
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
            repeat(2) {
                val response = client.get("/jobs/${lab.job}/files/$name")
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(text, response.bodyAsText())
                assertEquals("\"${ExecutionStore.digest(text)}\"", response.headers[HttpHeaders.ETag])
            }
        }
        val file = lab.config.jobDir(lab.job).resolve(lab.names[0])
        val modified = Files.getLastModifiedTime(file)
        Files.writeString(file, "x".repeat(lab.contents[0].length)); Files.setLastModifiedTime(file, modified)
        val corrupt = client.get("/jobs/${lab.job}/files/${lab.names[0]}")
        assertEquals(HttpStatusCode.ServiceUnavailable, corrupt.status)
        assertEquals("accepted preparation archive unavailable", Json.decodeFromString<ErrorBody>(corrupt.bodyAsText()).error)
        assertTrue(lab.archives.gets.isEmpty())
    }
}
